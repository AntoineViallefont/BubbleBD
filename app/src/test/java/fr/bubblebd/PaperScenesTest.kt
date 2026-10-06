package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class PaperScenesTest {
    @Test fun UnevenPaperSeparatesUnframedNeighboursWithoutSplittingAClosedScene() {
        val w=240;val h=300
        val pixels=IntArray(w*h) {i->val shade=215+(i%w)*20/w+(i/w)*12/h;(255 shl 24) or (shade shl 16) or (shade shl 8) or (shade-12)}
        fun line(l:Int,t:Int,r:Int,b:Int) {for(y in t until b)for(x in l until r)pixels[y*w+x]=0xff202020.toInt()}
        fun frame(l:Int,t:Int,r:Int,b:Int) {line(l,t,r,t+1);line(l,b-1,r,b);line(l,t,l+1,b);line(r-1,t,r,b)}
        frame(25,25,215,85)
        frame(25,140,140,210)
        frame(25,215,80,280);frame(145,215,215,280)
        // Three moments share one genuine frame; they must remain one case.
        for(x in listOf(35,70,105))line(x,155,x+15,190)
        for(x in listOf(35,95,170))line(x,95,x+20,130)
        line(170,150,200,200);line(100,230,125,275)
        val found=BookRules.orderPanels(PaperScenes.detect(pixels,w,h,pixels,w,h),false)
        assertEquals(9,found.size)
        assertTrue(found[4].contains(35f/w,155f/h) && found[4].contains(120f/w,190f/h))
    }
    @Test fun DenseOrUnstructuredArtworkCannotUseThePaperFallback() {
        assertTrue(PaperScenes.detect(IntArray(240*300){0xff803050.toInt()},240,300,IntArray(240*300){0xff803050.toInt()},240,300).isEmpty())
        assertTrue(PaperScenes.detect(IntArray(240*300){-1},240,300,IntArray(240*300){-1},240,300).isEmpty())
    }
}
