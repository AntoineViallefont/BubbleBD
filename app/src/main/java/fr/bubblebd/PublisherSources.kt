package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/** Direct public publisher pages, independent of the shared AI quota. */
object PublisherSources {
    private fun key(s:String)=BookRules.normalized(s).replace(Regex("[^a-z0-9]+")," ").trim()
    private fun isbn(s:String)=s.filter {it.isDigit() || it.uppercaseChar()=='X'}.uppercase()
    private fun delcourtUrl(value:String)=runCatching {
        val u=URI(value);u.scheme=="https" && u.host=="www.editions-delcourt.fr" && u.userInfo==null && u.port in listOf(-1,443) && u.path.contains("/album-")
    }.getOrDefault(false)
    fun delcourtLinks(html:String,book:Book):List<String> {
        val b=BibliographicIdentity.forLookup(book)
        val series=BibliographicSources.integralTitle(b) ?: b.series.ifBlank {b.title}
        val doc=Jsoup.parse(html,"https://www.editions-delcourt.fr/")
        return doc.select("a[href]").map {a -> a.attr("href",a.attr("href").trim());a.absUrl("href")}
            .filter {url ->delcourtUrl(url) && BibliographicIdentity.titleMatches(URI(url).path.substringBefore("/album-").substringAfterLast("/serie-"),series)}
            .distinct().take(12)
    }
    fun parseDelcourt(html:String,expectedIsbn:String):BibliographicSources.Result? {
        if(!BibliographicSources.validIsbn(expectedIsbn))return null
        val doc=Jsoup.parse(html)
        val url=doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty()
        if(!delcourtUrl(url))return null
        val fields=doc.select(".product-info__item").associate {key(it.selectFirst(".product-info__type")?.text().orEmpty()) to it.selectFirst(".product-info__detail")?.text().orEmpty()}
        val n=fields["ean"].orEmpty()
        val isbn10=doc.selectFirst(".product-isbn .field-name-field-isbn")?.text().orEmpty()
        if(isbn(expectedIsbn) !in listOf(isbn(n),isbn(isbn10)))return null
        fun author(role:String)=doc.select(".product-authors__item").filter {key(it.selectFirst(".product-authors__type")?.text().orEmpty())==role}
            .flatMap {it.select(".product-authors__name a").map {a ->a.text()}}.distinct().joinToString(", ")
        val rawDate=doc.selectFirst(".product-details__date")?.text().orEmpty().replace(Regex("^Paru le\\s+"),"")
        val date=runCatching {java.time.LocalDate.parse(rawDate,java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu",java.util.Locale.FRENCH)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString()}.getOrDefault("")
        val title=doc.selectFirst("h1")?.text().orEmpty()
        if(title.isBlank())return null
        return BibliographicSources.Result(BdTheque.Details(title=title,series=fields["serie"].orEmpty(),
            artist=author("illustrateur"),writer=author("scenariste"),publisher="Delcourt",date=date,isbn=expectedIsbn,
            genre=fields["themes"].orEmpty(),synopsis=doc.selectFirst(".product-details__text .field-name-body")?.text().orEmpty().take(12000)),listOf(url))
    }
    suspend fun delcourt(book:Book,cache:MutableMap<String,String>):BibliographicSources.Result?=withContext(Dispatchers.IO) {
        val b=BibliographicIdentity.forLookup(book)
        if(!b.publisher.contains("Delcourt",true) || !BibliographicSources.validIsbn(b.isbn))return@withContext null
        val series=BibliographicSources.integralTitle(b) ?: b.series.ifBlank {b.title}
        if(series.isBlank())return@withContext null
        suspend fun page(url:String):String=cache[url] ?: run {
            delay(350)
            Jsoup.connect(url).userAgent("BubbleBD/0.3 (bibliographic lookup)").timeout(12000).maxBodySize(2*1024*1024)
                .get().outerHtml().also {html ->
                    while(cache.size>=24)cache.remove(cache.keys.first())
                    cache[url]=html
                }
        }
        val search="https://www.editions-delcourt.fr/recherche?search_api_fulltext="+URLEncoder.encode(series,"UTF-8")
        for(url in delcourtLinks(page(search),b)) {
            parseDelcourt(page(url),b.isbn)?.let {return@withContext it}
        }
        null
    }
}
