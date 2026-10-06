package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bounded public publisher lookups. A blocked page is never bypassed. */
object PublicComicSources {
    private fun isbn(s:String)=s.filter(Char::isDigit)
    fun searchTitle(book:Book)=book.title.replace(Regex("(?i)\\s*[-–:]?\\s*(?:tome|t|vol\\.?)\\s*0*\\d+\\s*$"),"").trim()
    private fun allowed(url:String,host:String)=runCatching {val u=URI(url);u.scheme=="https" && u.host==host && u.userInfo==null && u.port in listOf(-1,443)}.getOrDefault(false)
    fun kanaLinks(html:String,book:Book):List<String> {
        val title=searchTitle(book)
        return Jsoup.parse(html,"https://www.kana.fr/").select("a[href]").filter {
            BibliographicIdentity.titleMatches(it.text(),title)
        }.map {it.absUrl("href")}.filter {allowed(it,"www.kana.fr") && URI(it).path.startsWith("/produit/")}.distinct().take(3)
    }
    fun parseKana(html:String,url:String,book:Book):BibliographicSources.Result? {
        if(!allowed(url,"www.kana.fr"))return null
        val doc=Jsoup.parse(html)
        val title=doc.selectFirst("h1.single-product-header__title")?.text().orEmpty()
        val fields=doc.select(".tech table tr").mapNotNull {row ->val cells=row.select("td");if(cells.size>=2)cells[0].text() to cells[1].text() else null}.toMap()
        val number=fields["ISBN / EAN"].orEmpty()
        if(!BibliographicSources.validIsbn(number))return null
        val exactIsbn=book.isbn.isNotBlank() && isbn(book.isbn)==isbn(number)
        if(book.isbn.isNotBlank() && !exactIsbn)return null
        if(!exactIsbn && !BibliographicIdentity.titleMatches(searchTitle(book),title))return null
        fun author(role:String)=doc.select(".auteurs__content li").filter {it.ownText().contains(role,true)}
            .map {it.select("h3 a").text()}.filter(String::isNotBlank).distinct().joinToString(", ")
        val artist=author("Dessin");val writer=author("Scénario")
        // Known creators distinguish a homonym; a filename suffix alone is not proof of a volume.
        if(!exactIsbn && listOf(book.artist to artist,book.writer to writer).any {(known,found)->known.isNotBlank() && !BibliographicIdentity.titleMatches(known,found)})return null
        if(book.number.isNotBlank() && book.metadataEdited && !exactIsbn)return null
        val date=runCatching {LocalDate.parse(fields["Parution"].orEmpty(),DateTimeFormatter.ofPattern("d MMMM uuuu",Locale.FRENCH).withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString()}.getOrDefault("")
        return BibliographicSources.Result(BdTheque.Details(title=title,artist=artist,writer=writer,publisher="Kana",date=date,isbn=number,
            genre=fields["Catégorie"].orEmpty(),synopsis=doc.selectFirst(".hero-area__description[itemprop=description]")?.text().orEmpty().take(12000)),listOf(url),true)
    }
    fun parseGlenat(html:String,url:String,book:Book):BibliographicSources.Result? {
        if(!allowed(url,"www.glenat.com") || !BibliographicSources.validIsbn(book.isbn))return null
        val doc=Jsoup.parse(html)
        val root=org.json.JSONObject(doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null)
            .optJSONObject("props")?.optJSONObject("pageProps") ?: return null
        val data=root.optJSONObject("data") ?: return null
        if(isbn(data.optString("ean"))!=isbn(book.isbn))return null
        val structured=doc.select("script[type=application/ld+json]").mapNotNull {
            runCatching {org.json.JSONObject(it.data())}.getOrNull()
        }.firstOrNull {it.optString("@type")=="Book"} ?: return null
        val title=structured.optString("name")
        if(title.isBlank())return null
        val sections=root.optJSONArray("sections")
        val product=(0 until (sections?.length() ?: 0)).mapNotNull {sections?.optJSONObject(it)?.optJSONObject("data")}
            .firstOrNull {isbn(it.optString("ean"))==isbn(book.isbn)}
        val authors=product?.optJSONObject("primary")?.optJSONArray("authors")
        fun author(role:String)=(0 until (authors?.length() ?: 0)).mapNotNull {authors?.optJSONObject(it)}
            .filter {it.optString("contribution").contains(role,true)}.joinToString(", ") {it.optString("label")}
        return BibliographicSources.Result(BdTheque.Details(title=title,publisher="Glénat",isbn=data.optString("ean"),
            date=PublicationDate.storage(structured.optString("datePublished")).orEmpty(),
            artist=author("Dessinateur"),writer=author("Scénariste"),
            genre=data.optJSONObject("main_category_calculated")?.optString("title").orEmpty(),
            synopsis=Jsoup.parse(data.optString("presentation_editoriale").ifBlank {data.optString("resume")}).text().take(12000)),listOf(url),true)
    }
    suspend fun lookup(book:Book,cache:MutableMap<String,String>):BibliographicSources.Result?=withContext(Dispatchers.IO) {
        suspend fun page(url:String)=cache[url] ?: Jsoup.connect(url).userAgent("BubbleBD (bibliographic lookup)")
            .timeout(10000).maxBodySize(2*1024*1024).get().outerHtml().also {while(cache.size>=24)cache.remove(cache.keys.first());cache[url]=it}
        val query=searchTitle(book)
        if(query.isBlank())return@withContext null
        if(book.publisher.contains("Glénat",true) && BibliographicSources.validIsbn(book.isbn)) {
            val slug=BookRules.normalized(query).replace(Regex("[^a-z0-9]+"),"-").trim('-')
            val url="https://www.glenat.com/glenat-bd/"+slug+"-"+isbn(book.isbn)+"/"
            // A URL guess is never an identification: validate the returned page's EAN.
            try {parseGlenat(page(url),url,book)?.let {return@withContext it}}
            catch(e:org.jsoup.HttpStatusException) {if(e.statusCode !in listOf(403,404))throw e}
        }
        val kanaSlug=BookRules.normalized(query).replace(Regex("[^a-z0-9]+"),"-").trim('-')
        val kanaUrl="https://www.kana.fr/produit/$kanaSlug/"
        try {parseKana(page(kanaUrl),kanaUrl,book)?.let {return@withContext it}}
        catch(e:org.jsoup.HttpStatusException) {if(e.statusCode !in listOf(403,404))throw e}
        val search="https://www.kana.fr/?s="+URLEncoder.encode(query,"UTF-8")+"&post_type=product"
        val matches=kanaLinks(page(search),book).mapNotNull {url->parseKana(page(url),url,book)}
        // Never choose arbitrarily between editions or homonyms.
        matches.singleOrNull()
    }
}
