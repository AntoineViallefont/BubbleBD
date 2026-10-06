package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class FrameForegroundTest {
    private val w=240
    private val h=300
    private val colour=0xffe0a080.toInt()
    private fun quad(l:Int,t:Int,r:Int,b:Int)=Panel(l.toFloat()/w,t.toFloat()/h,r.toFloat()/w,b.toFloat()/h,focusOutline=listOf(PanelPoint(l.toFloat()/w,t.toFloat()/h),PanelPoint(r.toFloat()/w,t.toFloat()/h),PanelPoint(r.toFloat()/w,b.toFloat()/h),PanelPoint(l.toFloat()/w,b.toFloat()/h)))
    private val upper=quad(30,30,210,155)
    private val lower=quad(40,145,150,240)
    private fun painted(closed:Boolean=false,wide:Boolean=false):IntArray {
        val p=IntArray(w*h){-1}
        for(y in 30 until 155)for(x in 30 until 210)p[y*w+x]=0xff409070.toInt()
        for(y in 145 until 240)for(x in 40 until 150)p[y*w+x]=colour
        for(y in 125 until 145)for(x in (if(wide)40 else 80) until (if(wide)150 else 92))p[y*w+x]=colour
        if(closed)for(x in 40 until 150)p[144*w+x]=0xff000000.toInt()
        return p
    }
    @Test fun FingerCrossingThePreviousFrameBelongsToItsOwner() {
        val result=FrameForeground.expand(listOf(upper,lower),painted(),w,h)
        assertTrue(result[1].top*h<=125)
        assertTrue(result[1].focusIncludes.any {it.foregroundOverhang})
        assertTrue(result[0].focusExclusions.any {it.foregroundOverhang})
        assertEquals(lower,result[1].readingOrderBounds)
    }
    @Test fun ClosedFrameAndContinuousBackgroundAreNotForegroundProof() {
        val input=listOf(upper,lower)
        assertEquals(input,FrameForeground.expand(input,painted(closed=true),w,h))
        assertEquals(input,FrameForeground.expand(input,painted(wide=true),w,h))
    }
    @Test fun SpeechAlreadyOwnedByThePreviousFrameIsProtected() {
        val input=listOf(upper.copy(focusIncludes=listOf(Panel(75f/w,120f/h,100f/w,146f/h))),lower)
        assertEquals(input,FrameForeground.expand(input,painted(),w,h))
    }
    @Test fun NarrowGutterDoesNotCutAContinuousFinger() {
        val input=listOf(quad(30,30,210,142),lower)
        val pixels=painted()
        for(y in 142 until 145)for(x in 30 until 210)if(x !in 80 until 92)pixels[y*w+x]=-1
        assertTrue(FrameForeground.expand(input,pixels,w,h)[1].top*h<=125)
        // A genuine white break is not a continuous foreground.
        for(x in 80 until 92)pixels[143*w+x]=-1
        assertEquals(input,FrameForeground.expand(input,pixels,w,h))
    }
    @Test fun ShadingRetainsTheFingerButDoesNotBorrowDifferentPaint() {
        val pixels=painted()
        for(y in 135 until 140)for(x in 80 until 92)pixels[y*w+x]=0xff804030.toInt()
        assertTrue(FrameForeground.expand(listOf(upper,lower),pixels,w,h)[1].top*h<=125)
        for(y in 135 until 140)for(x in 80 until 92)pixels[y*w+x]=0xff408050.toInt()
        assertTrue(FrameForeground.expand(listOf(upper,lower),pixels,w,h)[1].top*h>125)
        // Numerically close RGB values can still describe different paint.
        for(y in 135 until 140)for(x in 80 until 92)pixels[y*w+x]=0xffbec39b.toInt()
        assertTrue(FrameForeground.expand(listOf(upper,lower),pixels,w,h)[1].top*h>125)
    }
}
