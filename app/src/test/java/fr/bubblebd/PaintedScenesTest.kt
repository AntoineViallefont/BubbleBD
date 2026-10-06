package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*

class PaintedScenesTest {
    @Test fun paperGapConfirmsTwoPaintedScenesButInteriorSkyDoesNot() {
        val w=200;val h=300;val pixels=IntArray(w*h){-1}
        val frames=listOf(Panel(.05f,.03f,.95f,.68f),Panel(.05f,.72f,.95f,.97f))
        for(p in frames)for(y in (p.top*h).toInt() until (p.bottom*h).toInt())for(x in 10 until 190)pixels[y*w+x]=0xff80a0b0.toInt()
        assertTrue(LayoutEvidence.separatedStack(frames,pixels,w,h))
        for(y in 200 until 220)for(x in 100 until 190)pixels[y*w+x]=0xff80a0b0.toInt()
        assertFalse(LayoutEvidence.separatedStack(frames,pixels,w,h))
    }
    @Test fun wideWhiteGutterPreventsInventingABackgroundBehindTheNextRow() {
        val w=400;val h=600;val pixels=IntArray(w*h){-1}
        val panels=listOf(Panel(.025f,.02f,.975f,.58f))+ (0..2).map {i->Panel((10+i*130f)/w,.62f,(128+i*130f)/w,.97f)}
        for(p in panels)for(y in (p.top*h).toInt() until (p.bottom*h).toInt())for(x in (p.left*w).toInt() until (p.right*w).toInt())pixels[y*w+x]=0xffb09050.toInt()
        val proposals=panels.map {PanelCandidate(it,.99f,false)}
        val result=CanvasPanels.repair(panels,proposals,pixels,w,h,FrameContours(pixels,w,h,emptyList()))
        assertEquals(4,result.size)
        assertTrue(result.any {p->p.width>.9f && p.bottom<.60f && p.focusExclusions.isEmpty()})
    }
}
