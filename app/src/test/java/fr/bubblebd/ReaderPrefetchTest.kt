package fr.bubblebd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReaderPrefetchTest {
    @Test fun prepareFromHalfwayOrAsSoonAsFullPageAnalysisIsReady() {
        assertNull(ReaderPrefetch.nextPage(2,8,listOf(2),10,1))
        assertEquals(3,ReaderPrefetch.nextPage(2,8,listOf(5),10,1))
        assertEquals(3,ReaderPrefetch.nextPage(2,8,listOf(3),7,1))
        assertEquals(1,ReaderPrefetch.nextPage(2,8,listOf(1,2),10,-1))
        assertNull(ReaderPrefetch.nextPage(0,8,listOf(0),10,-1))
        assertNull(ReaderPrefetch.nextPage(7,8,listOf(9),10,1))
        assertEquals(3,ReaderPrefetch.nextPage(2,8,null,10,1))
        assertEquals(1,ReaderPrefetch.nextPage(2,8,null,0,-1))
    }
    @Test fun repeatedCaseChangesReuseOnePreparation()=runBlocking {
        var loads=0;var detections=0;val ready=CompletableDeferred<String>()
        val cache=ReaderPrefetch(this,load={page ->loads++;"page-$page"},detect={_,image ->detections++;ready.await()+image})
        cache.prepare(3);yield();cache.prepare(3);cache.prepare(3)
        val prepared=cache.take(3)!!
        val next=async {prepared.frames()};yield()
        assertFalse(next.isCompleted);assertEquals(1,loads);assertEquals(1,detections)
        ready.complete("cases-")
        assertEquals("cases-page-3",next.await());assertNull(cache.take(3))
    }
    @Test fun imageIsAvailableWithoutWaitingForCases()=runBlocking {
        val ready=CompletableDeferred<String>();val loaded=Any()
        val cache=ReaderPrefetch(this,load={loaded},detect={_,_ ->ready.await()})
        cache.prepare(1);yield();val prepared=cache.take(1)!!
        assertSame(loaded,prepared.image())
        val cases=async {prepared.frames()};yield();assertFalse(cases.isCompleted)
        ready.complete("cases");assertEquals("cases",cases.await());prepared.cancel()
    }
    @Test fun differentDirectionCancelsImageAndAnalysis()=runBlocking {
        val entered=mutableListOf<Int>();val cancelled=mutableListOf<Int>();val wait=CompletableDeferred<String>()
        val cache=ReaderPrefetch(this,load={"image-$it"},detect={page,_ ->entered.add(page);try {wait.await()}finally {cancelled.add(page)}})
        cache.prepare(3);yield();cache.prepare(1);yield()
        assertEquals(listOf(3,1),entered);assertEquals(listOf(3),cancelled)
        assertNull(cache.take(4));yield();assertEquals(listOf(3,1),cancelled)
    }
    @Test fun failedImageFallsBackWithoutStartingDetection()=runBlocking {
        var detected=false
        val cache=ReaderPrefetch<String,String>(this,load={throw java.io.IOException("Page non disponible")},detect={_,_ ->detected=true;"cases"})
        cache.prepare(3);yield();val prepared=cache.take(3)!!
        assertNull(prepared.image());assertNull(prepared.frames());assertFalse(detected)
    }
    @Test fun failedDetectionKeepsThePreparedImage()=runBlocking {
        val cache=ReaderPrefetch<String,String>(this,load={"image"},detect={_,_ ->throw java.io.IOException("Analyse indisponible")})
        cache.prepare(3);yield();val prepared=cache.take(3)!!
        assertEquals("image",prepared.image());assertNull(prepared.frames())
    }
    @Test fun transferredPreparationStopsWhenTheRequestLeaves()=runBlocking {
        val wait=CompletableDeferred<String>();var cancelled=false
        val cache=ReaderPrefetch(this,load={"image"},detect={_,_ ->try {wait.await()}finally {cancelled=true}})
        cache.prepare(1);yield();val prepared=cache.take(1)!!
        val transfer=async {try {prepared.frames()}finally {prepared.cancel()}};yield()
        transfer.cancel();transfer.join();yield();assertTrue(cancelled)
    }
    @Test fun abandoningAnUndecodedPageCancelsTheDecode()=runBlocking {
        val wait=CompletableDeferred<String>();var cancelled=false
        val cache=ReaderPrefetch(this,load={try {wait.await()}finally {cancelled=true}},detect={_,_ ->"cases"})
        cache.prepare(1);yield();cache.cancel();yield();assertTrue(cancelled)
    }
}
