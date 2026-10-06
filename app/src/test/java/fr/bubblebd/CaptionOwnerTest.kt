package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class CaptionOwnerTest {
    private val w=300
    private val h=400
    private val frames=listOf(Panel(.05f,.05f,.95f,.51f),Panel(.05f,.515f,.48f,.95f),Panel(.52f,.515f,.95f,.95f))
    @Test fun openRectangularCaptionBelongsToTheLowerFrameAcrossANarrowGutter() {
        val text=Panel(.08f,.522f,.40f,.558f)
        val pixels=IntArray(w*h){0xff305070.toInt()}
        for(y in 196 until 231)for(x in 15 until 128)pixels[y*w+x]=-1
        assertEquals(1,SpeechOwnership.captionOwner(text,text,frames,pixels,w,h,true))
        assertNotNull(SpeechOwnership.uncertainPair(text,text,frames,true))
    }
    @Test fun ovalBalloonNearTheSameGutterDoesNotBecomeANarrationBox() {
        val text=Panel(.08f,.522f,.40f,.558f)
        val pixels=IntArray(w*h){0xff305070.toInt()}
        for(y in 192 until 246)for(x in 10 until 138)if(((x-74)/64f)*((x-74)/64f)+((y-219)/27f)*((y-219)/27f)<1f)pixels[y*w+x]=-1
        assertNull(SpeechOwnership.captionOwner(text,text,frames,pixels,w,h,true))
    }
    @Test fun closedCaptionAtAThreeFrameJunctionStaysAmbiguous() {
        val text=Panel(.4f,.45f,.6f,.55f)
        assertNull(SpeechOwnership.captionOwner(text,text,frames,IntArray(w*h){-1},w,h,false))
        assertEquals(listOf(0,1,2),SpeechOwnership.uncertainCases(text,text,frames,true))
    }
}
