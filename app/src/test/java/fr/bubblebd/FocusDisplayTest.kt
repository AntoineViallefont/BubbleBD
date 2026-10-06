package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class FocusDisplayTest {
    @Test fun consecutiveStackedCasesNeverShareAReadingStep() {
        val upper=Panel(.1f,.1f,.9f,.49f)
        val lower=Panel(.1f,.51f,.9f,.9f)
        val frames=SpeechOwnership.linkViews(listOf(upper,lower),listOf(upper,lower),setOf(0 to 1))
        for(i in frames.indices) {
            val f=GuidedFrames.frame(frames,i,1000,1000,1500,2000)
            assertEquals(listOf(i),f.visible)
            assertEquals(i..i,f.indices)
        }
    }
    @Test fun sharedBalloonFitsEachSeparateCaseWithoutChangingPhysicalBounds() {
        val cores=listOf(Panel(.1f,.1f,.9f,.49f),Panel(.1f,.51f,.9f,.9f))
        val speech=Panel(.30f,.44f,.60f,.58f)
        val frames=SpeechOwnership.linkViews(cores,cores,setOf(0 to 1),mapOf(0 to listOf(speech),1 to listOf(speech)))
        frames.forEachIndexed {i,p ->
            assertEquals(cores[i].left,p.left,0f);assertEquals(cores[i].top,p.top,0f)
            assertEquals(cores[i].right,p.right,0f);assertEquals(cores[i].bottom,p.bottom,0f)
            val f=GuidedFrames.frame(frames,i,1000,1000,400,800)
            assertEquals(listOf(i),f.visible)
            assertTrue(f.bounds.top<=speech.top && f.bounds.bottom>=speech.bottom)
            assertTrue(f.bounds.width*1000*f.scale<=400.01f && f.bounds.height*1000*f.scale<=800.01f)
        }
    }
    @Test fun captionAtThreeCaseJunctionIsSharedButADirectedTailIsNot() {
        val cores=listOf(Panel(.1f,.1f,.9f,.48f),Panel(.1f,.52f,.48f,.9f),Panel(.52f,.52f,.9f,.9f))
        val text=Panel(.40f,.45f,.60f,.55f)
        assertEquals(listOf(0,1,2),SpeechOwnership.uncertainCases(text,text,cores,true))
        assertTrue(SpeechOwnership.uncertainCases(text,Panel(.38f,.44f,.62f,.70f),cores,false).isEmpty())
    }
    @Test fun largeSceneDoesNotAutomaticallyIncludeItsInset() {
        val scene=Panel(.05f,.05f,.95f,.75f)
        val inset=Panel(.1f,.5f,.45f,.7f)
        assertEquals(listOf(0),GuidedFrames.frame(listOf(scene,inset),0,1000,1000,2000,2000).visible)
    }
}
