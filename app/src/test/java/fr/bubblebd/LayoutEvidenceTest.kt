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
    @Test fun repeatedRecoveriesPreserveBalloonsAndSeparateLayeredScenes() {
        val frame=Panel(.25f,.15f,.55f,.55f)
        val balloon=Panel(.30f,.12f,.40f,.20f)
        val recovered=Panel(.252f,.152f,.552f,.552f,focusIncludes=listOf(balloon))
        val unique=LayoutEvidence.uniqueFrames(listOf(frame,recovered))
        assertEquals(1,unique.size)
        assertEquals(listOf(balloon),unique.single().focusIncludes)
        val layered=frame.copy(focusExclusions=listOf(Panel(.35f,.3f,.45f,.4f)))
        assertEquals(2,LayoutEvidence.uniqueFrames(listOf(layered,recovered)).size)
        val realInset=Panel(.28f,.2f,.50f,.50f)
        assertEquals(2,LayoutEvidence.uniqueFrames(listOf(frame,realInset)).size)
    }
    @Test fun anOffsetLineOfArtworkDoesNotCloseAnEmbeddedCrop() {
        val w=200;val h=240;val full=Panel(.1f,.1f,.9f,.8f);val half=Panel(.5f,.1f,.9f,.8f)
        val pixels=IntArray(w*h){0xffaaaaaa.toInt()}
        fun stroke(x:Int) {for(y in 24..192)for(dx in -1..1)pixels[y*w+x+dx]=0xff222222.toInt()}
        stroke(20);stroke(180)
        for(x in 20..180)for(dy in -1..1) {pixels[(24+dy)*w+x]=0xff222222.toInt();pixels[(192+dy)*w+x]=0xff222222.toInt()}
        stroke(94) // Near enough to attract a contour fit, but not the crop's edge.
        assertEquals(listOf(full),LayoutEvidence.removeEmbeddedCrops(listOf(full,half),FrameContours(pixels,w,h,emptyList()),w,h))
        stroke(100)
        assertEquals(listOf(full,half),LayoutEvidence.removeEmbeddedCrops(listOf(full,half),FrameContours(pixels,w,h,emptyList()),w,h))
    }
}
