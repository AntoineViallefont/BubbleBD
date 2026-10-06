package fr.bubblebd

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Worker results are separate from the library: reading progress is never overwritten. */
class MetadataQueueStore(context:Context,val endpoint:String=BuildConfig.METADATA_ENDPOINT) {
    companion object { private val lock=Any() }
    // Keep the existing production history, but isolate other service endpoints.
    // A production worker must never prune a development/test service's pending results.
    private val suffix=if(endpoint==BuildConfig.METADATA_ENDPOINT)"" else "-"+digest(endpoint).take(16)
    private val preferences=context.getSharedPreferences("metadata-queue-v1"+suffix,Context.MODE_PRIVATE)
    private val directory=File(context.filesDir,"metadata-pending"+suffix).apply {mkdirs()}
    private fun file(id:String)=AtomicFile(File(directory,digest(id)+".json"))
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    fun fingerprint(book:Book):String {
        val legacy=endpoint+"\n"+JSONObject(MetadataInformation.coreIdentity(book)).toString()
        return digest(if(book.metadataRevision==0L && !book.metadataLocked)legacy else legacy+"\n"+book.metadataRevision+"\n"+book.metadataLocked)
    }
    private fun cluesFingerprint(book:Book)=digest(JSONObject(MetadataInformation.identity(book)).toString()+File(book.cover).let {"${it.length()}:${it.lastModified()}"})
    private fun state(book:Book)=runCatching {JSONObject(preferences.getString(book.id,"{}").orEmpty())}.getOrElse {JSONObject()}
    fun research(book:Book):AlbumResearch {
        val saved=state(book)
        if(saved.optString("identity")!=fingerprint(book))return AlbumResearch()
        val completed=if(saved.has("completedAt"))saved.optLong("completedAt") else null
        val attempted=if(saved.has("attemptedAt"))saved.optLong("attemptedAt") else null
        val phase=when {
            saved.optBoolean("failed") -> if(saved.optString("failureReason")=="quota")AlbumResearch.State.QUOTA else AlbumResearch.State.RETRY
            saved.has("matched") -> if(saved.optBoolean("matched"))AlbumResearch.State.FOUND else AlbumResearch.State.NO_MATCH
            saved.has("nextAt") -> AlbumResearch.State.LEGACY
            else -> AlbumResearch.State.PENDING
        }
        return AlbumResearch(phase,completed,attempted)
    }
    fun attempted(book:Book,now:Long)=synchronized(lock) {
        val saved=state(book).takeIf {it.optString("identity")==fingerprint(book)} ?: JSONObject()
        preferences.edit().putString(book.id,saved.put("identity",fingerprint(book)).put("attemptedAt",now).put("failed",false).toString()).apply()
    }
    fun failed(book:Book,reason:String="")=synchronized(lock) {
        val saved=state(book)
        if(saved.optString("identity")==fingerprint(book))preferences.edit().putString(book.id,saved.put("failed",true).put("failureReason",reason).toString()).apply()
    }
    fun due(book:Book,now:Long=System.currentTimeMillis()):Boolean {
        if(book.demo || book.metadataLocked)return false
        val state=state(book)
        if(state.optBoolean("explicitRequest"))return true
        if(BibliographicSources.complete(book))return false
        return state.optString("identity")!=fingerprint(book) || (state.has("matched") && !state.optBoolean("matched") && state.optString("clues")!=cluesFingerprint(book)) || now>=state.optLong("nextAt",0)
    }
    fun invalidate(book:Book)=synchronized(lock) {
        val saved=state(book)
        preferences.edit().putString(book.id,saved.put("identity",fingerprint(book)).put("explicitRequest",true).put("nextAt",0).toString()).apply()
    }
    fun remaining(books:List<Book>,now:Long=System.currentTimeMillis())=MetadataOrder.ordered(books.filter {due(it,now)})
    fun pendingCount()=synchronized(lock) {directory.listFiles()?.count {it.extension=="json"} ?: 0}
    fun record(book:Book,raw:String,now:Long=System.currentTimeMillis())=synchronized(lock) {
        val payload=JSONObject(raw)
        require(payload.has("matched"))
        val found=MetadataInformation.parse(raw,book)!=null
        require(!payload.optBoolean("matched") || found) {"Correspondance non vérifiable"}
        val identity=fingerprint(book)
        if(found) {
            val record=JSONObject().put("identity",identity).put("token",java.util.UUID.randomUUID().toString()).put("body",raw)
            val atomic=file(book.id);val stream=atomic.startWrite()
            try {stream.write(record.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
            catch(e:Exception) {atomic.failWrite(stream);throw e}
        }
        val next=now+(if(found)30L else 7L)*24*60*60*1000
        preferences.edit().putString(book.id,JSONObject().put("identity",identity).put("nextAt",next).put("completedAt",now).put("attemptedAt",state(book).optLong("attemptedAt",now)).put("matched",found).put("clues",cluesFingerprint(book)).put("failed",false).toString())
            .putLong("resultsChanged",System.nanoTime()).apply()
    }
    data class Pending(val token:String,val result:MetadataInformation.Result)
    fun pending(book:Book):Pending?=synchronized(lock) {runCatching {
        val j=file(book.id).openRead().use {JSONObject(it.readBytes().toString(Charsets.UTF_8))}
        if(j.optString("identity")!=fingerprint(book))return@runCatching null
        val result=MetadataInformation.parse(j.getString("body"),book) ?: return@runCatching null
        Pending(j.getString("token"),result)
    }.getOrNull()}
    fun applied(book:Book,pending:Pending,current:Book=book)=synchronized(lock) {
        // An older UI callback must not delete a newer worker result.
        val atomic=file(book.id)
        val token=runCatching {atomic.openRead().use {JSONObject(it.readBytes().toString(Charsets.UTF_8)).optString("token")}}.getOrNull()
        if(token==pending.token) {
            atomic.delete()
            val saved=state(book)
            if(saved.optString("identity")==fingerprint(book))preferences.edit().putString(book.id,saved.put("identity",fingerprint(current)).put("clues",cluesFingerprint(current)).toString()).apply()
        }
    }
    fun prune(books:List<Book>)=synchronized(lock) {
        val retained=books.map {digest(it.id)+".json"}.toSet()
        directory.listFiles()?.filter {it.name.removeSuffix(".bak") !in retained}?.forEach {it.delete()}
        val ids=books.map {it.id}.toSet();val edit=preferences.edit()
        preferences.all.keys.filter {it!="resultsChanged" && it !in ids}.forEach {edit.remove(it)};edit.apply()
    }
    fun listen(listener:SharedPreferences.OnSharedPreferenceChangeListener)=preferences.registerOnSharedPreferenceChangeListener(listener)
    fun unlisten(listener:SharedPreferences.OnSharedPreferenceChangeListener)=preferences.unregisterOnSharedPreferenceChangeListener(listener)
}

object MetadataBatch {
    /** Bounded batches resume after network/process interruptions and never edit books. */
    suspend fun run(store:MetadataQueueStore,books:()->List<Book>,lookup:suspend (Book)->String,
                    pause:suspend ()->Unit={delay(11_000)},clock:()->Long=System::currentTimeMillis,maxMillis:Long=8*60*1000L,
                    activity:suspend (MetadataSearchStatus)->Unit={},scope:MetadataScanStore?=null):Boolean {
        val started=clock()
        try {
            while(true) {
                coroutineContext.ensureActive()
                val targets=scope?.targets()
                val current=books().filter {targets==null || it.id in targets}
                val requested=scope?.pending("service")
                val currentIds=current.filterNot {it.demo}.map {it.id}.toSet()
                requested?.filter {it !in currentIds}?.forEach {scope?.complete("service",it)}
                val candidates=current.filter {requested==null || it.id in requested}
                candidates.filter {!store.due(it,clock())}.forEach {scope?.complete("service",it.id)}
                val remaining=store.remaining(candidates,clock())
                val book=remaining.firstOrNull() ?: return true
                val total=current.count {!it.demo}
                val position=total-remaining.size+1
                if(store.pendingCount()>=200)return true
                if(clock()-started>=maxMillis)return false
                store.attempted(book,clock())
                activity(MetadataSearchStatus(MetadataSearchStatus.Phase.SEARCHING,book.title,position,total,book.id))
                val raw=try {lookup(book)}catch(e:Exception) {
                    if(e !is kotlinx.coroutines.CancellationException && books().any {it.id==book.id && store.fingerprint(it)==store.fingerprint(book)})store.failed(book,(e as? MetadataLookupException)?.reason.orEmpty())
                    throw e
                }
                coroutineContext.ensureActive()
                // Deleted albums and renamed identities are ignored, even if a lookup was in flight.
                val fresh=books().firstOrNull {it.id==book.id}
                if(fresh!=null && store.fingerprint(fresh)==store.fingerprint(book))try {store.record(fresh,raw,clock());scope?.complete("service",fresh.id)}catch(e:Exception) {store.failed(fresh);throw e}
                val nextRequested=scope?.pending("service")
                if(store.remaining(books().filter {nextRequested==null || it.id in nextRequested},clock()).isNotEmpty()) {
                    activity(MetadataSearchStatus(MetadataSearchStatus.Phase.WAITING,position=position,total=total))
                    pause()
                }
            }
        } finally {activity(MetadataSearchStatus())}
    }
}

class MetadataWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        if(BuildConfig.METADATA_ENDPOINT.isBlank())return Result.success()
        val repo=Repository(applicationContext);val store=MetadataQueueStore(applicationContext)
        return try {
            if(MetadataBatch.run(store,repo::loadBooks,{MetadataCoverLookup.fetch(applicationContext,it)},activity={setProgress(it.progress())},scope=MetadataScanStore(applicationContext)))Result.success() else Result.retry()
        }catch(e:Exception) {
            if(e is kotlinx.coroutines.CancellationException)throw e
            // A failed request is terminal; only the user's next request may retry it.
            val scope=MetadataScanStore(applicationContext)
            scope.pending("service").forEach {scope.complete("service",it)}
            Result.success()
        }
    }
    companion object {
        const val NAME="metadata-library-v3"
        fun retireLegacy(context:Context) {
            val prefs=context.getSharedPreferences("metadata-trigger-migration",Context.MODE_PRIVATE)
            if(!prefs.getBoolean("explicit-v3",false)) {
                WorkManager.getInstance(context).cancelUniqueWork("metadata-library-v1")
                WorkManager.getInstance(context).cancelUniqueWork("metadata-library-v2")
                MetadataScanStore(context).prune(emptySet())
                prefs.edit().putBoolean("explicit-v3",true).apply()
            }
        }
        fun schedule(context:Context) {
            if(BuildConfig.METADATA_ENDPOINT.isBlank())return
            val request=OneTimeWorkRequestBuilder<MetadataWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
    }
}
