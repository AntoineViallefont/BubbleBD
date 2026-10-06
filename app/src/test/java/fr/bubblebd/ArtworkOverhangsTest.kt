package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class ArtworkOverhangsTest {
    private val w=240
    private val h=300
    private val colour=0xffd04070.toInt()
    private fun painted(border:Boolean=false):IntArray {
        val pixels=IntArray(w*h){-1}
        for(y in 30 until 160)for(x in 30 until 100)pixels[y*w+x]=colour
        for(y in 190 until 270)for(x in 140 until 210)pixels[y*w+x]=0xff208080.toInt()
        for(y in 130 until 182)for(x in 60 until 82)pixels[y*w+x]=colour
        if(border)for(x in 30 until 100)pixels[159*w+x]=0xff000000.toInt()
        return pixels
    }
    private fun frames()=listOf(Panel(30f/w,30f/h,100f/w,160f/h),Panel(140f/w,190f/h,210f/w,270f/h))
    @Test fun foregroundCrossingAnOpenBorderRemainsVisible() {
        val result=ArtworkOverhangs.expand(frames(),painted(),w,h)
        assertTrue(result[0].bottom*h>=182)
        assertTrue(result[0].focusIncludes.any {it.focusOutline.isNotEmpty()})
        assertEquals(frames()[0],result[0].readingOrderBounds)
        assertEquals(frames()[1],result[1])
    }
    @Test fun closedBlackBorderDoesNotAttachAnUnrelatedDrawing() {
        assertEquals(frames(),ArtworkOverhangs.expand(frames(),painted(true),w,h))
    }
    @Test fun insetCannotFloodIntoItsPaintedBackground() {
        val inset=frames()[0]
        val background=Panel(.02f,.02f,.98f,.98f,readingOrderBounds=Panel(.02f,.6f,.98f,.98f),focusExclusions=listOf(inset))
        val input=listOf(inset,background)
        assertEquals(input,ArtworkOverhangs.expand(input,painted(),w,h))
    }
    @Test fun grayscaleAmbiguityKeepsTheExistingFrame() {
        val pixels=painted().map {c->if(c==colour)0xff808080.toInt() else c}.toIntArray()
        assertEquals(frames(),ArtworkOverhangs.expand(frames(),pixels,w,h))
    }
    @Test fun ExteriorOwnershipUsesBoundaryEvidenceRatherThanReadingOrder() {
        val pixels=IntArray(w*h){-1}
        val upper=Panel(80f/w,30f/h,140f/w,100f/h)
        val lower=Panel(30f/w,103f/h,140f/w,250f/h)
        for(y in 30 until 100)for(x in 80 until 140)pixels[y*w+x]=colour
        for(y in 100 until 250)for(x in 22 until 140)pixels[y*w+x]=colour
        val result=ArtworkOverhangs.expand(listOf(upper,lower),pixels,w,h)
        val reversed=ArtworkOverhangs.expand(listOf(lower,upper),pixels,w,h)
        assertTrue(result[1].left*w<=22)
        assertEquals(result[1],reversed[0])
        assertEquals(result[0],reversed[1])
    }
    @Test fun AConnectedAntialiasedBorderCannotPullTheNextSceneUpwards() {
        val pixels=IntArray(w*h){-1}
        val upper=Panel(80f/w,30f/h,140f/w,100f/h)
        val lower=Panel(30f/w,103f/h,140f/w,250f/h)
        for(y in 30 until 100)for(x in 80 until 140)pixels[y*w+x]=colour
        for(y in 50 until 100)pixels[y*w+79]=colour
        for(y in 100 until 250)for(x in 22 until 140)pixels[y*w+x]=colour
        val result=ArtworkOverhangs.expand(listOf(upper,lower),pixels,w,h)
        assertTrue(result[1].left*w<=22)
        assertTrue("Unrelated upper border is not part of the lower reading envelope",result[1].top*h>=95)
    }
    @Test fun MatchingPaintInTheNextFrameDoesNotAttachItsBorderToTheFirst() {
        val pixels=IntArray(w*h){-1}
        for(y in 30 until 200)for(x in 30 until 120)pixels[y*w+x]=colour
        val input=listOf(Panel(30f/w,30f/h,120f/w,100f/h),Panel(30f/w,103f/h,120f/w,200f/h))
        assertEquals(input,ArtworkOverhangs.expand(input,pixels,w,h))
    }
}
