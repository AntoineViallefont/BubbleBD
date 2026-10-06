package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class StructuralFramesTest {
    private val w=600;private val h=800
    private fun image(boxes:List<Panel>)=IntArray(w*h) {0xffffff}.also {pixels ->
        for(p in boxes)for(y in (p.top*h).toInt() until (p.bottom*h).toInt())
            for(x in (p.left*w).toInt() until (p.right*w).toInt())pixels[y*w+x]=0x555555
    }
    @Test fun fullWidthGuttersRecoverOuterBandsFromNarrowDecorCrops() {
        val physical=listOf(Panel(.10f,.05f,.90f,.28f),Panel(.10f,.29f,.90f,.67f),Panel(.10f,.68f,.90f,.94f))
        val cropped=listOf(physical[0],physical[1].copy(left=.30f),physical[2].copy(left=.32f,right=.82f))
        val result=BandLayout.confirm(cropped,image(physical),w,h,emptyList())
        assertEquals(3,result.size)
        assertTrue(result.all {it.left<.11f && it.right>.89f})
    }
    @Test fun realColumnGutterMustPreventWholeBandReplacement() {
        val frames=listOf(Panel(.10f,.05f,.90f,.28f),Panel(.10f,.29f,.90f,.67f),Panel(.10f,.68f,.90f,.94f))
        val pixels=image(frames)
        for(y in 234..534)for(x in 297..303)pixels[y*w+x]=0xffffff
        assertTrue(BandLayout.confirm(frames,pixels,w,h,emptyList()).isEmpty())
    }
    @Test fun thinGreyFrameRecoversWhitePortraitBelowConfirmedBands() {
        val known=listOf(Panel(.10f,.05f,.90f,.25f),Panel(.10f,.26f,.90f,.46f),Panel(.10f,.47f,.90f,.70f))
        val pixels=image(known)
        for(y in 576..752)for(x in 60..540)if(y==576 || y==752 || x==60 || x==540)pixels[y*w+x]=0xaaaaaa
        val result=BandLayout.confirm(known,pixels,w,h,emptyList())
        assertEquals(4,result.size);assertTrue(result.last().top<.73f && result.last().bottom>.93f)
        for(y in 576..752) {pixels[y*w+60]=0xffffff;pixels[y*w+540]=0xffffff}
        assertEquals(3,BandLayout.confirm(known,pixels,w,h,emptyList()).size)
    }
    @Test fun overlappingNeuralFragmentsAcrossDecorBeamKeepOnePhysicalCase() {
        val frame=Panel(.20f,.20f,.80f,.66f);val pixels=image(listOf(frame))
        for(y in 352..370)for(x in 120..479)pixels[y*w+x]=0x111111
        val fragments=listOf(frame.copy(bottom=.47f),frame.copy(top=.44f))
        val result=FrameContinuations.repair(fragments,pixels,w,h,emptyList())
        assertEquals(1,result.size);assertTrue(result.single().top<.21f && result.single().bottom>.65f)
    }
    @Test fun overlappingPredictionsAcrossRealPaperGutterRemainSeparate() {
        val frame=Panel(.20f,.20f,.80f,.66f);val pixels=image(listOf(frame))
        for(y in 355..362)for(x in 120..479)pixels[y*w+x]=0xffffff
        val fragments=listOf(frame.copy(bottom=.47f),frame.copy(top=.44f))
        assertEquals(2,FrameContinuations.repair(fragments,pixels,w,h,emptyList()).size)
    }
    @Test fun sharedCaptionExpandsViewWithoutChangingPhysicalFrame() {
        val physical=listOf(Panel(.50f,.10f,.90f,.50f),Panel(.10f,.51f,.49f,.90f),Panel(.50f,.51f,.90f,.90f))
        val caption=Panel(.40f,.45f,.65f,.56f)
        val result=SpeechOwnership.linkViews(physical,physical,emptySet(),physical.indices.associateWith {listOf(caption)})
        assertEquals(physical[0],result[0].readingOrderBounds)
        assertTrue(result[0].left<=caption.left && result[0].bottom>=caption.bottom)
        assertEquals(listOf(caption),result[0].focusIncludes)
        assertTrue(result.all {it.jointFocus.isEmpty()})
    }
}
