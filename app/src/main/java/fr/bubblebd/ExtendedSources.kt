package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/** More discovery sources; page counts corroborate identity, never replace the file's page count. */
object ExtendedSources {
    fun needsLookup(book:Book)=!BibliographicSources.complete(book) || book.rating==null
    /** A rating-only pass cannot overwrite the album information or its existing score. */
    fun mergeRating(book:Book,result:BibliographicSources.Result):Book {
        if(book.metadataLocked)return book
        val d=result.details
        val score=d.rating ?: return book
        val scale=d.ratingScale ?: return book
        val count=d.reviewCount ?: return book
        val source=result.urls.firstOrNull() ?: return book
        if(book.rating!=null || !score.isFinite() || !scale.isFinite() || scale<=0 || score !in 0.0..scale || count<=0)return book
        return book.copy(rating=score,ratingScale=scale,reviewCount=count,ratingSource=source)
    }
    data class Candidate(val title:String,val authors:String,val isbn:String,val pages:Int,val details:BdTheque.Details,val url:String,val identifiers:List<String> = emptyList())
    private fun key(s:String)=BookRules.normalized(s).replace(Regex("[^a-z0-9]+")," ").trim()
    private fun isbn(s:String)=s.filter {it.isDigit() || it.uppercaseChar()=='X'}.uppercase()
    fun queries(book:Book,coverLines:List<String>):List<String> {
        val b=BibliographicIdentity.forLookup(book)
        if(BibliographicSources.validIsbn(b.isbn))return listOf("isbn:${isbn(b.isbn)}")
        val title=BibliographicSources.titleFromFilename(b)
        val author=listOf(b.writer,b.artist).filter(String::isNotBlank).distinct().joinToString(" ")
        val full=listOf(b.series,b.title.takeUnless {key(it)==key(b.series)}.orEmpty(),b.number.takeIf {b.series.isNotBlank()}.orEmpty()).filter(String::isNotBlank).joinToString(" ")
        val fragments=coverLines.zipWithNext {a,c ->"$a $c"}.filter {it.length in 5..90}
        return (listOf(full,"$title $author".trim())+coverLines.filter {it.length in 5..90 && it.count(Char::isLetter)>4}.take(3)+fragments.take(2))
            .filter {it.isNotBlank()}.distinctBy(::key).take(6)
    }
    fun select(candidates:List<Candidate>,book:Book,coverLines:List<String>):Candidate? {
        val b=BibliographicIdentity.forLookup(book)
        val exact=if(BibliographicSources.validIsbn(b.isbn))candidates.filter {c ->(c.identifiers+c.isbn).any {isbn(it)==isbn(b.isbn)}} else emptyList()
        if(BibliographicSources.validIsbn(b.isbn))return exact.distinctBy {it.url}.singleOrNull()
        val names=listOf(b.title,BibliographicSources.titleFromFilename(b),"${b.series} ${b.number}".trim())
            .filter {it.isNotBlank()}.map(::key).toSet()
        val evidence=key((coverLines+listOf(b.artist,b.writer)).joinToString(" ")).split(' ').toSet()
        val matches=candidates.filter {c ->
            val title=key(c.title)
            val explicitNumber=Regex("(?i)(?:tome|vol(?:ume)?|t)\\s*0*(\\d+)").find(c.title)?.groupValues?.get(1)?.toIntOrNull()
            val requested=b.number.toIntOrNull()
            val author=c.authors.let(::key).split(' ').any {it.length>=4 && it in evidence}
            val coverTitle=(coverLines+coverLines.zipWithNext {a,c ->"$a $c"}).any {key(it)==title}
            val pageMatch=b.pages>0 && c.pages>0 && kotlin.math.abs(b.pages-c.pages)<=4
            val volumeOk=requested==null || explicitNumber==requested || title==key("${b.series} ${b.number}") ||
                (key(BibliographicSources.titleFromFilename(b))!=key(b.series) && title==key(BibliographicSources.titleFromFilename(b))) ||
                (explicitNumber==null && title!=key(b.series) && author && coverTitle && pageMatch)
            volumeOk && (names.any {BibliographicIdentity.titleMatches(c.title,it)} || coverTitle && author && (b.title.isBlank() || key(b.title)==key(b.series))) && (author || coverTitle && pageMatch)
        }
        val editions=matches.distinctBy {isbn(it.isbn).ifBlank {it.url}}
        if(editions.size<=1)return editions.singleOrNull()
        // Several editions are useful only when title and the full author list agree.
        val authors=editions.map {key(it.authors)}
        if(editions.map {BibliographicIdentity.titleKey(it.title)}.distinct().size!=1 || authors.any(String::isBlank) || authors.distinct().size!=1)return null
        return editions.minWithOrNull(compareBy<Candidate> {it.details.date.ifBlank {"9999"}}
            .thenByDescending {listOf(it.details.publisher,it.details.genre,it.details.synopsis).count(String::isNotBlank)}
            .thenBy {isbn(it.isbn).ifBlank {"~"}}.thenBy {it.url})
    }
    fun parseGoogle(json:String):List<Candidate> {
        val items=JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull {i ->
            val item=items.optJSONObject(i) ?: return@mapNotNull null
            val v=item.optJSONObject("volumeInfo") ?: return@mapNotNull null
            val id=item.optString("id").takeIf {it.matches(Regex("[A-Za-z0-9_-]+"))} ?: return@mapNotNull null
            val identifiers=v.optJSONArray("industryIdentifiers")
            val n=identifiers?.let {ids ->(0 until ids.length()).mapNotNull {ids.optJSONObject(it)?.optString("identifier")}.firstOrNull(BibliographicSources::validIsbn)}.orEmpty()
            fun list(name:String)=v.optJSONArray(name)?.let {a ->(0 until a.length()).map {a.optString(it)}.joinToString(", ")}.orEmpty()
            val score=v.optDouble("averageRating").takeIf {it.isFinite() && it in 1.0..5.0}
            val count=v.optInt("ratingsCount",0).takeIf {it>0}
            val rating=score.takeIf {count!=null}
            val url="https://books.google.com/books?id=$id"
            Candidate(listOf(v.optString("title"),v.optString("subtitle")).filter(String::isNotBlank).joinToString(" "),list("authors"),n,v.optInt("pageCount"),
                BdTheque.Details(publisher=v.optString("publisher"),date=v.optString("publishedDate"),isbn=n,genre=list("categories"),synopsis=Jsoup.parse(v.optString("description")).text().take(4000),rating=rating,ratingScale=if(rating!=null)5.0 else null,reviewCount=if(rating!=null)count else null),url)
        }
    }
    fun parseOpenLibrary(json:String):List<Candidate> {
        val docs=JSONObject(json).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull {i ->
            val d=docs.optJSONObject(i) ?: return@mapNotNull null
            val work=d.optString("key").takeIf {it.matches(Regex("/works/OL[0-9]+W"))} ?: return@mapNotNull null
            val authors=d.optJSONArray("author_name")?.let {a ->(0 until a.length()).joinToString(", "){a.optString(it)}}.orEmpty()
            val score=d.optDouble("ratings_average").takeIf {it.isFinite() && it in 1.0..5.0}
            val count=d.optInt("ratings_count",0).takeIf {it>0}
            val rating=score.takeIf {count!=null}
            // Work fields aggregate editions: do not transfer ISBN, publisher or publication date.
            Candidate(d.optString("title"),authors,"",d.optInt("number_of_pages_median"),
                BdTheque.Details(rating=rating,ratingScale=if(rating!=null)5.0 else null,reviewCount=if(rating!=null)count else null),"https://openlibrary.org$work",d.optJSONArray("isbn")?.let {a ->(0 until a.length()).map {a.optString(it)}} ?: emptyList())
        }
    }
    suspend fun lookup(b:Book,lines:List<String>,cache:MutableMap<String,String>,google:Boolean):BibliographicSources.Result?=withContext(Dispatchers.IO) {
        for(query in queries(b,lines)) {
            val encoded=URLEncoder.encode(query,"UTF-8")
            val url=if(google)"https://www.googleapis.com/books/v1/volumes?maxResults=20&q=$encoded" else
                "https://openlibrary.org/search.json?limit=20&lang=fr&fields=key,title,isbn,author_name,number_of_pages_median,ratings_average,ratings_count&q=$encoded"
            try {
                val json=cache[url] ?: run {delay(1000);Jsoup.connect(url).userAgent("BubbleBD/0.3 (bibliographic lookup)").timeout(12000).maxBodySize(2*1024*1024).ignoreContentType(true).execute().body().also {cache[url]=it}}
                val candidates=if(google)parseGoogle(json) else parseOpenLibrary(json)
                select(candidates,b,lines)?.let {return@withContext BibliographicSources.Result(it.details,listOf(it.url))}
            } catch(e:Exception) {if(e is CancellationException)throw e;throw IllegalStateException(if(google)"Google Books indisponible" else "Open Library indisponible",e)}
        }
        null
    }
}
