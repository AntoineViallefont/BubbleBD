package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class SpeechEnvelopeTest {
    private val w=200
    private val h=300
    private val body=Panel(30f/w,30f/h,130f/w,170f/h)
    private fun pixels():IntArray {
        val px=IntArray(w*h){0xff306050.toInt()}
        for(y in 30 until 80)for(x in 30 until 130)if((x-80f)*(x-80f)/2500+(y-55f)*(y-55f)/625<1)px[y*w+x]=-1
        for(y in 78 until 170)for(x in 79..81)px[y*w+x]=-1
        return px
    }
    @Test fun ShortSpeechGetsReadingSpaceButNeverEnlargesIntoAnotherFrame() {
        val small=Panel(.72f,.55f,.81f,.61f);val text=Panel(.735f,.565f,.79f,.586f)
        val own=Panel(.65f,.45f,.9f,.8f)
        val expanded=SpeechEnvelope.readingMargin(small,text,0,listOf(own,Panel(.1f,.1f,.5f,.4f)),w,h)
        assertTrue(expanded.top<small.top && expanded.bottom>small.bottom)
        assertEquals(small,SpeechEnvelope.readingMargin(small,text,0,listOf(own,Panel(.65f,.62f,.9f,.9f)),w,h))
        assertEquals(body,SpeechEnvelope.readingMargin(body,text,0,listOf(own),w,h))
    }
    @Test fun complementaryTextLimitsPreserveTheWholeThinBalloon() {
        val text=Panel(25f/w,25f/h,135f/w,85f/h)
        val result=SpeechEnvelope.complete(body,text,pixels(),w,h)
        assertTrue(result.left<text.left && result.right>text.right)
        assertEquals(text.top,result.top)
        assertEquals(body.bottom,result.bottom)
    }
    @Test fun NearbyUnrelatedTextCannotEnlargeTheBalloon() {
        assertEquals(body,SpeechEnvelope.complete(body,Panel(.6f,.05f,.9f,.2f),pixels(),w,h))
        assertEquals(body,SpeechEnvelope.complete(body,Panel(0f,0f,1f,1f),pixels(),w,h))
    }
    @Test fun OrdinaryWhiteCaptionDoesNotBorrowTextLimits() {
        assertEquals(body,SpeechEnvelope.complete(body,Panel(.1f,.05f,.9f,.5f),IntArray(w*h){-1},w,h))
    }
}
