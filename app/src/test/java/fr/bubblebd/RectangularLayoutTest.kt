package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class RectangularLayoutTest {
    private val w=600;private val h=800
    private val frames=listOf(Panel(.08f,.06f,.53f,.28f),Panel(.54f,.06f,.92f,.28f),
        Panel(.08f,.29f,.40f,.51f),Panel(.41f,.29f,.92f,.51f),
        Panel(.08f,.52f,.92f,.74f),Panel(.08f,.75f,.46f,.94f),Panel(.47f,.75f,.92f,.94f))
    private fun pixels(boxes:List<Panel>)=IntArray(w*h) {0xffffff}.also {pixels ->
        for(p in boxes)for(y in (p.top*h).toInt() until (p.bottom*h).toInt())
            for(x in (p.left*w).toInt() until (p.right*w).toInt())pixels[y*w+x]=0x777777
    }
    private fun proposals(boxes:List<Panel>)=boxes.map {PanelCandidate(it,.96f,false)}
    @Test fun wholePageWithDifferentWidthsConfirmsOrthogonalRows() {
        val result=RectangularLayout.confirm(proposals(frames),pixels(frames),w,h)
        assertEquals(7,result.size);assertTrue(result.all {it.focusOutline.isEmpty()})
    }
    @Test fun tallFramesCanNeighbourTwoShorterFramesWithoutInventingUniformRows() {
        val mixed=listOf(Panel(.08f,.06f,.35f,.50f),Panel(.36f,.06f,.65f,.27f),
            Panel(.36f,.28f,.65f,.50f),Panel(.66f,.06f,.92f,.50f),
            Panel(.08f,.51f,.60f,.94f),Panel(.61f,.51f,.92f,.94f))
        val result=RectangularLayout.confirm(proposals(mixed),pixels(mixed),w,h)
        assertEquals(6,result.size)
        assertTrue(result.all {it.focusOutline.isEmpty()})
    }
    @Test fun missingCaseDoesNotTurnPartialLayoutIntoCompleteGrid() {
        assertTrue(RectangularLayout.confirm(proposals(frames.drop(1)),pixels(frames),w,h).isEmpty())
    }
    @Test fun weakerProposalNeedsItsCompletePhysicalFrameToFillTheGrid() {
        val observed=proposals(frames).toMutableList();observed[1]=PanelCandidate(frames[1],.55f,false)
        assertEquals(7,RectangularLayout.confirm(observed,pixels(frames),w,h).size)
        val missing=pixels(frames.drop(1))
        assertTrue(RectangularLayout.confirm(observed,missing,w,h).isEmpty())
    }
    @Test fun strongPredictionsNeedActualFrameEvidence() {
        assertTrue(RectangularLayout.confirm(proposals(frames),IntArray(w*h) {0xffffff},w,h).isEmpty())
    }
    @Test fun independentWeakInsetMustNotDisappear() {
        val extra=Panel(.20f,.32f,.32f,.44f)
        assertTrue(RectangularLayout.confirm(proposals(frames)+PanelCandidate(extra,.60f,false),pixels(frames),w,h).isEmpty())
    }
    @Test fun wholeRowObservationCannotEraseItsTwoPhysicalColumns() {
        val observed=proposals(frames.filterIndexed {i,_->i !in 2..3})+
            PanelCandidate(Panel(.08f,.29f,.92f,.51f),.96f,false)
        assertTrue(RectangularLayout.confirm(observed,pixels(frames),w,h,geometry=frames).isEmpty())
    }
    @Test fun completeGeometryFrameOutsideModelLayoutCannotBeDiscarded() {
        val small=listOf(Panel(.08f,.06f,.51f,.28f),Panel(.52f,.06f,.73f,.28f),Panel(.74f,.06f,.92f,.28f),
            Panel(.08f,.29f,.92f,.51f),Panel(.08f,.52f,.92f,.74f),Panel(.08f,.75f,.92f,.94f))
        assertTrue(RectangularLayout.confirm(proposals(small.filterIndexed {i,_->i!=2}),pixels(small),w,h,geometry=small).isEmpty())
    }
    @Test fun BackgroundAndInsetsDoNotEstablishRectangularTiling() {
        val scene=Panel(.08f,.06f,.92f,.94f)
        assertTrue(RectangularLayout.confirm(proposals(frames+scene),pixels(frames),w,h).isEmpty())
    }
    @Test fun DiagonalGuttersKeepGeneralDetector() {
        val image=pixels(frames)
        for(y in (frames[0].top*h).toInt() until (frames[0].bottom*h).toInt()) {
            val shift=(y-(frames[0].top*h).toInt())/12
            for(x in 316..330+shift)image[y*w+x]=0x777777
        }
        assertTrue(RectangularLayout.confirm(proposals(frames),image,w,h).isEmpty())
    }
    @Test fun cancelledMergeStopsBeforeFurtherAnalysis() {
        var reached=false
        try {
            HybridPanels.combine(frames,proposals(frames),pixels(frames),w,h,stop={throw java.util.concurrent.CancellationException("cancelled")},trace={_,_->reached=true})
            fail("Cancelled analysis must not return a result")
        } catch(_:java.util.concurrent.CancellationException) {assertFalse(reached)}
    }
    @Test fun cancelledObliqueSearchStopsDuringItsPixelSearch() {
        val overlapping=listOf(Panel(.10f,.10f,.55f,.45f),Panel(.53f,.10f,.90f,.45f))
        var checks=0
        try {
            ObliqueFrames.attach(overlapping,pixels(overlapping),w,h,stop={checks++;throw java.util.concurrent.CancellationException("cancelled")})
            fail("Obsolete fitting must stop")
        } catch(_:java.util.concurrent.CancellationException) {assertEquals(1,checks)}
    }

}
