package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

/** Public catalogues; same album and authors may use a reference edition, never a different tome. */
object BibliographicSources {
    data class Entry(val details:BdTheque.Details,val number:String,val url:String,val comic:Boolean=false,val subtitle:String="",val contents:List<String> = emptyList())
    data class Result(val details:BdTheque.Details,val urls:List<String>,val replaceExisting:Boolean=false,val number:String?=null)
    private fun key(s:String)=BookRules.normalized(s).replace(Regex("[^a-z0-9]+")," ").trim()
    /** Only a separate INT number or a terminal marker denotes an unnumbered integral. */
    fun integralTitle(b:Book):String? {
        fun base(text:String)=Regex("^(.*?)\\s+(?:int|integrale)$").matchEntire(BibliographicIdentity.titleKey(text))?.groupValues?.get(1)?.takeIf(String::isNotBlank)
        if(key(b.number) in setOf("int","integrale"))return base(b.series.ifBlank {b.title}) ?: key(b.series.ifBlank {b.title})
        if(b.number.isNotBlank())return null
        return base(b.title) ?: base(titleFromFilename(b))
    }
    private fun knownAuthorMatches(known:String,found:String):Boolean {
        if(known.isBlank())return true
        val words=key(known).split(' ').filter {it.length>=3}
        val evidence=key(found).split(' ').toSet()
        return if(words.isEmpty())key(known)==key(found) else words.all {it in evidence}
    }
    private fun isbn(s:String)=s.replace(Regex("[^0-9Xx]"),"").uppercase()
    private fun volume(s:String)=s.trim().toIntOrNull()?.toString() ?: key(s)
    private fun connection(url:String)=Jsoup.connect(url).userAgent("BubbleBD/0.3 (bibliographic lookup)").timeout(12000).maxBodySize(2*1024*1024)
    fun complete(b:Book)=b.title.isNotBlank() && !missing(b) && b.synopsis.isNotBlank()
    fun missing(b:Book)=listOf(b.artist,b.writer,b.publisher,b.date,b.isbn,b.genre).any(String::isBlank)
    fun parseBnf(xml:String):List<Entry> {
        val doc=Jsoup.parse(xml,"",Parser.xmlParser()).also {doc -> doc.getAllElements().forEach {it.tagName(it.tagName().substringAfter(':'))}}
        return doc.select("record").filter {it.selectFirst("datafield")!=null}.mapNotNull {r ->
            fun fields(tag:String)=r.select("datafield[tag=$tag]")
            fun Element.sub(code:String)=select("subfield[code=$code]").joinToString(", "){it.text()}
            fun value(tag:String,code:String)=fields(tag).map {it.sub(code)}.firstOrNull {it.isNotBlank()}.orEmpty()
            val title=value("200","a");if(title.isBlank())return@mapNotNull null
            val series=value("461","t").ifBlank {value("225","a")}
            val number=value("461","v").ifBlank {value("225","v")}.ifBlank {value("200","h")}
            val responsibility=(value("200","f")+" "+value("200","g")).lowercase()
            fun person(role:String)=listOf("700","701","702").flatMap {fields(it)}.filter {it.select("subfield[code=4]").any {s ->s.text()==role}}.map {
                listOf(it.sub("b"),it.sub("a")).filter(String::isNotBlank).joinToString(" ")
            }.distinct().joinToString(", ")
            val writer=if("scénario" in responsibility || "scenario" in responsibility)person("070") else ""
            fun publication(code:String)=fields("214").filter {it.attr("ind2")=="0"}.map {it.sub(code)}.firstOrNull(String::isNotBlank).orEmpty()
            val dateRaw=publication("d").ifBlank {value("214","d")}.ifBlank {value("210","d")}
            val date=Regex("(?:DL )?([12][0-9]{3})").matchEntire(dateRaw)?.groupValues?.get(1).orEmpty()
            val url=r.selectFirst("controlfield[tag=003]")?.text()?.replace("http://","https://").orEmpty()
            if(!url.startsWith("https://catalogue.bnf.fr/ark:/"))return@mapNotNull null
            Entry(BdTheque.Details(title=title,series=series,artist=person("440"),writer=writer,
                publisher=publication("c").ifBlank {value("214","c")}.ifBlank {value("210","c")},date=date,isbn=value("010","a"),genre=value("608","a"),synopsis=value("330","a").take(4000)),number,url,person("440").isNotBlank() || r.select("datafield[tag=608]").text().contains("bandes dessin",true),value("200","e"),fields("423").map {it.sub("t")}.filter(String::isNotBlank))
        }.distinctBy {it.url}
    }
    fun selectBnf(entries:List<Entry>,book:Book):Result? {
        val b=BibliographicIdentity.forLookup(book)
        val integral=integralTitle(b)
        val series=integral ?: key(b.series.ifBlank {b.title})
        val seriesEntry=entries.filter {it.comic && key(it.details.title)==series && it.details.series.isBlank() && it.details.isbn.isBlank() && knownAuthorMatches(b.artist,it.details.artist) && knownAuthorMatches(b.writer,it.details.writer)}.singleOrNull()
        val matches=if(b.isbn.isNotBlank())entries.filter {isbn(it.details.isbn)==isbn(b.isbn)} else entries.filter {
            if(integral!=null) it.comic && key(it.subtitle)=="integrale" && key(it.details.title)==integral && it.details.isbn.isNotBlank() &&
                knownAuthorMatches(b.artist,it.details.artist) && knownAuthorMatches(b.writer,it.details.writer)
            else if(b.number.isNotBlank()) key(it.details.series)==series && volume(it.number)==volume(b.number)
            else it.comic && BibliographicIdentity.titleMatches(it.details.title,b.title) && it.details.isbn.isNotBlank()
        }
        val compatibleMatches=if(b.isbn.isNotBlank())matches else matches.filter {
            knownAuthorMatches(b.artist,it.details.artist.ifBlank {seriesEntry?.details?.artist.orEmpty()}) &&
                knownAuthorMatches(b.writer,it.details.writer.ifBlank {seriesEntry?.details?.writer.orEmpty()})
        }
        val common=seriesEntry?.details
        // Different integral ranges are different albums. Explicit contents must agree across editions.
        val integralEditionsOk=integral==null || compatibleMatches.size<=1 || compatibleMatches.all {it.contents.isNotEmpty()} &&
            compatibleMatches.map {it.contents.map {title ->key(title).replace(" ","")}.sorted()}.distinct().size==1
        val album=compatibleMatches.singleOrNull() ?: if(integralEditionsOk)referenceEdition(compatibleMatches,common) else null
        // Known ISBN and tome remain constraints; homonymous works never share metadata.
        if(album==null) return seriesEntry?.let {Result(it.details.copy(title="",series=it.details.title,date="",isbn=""),listOf(it.url))}
        return Result(album.details.copy(artist=album.details.artist.ifBlank {common?.artist.orEmpty()},
            writer=album.details.writer.ifBlank {common?.writer.orEmpty()}),listOfNotNull(album.url,seriesEntry?.url),true,album.number.takeIf {it.isNotBlank()})
    }
    private fun referenceEdition(entries:List<Entry>,common:BdTheque.Details?):Entry? {
        if(entries.isEmpty() || entries.map {key(it.details.title)}.distinct().size!=1)return null
        val artists=entries.map {key(it.details.artist.ifBlank {common?.artist.orEmpty()})}
        val writers=entries.map {key(it.details.writer.ifBlank {common?.writer.orEmpty()})}
        if(artists.filter(String::isNotBlank).distinct().size>1 || writers.filter(String::isNotBlank).distinct().size>1)return null
        if(!(artists.all(String::isNotBlank) || writers.all(String::isNotBlank)))return null
        return entries.filter {validIsbn(it.details.isbn)}.minWithOrNull(compareBy<Entry> {it.details.date.ifBlank {"9999"}}
            .thenByDescending {listOf(it.details.artist,it.details.writer,it.details.publisher,it.details.genre,it.details.synopsis).count(String::isNotBlank)}
            .thenBy {isbn(it.details.isbn)}.thenBy {it.url})
    }
    fun validIsbn(value:String):Boolean {
        val n=isbn(value)
        return when(n.length) {
            13->n.all(Char::isDigit) && n.mapIndexed {i,c -> (c-'0')*(if(i%2==0)1 else 3)}.sum()%10==0
            10->n.take(9).all(Char::isDigit) && (n.last().isDigit() || n.last()=='X') && n.mapIndexed {i,c -> (if(c=='X')10 else c-'0')*(10-i)}.sum()%11==0
            else->false
        }
    }
    private suspend fun searchBnf(query:String,cache:MutableMap<String,List<Entry>>):List<Entry> = withContext(Dispatchers.IO) {
        cache[query] ?: run {
            val url="https://catalogue.bnf.fr/api/SRU?version=1.2&operation=searchRetrieve&recordSchema=unimarcxchange&maximumRecords=50&query="+URLEncoder.encode(query,"UTF-8")
            kotlinx.coroutines.delay(350)
            val response=connection(url).ignoreContentType(true).execute()
            parseBnf(response.body()).also {cache[query]=it}
        }
    }
    suspend fun bnf(book:Book,cache:MutableMap<String,List<Entry>>):Result? {
        val b=BibliographicIdentity.forLookup(book)
        val query=if(validIsbn(b.isbn))"bib.isbn all \"${isbn(b.isbn)}\"" else {
            val title=(integralTitle(b) ?: BibliographicIdentity.titleKey(b.series.ifBlank {b.title})).take(180)
            if(title.isBlank())return null
            "bib.title all \"$title\""
        }
        return selectBnf(searchBnf(query,cache),b)
    }
    fun titleFromFilename(b:Book):String {
        var text=b.filename.substringBeforeLast('.').replace('_',' ')
        if(b.series.isNotBlank() && text.startsWith(b.series,true))text=text.substring(b.series.length)
        text=text.trim().replace(Regex("(?i)^(?:\\s*[-–._]\\s*)?(?:(?:tome|t|fr|vol)\\s*)?0*[0-9]{1,3}(?:\\s*[-–._:]\\s*|\\s+|$)"),"")
        return text.trim(' ','-','–','.')
    }
    suspend fun fromFirstPage(b:Book,lines:List<String>,cache:MutableMap<String,List<Entry>>):Result? {
        val isbnCandidates=Regex("(?:97[89][ -]?)?[0-9][0-9 Xx-]{8,23}[0-9Xx]").findAll(lines.joinToString(" "))
            .map {isbn(it.value)}.filter(::validIsbn).distinct().take(2).toList()
        for(n in isbnCandidates)bnf(b.copy(isbn=n),cache)?.let {return it}
        // A failed integral match must not fall through to a single constituent tome or homonym.
        if(integralTitle(b)!=null)return null
        val explicit=titleFromFilename(b).takeIf {key(it)!=key(b.series) && key(it).length>=4}
        val candidates=(listOfNotNull(explicit)+lines).map {it.trim()}.filter {it.length in 4..90 && it.count(Char::isLetter)>=4 && !it.startsWith("ISBN",true)}.distinctBy(::key).take(5)
        val evidence=key(lines.joinToString(" ")+" "+b.writer+" "+b.artist).split(' ').toSet()
        for(title in candidates) {
            val k=key(title);if(k.isBlank())continue
            val matches=searchBnf("(bib.title all \"$k\" or bib.title all \"${k.replace(" ","")}\")",cache).filter {entry ->
                val d=entry.details
                val titleMatch=key(d.title).replace(" ","")==k.replace(" ","")
                val authorMatch=key(d.artist+" "+d.writer).split(' ').any {it.length>=4 && it in evidence}
                entry.comic && titleMatch && (title==explicit || authorMatch) && d.isbn.isNotBlank()
            }
            val found=matches.distinctBy {isbn(it.details.isbn)}.singleOrNull() ?: continue
            return Result(found.details,listOf(found.url),true,found.number.takeIf {it.isNotBlank()})
        }
        return null
    }
    fun dupuisAlbumLinks(html:String,b:Book):List<String> {
        val doc=Jsoup.parse(html,"https://www.dupuis.com/")
        val expected=key(b.series)
        if(expected.isBlank())return emptyList()
        return doc.select("a[href]").map {it.absUrl("href")}.filter {url ->
            val uri=runCatching {java.net.URI(url)}.getOrNull()
            uri?.host=="www.dupuis.com" && uri.scheme=="https" && uri.path.contains("/bd/") && !uri.path.contains("/reader/") &&
                key(uri.path.substringBefore("/bd/")).removePrefix("l ")==expected.removePrefix("l ")
        }.distinct().take(12)
    }
    fun parseDupuis(html:String,expectedIsbn:String):BdTheque.Details? {
        val doc=Jsoup.parse(html)
        val fields=doc.select("li").mapNotNull {li ->
            val text=li.text();val pos=text.indexOf(':');if(pos<0)null else key(text.substring(0,pos)) to text.substring(pos+1).trim()
        }.toMap()
        if(isbn(expectedIsbn).isBlank() || isbn(fields["isbn"].orEmpty())!=isbn(expectedIsbn))return null
        val raw=fields["date de parution"].orEmpty()
        val date=runCatching {java.time.LocalDate.parse(raw,java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString()}.getOrDefault("")
        return BdTheque.Details(date=date,genre=fields["genre"].orEmpty(),publisher="Dupuis",isbn=expectedIsbn)
    }
    suspend fun dupuis(b:Book,cache:MutableMap<String,String>):Result?=withContext(Dispatchers.IO) {
        if(!b.publisher.contains("Dupuis",true) || b.isbn.isBlank() || b.series.isBlank())return@withContext null
        val searchKey="search:"+key(b.series)
        val html=cache[searchKey] ?: connection("https://www.dupuis.com/catalogue/FR/moteur_recherche_menu.html")
            .data("nom",b.series).post().outerHtml().also {cache[searchKey]=it}
        // Follow only candidates matching the requested tome; verify the ISBN on the page.
        val links=dupuisAlbumLinks(html,b).filter {url -> b.number.toIntOrNull()?.let {n -> Regex("-tome-$n-").containsMatchIn(url)} ?: true}
        for(url in links) {
            val page=cache[url] ?: connection(url).get().outerHtml().also {cache[url]=it}
            val d=parseDupuis(page,b.isbn) ?: continue
            return@withContext Result(d,listOf(url))
        }
        null
    }
    fun merge(b:Book,result:Result):Book {
        if(b.metadataLocked)return b
        val d=result.details
        fun value(old:String,new:String)=if(new.isNotBlank() && (result.replaceExisting || old.isBlank()))new else old
        val updated=b.copy(title=value(b.title,d.title),series=value(b.series,d.series),number=result.number ?: b.number,
            artist=value(b.artist,d.artist),writer=value(b.writer,d.writer),publisher=value(b.publisher,d.publisher),
            date=value(b.date,d.date),isbn=value(b.isbn,d.isbn),genre=value(b.genre,d.genre),synopsis=value(b.synopsis,d.synopsis),
            rating=d.rating ?: b.rating,ratingScale=if(d.rating!=null)d.ratingScale else b.ratingScale,
            reviewCount=if(d.rating!=null)d.reviewCount else b.reviewCount,
            ratingSource=if(d.rating!=null)result.urls.first() else b.ratingSource)
        if(updated==b)return b
        return updated.copy(metadataSource=(b.metadataSource.lines()+result.urls).filter(String::isNotBlank).distinct().joinToString("\n"),metadataEdited=true,metadataNote="")
    }
    fun sourceLabel(value:String)=value.lines().mapNotNull {
        when {it.contains("catalogue.bnf.fr/")->"BnF";it.contains("dupuis.com/")->"Dupuis";it.contains("editions-delcourt.fr/")->"Delcourt";it.contains("bdtheque.com/")->"BDThèque";it.contains("bedetheque.com/")->"Bedetheque";it.contains("wikipedia.org/")->"Wikipédia";it.contains("amazon.fr/") || it.contains("amazon.com/")->"Amazon";it.contains("books.google.com/")->"Google Books";it.contains("openlibrary.org/")->"Open Library";else->runCatching {java.net.URI(it).host?.removePrefix("www.")}.getOrNull()}
    }.distinct().joinToString(" · ")
}
