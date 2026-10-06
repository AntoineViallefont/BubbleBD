package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class SpeechTailTest {
    private val w=200
    private val h=300
    private val body=Panel(30f/w,30f/h,130f/w,170f/h)
    @Test fun longNarrowTailDoesNotEraseItsCompactBalloon() {
        val px=IntArray(w*h){0xff306050.toInt()}
        for(y in 30 until 80)for(x in 30 until 130)if((x-80f)*(x-80f)/2500+(y-55f)*(y-55f)/625<1)px[y*w+x]=-1
        for(y in 78 until 170)for(x in 79..81)px[y*w+x]=-1
        assertTrue(SpeechTail.thinLobe(body,px,w,h))
    }
    @Test fun rectangularCaptionHasNoDirectedTail() {
        assertFalse(SpeechTail.thinLobe(body,IntArray(w*h){-1},w,h))
    }
    @Test fun ordinaryOvalKeepsTheAmbiguityFallback() {
        val px=IntArray(w*h){0xff306050.toInt()}
        for(y in 30 until 170)for(x in 30 until 130)if((x-80f)*(x-80f)/2500+(y-100f)*(y-100f)/4900<1)px[y*w+x]=-1
        assertFalse(SpeechTail.thinLobe(body,px,w,h))
    }
}
