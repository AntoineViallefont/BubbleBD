package fr.bubblebd

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MetadataQueueTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val book=Book("queue-test","content://private","private.pdf","Album test",pages=70,page=18,started=true,writer="Correction")
    private fun store(endpoint:String="https://catalog.example")=MetadataQueueStore(context,endpoint)
    private fun response(b:Book)=JSONObject().put("matched",true).put("title",b.title).put("artist","Artiste test")
        .put("writer","Suggestion").put("synopsis","Résumé test")
        .put("sources",JSONArray().put(JSONObject().put("url","https://publisher.example/album")))
        .put("searchAttribution","<p>Recherche Web · Mistral</p>").toString()
    @Before fun reset() {store().prune(emptyList())}

    @Test fun completeAlbumIsSearchedOnlyAfterExplicitInvalidation() {
        val full=book.copy(artist="Dessinateur",writer="Scénariste",publisher="Éditeur",date="2026",isbn="9782205087327",genre="Aventure",synopsis="Résumé")
        assertTrue(BibliographicSources.complete(full))
        val queue=store()
        assertFalse(queue.due(full,Long.MAX_VALUE))
        queue.invalidate(full)
        assertTrue(queue.due(full,Long.MAX_VALUE))
        assertEquals(listOf(full),queue.remaining(listOf(full)))
        assertTrue(queue.due(full.copy(synopsis="")))
        assertTrue(queue.due(full.copy(genre="")))
    }

    @Test fun researchHistoryDistinguishesFailureNoMatchAndSuccessAndSurvivesRestart() {
        val queue=store()
        assertEquals(AlbumResearch.State.PENDING,queue.research(book).state)
        queue.attempted(book,1000);queue.failed(book)
        assertEquals(AlbumResearch.State.RETRY,store().research(book).state)
        assertNull(queue.research(book).completedAt)
        queue.failed(book,"quota")
        assertEquals(AlbumResearch.State.QUOTA,store().research(book).state)
        queue.record(book,"{\"matched\":false}",2000)
        assertEquals(AlbumResearch.State.NO_MATCH,store().research(book).state)
        assertEquals(2000L,queue.research(book).completedAt)
        assertTrue("New author clues should retry a previous no-match",queue.due(book.copy(artist="Nouvel auteur"),2001))
        queue.record(book,response(book),3000)
        assertEquals(AlbumResearch.State.FOUND,store().research(book).state)
        assertEquals(3000L,queue.research(book).completedAt)
        queue.attempted(book,4000);queue.failed(book)
        assertEquals(AlbumResearch.State.RETRY,queue.research(book).state)
        assertEquals("An interruption does not replace the last completed search",3000L,queue.research(book).completedAt)
        assertEquals(AlbumResearch.State.PENDING,queue.research(book.copy(title="Autre album")).state)
    }
    @Test fun coverFallbackRunsOnlyAfterNoMatchAndKeepsImagesBounded()=runBlocking {
        val file=java.io.File(context.cacheDir,"fictional-cover-test.jpg")
        val bitmap=android.graphics.Bitmap.createBitmap(40,60,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,it)};bitmap.recycle()
        try {
            val covered=book.copy(cover=file.absolutePath)
            var requests=0;var pauses=0
            val first=MetadataCoverLookup.fetch(context,covered,pause={pauses++}) {b,clues ->requests++;assertTrue(clues.isEmpty());response(b)}
            assertTrue(JSONObject(first).optBoolean("matched"));assertEquals(1,requests);assertEquals(0,pauses)
            val second=MetadataCoverLookup.fetch(context,covered,pause={assertEquals(2,requests);pauses++}) {b,clues ->
                requests++
                if(clues.isEmpty())"{\"matched\":false}" else {
                    assertEquals(1,pauses)
                    val image=clues.getValue("coverImage")
                    assertTrue(image.startsWith("data:image/jpeg;base64,"));assertTrue(image.length<=214000)
                    assertFalse(clues.values.any {it.contains(file.absolutePath)})
                    response(b)
                }
            }
            assertTrue(JSONObject(second).optBoolean("matched"));assertEquals(3,requests)
            MetadataCoverLookup.fetch(context,book.copy(cover=""),pause={pauses++}) {_,clues->requests++;assertTrue(clues.isEmpty());"{\"matched\":false}"}
            assertEquals(4,requests);assertEquals(1,pauses)
        } finally {file.delete()}
    }
    @Test fun applyingIdentifiedIsbnKeepsResearchHistoryOnTheUpdatedIdentity() {
        val queue=store()
        queue.record(book,JSONObject(response(book)).put("isbn","9782413047551").toString(),1000)
        val pending=queue.pending(book)!!
        val current=MetadataInformation.merge(book,pending.result)
        assertEquals("9782413047551",current.isbn)
        queue.applied(book,pending,current)
        assertEquals(AlbumResearch.State.FOUND,queue.research(current).state)
        assertEquals(1000L,queue.research(current).completedAt)
        assertFalse(queue.due(current,1001))
    }

    @Test fun completeAlbumsAreSkippedAndLegacyResultsSurviveStoreRecreation() {
        val full=book.copy(artist="Artiste",publisher="Éditeur",date="2020",genre="BD",synopsis="Résumé",isbn="9782800169085")
        val queue=store()
        assertFalse(queue.due(full,0))
        queue.record(full,JSONObject(response(full)).put("isbn",full.isbn).toString(),0)
        val reopened=store()
        assertFalse(reopened.due(full,1))
        val pending=reopened.pending(full)!!
        val current=full.copy(page=31,readingState="read")
        val merged=MetadataInformation.merge(current,pending.result)
        assertEquals(31,merged.page);assertEquals("read",merged.readingState)
        assertEquals("Suggestion",merged.writer);assertEquals("Résumé test",merged.synopsis)
        assertTrue(merged.metadataSource.contains("publisher.example"))
        reopened.applied(full,pending)
        assertEquals(0,reopened.pendingCount())
    }
    @Test fun failuresResumeWithoutRepeatingCompletedAlbum()=runBlocking {
        val queue=store();val second=book.copy(id="second",title="Autre album")
        val books=listOf(book,second)
        try {
            MetadataBatch.run(queue,{books},{if(it.id==second.id)throw java.io.IOException("Réseau") else response(it)},pause={},clock={0})
            fail("Le défaut réseau doit être repris")
        }catch(_:java.io.IOException) { }
        val requested=mutableListOf<String>()
        val resumed=mutableListOf<MetadataSearchStatus>()
        assertTrue(MetadataBatch.run(store(),{books},{requested.add(it.id);response(it)},pause={},clock={0},activity={resumed.add(it)}))
        assertEquals(MetadataSearchStatus(MetadataSearchStatus.Phase.SEARCHING,second.title,2,2,second.id),resumed.first())
        assertEquals(listOf(second.id),requested)
        assertEquals(2,queue.pendingCount())
    }
    @Test fun deletionAndIdentityChangeDuringRequestDiscardResult()=runBlocking {
        val queue=store();var current=listOf(book)
        assertTrue(MetadataBatch.run(queue,{current},{current=emptyList();response(it)},pause={},clock={0}))
        assertEquals(0,queue.pendingCount())
        current=listOf(book)
        MetadataBatch.run(queue,{current},{current=listOf(book.copy(title="Titre corrigé"));response(it)},
            pause={current=emptyList()},clock={0})
        assertEquals(0,queue.pendingCount())
        assertTrue(queue.due(book,0))
    }
    @Test fun malformedMatchIsNotMarkedDoneAndOldConsumerCannotEraseNewResult() {
        val queue=store()
        try {queue.record(book,"{\"matched\":true}",0);fail("Correspondance non sourcée")}
        catch(_:IllegalArgumentException) { }
        assertTrue(queue.due(book,0))
        queue.record(book,response(book),0);val old=queue.pending(book)!!
        queue.record(book,response(book),1);queue.applied(book,old)
        assertNotNull(queue.pending(book))
        assertTrue(store("https://other.example").due(book,2))
        assertNull(queue.pending(book.copy(title="Autre titre")))
    }
    @Test fun noMatchWaitsAndBoundedBatchDoesNotStartExtraRequest()=runBlocking {
        val queue=store()
        queue.record(book,"{\"matched\":false}",0)
        assertFalse(queue.due(book,6*24*60*60*1000L))
        assertTrue(queue.due(book,7*24*60*60*1000L))
        assertFalse(queue.due(book.copy(demo=true),Long.MAX_VALUE))
        assertFalse(MetadataBatch.run(queue,{listOf(book.copy(id="new"))},{fail("Lot expiré");response(it)},pause={},clock={0},maxMillis=0))
        assertEquals(0,queue.pendingCount())
    }
    @Test fun asynchronousPublicationKeepsNewReadingProgress() {
        val full=book.copy(artist="Artiste",publisher="Éditeur",date="2020",genre="BD",synopsis="Résumé",isbn="9782800169085",rating=4.0)
        Repository(context).saveBooks(listOf(full))
        // Use the production namespace/endpoint, as a result waiting at process startup would.
        val queue=MetadataQueueStore(context)
        queue.prune(emptyList())
        queue.record(full,JSONObject(response(full)).put("isbn",full.isbn).toString())
        val instrument=InstrumentationRegistry.getInstrumentation()
        val owner=androidx.lifecycle.ViewModelStore()
        lateinit var viewModel:LibraryViewModel
        instrument.runOnMainSync {
            viewModel=LibraryViewModel(context.applicationContext as android.app.Application)
            owner.put("library",viewModel)
            viewModel.progress(full.id,49,70)
        }
        try {
            val deadline=System.currentTimeMillis()+5000
            var published=false
            while(!published && System.currentTimeMillis()<deadline) {
                instrument.runOnMainSync {published=viewModel.books.single().metadataAttribution.isNotBlank()}
                if(!published)Thread.sleep(20)
            }
            assertTrue("Résultat publié dans la bibliothèque",published)
            instrument.runOnMainSync {
                val current=viewModel.books.single()
                assertEquals(49,current.page);assertEquals("Suggestion",current.writer)
                assertEquals("Résumé test",current.synopsis)
            }
        }finally {instrument.runOnMainSync {owner.clear()};queue.prune(emptyList())}
    }
    @Test fun nextRequestReflectsReadingAndImportsDuringTheBatch()=runBlocking {
        val queue=store()
        val older=book.copy(id="older",title="A",lastRead=10,added=1)
        val recent=book.copy(id="recent",title="Z",lastRead=20,added=2)
        val unread=book.copy(id="unread",title="B",lastRead=0,added=30)
        val newcomer=book.copy(id="imported",title="C",lastRead=0,added=100)
        var current=listOf(unread,older,recent)
        val calls=mutableListOf<String>()
        val progress=mutableListOf<Pair<Int,Int>>()
        MetadataBatch.run(queue,{current},{calls.add(it.id);response(it)},pause={
            if(calls.size==1)current=current.map {if(it.id==unread.id)it.copy(lastRead=40) else it}+newcomer
        },clock={0},activity={if(it.phase==MetadataSearchStatus.Phase.SEARCHING)progress.add(it.position to it.total)})
        assertEquals(listOf(1 to 3,2 to 4,3 to 4,4 to 4),progress)
        assertEquals(listOf("recent","unread","older","imported"),calls)
    }
    @Test fun activityClearsOnFailureAndCancellation()=runBlocking {
        val events=mutableListOf<MetadataSearchStatus>()
        try {
            MetadataBatch.run(store(),{listOf(book)},{
                assertEquals(MetadataSearchStatus(MetadataSearchStatus.Phase.SEARCHING,book.title,1,1,book.id),events.last())
                throw java.io.IOException("Interruption")
            },activity={events.add(it)},clock={0})
            fail("Erreur attendue")
        }catch(_:java.io.IOException) { }
        assertEquals(MetadataSearchStatus(),events.last())
        val begun=kotlinx.coroutines.CompletableDeferred<Unit>()
        val job=launch {
            MetadataBatch.run(store(),{listOf(book)},{begun.complete(Unit);kotlinx.coroutines.awaitCancellation()},
                activity={events.add(it)},clock={0})
        }
        begun.await();job.cancel();job.join()
        assertEquals(MetadataSearchStatus(),events.last())
        assertTrue(store().due(book,0))
    }
    @Test fun stoppedWorkerNeverShowsItsOldActiveTitle() {
        val searching=MetadataSearchStatus(MetadataSearchStatus.Phase.SEARCHING,"Album en cours",3,120)
        fun work(state:androidx.work.WorkInfo.State)=androidx.work.WorkInfo(java.util.UUID.randomUUID(),state,emptySet(),progress=searching.progress())
        assertEquals(searching,MetadataSearchStatus.fromWork(listOf(work(androidx.work.WorkInfo.State.RUNNING))))
        for(state in listOf(androidx.work.WorkInfo.State.ENQUEUED,androidx.work.WorkInfo.State.BLOCKED)) {
            val waiting=MetadataSearchStatus.fromWork(listOf(work(state)))
            assertEquals(MetadataSearchStatus(MetadataSearchStatus.Phase.WAITING),waiting)
            assertFalse(waiting.visible)
        }
        assertTrue(searching.visible)
        assertFalse(MetadataSearchStatus(MetadataSearchStatus.Phase.WAITING,position=3,total=120).visible)
        for(state in listOf(androidx.work.WorkInfo.State.SUCCEEDED,androidx.work.WorkInfo.State.FAILED,androidx.work.WorkInfo.State.CANCELLED)) {
            assertFalse(MetadataSearchStatus.fromWork(listOf(work(state))).visible)
        }
        assertEquals(searching,MetadataSearchStatus.fromWork(listOf(work(androidx.work.WorkInfo.State.SUCCEEDED),work(androidx.work.WorkInfo.State.RUNNING))))
    }
    @Test fun differentServicesCannotPruneOrReplaceEachOthersResults() {
        val first=store();val second=store("https://other.example")
        second.prune(emptyList())
        try {
            first.record(book,response(book),0)
            val token=first.pending(book)!!.token
            second.record(book,response(book),1)
            assertNotEquals(token,second.pending(book)!!.token)
            second.prune(emptyList())
            assertEquals(token,first.pending(book)!!.token)
            assertFalse(first.due(book,1))
            assertEquals(AlbumResearch.State.FOUND,first.research(book).state)
        } finally {first.prune(emptyList());second.prune(emptyList())}
    }

    @Test fun explicitScopePersistsAndDoesNotSearchOldAlbumsDuringImport()=runBlocking {
        val queue=store();val scope=MetadataScanStore(context,"test-import")
        scope.prune(emptySet())
        val old=book.copy(id="old",title="Ancien album",lastRead=100)
        val fresh=book.copy(id="new",title="Nouvel album")
        val calls=mutableListOf<String>();val progress=mutableListOf<MetadataSearchStatus>()
        suspend fun batch()=MetadataBatch.run(queue,{listOf(old,fresh)},{calls.add(it.id);response(it)},pause={},clock={0},scope=MetadataScanStore(context,"test-import"),activity={if(it.visible)progress.add(it)})
        assertTrue(batch());assertTrue(calls.isEmpty())
        scope.request(setOf(fresh.id))
        assertTrue(batch());assertEquals(listOf("new"),calls)
        assertEquals("BD 1/1",progress.single().counter)
        assertTrue(scope.pending("service").isEmpty())
        assertTrue(batch());assertEquals(1,calls.size)
        // A manual scan explicitly includes old albums, even if a title was searched before.
        scope.request(setOf(old.id,fresh.id),replace=true)
        queue.invalidate(fresh)
        assertTrue(batch());assertEquals(listOf("new","old","new"),calls)
        assertEquals(setOf(old.id,fresh.id),scope.targets())
        assertEquals("BD 2/2",progress.last().counter)
        scope.prune(emptySet())
    }
    @Test fun importsJoiningAnActiveScopeDoNotExpandToUnrequestedAlbums()=runBlocking {
        val queue=store();val scope=MetadataScanStore(context,"test-joining")
        scope.prune(emptySet())
        val first=book.copy(id="first");val next=book.copy(id="next");val unrelated=book.copy(id="unrelated",lastRead=999)
        scope.request(setOf(first.id))
        val calls=mutableListOf<String>()
        MetadataBatch.run(queue,{listOf(first,next,unrelated)},{calls.add(it.id);if(it.id==first.id)scope.request(setOf(next.id));response(it)},pause={},clock={0},scope=scope)
        assertEquals(listOf("first","next"),calls)
        assertTrue(MetadataScanStore(context,"test-joining").pending("service").isEmpty())
        scope.complete("catalog",first.id);scope.complete("catalog",next.id)
        scope.request(setOf(unrelated.id))
        assertEquals(setOf(unrelated.id),scope.targets())
        scope.prune(emptySet())
    }
    @Test fun libraryStartupDoesNotCreateAnInformationScan() {
        val scope=MetadataScanStore(context);scope.prune(emptySet())
        Repository(context).saveBooks(listOf(book.copy(id="unsearched-startup")))
        val instrument=InstrumentationRegistry.getInstrumentation();val owner=androidx.lifecycle.ViewModelStore()
        instrument.runOnMainSync {owner.put("library",LibraryViewModel(context.applicationContext as android.app.Application))}
        try {
            assertTrue(scope.targets().isEmpty())
            assertTrue(scope.pending("catalog").isEmpty())
            assertTrue(scope.pending("service").isEmpty())
        } finally {instrument.runOnMainSync {owner.clear()};scope.prune(emptySet())}
    }

    @Test fun lockedBookPersistsAndCannotBeSearchedOrOverwritten()=runBlocking {
        val locked=book.copy(metadataLocked=true,metadataRevision=4)
        val repo=Repository(context);repo.saveBooks(listOf(locked))
        assertEquals(locked,repo.loadBooks().single())
        val queue=store();queue.invalidate(locked)
        assertFalse(queue.due(locked,Long.MAX_VALUE))
        var calls=0
        MetadataBatch.run(queue,{listOf(locked)},{calls++;response(it)},pause={})
        assertEquals(0,calls)
        val result=MetadataInformation.parse(response(book),book)!!
        assertEquals(locked,MetadataInformation.merge(locked,result))
        assertEquals(locked,BibliographicSources.merge(locked,result.details))
    }
    @Test fun freezeDuringLookupDiscardsResultEvenAfterUnlock()=runBlocking {
        val queue=store();var current=book
        MetadataBatch.run(queue,{listOf(current)},{requested ->
            current=current.copy(metadataLocked=true,metadataRevision=1)
            response(requested)
        },pause={})
        assertNull(queue.pending(current))
        current=current.copy(metadataLocked=false,metadataRevision=2)
        assertNull(queue.pending(current))
        queue.record(book,response(book))
        assertNull(queue.pending(current))
    }
    @Test fun targetedScopeOnlyRequestsChosenAlbum()=runBlocking {
        val queue=store();val other=book.copy(id="other",title="Autre album")
        val scope=MetadataScanStore(context,"single-title");scope.prune(emptySet());scope.request(setOf(book.id))
        val calls=mutableListOf<String>()
        MetadataBatch.run(queue,{listOf(book,other)},{calls.add(it.id);response(it)},pause={},scope=scope)
        assertEquals(listOf(book.id),calls)
        scope.prune(emptySet())
    }
    @Test fun unchangedAlbumsKeepTheirPreUpgradeResearchHistory() {
        val queue=store()
        val legacy=queue.endpoint+"\n"+JSONObject(MetadataInformation.coreIdentity(book)).toString()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(legacy.toByteArray()).joinToString("") {"%02x".format(it)}
        assertEquals(hash,queue.fingerprint(book))
        assertNotEquals(hash,queue.fingerprint(book.copy(metadataRevision=1)))
    }
}
