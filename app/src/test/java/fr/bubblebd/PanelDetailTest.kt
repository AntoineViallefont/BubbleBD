package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class PanelDetailTest {
    private val frames=listOf(Panel(0f,0f,1f,.4f),Panel(.1f,.5f,.3f,.8f),Panel(.4f,.5f,.6f,.8f),Panel(.7f,.5f,.9f,.8f))
    @Test fun broadPaperRegionWithSmallScenesGetsASecondLook() {
        assertTrue(PanelDetail.needed(frames,IntArray(200*300){0xffece0d0.toInt()},200,300))
    }
    @Test fun paintedSceneDoesNotPayForEightExtraTiles() {
        assertFalse(PanelDetail.needed(frames,IntArray(200*300){0xff306050.toInt()},200,300))
    }
    @Test fun establishedInsetBackgroundKeepsItsOwnGeometry() {
        val background=frames[0].copy(focusExclusions=listOf(Panel(.2f,.1f,.5f,.3f)))
        assertFalse(PanelDetail.needed(listOf(background)+frames.drop(1),IntArray(200*300){-1},200,300))
    }
}
