package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class ObliqueFramesTest {
    private fun painted():Triple<IntArray,Int,Int> {
        val w=360;val h=400;val px=IntArray(w*h){-1}
        for(y in 20 until 380)for(x in 20 until 340) {
            val upperBottom=170f+(x-20)/16f
            val lowerTop=180f+(x-20)/16f
            if(y<upperBottom || y>=lowerTop)px[y*w+x]=0xff204060.toInt()
        }
        return Triple(px,w,h)
    }
    @Test fun physicalDiagonalGutterGetsIndependentQuadrilaterals() {
        val (px,w,h)=painted()
        val input=listOf(Panel(20f/w,20f/h,340f/w,190f/h),Panel(20f/w,180f/h,340f/w,380f/h))
        val result=ObliqueFrames.attach(input,px,w,h)
        assertTrue(result.all {it.focusOutline.size==4})
        assertTrue(result[0].focusOutline[2].y>result[0].focusOutline[3].y)
        assertTrue(result[1].focusOutline[1].y>result[1].focusOutline[0].y)
        assertTrue(result[0].focusOutline[3].y*h<180)
        assertTrue(result[1].focusOutline[0].y*h>170)
        val biased=ObliqueFrames.attach(input,px,w,h,rectanglePrior=true)
        assertTrue("A clear diagonal survives the album's rectangular preference",biased.all {it.focusOutline.size==4})
    }
    @Test fun whiteDiagonalInsideArtworkDoesNotInventPhysicalFrameEdges() {
        val (px,w,h)=painted()
        for(y in 0 until h)for(x in 0 until w)if(x<20 || x>=340 || y<20 || y>=380)px[y*w+x]=0xff607080.toInt()
        val input=listOf(Panel(20f/w,20f/h,340f/w,190f/h),Panel(20f/w,180f/h,340f/w,380f/h))
        assertTrue(ObliqueFrames.attach(input,px,w,h).all {it.focusOutline.isEmpty()})
    }
    @Test fun backgroundAndItsInsetKeepTheirExistingMask() {
        val (px,w,h)=painted();val inset=Panel(.2f,.2f,.4f,.4f)
        val input=listOf(Panel(0f,0f,1f,1f,focusExclusions=listOf(inset)),inset)
        assertEquals(input,ObliqueFrames.attach(input,px,w,h))
    }
    @Test fun smallSpeechExclusionDoesNotHideThePhysicalDiagonalGutter() {
        val (px,w,h)=painted();val speech=Panel(.4f,.1f,.45f,.15f)
        val input=listOf(Panel(20f/w,20f/h,340f/w,190f/h,focusExclusions=listOf(speech)),Panel(20f/w,180f/h,340f/w,380f/h))
        val result=ObliqueFrames.attach(input,px,w,h)
        assertEquals(4,result.first().focusOutline.size)
        assertEquals(listOf(speech),result.first().focusExclusions)
    }
}
