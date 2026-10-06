package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Bounded publisher discovery; verify the full album page before importing its fields. */
object MorePublisherSources {
    private fun isbn(s:String)=s.filter(Char::isDigit)
    private fun host(url:String)=runCatching {val u=URI(url);if(u.scheme=="https" && u.userInfo==null && u.port in listOf(-1,443))u.host else null}.getOrNull()
    private fun date(s:String,pattern:String)=runCatching {LocalDate.parse(s,DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.FRENCH).withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString()}.getOrDefault("")
    fun parse(html:String,url:String,book:Book,allowTitleMatch:Boolean=false):BibliographicSources.Result? {
        if(book.metadataLocked)return null
        val doc=Jsoup.parse(html)
        val d=when(host(url)) {
            "www.dargaud.com" -> {
                val fields=doc.select("dl.album-caracs dt").associate {it.text().removeSuffix(":").trim() to it.nextElementSibling()?.text().orEmpty()}
                val foundIsbn=fields["ISBN/EAN"].orEmpty()
                if(!BibliographicSources.validIsbn(foundIsbn))return null
                val exact=book.isbn.isNotBlank() && isbn(foundIsbn)==isbn(book.isbn)
                if(book.isbn.isNotBlank() && !exact)return null
                fun author(role:String)=doc.select(".album-details__authors li").filter {it.ownText().contains(role,true)}.map {it.select("a").text()}.distinct().joinToString(", ")
                val title=doc.selectFirst("h1")?.text().orEmpty()
                if(!exact) {
                    if(!allowTitleMatch || !BibliographicIdentity.titleMatches(title,PublicComicSources.searchTitle(book)))return null
                    // A filename volume suffix is not evidence of the publisher's edition.
                    if(book.number.isNotBlank())return null
                    if(listOf(book.artist to author("Dessin"),book.writer to author("Scénario")).any {(known,found)->
                        known.isNotBlank() && !BibliographicIdentity.titleMatches(known,found)})return null
                }
                BdTheque.Details(title=title,publisher="Dargaud",isbn=foundIsbn,
                    artist=author("Dessin"),writer=author("Scénario"),date=date(fields["Date de parution"].orEmpty(),"dd.MM.uuuu"),
                    synopsis=doc.selectFirst("#album-details-tab0 .field--name-field-description")?.text().orEmpty().take(12000))
            }
            "www.lelombard.com" -> {
                val fields=doc.select(".listinfos__el").associate {it.select(".listinfos__title").text() to it.select("p").text()}
                if(!BibliographicSources.validIsbn(book.isbn) || isbn(fields["ISBN"].orEmpty())!=isbn(book.isbn))return null
                fun author(role:String)=doc.select(".listround__content").filter {it.select(".listround__subtitle").text().contains(role,true)}
                    .map {it.select(".listround__title").text()}.distinct().joinToString(", ")
                BdTheque.Details(title=doc.selectFirst("h2.panel__title")?.text().orEmpty(),series=doc.selectFirst("h1.panel__title")?.text().orEmpty(),
                    publisher="Le Lombard",isbn=book.isbn,artist=author("Dessin"),writer=author("Scénario"),genre=fields["Genre"].orEmpty(),
                    date=date(fields["Parution le"].orEmpty(),"d MMMM uuuu"),
                    synopsis=doc.selectFirst("h2.panel__subtitle--smallspace + p")?.text().orEmpty().take(12000))
            }
            else ->return null
        }
        if(d.title.isBlank())return null
        return BibliographicSources.Result(d,listOf(url),true)
    }
    /** Autocomplete is discovery only; refuse truncated or ambiguous result sets. */
    fun dargaudLinks(json:String):List<String> {
        val albums=org.json.JSONObject(json).optJSONObject("album") ?: return emptyList()
        val items=albums.optJSONArray("items") ?: return emptyList()
        if(albums.optInt("total",-1)!=items.length() || items.length()>3)return emptyList()
        return (0 until items.length()).mapNotNull {i ->
            val url=URI("https://www.dargaud.com/").resolve(items.optJSONObject(i)?.optString("url").orEmpty()).toString()
            url.takeIf {host(it)=="www.dargaud.com" && URI(it).path.matches(Regex("/bd/[^/]+-bda[0-9]+"))}
        }.distinct()
    }
    suspend fun lookup(book:Book,cache:MutableMap<String,String>):BibliographicSources.Result?=withContext(Dispatchers.IO) {
        if(book.metadataLocked)return@withContext null
        fun page(url:String)=cache[url] ?: Jsoup.connect(url).userAgent("BubbleBD (bibliographic lookup)").timeout(10000).maxBodySize(2*1024*1024)
            .get().outerHtml().also {while(cache.size>=24)cache.remove(cache.keys.first());cache[url]=it}
        val urls=book.metadataSource.lines().filter {host(it) in listOf("www.dargaud.com","www.lelombard.com") && URI(it).path.startsWith("/bd/")}.take(3).toMutableList()
        if(book.publisher.contains("Lombard",true) && BibliographicSources.validIsbn(book.isbn)) {
            val search="https://www.lelombard.com/recherche?t="+URLEncoder.encode(isbn(book.isbn),"UTF-8")
            urls+=Jsoup.parse(page(search),search).select("a[href]").map {it.absUrl("href")}
                .filter {host(it)=="www.lelombard.com" && URI(it).path.startsWith("/bd/") && URI(it).path.count {c->c=='/'}>=3}.distinct().take(3)
        }
        for(url in urls.distinct()) {
            try {parse(page(url),url,book)?.let {return@withContext it}}
            catch(e:org.jsoup.HttpStatusException) {if(e.statusCode !in listOf(403,404))throw e}
        }
        val query=PublicComicSources.searchTitle(book)
        if(query.length<2)return@withContext null
        val search="https://www.dargaud.com/autocomplete/search?q="+URLEncoder.encode(query,"UTF-8")
        // JSON endpoint used by the publisher's public search, not HTML scraping of Google.
        val json=cache[search] ?: Jsoup.connect(search).userAgent("BubbleBD (bibliographic lookup)")
            .timeout(10000).maxBodySize(2*1024*1024).ignoreContentType(true).execute().body().also {
                while(cache.size>=24)cache.remove(cache.keys.first());cache[search]=it
            }
        val matches=dargaudLinks(json).mapNotNull {url ->
            try {parse(page(url),url,book,allowTitleMatch=true)}
            catch(e:org.jsoup.HttpStatusException) {if(e.statusCode !in listOf(403,404))throw e else null}
        }
        matches.singleOrNull()
    }
}
