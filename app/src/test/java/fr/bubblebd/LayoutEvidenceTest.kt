package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class LayoutEvidenceTest {
    @Test fun repeatedHalfCropNeedsItsOwnBorderToBecomeAnInset() {
        val w=200;val h=240;val full=Panel(.1f,.1f,.9f,.8f);val half=Panel(.1f,.1f,.5f,.8f)
        val pixels=IntArray(w*h){0xffaaaaaa.toInt()}
        fun frame(p:Panel) {
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            for(y in t..b)for(dx in -1..1) {pixels[y*w+l+dx]=0xff222222.toInt();pixels[y*w+r+dx]=0xff222222.toInt()}
            for(x in l..r)for(dy in -1..1) {pixels[(t+dy)*w+x]=0xff222222.toInt();pixels[(b+dy)*w+x]=0xff222222.toInt()}
        }
        frame(full)
        val input=listOf(PanelCandidate(full,.91f,false),PanelCandidate(half,.95f,false))
        assertEquals(listOf(input.first()),LayoutEvidence.removeCrops(input,pixels,w,h))
        frame(half)
        assertEquals(input,LayoutEvidence.removeCrops(input,pixels,w,h))
    }
}
