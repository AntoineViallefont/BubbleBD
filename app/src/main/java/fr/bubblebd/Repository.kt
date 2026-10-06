package fr.bubblebd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import kotlin.coroutines.coroutineContext

object ArchiveBridge {
    init { System.loadLibrary("bubblearchive") }
    external fun entries(path: String): Array<String>
    external fun read(path: String, name: String): ByteArray
}
class ComicDocument(private val file: File): Closeable {
    private val pdf = if(file.extension.equals("pdf",true)) PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)) else null
    private val entries = if(pdf==null) ArchiveBridge.entries(file.path).toList() else emptyList()
    private val images = entries.filter { !it.startsWith("__MACOSX/") && Regex("(?i)\\.(jpe?g|png|webp|bmp|gif|avif)$").containsMatchIn(it) }.sortedWith(BookRules::compareNatural)
    val count get() = pdf?.pageCount ?: images.size
    fun metadata(): Map<String,String> {
        val path=entries.firstOrNull { it.substringAfterLast('/').equals("ComicInfo.xml",true) } ?: return emptyMap()
        val bytes=ArchiveBridge.read(file.path,path)
        if(bytes.size>2*1024*1024) return emptyMap()
        return runCatching {
            val parser=Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
            parser.setInput(bytes.inputStream(),null)
            val wanted=setOf("Title","Series","Number","Year","Month","Day","Genre","Penciller","Writer","Publisher","GTIN")
            val values=mutableMapOf<String,String>()
            while(parser.next()!=XmlPullParser.END_DOCUMENT) {
                if(parser.eventType==XmlPullParser.START_TAG && parser.name in wanted) {
                    val key=parser.name; values[key]=parser.nextText().trim()
                }
            }
            values
        }.getOrDefault(emptyMap())
    }
    @Synchronized fun bitmap(index:Int, target:Int=2200):Bitmap {
        require(index in 0 until count) { "Page hors limites" }
        pdf?.let { document ->
            document.openPage(index).use { p ->
                val ratio=minOf(target.toFloat()/maxOf(p.width,p.height),3f)
                val b=Bitmap.createBitmap((p.width*ratio).toInt().coerceAtLeast(1),(p.height*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE); p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); return b
            }
        }
        val data=ArchiveBridge.read(file.path,images[index])
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(data,0,data.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Image non prise en charge" }
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>target*1.5 || bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample)>8_000_000) sample*=2
        return BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: throw IOException("Image illisible")
    }
    @Synchronized override fun close() { pdf?.close() }
}
class Repository(val context:Context) {
    fun loadPanelStyle(id:String):PanelStyleHistory {
        val storage=context.getSharedPreferences("panel-style-v1",Context.MODE_PRIVATE)
        val entries=runCatching {
            val rows=JSONArray(storage.getString(id,"[]"))
            (0 until minOf(64,rows.length())).associate {i->val row=rows.getJSONArray(i);row.getInt(0) to (row.getInt(1) to row.getInt(2))}
        }.getOrDefault(emptyMap())
        return PanelStyleHistory(entries)
    }
    fun savePanelStyle(id:String,history:PanelStyleHistory) {
        val rows=JSONArray(history.snapshot().map {(page,counts)->listOf(page,counts.first,counts.second)})
        context.getSharedPreferences("panel-style-v1",Context.MODE_PRIVATE).edit().putString(id,rows.toString()).apply()
    }
    companion object {private val deletedLock=Any()}
    private val prefs=context.getSharedPreferences("bubblebd",Context.MODE_PRIVATE)
    fun metadataDue(id:String)=System.currentTimeMillis()-prefs.getLong("metadataChecked:v7:"+id,0)>24*60*60*1000L
    fun metadataChecked(id:String) {prefs.edit().putLong("metadataChecked:v7:"+id,System.currentTimeMillis()).apply()}
    fun metadataRetry(id:String) {prefs.edit().putLong("metadataChecked:v7:"+id,System.currentTimeMillis()-24*60*60*1000L+5*60*1000L).apply()}
    fun metadataInvalidate(id:String) {prefs.edit().remove("metadataChecked:v7:"+id).apply()}
    fun metadataAllowed()=System.currentTimeMillis()>prefs.getLong("metadataBlockedUntil",0)
    fun deferMetadata() {prefs.edit().putLong("metadataBlockedUntil",System.currentTimeMillis()+24*60*60*1000L).apply()}
    private val covers=File(context.filesDir,"covers").apply { mkdirs() }
    private val deletedCovers=File(context.filesDir,"deleted-covers").apply {mkdirs()}
    private val downloads=File(context.filesDir,"offline").apply { mkdirs() }
    private val cache=File(context.cacheDir,"comics").apply { mkdirs() }
    init {
        if(prefs.getInt("demoRemovalVersion",0)<1) {
            val all=loadBooks()
            all.filter {it.demo}.forEach {b -> File(covers,File(b.cover).name).delete()}
            File(context.filesDir,"demo").deleteRecursively()
            saveBooks(all.filterNot {it.demo})
            prefs.edit().putInt("demoRemovalVersion",1).apply()
        }
    }
    fun isCloud(uri:Uri):Boolean = uri.scheme=="onedrive" || (uri.scheme=="content" && uri.authority !in setOf("com.android.externalstorage.documents","com.android.providers.downloads.documents","com.android.providers.media.documents","media"))
    fun loadBooks():List<Book> = parseArray(prefs.getString("books","[]")).mapNotNull { runCatching { bookFrom(it) }.getOrNull() }
    fun saveBooks(books:List<Book>) { prefs.edit().putString("books",JSONArray(books.map(::bookJson)).toString()).apply() }
    fun loadFolders()=parseArray(prefs.getString("folders","[]")).map { SourceFolder(it.getString("uri"),it.getString("name"),it.optInt("count"),it.optLong("scanned"),it.optString("error")) }
    fun saveFolders(sources:List<SourceFolder>) { prefs.edit().putString("folders",JSONArray(sources.map { JSONObject().put("uri",it.uri).put("name",it.name).put("count",it.count).put("scanned",it.scanned).put("error",it.error) }).toString()).apply() }
    fun loadPrefs():Preferences {
        // The experimental model choice is retired; always use the compact model.
        if(prefs.contains("panelModel"))prefs.edit().remove("panelModel").apply()
        // Apply Antoine's new default once for existing installations.
        if(prefs.getInt("sortVersion",0)<2) prefs.edit().putString("sort","Dernière ouverture").putBoolean("descending",false).putInt("sortVersion",3).apply()
        else if(prefs.getInt("sortVersion",0)<3) {
            if(prefs.getString("sort","")=="Dernière ouverture") prefs.edit().putBoolean("descending",!prefs.getBoolean("descending",true)).apply()
            prefs.edit().putInt("sortVersion",3).apply()
        }
        return Preferences(prefs.getString("theme","system")!!,prefs.getBoolean("rtl",false),prefs.getBoolean("grid",true),prefs.getInt("columns",3),prefs.getString("sort","Dernière ouverture")!!,prefs.getBoolean("descending",false),prefs.getInt("cacheMb",512),prefs.getBoolean("homeGrid",true),prefs.getInt("outsideDim",60).coerceIn(0,100))
    }

    fun savePrefs(p:Preferences) { prefs.edit().putInt("sortVersion",3).putString("theme",p.theme).putBoolean("rtl",p.rtl).putBoolean("grid",p.grid).putInt("columns",p.columns).putString("sort",p.sort).putBoolean("descending",p.descending).putInt("cacheMb",p.cacheMb).putBoolean("homeGrid",p.homeGrid).putInt("outsideDim",p.outsideDim.coerceIn(0,100)).remove("panelModel").apply() }
    fun excludedUris():Set<String> = prefs.getStringSet("excludedUris",emptySet())!!.toSet()
    fun deletedBooks():List<Book> {
        val saved=parseArray(prefs.getString("deletedBooks","[]")).mapNotNull {runCatching {bookFrom(it)}.getOrNull()}.associateBy {it.uri}
        return excludedUris().map {uri -> saved[uri] ?: run {
            val name=Uri.parse(uri).lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank {"Album inaccessible"}
            cloudPlaceholder(Uri.parse(uri),name,"")
        }}.sortedBy {it.title.lowercase()}
    }
    fun allowImport(uri:String) = synchronized(deletedLock) {
        prefs.edit().putStringSet("excludedUris",excludedUris()-uri)
            .putString("deletedBooks",JSONArray(deletedBooks().filterNot {it.uri==uri}.map(::bookJson)).toString()).apply()
    }
    suspend fun resolveDeletedNames()=withContext(Dispatchers.IO) {
        val saved=parseArray(prefs.getString("deletedBooks","[]")).mapNotNull {runCatching {bookFrom(it)}.getOrNull()}.associateBy {it.uri}
        val resolved=deletedBooks().filter {it.uri !in saved}.mapNotNull {book ->
            val uri=Uri.parse(book.uri)
            val name=runCatching {
                if(uri.scheme=="onedrive")OneDrive.get(context).describe(book.uri).name
                else if(uri.scheme=="file")File(uri.path.orEmpty()).name
                else DocumentFile.fromSingleUri(context,uri)?.name
            }.getOrNull()
            if(name.isNullOrBlank())null else cloudPlaceholder(uri,name,book.folder)
        }
        synchronized(deletedLock) {
            val current=parseArray(prefs.getString("deletedBooks","[]")).mapNotNull {runCatching {bookFrom(it)}.getOrNull()}
            val known=current.map {it.uri}.toSet()
            prefs.edit().putString("deletedBooks",JSONArray((current+resolved.filter {it.uri !in known && it.uri in excludedUris()}).map(::bookJson)).toString()).apply()
        }
    }
    suspend fun deletedPreview(book:Book):Bitmap {
        var rendered:Bitmap?=null
        try {return withContext(Dispatchers.IO) {
            val saved=File(book.cover)
            if(saved.parentFile?.canonicalFile==deletedCovers.canonicalFile && saved.isFile) {
                rendered=android.graphics.BitmapFactory.decodeFile(saved.path)
                if(rendered!=null)return@withContext rendered!!
            }
            // Separate staging prevents a thumbnail from deleting a simultaneous reimport.
            val stage=File(context.cacheDir,"deleted-previews").apply {mkdirs()}
            val file=File.createTempFile("page-",".${book.filename.substringAfterLast('.')}",stage)
            try {
                val input=if(book.demo)File(context.filesDir,"demo/${book.filename}").inputStream()
                    else if(Uri.parse(book.uri).scheme=="onedrive")OneDrive.get(context).open(book.uri)
                    else context.contentResolver.openInputStream(Uri.parse(book.uri)) ?: throw IOException("Fichier inaccessible")
                input.use {source ->file.outputStream().use {out ->
                    val buffer=ByteArray(256*1024);var total=0L
                    while(true) {coroutineContext.ensureActive();val n=source.read(buffer);if(n<0)break;total+=n;require(total<=2L*1024*1024*1024) {"Album supérieur à 2 Go"};out.write(buffer,0,n)}
                }}
                ComicDocument(file).use {rendered=it.bitmap(0,420)}
                synchronized(deletedLock) {
                    if(book.uri in excludedUris()) {
                        val thumbnail=File(deletedCovers,"${book.id}.jpg")
                        thumbnail.outputStream().use {rendered!!.compress(Bitmap.CompressFormat.JPEG,88,it)}
                        val retained=deletedBooks().map {if(it.uri==book.uri)it.copy(cover=thumbnail.path) else it}
                        prefs.edit().putString("deletedBooks",JSONArray(retained.map(::bookJson)).toString()).apply()
                    }
                }
                rendered!!
            } finally {file.delete()}
        }} catch(e:Exception) {rendered?.recycle();throw e}
    }
    fun removeAlbum(book:Book) = synchronized(deletedLock) {
        val originalCover=File(book.cover)
        val preservedCover=File(deletedCovers,"${book.id}.jpg")
        if(originalCover.isFile && originalCover.parentFile?.canonicalFile==covers.canonicalFile)originalCover.copyTo(preservedCover,overwrite=true)
        val retained=deletedBooks().filterNot {it.uri==book.uri}+book.copy(cover=preservedCover.path.takeIf {preservedCover.isFile}.orEmpty(),pinned=false)
        prefs.edit().putStringSet("excludedUris",excludedUris()+book.uri)
            .putString("deletedBooks",JSONArray(retained.map(::bookJson)).toString()).apply()
        saveBooks(loadBooks().filterNot {it.id==book.id})
        val cover=File(book.cover)
        if(cover.parentFile?.canonicalFile==covers.canonicalFile) cover.delete()
        listOf(cache,downloads).forEach {dir -> dir.listFiles()?.filter {it.name.substringBefore('.')==book.id}?.forEach {it.delete()} }
        // Deliberately never touch book.uri: the user's source is preserved.
    }
    fun id(uri:String)=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    fun accepted(name:String)=name.substringAfterLast('.').lowercase() in listOf("cbz","zip","cbr","rar","pdf")
    data class SourceDocument(val uri:Uri,val name:String,val parent:String="",val parentName:String="")
    suspend fun discover(folder:SourceFolder):List<SourceDocument> = withContext(Dispatchers.IO) {
        if(Uri.parse(folder.uri).scheme=="onedrive") {
            val found=mutableListOf<SourceDocument>();val visited=mutableSetOf<String>()
            suspend fun walk(uri:String,label:String,depth:Int) {
                coroutineContext.ensureActive()
                if(depth>30 || !visited.add(uri))return
                for(item in OneDrive.get(context).children(uri)) {
                    check(found.size<10000) {"Limite de 10 000 albums par dossier atteinte"}
                    if(item.folder)walk(item.uri,item.name,depth+1) else if(accepted(item.name))found.add(SourceDocument(Uri.parse(item.uri),item.name,uri,label))
                }
            }
            walk(folder.uri,folder.name,0);return@withContext found
        }
        val root=DocumentFile.fromTreeUri(context,Uri.parse(folder.uri)) ?: throw IOException("Dossier inaccessible")
        if(!root.canRead()) throw IOException("Autorisation perdue : sélectionnez à nouveau le dossier")
        val found=mutableListOf<SourceDocument>(); val seen=mutableSetOf<String>()
        fun walk(d:DocumentFile,depth:Int) {
            if(depth>30 || !seen.add(d.uri.toString())) return
            for(f in d.listFiles()) {
                if(found.size>=10000) throw IOException("Limite de 10 000 albums par dossier atteinte")
                if(f.isDirectory) walk(f,depth+1) else if(accepted(f.name.orEmpty())) found.add(SourceDocument(f.uri,f.name.orEmpty(),d.uri.toString(),d.name.orEmpty()))
            }
        }
        walk(root,0); found
    }
    fun cloudPlaceholder(uri:Uri,name:String,folder:String):Book {
        val title=name.substringBeforeLast('.').replace('_',' ')
        val guessed=Regex("(?i)^(.+?)[ -]+(?:t(?:ome)?[ .]*)?(\\d{1,4})(?:[ -]+(.+))?$").matchEntire(title)
        return Book(id(uri.toString()),uri.toString(),name,title,series=guessed?.groupValues?.get(1).orEmpty(),number=guessed?.groupValues?.get(2).orEmpty(),folder=folder,cloud=isCloud(uri))
    }
    suspend fun index(uri:Uri, name:String, folder:String="", old:Book?=null):Book = withContext(Dispatchers.IO) {
        val base=old ?: Book(id(uri.toString()),uri.toString(),name,name.substringBeforeLast('.').replace('_',' '),folder=folder,cloud=isCloud(uri))
        try {
            val file=localFile(base)
            ComicDocument(file).use { doc ->
                require(doc.count>0) { "Aucune page image dans cette archive" }
                val cover=File(covers,"${base.id}.jpg")
                val bmp=doc.bitmap(0,1400); cover.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG,88,it) }; bmp.recycle()
                val m=doc.metadata()
                val guessed=Regex("(?i)^(.+?)[ -]+(?:t(?:ome)?[ .]*)?(\\d{1,4})(?:[ -]+(.+))?$").matchEntire(base.title)
                base.copy(
                    title=if(base.metadataEdited) base.title else m["Title"].orEmpty().ifBlank { guessed?.groupValues?.get(3)?.ifBlank { base.title } ?: base.title },
                    series=if(base.metadataEdited) base.series else m["Series"].orEmpty().ifBlank { guessed?.groupValues?.get(1).orEmpty().ifBlank {base.series} },
                    number=if(base.metadataEdited) base.number else m["Number"].orEmpty().ifBlank { guessed?.groupValues?.get(2).orEmpty().ifBlank {base.number} },
                    date=base.date.ifBlank { listOf("Year","Month","Day").mapNotNull { m[it]?.takeIf(String::isNotBlank) }.mapIndexed { i,s -> if(i>0) s.padStart(2,'0') else s }.joinToString("-") },
                    genre=base.genre.ifBlank { m["Genre"].orEmpty() }, artist=base.artist.ifBlank { m["Penciller"].orEmpty() }, writer=base.writer.ifBlank { m["Writer"].orEmpty() }, publisher=base.publisher.ifBlank { m["Publisher"].orEmpty() }, isbn=base.isbn.ifBlank {m["GTIN"].orEmpty()},
                    pages=doc.count,page=base.page.coerceAtMost(doc.count-1),cover=cover.path,issue="",coverIssue="",cloud=isCloud(uri),seriesFromFile=base.seriesFromFile || !m["Series"].isNullOrBlank() || !m["Number"].isNullOrBlank()
                )
            }
        } catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; base.copy(issue=e.message ?: "Lecture impossible") }
    }
    suspend fun fetchCover(book:Book):Book=withContext(Dispatchers.IO) {
        val cover=File(covers,"${book.id}.jpg")
        val temporary=File(context.cacheDir,"cover-staging/${book.id}.${book.filename.substringAfterLast('.')}").apply {parentFile!!.mkdirs()}
        val imageTemporary=File(covers,"${book.id}.part")
        try {
            var bitmap:Bitmap?=null
            if(Uri.parse(book.uri).scheme=="onedrive" && book.filename.substringAfterLast('.').lowercase() in listOf("zip","cbz")) {
                try {
                    val bytes=OneDrive.get(context).firstZipPage(book.uri)
                    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600 || bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample)>3_000_000)sample*=2
                    bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
                } catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException)throw e}
            }
            if(bitmap==null) {
                // RAR/PDF and providers without byte ranges need a temporary complete file, discarded below.
                val source=if(book.local)localFile(book) else {
                    val uri=Uri.parse(book.uri)
                    (if(uri.scheme=="onedrive")OneDrive.get(context).open(book.uri) else context.contentResolver.openInputStream(uri))?.use {input ->
                        temporary.outputStream().use {out ->
                            val buffer=ByteArray(256*1024);var total=0L
                            while(true) {coroutineContext.ensureActive();val n=input.read(buffer);if(n<0)break;total+=n;require(total<=2L*1024*1024*1024);out.write(buffer,0,n)}
                        }
                    } ?: error("Source inaccessible")
                    temporary
                }
                ComicDocument(source).use {bitmap=it.bitmap(0,1400)}
            }
            val result=bitmap ?: error("Première page illisible")
            try {imageTemporary.outputStream().use {result.compress(Bitmap.CompressFormat.JPEG,88,it)}} finally {result.recycle()}
            check(imageTemporary.renameTo(cover))
            book.copy(cover=cover.path,coverIssue="")
        } catch(e:Exception) {
            if(e is kotlinx.coroutines.CancellationException)throw e
            book.copy(coverIssue="Couverture indisponible. Relancez Scanner pour réessayer.")
        } finally {temporary.delete();imageTemporary.delete()}
    }
    suspend fun localFile(book:Book):File = withContext(Dispatchers.IO) {
        val ext=book.filename.substringAfterLast('.').lowercase()
        val saved=File(downloads,"${book.id}.$ext")
        if(saved.exists()) return@withContext saved
        if(book.demo) return@withContext File(context.filesDir,"demo/${book.filename}")
        val f=File(cache,"${book.id}.$ext")
        if(f.exists()) { f.setLastModified(System.currentTimeMillis()); return@withContext f }
        val temp=File(cache,"${book.id}.part")
        try {
            (if(Uri.parse(book.uri).scheme=="onedrive") OneDrive.get(context).open(book.uri) else context.contentResolver.openInputStream(Uri.parse(book.uri)))?.use { input ->
                temp.outputStream().use { output ->
                    val buffer=ByteArray(256*1024); var total=0L
                    while(true) { coroutineContext.ensureActive(); val n=input.read(buffer); if(n<0) break; total+=n; require(total<=2L*1024*1024*1024) { "Album supérieur à 2 Go" }; output.write(buffer,0,n) }
                }
            } ?: throw IOException("Fichier inaccessible")
            if(!temp.renameTo(f)) throw IOException("Impossible de conserver le fichier temporaire")
        } finally { temp.delete() }
        f
    }
    suspend fun pin(book:Book):Book = withContext(Dispatchers.IO) {
        val f=localFile(book); val dest=File(downloads,f.name)
        if(f!=dest) { val temp=File(downloads,"${f.name}.part"); try { f.copyTo(temp,true); check(temp.renameTo(dest)); } finally { temp.delete() } }
        book.copy(pinned=true)
    }
    fun unpin(book:Book):Book { File(downloads,"${book.id}.${book.filename.substringAfterLast('.').lowercase()}").delete(); return book.copy(pinned=false) }
    fun invalidateCache(book:Book) {File(cache,"${book.id}.${book.filename.substringAfterLast('.').lowercase()}").delete()}
    fun trimCache(maxMb:Int, keepId:String="") { var total=cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }; cache.listFiles()?.sortedBy { it.lastModified() }?.filter { !it.name.startsWith(keepId) || keepId.isEmpty() }?.forEach { if(total>maxMb*1024L*1024) { val size=it.length(); if(it.delete()) total-=size } } }
    fun clearCache() { cache.listFiles()?.forEach { it.delete() } }
    fun cacheSize()=cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }/1024/1024
    private fun parseArray(value:String?) = runCatching { val a=JSONArray(value); (0 until a.length()).map { a.getJSONObject(it) } }.getOrDefault(emptyList())
    private fun bookJson(b:Book)=JSONObject().apply {
        put("id",b.id); put("uri",b.uri); put("filename",b.filename); put("title",b.title); put("series",b.series); put("number",b.number); put("date",b.date); put("genre",b.genre); put("artist",b.artist); put("writer",b.writer); put("publisher",b.publisher); put("isbn",b.isbn); put("pages",b.pages); put("page",b.page); put("started",b.started); put("added",b.added); put("lastRead",b.lastRead); put("folder",b.folder); put("cover",b.cover); put("pinned",b.pinned); put("bdtheque",b.bdtheque); put("bdovore",b.bdovore); put("issue",b.issue); put("demo",b.demo); put("metadataEdited",b.metadataEdited);put("synopsis",b.synopsis);put("metadataSource",b.metadataSource);put("metadataNote",b.metadataNote);put("cloud",b.cloud);put("parentFolder",b.parentFolder);put("parentName",b.parentName);put("seriesFromFile",b.seriesFromFile);put("coverIssue",b.coverIssue);put("readingState",b.readingState);put("rating",b.rating);put("ratingScale",b.ratingScale);put("reviewCount",b.reviewCount);put("ratingSource",b.ratingSource);put("metadataAttribution",b.metadataAttribution);put("metadataLocked",b.metadataLocked);put("metadataRevision",b.metadataRevision)
    }
    private fun bookFrom(j:JSONObject)=Book(j.getString("id"),j.getString("uri"),j.getString("filename"),j.getString("title"),j.optString("series"),j.optString("number"),j.optString("date"),j.optString("genre"),j.optString("artist"),j.optString("writer"),j.optString("publisher"),j.optString("isbn"),j.optInt("pages"),j.optInt("page"),j.optBoolean("started"),j.optLong("added"),j.optLong("lastRead"),j.optString("folder"),j.optString("cover"),j.optBoolean("pinned"),j.optString("bdtheque"),j.optString("bdovore"),j.optString("issue"),j.optBoolean("demo"),j.optBoolean("metadataEdited"),j.optString("synopsis"),j.optString("metadataSource"),j.optString("metadataNote"),if(j.has("cloud"))j.optBoolean("cloud") else isCloud(Uri.parse(j.getString("uri"))),j.optString("parentFolder"),j.optString("parentName"),j.optBoolean("seriesFromFile"),j.optString("coverIssue"),j.optString("readingState"),j.optDouble("rating").takeIf {it.isFinite() && it>=0},j.optDouble("ratingScale").takeIf {it.isFinite() && it>0},if(j.isNull("reviewCount"))null else j.optInt("reviewCount",-1).takeIf {it>=0},j.optString("ratingSource"),j.optString("metadataAttribution"),j.optBoolean("metadataLocked"),j.optLong("metadataRevision"))
}
