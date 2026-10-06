package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** Direct, bounded encyclopedia lookup, independent of the shared AI quota. */
object WebBibliographicSources {
    private fun key(value:String)=BookRules.normalized(value).replace(Regex("[^a-z0-9]+")," ").trim()
    private fun titleKey(value:String)=key(value.replace(Regex("(?i)\\s*\\((?:bande dessinée|album|comics?)\\)\\s*$"),""))
    private fun canonicalIsbn(value:String):String {
        val n=value.filter {it.isDigit() || it.uppercaseChar()=='X'}.uppercase()
        if(n.length!=10 || !BibliographicSources.validIsbn(n))return n
        val prefix="978"+n.take(9)
        val checksum=(10-prefix.mapIndexed {i,c ->(c-'0')*(if(i%2==0)1 else 3)}.sum()%10)%10
        return prefix+checksum
    }
    fun queries(book:Book)=listOf(book.title,BibliographicSources.titleFromFilename(book),book.series)
        .map {it.trim()}.filter {it.length in 2..240}.distinctBy(::key).take(3)

    fun parseWikipedia(html:String,url:String,book:Book):BibliographicSources.Result? {
        val uri=runCatching {URI(url)}.getOrNull() ?: return null
        if(uri.scheme!="https" || uri.host!="fr.wikipedia.org" || !uri.path.startsWith("/wiki/") || uri.userInfo!=null || uri.port !in listOf(-1,443))return null
        val doc=Jsoup.parse(html,url)
        val article=doc.selectFirst(".mw-parser-output") ?: return null
        if(doc.select(".bandeau-homonymie, .homonymie").isNotEmpty())return null
        val title=doc.selectFirst("#firstHeading, h1")?.text().orEmpty()
        val requested=queries(book).map(::titleKey).toSet()
        if(titleKey(title) !in requested)return null
        val fields=mutableMapOf<String,String>()
        article.select(".infobox tr, .infobox_v2 tr").forEach {row ->
            val label=row.selectFirst("th")?.text().orEmpty()
            val value=row.selectFirst("td")?.text().orEmpty()
            if(label.isNotBlank() && value.isNotBlank())fields[key(label)]=value
        }
        fun field(vararg labels:String)=labels.firstNotNullOfOrNull {fields[it]} ?: ""
        article.select("sup, .infobox, .infobox_v2, .bandeau-container, .navbox, .mw-editsection, .reflist, .references").remove()
        val paragraphs=article.select("p").filter {it.text().isNotBlank()}
        val intro=paragraphs.take(2).joinToString(" "){it.text()}
        if(!key(intro).contains("bande dessinee") && !key(field("charte")).contains("comic"))return null

        // Album lists determine scope: a series introduction is never a tome synopsis.
        fun sectionEntries(heading:Element):List<Element> {
            val section=heading.parents().firstOrNull {it.tagName()=="section"}
            if(section!=null)return section.select("li").filter {it.parents().count {p->p.tagName()=="li"}==0}
            val container=heading.parent()?.takeIf {it.hasClass("mw-heading")} ?: heading
            val entries=mutableListOf<Element>()
            var next=container.nextElementSibling()
            while(next!=null && next.tagName() !in listOf("h2","h3") && !next.hasClass("mw-heading")) {
                entries.addAll(next.select("li").filter {it.parents().count {p->p.tagName()=="li"}==0})
                next=next.nextElementSibling()
            }
            return entries
        }
        val albumEntries=article.select("h2, h3").filter {key(it.text()) in listOf("album","albums","publication","publications")}
            .flatMap(::sectionEntries).filter {Regex("\\b(?:19|20)\\d{2}\\b").containsMatchIn(it.text())}
        val albumCategory=doc.select("#catlinks a, link[rel=mw:PageProp/Category]").any {key(it.text()+" "+it.attr("href")).contains("album de bande dessinee")}
        val singleAlbum=albumEntries.size==1 || albumCategory && albumEntries.isEmpty()
        val requestedVolume=book.number.toIntOrNull()
        val albumScope=singleAlbum && (book.number.isBlank() || requestedVolume==1)
        val albumText=if(albumScope)albumEntries.singleOrNull()?.text().orEmpty() else ""
        val isbn=Regex("(?i)ISBN\\s*[:：]?\\s*([0-9Xx][0-9Xx\\s-]{8,24})").find(albumText.ifBlank {field("isbn")})
            ?.groupValues?.get(1)?.filter {it.isDigit() || it.uppercaseChar()=='X'}.orEmpty()
            .takeIf(BibliographicSources::validIsbn).orEmpty()
        if(albumScope && book.isbn.isNotBlank() && isbn.isNotBlank() && canonicalIsbn(book.isbn)!=canonicalIsbn(isbn))return null
        fun role(pattern:String)=Regex(pattern,RegexOption.IGNORE_CASE).find(intro)?.groupValues?.get(1)?.trim().orEmpty()
        val both=role("dessinée?\\s+et\\s+scénarisée?\\s+par\\s+([^.;]+)")
        val artist=field("dessin","dessinateur","dessinateurs").ifBlank {both.ifBlank {role("dessinée?\\s+par\\s+([^.;]+)")}}
        val writer=field("scenario","scenariste","scenaristes").ifBlank {both.ifBlank {role("scénarisée?\\s+par\\s+([^.;]+)")}}
        // Known author clues must agree with the article, even for a title match.
        for((known,found) in listOf(book.artist to artist,book.writer to writer)) {
            if(known.isNotBlank() && found.isNotBlank() && key(known)!=key(found))return null
        }
        val publisher=if(albumScope)field("editeur","editeurs").ifBlank {
            article.select("p, li").firstNotNullOfOrNull {element ->
                Regex("(?i)^(?:L['’]éditeur est|Éditeur\\s*:)\\s*(.+?)(?:[,.;]|$)").find(element.text())?.groupValues?.get(1)?.trim()
            }.orEmpty()
        } else ""
        val year=if(albumScope)Regex("\\b(?:19|20)\\d{2}\\b").find(albumText.ifBlank {field("premiere publication","premiere parution","publication","date de parution")})?.value.orEmpty() else ""
        val genre=field("genre","genres").ifBlank {article.select("a").filter {key(it.text()) in listOf("science fiction","fantastique","fantasy","humour","aventure","western","historique","policier")}.map {it.text()}.distinct().joinToString(", ")}
        val synopsis=if(albumScope)intro.take(1100) else ""
        val details=BdTheque.Details(artist=artist,writer=writer,publisher=publisher,date=year,isbn=if(albumScope)isbn else "",genre=genre,synopsis=synopsis)
        if(details==BdTheque.Details())return null
        return BibliographicSources.Result(details,listOf(url))
    }

    suspend fun wikipedia(book:Book,cache:MutableMap<String,String>):BibliographicSources.Result?=withContext(Dispatchers.IO) {
        suspend fun get(url:String):String=cache[url] ?: run {
            delay(1000)
            Jsoup.connect(url).userAgent("BubbleBD/0.3 (bibliographic lookup; https://bubblebd-source-macavi.web.app)")
                .timeout(12000).maxBodySize(2*1024*1024).ignoreContentType(true).followRedirects(false)
                .execute().body().also {if(cache.size>=24)cache.keys.firstOrNull()?.let(cache::remove);cache[url]=it}
        }
        for(query in queries(book)) {
            val api="https://fr.wikipedia.org/w/api.php?action=query&list=search&srlimit=3&format=json&srsearch="+URLEncoder.encode(query,"UTF-8")
            val results=JSONObject(get(api)).optJSONObject("query")?.optJSONArray("search") ?: continue
            val titles=(0 until results.length()).map {results.getJSONObject(it).optString("title")}
                .filter {titleKey(it)==titleKey(query)}.distinct()
            if(titles.size!=1)continue
            val url="https://fr.wikipedia.org/wiki/"+URLEncoder.encode(titles.single().replace(' ','_'),"UTF-8").replace("+","%20")
            parseWikipedia(get(url),url,book)?.let {return@withContext it}
        }
        null
    }
}
