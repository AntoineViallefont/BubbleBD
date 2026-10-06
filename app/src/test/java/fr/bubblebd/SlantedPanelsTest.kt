package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class SlantedPanelsTest {
    @Test fun diagonalGutterMustBeVisibleBeforeTwoInsertsAreRecovered() {
        val w=200;val h=300
        val proposals=listOf(
            PanelCandidate(Panel(.08f,.25f,.92f,.48f),.96f,false),
            PanelCandidate(Panel(.08f,.40f,.92f,.63f),.97f,false))
        val plain=IntArray(w*h) {0xff203040.toInt()}
        assertTrue(SlantedPanels.find(proposals,plain,w,h).isEmpty())
        val pixels=plain.copyOf()
        for(x in 16..184) {
            val y=(144-(x-16)*.14f).toInt()
            for(d in -3..3)pixels[(y+d)*w+x]=0xffffffff.toInt()
        }
        val found=SlantedPanels.find(proposals,pixels,w,h)
        assertEquals(2,found.size)
        assertTrue(found.all {it.second.focusOutline.size==4})
        val upper=found[0].second.focusOutline
        val lower=found[1].second.focusOutline
        assertTrue(upper[3].y>upper[2].y)
        assertTrue(lower[0].y>upper[3].y && lower[1].y>upper[2].y)
    }
}
