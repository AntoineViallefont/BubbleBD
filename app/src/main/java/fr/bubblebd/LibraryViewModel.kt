package fr.bubblebd

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

class LibraryViewModel(app:Application):AndroidViewModel(app) {
    val repo=Repository(app)
    val oneDrive=OneDrive.get(app)
    var oneDriveConnected by mutableStateOf(oneDrive.connected);private set
    var books by mutableStateOf(SeriesInference.infer(repo.loadBooks())); private set
    var folders by mutableStateOf(repo.loadFolders()); private set
    var deletedBooks by mutableStateOf(repo.deletedBooks()); private set
    var prefs by mutableStateOf(repo.loadPrefs()); private set
    var busy by mutableStateOf(false); private set
    var task by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null)
    var coversLoading by mutableStateOf(false);private set
    var coversTask by mutableStateOf("");private set
    private var coverJob:kotlinx.coroutines.Job?=null
    private var metadataJob:kotlinx.coroutines.Job?=null
    private var metadataWorkStatus by mutableStateOf(MetadataSearchStatus())
    private var catalogSearchStatus by mutableStateOf(MetadataSearchStatus())
    val metadataSearchStatus:MetadataSearchStatus get()=when {
        metadataWorkStatus.phase==MetadataSearchStatus.Phase.SEARCHING -> metadataWorkStatus
        catalogSearchStatus.visible -> catalogSearchStatus
        books.any {!it.demo} -> metadataWorkStatus
        else -> MetadataSearchStatus()
    }
    private val metadataStore=MetadataQueueStore(app)
    private val metadataScope=MetadataScanStore(app)
    private var metadataRevision by mutableStateOf(0)
    fun albumResearch(book:Book):AlbumResearch {
        @Suppress("UNUSED_VARIABLE") val revision=metadataRevision
        val saved=metadataStore.research(book)
        val searching=listOf(metadataWorkStatus,catalogSearchStatus).any {it.phase==MetadataSearchStatus.Phase.SEARCHING && it.bookId==book.id}
        return if(searching)saved.copy(state=AlbumResearch.State.SEARCHING) else saved
    }
    private val metadataListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key ->
        viewModelScope.launch {metadataRevision++;if(key=="resultsChanged")applyMetadataResults()}
    }
    init {
        repo.saveBooks(books)
        MetadataWorker.retireLegacy(app)
        metadataScope.prune(books.map {it.id}.toSet())
        metadataStore.listen(metadataListener)
        applyMetadataResults()
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(MetadataWorker.NAME).collect {
                metadataWorkStatus=MetadataSearchStatus.fromWork(it)
            }
        }
        loadMissingCovers()
    }
    private fun applyMetadataResults() {viewModelScope.launch {
        val snapshot=books.toList()
        val pending=withContext(Dispatchers.IO) {snapshot.mapNotNull {book ->
            metadataStore.pending(book)?.let {Triple(book,metadataStore.fingerprint(book),it)}
        }.associateBy {it.first.id}}
        val applied=mutableListOf<Pair<Book,MetadataQueueStore.Pending>>()
        books=books.map {book ->
            val saved=pending[book.id] ?: return@map book
            if(metadataStore.fingerprint(book)!=saved.second)return@map book
            applied.add(book to saved.third)
            MetadataInformation.merge(book,saved.third.result)
        }
        if(applied.isNotEmpty()) {
            repo.saveBooks(books)
            val current=books.associateBy {it.id}
            withContext(Dispatchers.IO) {applied.forEach {(book,result) ->metadataStore.applied(book,result,current[book.id] ?: book)}}
        }
    }}
    override fun onCleared() {metadataStore.unlisten(metadataListener);super.onCleared()}
    fun refreshMetadata() {
        val targets=books.filter {!it.demo && !it.metadataLocked}
        targets.forEach {repo.metadataInvalidate(it.id);metadataStore.invalidate(it)}
        metadataScope.request(targets.map {it.id}.toSet(),replace=true)
        enrichMissing()
    }
    fun saveMetadata(edited:Book) {
        val current=books.firstOrNull {it.id==edited.id} ?: return
        update(current.copy(title=edited.title,series=edited.series,number=edited.number,date=edited.date,
            genre=edited.genre,artist=edited.artist,writer=edited.writer,publisher=edited.publisher,isbn=edited.isbn,
            synopsis=edited.synopsis,metadataEdited=true,seriesFromFile=edited.seriesFromFile,
            metadataLocked=edited.metadataLocked,metadataRevision=current.metadataRevision+1,metadataNote=""))
        metadataScope.complete("catalog",edited.id);metadataScope.complete("service",edited.id)
    }
    fun searchMetadataFor(edited:Book) {
        if(edited.metadataLocked || edited.demo)return
        saveMetadata(edited)
        val current=books.firstOrNull {it.id==edited.id} ?: return
        repo.metadataInvalidate(current.id);metadataStore.invalidate(current)
        metadataScope.request(setOf(current.id))
        // Restart only the catalog coroutine; existing explicitly requested targets remain queued.
        metadataJob?.cancel();metadataJob=null
        enrichMissing()
    }
    private fun searchImported(previous:Set<String>) {
        val added=books.filter {!it.demo && !it.metadataLocked && it.id !in previous && ExtendedSources.needsLookup(it)}
        if(added.isEmpty())return
        metadataScope.request(added.map {it.id}.toSet())
        enrichMissing()
    }
    private fun enrichMissing() {
        if(metadataScope.pending("service").isNotEmpty())MetadataWorker.schedule(getApplication())
        if(metadataScope.pending("catalog").isEmpty())return
        if(metadataJob?.isActive==true)return
        metadataJob=viewModelScope.launch {
            val bnfCache=mutableMapOf<String,List<BibliographicSources.Entry>>()
            val publisherCache=mutableMapOf<String,String>()
            val attempted=mutableSetOf<String>()
            var publisherAllowed=true
            val extendedCache=mutableMapOf<String,String>()
            var googleAllowed=true
            var openLibraryAllowed=true
            var wikipediaAllowed=true
            try {
                while(true) {
                    val pendingCatalog=metadataScope.pending("catalog")
                    val targets=metadataScope.targets()
                    val eligible=books.filter {!it.demo && !it.metadataLocked && it.id in pendingCatalog && it.id !in attempted && repo.metadataDue(it.id)}
                    val b=MetadataOrder.ordered(eligible).firstOrNull() ?: break
                    attempted.add(b.id)
                    catalogSearchStatus=MetadataSearchStatus(MetadataSearchStatus.Phase.SEARCHING,b.title,books.count {it.id in targets}-pendingCatalog.size+1,books.count {it.id in targets},b.id)
                    var networkFailed=false
                    val notes=mutableListOf<String>()
                    var bnf:BibliographicSources.Result?=null
                    val afterSearch=books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision} ?: continue
                    val ratingOnly=BibliographicSources.complete(afterSearch)
                    try {
                        books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {update(it.copy(metadataNote="Recherche des informations…"))}
                        bnf=BibliographicSources.bnf(afterSearch,bnfCache)
                        if(bnf?.details?.isbn.isNullOrBlank()) {
                            val lines=CoverRecognition.read(getApplication(),b.cover)
                            val identified=BibliographicSources.fromFirstPage(afterSearch,lines,bnfCache)
                            if(identified!=null)bnf=identified
                        }
                    } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        networkFailed=true;notes.add("Recherche interrompue. Relancez la recherche depuis les réglages.")
                    }
                    // Identify first, then prefer the publisher’s precise publication fields.
                    val candidate=bnf?.let {BibliographicSources.merge(afterSearch,it)} ?: afterSearch
                    if(bnf!=null)books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {update(BibliographicSources.merge(it,bnf))}
                    if(publisherAllowed && (candidate.date.isBlank() || candidate.genre.isBlank() || candidate.synopsis.isBlank()))try {
                        val publisher=if(candidate.publisher.contains("Delcourt",true))PublisherSources.delcourt(candidate,publisherCache) else BibliographicSources.dupuis(candidate,publisherCache)
                        if(publisher!=null)books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {update(BibliographicSources.merge(it,publisher))}
                    } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        publisherAllowed=false;notes.add("Complément éditeur temporairement indisponible.")
                    }
                    try {
                        PublicComicSources.lookup(candidate.copy(metadataEdited=afterSearch.metadataEdited),extendedCache)?.let {result ->
                            books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {update(BibliographicSources.merge(it,result))}
                        }
                    } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        notes.add("Complément éditeur indisponible.")
                    }
                    var currentExtra=books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision} ?: continue
                    if(wikipediaAllowed && (BibliographicSources.missing(currentExtra) || currentExtra.synopsis.isBlank()))try {
                        WebBibliographicSources.wikipedia(currentExtra,extendedCache)?.let {result ->
                            books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {update(BibliographicSources.merge(it,result))}
                            currentExtra=books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision} ?: currentExtra
                        }
                    } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        wikipediaAllowed=false;networkFailed=true;notes.add("Wikipédia temporairement indisponible.")
                    }
                    if(BibliographicSources.missing(currentExtra) || currentExtra.synopsis.isBlank() || currentExtra.rating==null) {
                        val lines=if(ratingOnly)emptyList() else CoverRecognition.read(getApplication(),currentExtra.cover)
                        for(google in listOf(true,false)) {
                            if(google && !googleAllowed || !google && !openLibraryAllowed)continue
                            try {
                                ExtendedSources.lookup(currentExtra,lines,extendedCache,google)?.let {result ->
                                    books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {update(if(ratingOnly)ExtendedSources.mergeRating(it,result) else BibliographicSources.merge(it,result))}
                                    currentExtra=books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision} ?: currentExtra
                                }
                            } catch(e:Exception) {
                                if(e is kotlinx.coroutines.CancellationException)throw e
                                networkFailed=true
                                if(google)googleAllowed=false else openLibraryAllowed=false
                                notes.add(e.message.orEmpty())
                            }
                            if(!BibliographicSources.missing(currentExtra) && currentExtra.synopsis.isNotBlank() && currentExtra.rating!=null)break
                        }
                    }
                    try {
                        val current=books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision} ?: continue
                        MorePublisherSources.lookup(current,extendedCache)?.let {result ->
                            books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {update(BibliographicSources.merge(it,result))}
                        }
                    } catch(e:Exception) {
                        if(e is kotlinx.coroutines.CancellationException)throw e
                        notes.add("Complément éditeur indisponible.")
                    }
                    if(networkFailed)repo.metadataRetry(b.id) else repo.metadataChecked(b.id)
                    metadataScope.complete("catalog",b.id)
                    books.firstOrNull {it.id==b.id && !it.metadataLocked && it.metadataRevision==b.metadataRevision}?.let {
                        if(bnf==null && notes.isEmpty() && BibliographicSources.missing(it))notes.add("Aucune correspondance suffisamment sûre avec le nom du fichier et la première page.")
                        update(it.copy(metadataNote=notes.joinToString(" ")))
                    }
                    catalogSearchStatus=MetadataSearchStatus()
                    kotlinx.coroutines.delay(1500)
                }
            } finally {catalogSearchStatus=MetadataSearchStatus()}
        }
    }
    private fun loadMissingCovers() {
        if(coverJob?.isActive==true)return
        coverJob=viewModelScope.launch {
            coversLoading=true
            val attempted=mutableSetOf<String>()
            try {
                while(true) {
                    val book=books.firstOrNull {it.cloud && !java.io.File(it.cover).isFile && it.id !in attempted} ?: break
                    attempted.add(book.id);coversTask="Couverture · ${book.title}"
                    val loaded=repo.fetchCover(book)
                    val current=books.firstOrNull {it.id==book.id}
                    if(current!=null) {
                        update(current.copy(cover=loaded.cover.takeIf {java.io.File(it).isFile} ?: current.cover,coverIssue=if(java.io.File(current.cover).isFile) "" else loaded.coverIssue))
                    }
                    else if(loaded.cover.isNotBlank())java.io.File(loaded.cover).delete()
                }
            } finally {coversLoading=false;coversTask=""}
        }
    }
    fun preferences(p:Preferences) { prefs=p; repo.savePrefs(p) }
    fun update(b:Book) { books=books.map { if(it.id==b.id) b else it }; repo.saveBooks(books) }

    fun markReading(ids:Set<String>,read:Boolean) {
        books=books.map {if(it.id in ids)it.copy(readingState=if(read)"read" else "to_read",started=read,page=if(read)maxOf(0,it.pages-1) else 0) else it}
        repo.saveBooks(books)
    }
    fun progress(id:String,page:Int,total:Int) { books.firstOrNull { it.id==id }?.let { update(it.copy(readingState="",page=page,pages=total,started=true,lastRead=System.currentTimeMillis())) } }
    fun source(uri:Uri) {
        if(folders.none { it.uri==uri.toString() }) {
            folders=folders+SourceFolder(uri.toString(),DocumentFile.fromTreeUri(getApplication(),uri)?.name ?: "Dossier BD")
            repo.saveFolders(folders)
        }
        scan()
    }
    fun cloudSource(item:OneDrive.Item) {
        if(folders.none {it.uri==item.uri}) {folders=folders+SourceFolder(item.uri,item.name);repo.saveFolders(folders)}
        scan()
    }
    fun finishOneDrive(intent:android.content.Intent,ready:()->Unit) {
        viewModelScope.launch {
            try {oneDrive.finishLogin(intent);oneDriveConnected=oneDrive.connected;ready()}
            catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException)throw e;message=e.message}
        }
    }
    fun disconnectOneDrive() {viewModelScope.launch {oneDrive.disconnect();oneDriveConnected=false;message="OneDrive déconnecté. Les copies hors ligne restent disponibles."}}
    fun prepare(b:Book,ready:(Book)->Unit) {
        if(b.pages>0) {ready(b);return}
        if(busy)return
        viewModelScope.launch {busy=true;task="Téléchargement et ouverture de ${b.title}…"
            try {val loaded=repo.index(Uri.parse(b.uri),b.filename,b.folder,b);merge(loaded);ready(loaded)}
            finally {busy=false;task=""}
        }
    }
    fun removeSource(source:SourceFolder) {
        folders=folders.filterNot { it.uri==source.uri }; repo.saveFolders(folders)
        message="Dossier retiré du scan. Les albums et leur progression sont conservés."
    }
    fun scan() {
        if(busy) return
        viewModelScope.launch {
            busy=true
            val previous=books.map {it.id}.toSet()
            try {
                for(source in folders.toList()) {
                    task="Analyse de ${source.name}…"
                    runCatching {
                        val files=repo.discover(source)
                        for((i,file) in files.withIndex()) {
                            task="${source.name} · ${i+1}/${files.size}"
                            if(file.uri.toString() in repo.excludedUris()) continue
                            val existing=books.firstOrNull { it.uri==file.uri.toString() }
                            // A normal rescan skips indexed files; rereading is available from the album sheet.
                            if(existing!=null && existing.issue.isEmpty()) {
                                if(existing.parentFolder!=file.parent || existing.parentName!=file.parentName) merge(existing.copy(parentFolder=file.parent,parentName=file.parentName))
                                continue
                            }
                            val book=if(repo.isCloud(file.uri) && existing==null)repo.cloudPlaceholder(file.uri,file.name,source.uri) else repo.index(file.uri,file.name,source.uri,existing)
                            merge(book.copy(parentFolder=file.parent,parentName=file.parentName))
                            withContext(Dispatchers.IO) { repo.trimCache(prefs.cacheMb) }
                        }
                        folders=folders.map { if(it.uri==source.uri) it.copy(count=files.size,scanned=System.currentTimeMillis(),error="") else it }
                    }.onFailure { e -> folders=folders.map { if(it.uri==source.uri) it.copy(error=e.message ?: "Dossier inaccessible") else it } }
                    repo.saveFolders(folders)
                }
                books=SeriesInference.infer(books);repo.saveBooks(books);loadMissingCovers();searchImported(previous)
                message="Scan terminé · ${books.size} album(s) dans la bibliothèque"
            } finally { busy=false; task="" }
        }
    }
    fun import(uris:List<Uri>) {
        if(busy) return
        viewModelScope.launch {
            busy=true
            val previous=books.map {it.id}.toSet()
            try {
                for((i,uri) in uris.withIndex()) {
                    task="Import ${i+1}/${uris.size}"
                    val name=DocumentFile.fromSingleUri(getApplication(),uri)?.name ?: "Album"
                    if(!repo.accepted(name)) { message="Format non reconnu : $name"; continue }
                    repo.allowImport(uri.toString())
                    merge(repo.index(uri,name,old=books.firstOrNull { it.uri==uri.toString() }))
                    withContext(Dispatchers.IO) { repo.trimCache(prefs.cacheMb) }
                }
                books=SeriesInference.infer(books);repo.saveBooks(books);searchImported(previous)
                message="Import terminé"
            } finally { busy=false; task="" }
        }
    }
    private fun merge(b:Book) { books=if(books.any { it.id==b.id }) books.map { if(it.id==b.id) b else it } else books+b; repo.saveBooks(books) }
    fun removeAlbum(b:Book) {
        if(busy) return
        repo.removeAlbum(b);deletedBooks=repo.deletedBooks();books=books.filterNot {it.id==b.id};metadataStore.prune(books);metadataScope.prune(books.map {it.id}.toSet())
        message="Album supprimé de BubbleBD. Fichier original conservé."
    }
    fun refreshDeletedNames() {viewModelScope.launch {repo.resolveDeletedNames();deletedBooks=repo.deletedBooks()}}
    fun restoreDeleted(selected:Set<String>) {
        if(busy || selected.isEmpty())return
        val targets=deletedBooks.filter {it.id in selected}
        viewModelScope.launch {
            busy=true
            val previous=books.map {it.id}.toSet()
            var restored=0
            try {
                for((i,book) in targets.withIndex()) {
                    task="Réimport ${i+1}/${targets.size}"
                    val indexed=repo.index(Uri.parse(book.uri),book.filename,book.folder,book.copy(cover="",pinned=false))
                    if(indexed.issue.isEmpty()) {merge(indexed);repo.allowImport(book.uri);restored++}
                    withContext(Dispatchers.IO) {repo.trimCache(prefs.cacheMb)}
                }
                deletedBooks=repo.deletedBooks()
                books=SeriesInference.infer(books);repo.saveBooks(books);searchImported(previous)
                message=if(restored==targets.size)"$restored album(s) réimporté(s)" else "$restored/${targets.size} réimporté(s) · sources indisponibles conservées dans les albums supprimés"
            } finally {busy=false;task=""}
        }
    }
    fun offline(b:Book) {
        if(busy) return
        viewModelScope.launch {
            busy=true; task=if(b.pinned) "Suppression de la copie hors ligne…" else "Conservation hors ligne…"
            try { update(if(b.pinned) repo.unpin(b) else repo.pin(b)) }
            catch(e:Exception) { message=e.message }
            finally { busy=false; task="" }
        }
    }
    fun reindex(b:Book) {
        if(busy) return
        viewModelScope.launch { busy=true; task="Relecture de l’album…"; try { repo.invalidateCache(b); merge(repo.index(Uri.parse(b.uri),b.filename,b.folder,b)) } finally { busy=false; task="" } }
    }
}
