package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class GuidedSequenceTest {
    private fun steps(p:List<Panel>)=GuidedSequence.steps(p,1000,1000,400,800)
    @Test fun fullyDisplayedInsetsAreSkippedButPhysicalCasesRemainAvailable() {
        val scene=Panel(.05f,.05f,.95f,.65f)
        val inset=Panel(.2f,.2f,.4f,.4f)
        val next=Panel(.1f,.7f,.9f,.95f)
        val panels=listOf(scene,inset,next)
        val route=steps(panels)
        assertEquals(listOf(0,2),route.map {it.indices.first})
        assertEquals(listOf(2,0),route.asReversed().map {it.indices.first})
        assertEquals(listOf(1),GuidedFrames.frame(panels,1,1000,1000,400,800).visible)
        assertEquals(3,panels.size)
    }
    @Test fun aBalloonOutsideTheAlreadyDisplayedRectangleKeepsItsStep() {
        val scene=Panel(.05f,.05f,.95f,.65f)
        val inset=Panel(.2f,.2f,.4f,.4f,focusIncludes=listOf(Panel(.3f,.6f,.5f,.7f)))
        assertEquals(listOf(0,1),steps(listOf(scene,inset)).map {it.indices.first})
    }
    @Test fun partialOverlapAndLaterEnclosureNeverRemoveEarlierSteps() {
        val left=Panel(.05f,.05f,.4f,.5f)
        val right=Panel(.3f,.05f,.95f,.5f)
        val background=Panel(.01f,.01f,.99f,.99f)
        assertEquals(listOf(0,1,2),steps(listOf(left,right,background)).map {it.indices.first})
    }
    @Test fun separateEarlierRectanglesCannotCollectivelyHideAnUnreadCase() {
        val top=Panel(.1f,.1f,.9f,.4f)
        val bottom=Panel(.1f,.5f,.9f,.8f)
        val crossing=Panel(.2f,.3f,.4f,.6f)
        assertEquals(listOf(0,1,2),steps(listOf(top,bottom,crossing)).map {it.indices.first})
    }
    @Test fun wholePageFallbackIsNotACompletedComicScene() {
        val fallback=Panel(0f,0f,1f,1f)
        val inset=Panel(.2f,.2f,.4f,.4f)
        assertEquals(listOf(0,1),steps(listOf(fallback,inset)).map {it.indices.first})
        val scene=fallback.copy(focusExclusions=listOf(inset))
        assertEquals(listOf(0),steps(listOf(scene,inset)).map {it.indices.first})
    }
    @Test fun jointViewCanCoverALaterCaseWithoutChangingItsNumbers() {
        val a=Panel(.05f,.1f,.45f,.5f)
        val b=Panel(.55f,.1f,.95f,.5f)
        val balloon=Panel(.4f,.05f,.6f,.2f)
        val linked=SpeechOwnership.linkViews(listOf(a,b),listOf(a,b),setOf(0 to 1),mapOf(0 to listOf(balloon),1 to listOf(balloon)))
        val inset=Panel(.46f,.3f,.54f,.4f)
        val route=steps(linked+inset)
        assertEquals(1,route.size)
        assertEquals(listOf(0,1),route.single().visible)
        assertEquals("Cases 1 et 2/3",GuidedFrames.label(route.single().visible,3))
    }
}
