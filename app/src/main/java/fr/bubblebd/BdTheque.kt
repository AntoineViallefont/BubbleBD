package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URI

/** Reads public bibliographic fields only; never guesses an album date from a series date. */
object BdTheque {
    data class Details(val series:String="",val title:String="",val genre:String="",val artist:String="",val writer:String="",val publisher:String="",val date:String="",val isbn:String="",val synopsis:String="",val rating:Double?=null,val ratingScale:Double?=null,val reviewCount:Int?=null)
    fun validUrl(value:String):Boolean = runCatching {
        val uri=URI(value)
        uri.scheme=="https" && uri.host in listOf("bdtheque.com","www.bdtheque.com") && uri.userInfo==null && uri.port in listOf(-1,443) && Regex("^/(series|albums)/[0-9]+/[^/]+/?$").matches(uri.path)
    }.getOrDefault(false)
    fun browserUrl(value:String)=value.trim().takeIf(::validUrl) ?: "https://www.bdtheque.com/"
    class AccessBlocked:IllegalStateException("BDThèque bloque la récupération automatique. La fiche reste accessible dans le navigateur.")
    class SearchUnavailable:IllegalStateException("Recherche automatique BDThèque indisponible. Ajoutez l’adresse exacte dans la fiche.")
    data class SearchForm(val action:String,val field:String)
    private var searchForm:SearchForm?=null
    fun missing(book:Book)=listOf(book.series,book.genre,book.artist,book.writer,book.publisher,book.date).any {it.isBlank()} || book.rating==null || book.reviewCount==null
    private fun siteUrl(value:String)=runCatching {URI(value).let {it.scheme=="https" && it.host in listOf("bdtheque.com","www.bdtheque.com") && it.userInfo==null && it.port in listOf(-1,443)}}.getOrDefault(false)
    private fun page(url:String):String {
        require(siteUrl(url))
        val response=Jsoup.connect(url).userAgent("BubbleBD/0.3 (Android comic reader)").timeout(12000).maxBodySize(2*1024*1024).followRedirects(false).ignoreHttpErrors(true).execute()
        if(response.statusCode() in listOf(401,403,429))throw AccessBlocked()
        require(response.statusCode()==200) {"BDThèque est temporairement indisponible."}
        val html=response.body()
        if(Jsoup.parse(html).select("#challenge-running, #challenge-form").isNotEmpty())throw AccessBlocked()
        return html
    }
    fun discoverSearchForm(html:String):SearchForm? {
        val doc=Jsoup.parse(html,"https://www.bdtheque.com/")
        return doc.select("form").firstNotNullOfOrNull {form ->
            if(form.attr("method").isNotBlank() && !form.attr("method").equals("get",true))return@firstNotNullOfOrNull null
            val action=form.absUrl("action")
            if(!siteUrl(action))return@firstNotNullOfOrNull null
            val field=form.selectFirst("input[type=search][name], input[name=q], input[name=query], input[name=recherche], input[name=search]") ?: return@firstNotNullOfOrNull null
            SearchForm(action,field.attr("name"))
        }
    }
    fun uniqueResult(html:String,book:Book,base:String):String? {
        val expected=BookRules.normalized(book.series.ifBlank {book.title}).trim()
        if(expected.isBlank())return null
        val links=Jsoup.parse(html,base).select("a[href]").mapNotNull {a ->
            val url=a.absUrl("href")
            if(validUrl(url) && BookRules.normalized(a.text()).trim()==expected &&
                (book.series.isNotBlank() && URI(url).path.startsWith("/series/") || book.series.isBlank() && URI(url).path.startsWith("/albums/")))url else null
        }.distinct()
        return links.singleOrNull()
    }
    suspend fun findUrl(book:Book):String?=withContext(Dispatchers.IO) {
        if(validUrl(book.bdtheque))return@withContext book.bdtheque
        val form=searchForm ?: discoverSearchForm(page("https://www.bdtheque.com/"))?.also {searchForm=it} ?: throw SearchUnavailable()
        val query=java.net.URLEncoder.encode(book.series.ifBlank {book.title},"UTF-8")
        val field=java.net.URLEncoder.encode(form.field,"UTF-8")
        val url=form.action+(if('?' in form.action)"&" else "?")+"$field=$query"
        uniqueResult(page(url),book,url)
    }
    suspend fun fetch(url:String):Details=withContext(Dispatchers.IO) {
        require(validUrl(url)) {"Adresse BDThèque invalide"}
        val details=parse(page(url),url)
        require(details!=Details()) {"Les informations de cette fiche ne sont pas récupérables automatiquement."}
        details
    }
    fun parse(html:String,url:String,volume:String=""):Details {
        require(validUrl(url))
        val doc=Jsoup.parse(html,url)
        if(doc.title().contains("instant",true) || doc.select("#challenge-running, #challenge-form").isNotEmpty()) return Details()
        val seriesPage=URI(url).path.startsWith("/series/")
        val h1=doc.selectFirst("h1")?.text().orEmpty().trim()
        val fields=mutableMapOf<String,String>()
        doc.select("dt, th").forEach {label ->
            val value=label.nextElementSibling()?.takeIf {it.tagName() in listOf("dd","td")}?.text().orEmpty()
            if(value.isNotBlank()) fields[BookRules.normalized(label.text()).trim().trimEnd(':').trim()]=value
        }
        doc.select("tr").forEach {row ->
            val cells=row.children().filter {it.tagName() in listOf("td","th")}
            if(cells.size==2) {
                val label=BookRules.normalized(cells[0].text()).trim().trimEnd(':').trim()
                fields[label]=cells[1].text()
                cells[1].selectFirst("a[href*='/genre=']")?.let {fields["genre"]=it.text()}
            }
        }
        fun field(vararg labels:String)=labels.firstNotNullOfOrNull {fields[it]} ?: ""
        fun names(value:Any?):String=when(value) {
            is String->value
            is JSONObject->value.optString("name")
            is JSONArray->(0 until value.length()).map {names(value.opt(it))}.filter {it.isNotBlank()}.joinToString(", ")
            else->""
        }
        val nodes=mutableListOf<JSONObject>()
        fun collect(value:Any?) {when(value) {is JSONObject->{nodes.add(value);value.opt("@graph")?.let(::collect)};is JSONArray->for(i in 0 until value.length())collect(value.opt(i))}}
        doc.select("script[type=application/ld+json]").forEach {runCatching { val raw=it.data().trim();collect(if(raw.startsWith("["))JSONArray(raw) else JSONObject(raw))}}
        val node=nodes.firstOrNull {n -> n.optString("@type") in if(seriesPage) listOf("ComicSeries","BookSeries","CreativeWorkSeries") else listOf("ComicIssue","ComicStory","Book")}
        val name=node?.optString("name").orEmpty().ifBlank {h1}
        if(name.isBlank()) return Details()
        val aggregate=node?.optJSONObject("aggregateRating")
        val global=doc.selectFirst("#bubble-global-rating") ?: doc.select("img[alt^='Note:']").firstOrNull {
            it.parents().none {p->p.id() in listOf("series_comments","albumsModal")} && Regex("pour\\s+\\d+\\s+avis").containsMatchIn(it.parent()?.text().orEmpty())
        }?.parent()
        val score=Regex("Note:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*/\\s*([0-9]+(?:[.,][0-9]+)?)").find(global?.selectFirst("img[alt]")?.attr("alt").orEmpty())
        val scale=aggregate?.optDouble("bestRating")?.takeIf {it.isFinite() && it>0} ?: score?.groupValues?.get(2)?.replace(',','.')?.toDoubleOrNull()?.takeIf {it>0}
        val rawRating=aggregate?.optDouble("ratingValue")?.takeIf {it.isFinite()} ?: score?.groupValues?.get(1)?.replace(',','.')?.toDoubleOrNull()
        val count=aggregate?.takeUnless {it.isNull("reviewCount")}?.optInt("reviewCount",-1)?.takeIf {it>=0} ?: Regex("pour\\s+(\\d+)\\s+avis").find(global?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val rating=rawRating?.takeIf {scale!=null && it in 0.0..scale && count!=null && count>0}
        // Modal album fields are usable only for one published album or an exact volume match.
        val cards=doc.select("#albums .card")
        val album=if(volume.isBlank() && cards.size==1 && field("statut histoire").contains("1 tome paru") && field("statut histoire").contains("One shot",true))cards.single()
            else if(volume.toIntOrNull()!=null)cards.filter {Regex("^([0-9]+)\\s*[-–]").find(it.selectFirst(".card-title")?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()==volume.toIntOrNull()}.singleOrNull() else null
        val albumText=album?.select("small")?.text().orEmpty()
        fun date(value:String):String {
            PublicationDate.storage(value)?.let {return it}
            val match=Regex("^(\\d{1,2})\\s+([a-z]+)\\s+(\\d{4})$",RegexOption.IGNORE_CASE).find(BookRules.normalized(value).trim()) ?: return ""
            val months=listOf("janvier","fevrier","mars","avril","mai","juin","juillet","aout","septembre","octobre","novembre","decembre")
            val month=months.indexOf(match.groupValues[2])+1
            return if(month>0)PublicationDate.storage("%02d/%02d/%s".format(match.groupValues[1].toInt(),month,match.groupValues[3])).orEmpty() else ""
        }
        val modalDate=Regex("Date de parution\\s*:\\s*([^|]+)").find(albumText)?.groupValues?.get(1)?.trim().orEmpty()
        val modalIsbn=Regex("ISBN\\s*:\\s*([0-9-]+)").find(albumText)?.groupValues?.get(1).orEmpty()
        return Details(
            series=if(seriesPage)name else names(node?.opt("isPartOf")),title=if(seriesPage)"" else name,
            genre=names(node?.opt("genre")).ifBlank {field("genre","genres")},
            artist=names(node?.opt("illustrator")).ifBlank {field("dessin","dessinateur","dessinateurs")},
            writer=names(node?.opt("author")).ifBlank {field("scenario","scenariste","scenaristes")},
            publisher=names(node?.opt("publisher")).ifBlank {field("editeur","editeurs")},
            date=if(seriesPage)date(modalDate) else date(node?.optString("datePublished").orEmpty().ifBlank {field("date de parution")}),isbn=if(seriesPage)modalIsbn else node?.optString("isbn").orEmpty().ifBlank {field("isbn")},
            synopsis=Jsoup.parse(node?.optString("description").orEmpty()).text().ifBlank {album?.selectFirst("p.card-text")?.text().orEmpty().ifBlank {doc.select("p.lead,#storyParagraph").text()}}.take(4000),rating=rating,ratingScale=if(rating!=null)scale else null,reviewCount=if(rating!=null)count else null
        )
    }
    fun merge(book:Book,d:Details,url:String)=book.copy(
        series=book.series.ifBlank {d.series},title=book.title.ifBlank {d.title},genre=book.genre.ifBlank {d.genre},
        artist=book.artist.ifBlank {d.artist},writer=book.writer.ifBlank {d.writer},publisher=book.publisher.ifBlank {d.publisher},
        date=book.date.ifBlank {d.date},isbn=book.isbn.ifBlank {d.isbn},synopsis=book.synopsis.ifBlank {d.synopsis},
        bdtheque=url,metadataSource=(book.metadataSource.lines()+url).filter {it.isNotBlank()}.distinct().joinToString("\n"),metadataEdited=true,
        rating=d.rating ?: book.rating,
        ratingScale=if(d.rating!=null)d.ratingScale else book.ratingScale,
        reviewCount=if(d.rating!=null)d.reviewCount else book.reviewCount,
        ratingSource=if(d.rating!=null)url else book.ratingSource
    )
}
