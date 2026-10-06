package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*

class GridPanelsTest {
    private fun grid(gutter:Int=10):Triple<IntArray,Int,Int> {
        val w=320;val h=260;val pixels=IntArray(w*h){-1}
        for(yi in 0..1)for(xi in 0..2) {
            val l=xi*(100+gutter);val r=l+99;val t=yi*125;val b=t+114
            for(x in l..r) {pixels[t*w+x]=0xff000000.toInt();pixels[b*w+x]=0xff000000.toInt()}
            for(y in t..b) {pixels[y*w+l]=0xff000000.toInt();pixels[y*w+r]=0xff000000.toInt()}
            // Lettering and figures in each cell do not need to share an appearance.
            for(y in t+12..t+25)for(x in l+10..l+20) pixels[y*w+x]=0xff404040.toInt()
        }
        return Triple(pixels,w,h)
    }
    @Test fun alignedClosedFramesAtImageEdgesRemainSixSeparateCases() {
        val (px,w,h)=grid()
        val panels=GridPanels.detect(px,w,h)
        assertEquals(6,panels.size)
        assertTrue(panels.all {it.width<.34f && it.height<.45f})
        assertEquals(0f,panels.first().left)
    }
    @Test fun outerBlackFramesDoNotTurnTheWhiteGuttersIntoBlackPaper() {
        val (px,w,_) = grid()
        assertEquals(6,PanelGeometry.detect(px.copyOf(w*240),w,240).size)
    }
    @Test fun sharedDecorLinesWithoutPaperGuttersDoNotCreateGrid() {
        val (px,w,h)=grid(0)
        assertTrue(GridPanels.detect(px,w,h).isEmpty())
    }
    @Test fun damagedBordersAreNotCompletedByInventingCells() {
        val (px,w,h)=grid()
        for(y in 125 until 230)px[y*w+209]=-1
        assertTrue(GridPanels.detect(px,w,h).isEmpty())
    }
    @Test fun singleStripWithAntialiasedNarrowGuttersKeepsThreeCases() {
        val w=312;val h=100;val px=IntArray(w*h){-1}
        for(i in 0..2) {
            val l=i*106;val r=l+99
            for(x in l..r) {px[x]=0xff000000.toInt();px[(h-1)*w+x]=0xff000000.toInt()}
            for(y in 0 until h) {px[y*w+l]=0xff000000.toInt();px[y*w+r]=0xff000000.toInt()}
        }
        for(x in listOf(100,105,206,211))for(y in 0 until h)px[y*w+x]=0xffb0b0b0.toInt()
        assertEquals(3,GridPanels.detect(px,w,h).size)
        assertEquals(3,PanelGeometry.detect(px,w,h).size)
    }
    @Test fun oneClosedFrameDoesNotCreateAnArtificialGrid() {
        val w=300;val h=100;val px=IntArray(w*h){-1}
        for(x in 0 until w) {px[x]=0xff000000.toInt();px[(h-1)*w+x]=0xff000000.toInt()}
        for(y in 0 until h) {px[y*w]=0xff000000.toInt();px[y*w+w-1]=0xff000000.toInt()}
        assertTrue(GridPanels.detect(px,w,h).isEmpty())
    }
    @Test fun wideSceneWithBlackLandscapeAboveThreeFramedCasesRemainsFour() {
        val w=320;val h=260;val px=IntArray(w*h){-1}
        for(x in 0 until w)px[x]=0xff000000.toInt()
        for(y in 0..120) {px[y*w]=0xff000000.toInt();px[y*w+w-1]=0xff000000.toInt()}
        for(y in 100..120)for(x in 0 until w)px[y*w+x]=0xff000000.toInt()
        for(i in 0..2) {
            val l=i*110;val r=l+99
            for(x in l..r) {px[135*w+x]=0xff000000.toInt();px[(h-1)*w+x]=0xff000000.toInt()}
            for(y in 135 until h) {px[y*w+l]=0xff000000.toInt();px[y*w+r]=0xff000000.toInt()}
        }
        val panels=GridPanels.detect(px,w,h)
        assertEquals(4,panels.size)
        assertTrue(panels.first().bottom>120f/h && panels.first().width>.99f)
        assertTrue(panels.drop(1).all {it.top>=135f/h})
    }
    @Test fun grayAntialiasedBorderStillSeparatesRealClosedCells() {
        val (px,w,h)=grid()
        for(i in px.indices)if(px[i]==0xff000000.toInt())px[i]=0xff808080.toInt()
        assertEquals(6,GridPanels.detect(px,w,h).size)
    }
    @Test fun oneContinuousWhitePixelKeepsAdjacentFramesSeparate() {
        val w=306;val h=100;val px=IntArray(w*h){-1}
        for(i in 0..2) {
            val l=i*103;val r=l+99
            for(x in l..r) {px[x]=0xff808080.toInt();px[(h-1)*w+x]=0xff808080.toInt()}
            for(y in 0 until h)for(x in listOf(l,l+1,r-1,r))px[y*w+x]=0xff808080.toInt()
        }
        // One white column plus antialiasing next to either physical border.
        for(y in 0 until h)for(x in listOf(100,102,203,205))px[y*w+x]=0xff808080.toInt()
        assertEquals(3,GridPanels.detect(px,w,h).size)
        assertEquals(3,PanelGeometry.detect(px,w,h).size)
    }
}
