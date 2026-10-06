package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class EdgeSpeechTest {
    private val width=200
    private val height=300
    private val frames=listOf(Panel(.1f,.1f,.9f,.5f),Panel(.1f,.52f,.9f,.9f))
    private fun pixels(top:Int):IntArray=IntArray(width*height) {0xff303030.toInt()}.apply {
        for(y in top until 150)for(x in 50 until 80)this[y*width+x]=0xffffffff.toInt()
        for(y in 156 until 167)for(x in 50 until 80)this[y*width+x]=0xffffffff.toInt()
    }
    @Test fun upperProtrusionKeepsTheOpenBalloonInItsOriginalFrame() {
        val text=Panel(52f/width,133f/height,76f/width,146f/height)
        val result=EdgeSpeech.expand(frames,listOf(text),emptyList(),pixels(50),width,height)
        assertEquals(frames[1].top,result[1].top,0f)
        assertTrue(result[0].focusExclusions.isEmpty())
        assertTrue(result[0].bottom>=text.bottom)
    }
    @Test fun whiteMarginAcrossMoreThanHalfTheFrameKeepsItsSpeaker() {
        val text=Panel(52f/width,133f/height,76f/width,146f/height)
        val result=EdgeSpeech.expand(frames,listOf(text),emptyList(),pixels(82),width,height)
        assertEquals(frames[1].top,result[1].top,0f)
        assertTrue(result[0].focusExclusions.isEmpty())
    }
    @Test fun lowerOpeningCanStillAttachToTheFollowingFrame() {
        val text=Panel(52f/width,130f/height,76f/width,140f/height)
        val result=EdgeSpeech.expand(frames,listOf(text),emptyList(),pixels(127),width,height)
        assertTrue(result[1].top<frames[1].top)
        assertEquals(1,result[0].focusExclusions.size)
    }
    @Test fun narrowUpperTailRetainsItsSpeakerDespiteTheLowerOpening() {
        val ink=IntArray(width*height) {0xff303030.toInt()}
        for(y in 110 until 150)for(x in 50 until 100)ink[y*width+x]=0xffffffff.toInt()
        for(y in 96 until 110)for(x in 60 until 65)ink[y*width+x]=0xffffffff.toInt()
        for(y in 156 until 167)for(x in 50 until 100)ink[y*width+x]=0xffffffff.toInt()
        val text=Panel(55f/width,130f/height,95f/width,145f/height)
        val result=EdgeSpeech.expand(frames,listOf(text),emptyList(),ink,width,height)
        assertEquals(frames[1].top,result[1].top,0f)
        assertTrue(result[0].focusExclusions.isEmpty())
    }
}
