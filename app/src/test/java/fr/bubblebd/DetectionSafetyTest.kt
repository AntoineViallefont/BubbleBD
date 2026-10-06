package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class DetectionSafetyTest {
    private val pixels=IntArray(200*300){0xff406070.toInt()}
    private val a=Panel(.1f,.1f,.4f,.5f)
    private val b=Panel(.5f,.5f,.9f,.9f)
    @Test fun unsupportedLowConfidenceGuessesReturnTheWholePage() {
        assertTrue(DetectionSafety.accept(listOf(a,b),emptyList(),listOf(PanelCandidate(a,.55f,false)),pixels,200,300).isEmpty())
    }
    @Test fun oneReliableCaseKeepsPartialDetectionUsable() {
        val frames=listOf(a,b)
        assertEquals(frames,DetectionSafety.accept(frames,emptyList(),listOf(PanelCandidate(a,.90f,false)),pixels,200,300))
    }
    @Test fun confidentSpeechAloneDoesNotProveAPhysicalCase() {
        assertTrue(DetectionSafety.accept(listOf(a),emptyList(),listOf(PanelCandidate(a,.98f,true)),pixels,200,300).isEmpty())
    }
    @Test fun fourPhysicalEdgesAtImageBorderKeepAUsableCase() {
        val image=IntArray(200*300){-1};val p=Panel(0f,0f,.5f,1f)
        for(y in 0 until 300) {image[y*200]=0xff000000.toInt();image[y*200+99]=0xff000000.toInt()}
        for(x in 0 until 100) {image[x]=0xff000000.toInt();image[299*200+x]=0xff000000.toInt()}
        assertEquals(listOf(p),DetectionSafety.accept(listOf(p),listOf(p),emptyList(),image,200,300))
        assertEquals(listOf(p),DetectionSafety.accept(listOf(p),emptyList(),emptyList(),image,200,300))
    }
    @Test fun oneDecorativeEdgeIsInsufficient() {
        val image=IntArray(200*300){-1};val p=Panel(0f,0f,.5f,1f)
        for(y in 0 until 300)image[y*200]=0xff000000.toInt()
        assertTrue(DetectionSafety.accept(listOf(p),listOf(p),emptyList(),image,200,300).isEmpty())
    }
    @Test fun uniformDarkArtworkDoesNotProveFourBorders() {
        val p=Panel(0f,0f,.5f,1f)
        assertTrue(DetectionSafety.accept(listOf(p),emptyList(),emptyList(),pixels,200,300).isEmpty())
    }
}
