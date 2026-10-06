package fr.bubblebd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.HttpURLConnection

class MetadataLookupException(val reason:String):java.io.IOException(if(reason=="quota")"Quota gratuit atteint" else "Recherche différée")

/** Bibliographic clues pass through the common service; no reader account or API key. */
object MetadataInformation {
    data class Result(val details:BibliographicSources.Result,val attribution:String)
    fun validEndpoint(value:String)=runCatching {
        val u=URI(value);u.scheme=="https" && !u.host.isNullOrBlank() && u.userInfo==null && u.port in listOf(-1,443) && u.query==null && u.fragment==null
    }.getOrDefault(false)
    fun coreIdentity(original:Book):Map<String,String> {
        val book=BibliographicIdentity.forLookup(original)
        return mapOf("title" to book.title.take(240),"series" to book.series.take(200),"number" to book.number.take(12),"isbn" to book.isbn.take(20))
    }
    private fun clue(value:String,limit:Int)=value.replace(Regex("\\s+")," ").trim().take(limit)
    fun identity(book:Book)=coreIdentity(book)+mapOf(
        "artist" to clue(book.artist,300),"writer" to clue(book.writer,300),"publisher" to clue(book.publisher,300),
        "date" to clue(book.date,20),"genre" to clue(book.genre,200),"synopsis" to clue(book.synopsis,1500),
        "knownSources" to book.metadataSource.lines().filter {runCatching {val u=URI(it);u.scheme=="https" && u.host!=null && u.userInfo==null}.getOrDefault(false)}.take(3).joinToString(" ").take(1000))
    private fun key(value:String)=BookRules.normalized(value).replace(Regex("[^a-z0-9]+")," ").trim()
    private fun volume(value:String)=value.toIntOrNull()?.toString() ?: key(value)
    fun parse(raw:String,original:Book):Result? {
        val book=BibliographicIdentity.forLookup(original)
        val j=JSONObject(raw)
        if(!j.optBoolean("matched"))return null
        val coverMatch=j.optBoolean("identifiedFromCover") && j.optJSONObject("requestedIdentity")?.let {requested ->
            coreIdentity(book).all {(field,value)->requested.optString(field)==value}
        }==true
        if(!coverMatch && book.number.isNotBlank()) {
            if(volume(j.optString("number"))!=volume(book.number) || key(j.optString("series"))!=key(book.series.ifBlank {book.title}))return null
        } else if(!coverMatch && !BibliographicIdentity.titleMatches(j.optString("title"),book.title))return null
        if(book.number.isNotBlank() && volume(j.optString("number"))!=volume(book.number))return null
        val isbn=j.optString("isbn").filter {it.isDigit() || it=='X'}
        if(book.isbn.isNotBlank() && isbn!=book.isbn.filter {it.isDigit() || it=='X'})return null
        val sources=j.optJSONArray("sources") ?: return null
        val urls=(0 until sources.length()).map {sources.getJSONObject(it).optString("url")}.filter {url ->
            runCatching {val u=URI(url);u.scheme=="https" && !u.host.isNullOrBlank() && u.userInfo==null && u.port in listOf(-1,443)}.getOrDefault(false)
        }.distinct()
        val attribution=j.optString("searchAttribution")
        if(urls.isEmpty() || attribution.isBlank() || attribution.length>50000)return null
        fun field(name:String)=j.optString(name).take(400)
        val d=BdTheque.Details(series=field("series"),title=field("title"),artist=field("artist"),writer=field("writer"),publisher=field("publisher"),
            date=PublicationDate.storage(field("date")).orEmpty(),isbn=isbn.takeIf(BibliographicSources::validIsbn).orEmpty(),genre=field("genre"),synopsis=j.optString("synopsis").take(12000))
        return Result(BibliographicSources.Result(d,urls,true,field("number").takeIf {it.isNotBlank()}),attribution)
    }
    fun merge(book:Book,result:Result):Book {
        if(book.metadataLocked)return book
        val updated=BibliographicSources.merge(book,result.details)
        return updated.copy(metadataAttribution=result.attribution,metadataNote="",
            metadataSource=(updated.metadataSource.lines()+result.details.urls).filter(String::isNotBlank).distinct().joinToString("\n"))
    }
    suspend fun lookup(book:Book,endpoint:String=BuildConfig.METADATA_ENDPOINT):Result? =
        if(endpoint.isBlank())null else parse(fetch(book,endpoint),book)
    suspend fun fetch(book:Book,endpoint:String=BuildConfig.METADATA_ENDPOINT,clues:Map<String,String> = emptyMap()):String=withContext(Dispatchers.IO) {
        require(endpoint.isNotBlank())
        require(validEndpoint(endpoint))
        val connection=URI(endpoint.trimEnd('/')+"/v1/books").toURL().openConnection() as HttpURLConnection
        connection.requestMethod="POST";connection.connectTimeout=10000;connection.readTimeout=65000
        connection.instanceFollowRedirects=false;connection.doOutput=true
        connection.setRequestProperty("Content-Type","application/json")
        connection.setRequestProperty("User-Agent","BubbleBD/${BuildConfig.VERSION_NAME} (Android)")
        try {
            val body=JSONObject(identity(book)+clues).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use {it.write(body)}
            if(connection.responseCode==429) {
                val reason=runCatching {connection.errorStream.use {stream ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(512)
                    while(out.size()<2000) {val count=stream.read(buffer,0,minOf(buffer.size,2000-out.size()));if(count<0)break;out.write(buffer,0,count)}
                    JSONObject(out.toByteArray().toString(Charsets.UTF_8)).optString("error")
                }}.getOrDefault("")
                throw MetadataLookupException(if(reason in listOf("provider_quota","quota"))"quota" else "waiting")
            }
            if(connection.responseCode!=200)throw java.io.IOException("Service bibliographique indisponible (${connection.responseCode})")
            val raw=connection.inputStream.use {stream ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(out.size()<=150000) {val count=stream.read(buffer,0,minOf(buffer.size,150001-out.size()));if(count<0)break;out.write(buffer,0,count)}
                out.toByteArray()
            }
            require(raw.size<=150000) {"Réponse bibliographique trop grande"}
            val text=raw.toString(Charsets.UTF_8)
            require(!JSONObject(text).optBoolean("matched") || parse(text,book)!=null) {"Réponse bibliographique non vérifiable"}
            text
        }finally {connection.disconnect()}
    }
}
