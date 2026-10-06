package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class PanelStyleHistoryTest {
    private val row=listOf(Panel(.1f,.1f,.3f,.4f),Panel(.35f,.1f,.6f,.4f),Panel(.65f,.1f,.9f,.4f))
    @Test fun earlierDistinctPagesProvideThePriorNotRepeatedVisitsOrFuturePages() {
        val history=PanelStyleHistory()
        history.record(0,row);history.record(0,row)
        assertFalse(history.prefersRectangles(1))
        history.record(2,row)
        assertFalse(history.prefersRectangles(1))
        assertTrue(history.prefersRectangles(3))
        assertTrue(PanelStyleHistory(history.snapshot()).prefersRectangles(3))
    }
    @Test fun mixedShapesAndBackgroundsDoNotEstablishARectangularStyle() {
        val history=PanelStyleHistory()
        val tilted=row.map {it.copy(focusOutline=listOf(PanelPoint(it.left,it.top),PanelPoint(it.right,it.top),PanelPoint(it.right,it.bottom)))}
        history.record(0,row);history.record(1,tilted)
        assertFalse(history.prefersRectangles(2))
        val empty=PanelStyleHistory();empty.record(0,listOf(Panel(0f,0f,1f,1f)));empty.record(1,row.take(1))
        assertFalse(empty.prefersRectangles(2))
    }
    @Test fun historyKeepsBeginningWithBoundedStorage() {
        val history=PanelStyleHistory();repeat(200) {history.record(it,row)}
        assertEquals(64,history.snapshot().size)
        assertTrue(history.snapshot().containsKey(0));assertTrue(history.snapshot().containsKey(199))
    }
}
