package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class RectangularFocusTest {
    @Test fun rectangleContainsPanelAndDetachedBalloonWithoutChangingDetection() {
        val hole=Panel(.3f,.4f,.4f,.5f)
        val balloon=Panel(.65f,.1f,.9f,.3f)
        val panel=Panel(.1f,.3f,.7f,.9f,focusIncludes=listOf(balloon),focusExclusions=listOf(hole),
            focusOutline=listOf(PanelPoint(.1f,.3f),PanelPoint(.7f,.5f),PanelPoint(.7f,.9f)))
        val lit=GuidedFrames.illuminatedBounds(listOf(panel),listOf(0))!!
        assertEquals(Panel(.1f,.1f,.9f,.9f),lit)
        assertTrue(lit.contains(.15f,.15f)) // Empty corner between balloon and panel stays lit.
        assertTrue(lit.focusExclusions.isEmpty())
        assertEquals(listOf(hole),panel.focusExclusions)
        assertEquals(3,panel.focusOutline.size)
    }
    @Test fun jointFrameHasOneRectangleButDoesNotIncludeUnselectedCase() {
        val panels=listOf(Panel(.1f,.2f,.4f,.7f),Panel(.5f,.2f,.8f,.7f,
            focusIncludes=listOf(Panel(.7f,.05f,.85f,.25f))),Panel(.02f,.8f,.98f,.98f))
        assertEquals(Panel(.1f,.05f,.85f,.7f),GuidedFrames.illuminatedBounds(panels,listOf(0,1)))
        assertEquals(Panel(.1f,.2f,.4f,.7f),GuidedFrames.illuminatedBounds(panels,listOf(0,0,-1,99)))
        assertNull(GuidedFrames.illuminatedBounds(panels,emptyList()))
    }
}
