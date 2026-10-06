package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PanelCandidatesTest {
    private fun candidate(p:Panel,score:Float=.9f)=PanelCandidate(p,score,false)
    @Test fun nestedPanelSurvivesEvenWhenItSharesParentHeight() {
        val scene=candidate(Panel(.05f,.1f,.95f,.9f))
        val inset=candidate(Panel(.55f,.11f,.9f,.88f),.95f)
        assertEquals(setOf(scene,inset),PanelCandidates.consolidate(listOf(scene,inset)).toSet())
    }
    @Test fun duplicatesDoNotChangeResultAndHighestConfidenceWins() {
        val precise=candidate(Panel(.1f,.1f,.4f,.5f))
        val loose=candidate(Panel(.08f,.08f,.42f,.52f),.4f)
        val other=candidate(Panel(.6f,.1f,.9f,.5f))
        val input=listOf(precise,loose,other,loose,precise)
        val expected=PanelCandidates.consolidate(listOf(precise,other))
        repeat(30) {assertEquals(expected,PanelCandidates.consolidate(input.shuffled(Random(it))))}
    }
    @Test fun independentChildrenReplaceWeakEnclosingRegion() {
        val parent=candidate(Panel(.1f,.1f,.9f,.9f),.35f)
        val children=listOf(candidate(Panel(.1f,.1f,.48f,.9f)),candidate(Panel(.52f,.1f,.9f,.9f)))
        assertEquals(children.toSet(),PanelCandidates.consolidate(children+parent+children).toSet())
    }
    @Test fun oneRepeatedInsetCannotEraseItsScene() {
        val scene=candidate(Panel(.1f,.1f,.9f,.9f),.4f)
        val inset=candidate(Panel(.2f,.2f,.5f,.6f))
        assertEquals(setOf(scene,inset),PanelCandidates.consolidate(listOf(scene)+List(20){inset}).toSet())
    }
    @Test fun partialObservationsSharingThreeEdgesAreNotExtraPanels() {
        val full=candidate(Panel(.1f,.1f,.9f,.5f),.8f)
        val cropped=candidate(Panel(.5f,.1f,.9f,.5f),.4f)
        assertEquals(listOf(full),PanelCandidates.consolidate(listOf(cropped,full)))
    }
    @Test fun mirrorAndScaleDoNotChangeIndependentPanelSelection() {
        val input=listOf(candidate(Panel(.05f,.1f,.95f,.9f)),candidate(Panel(.55f,.2f,.85f,.5f)),candidate(Panel(.54f,.19f,.86f,.51f),.4f))
        val expected=PanelCandidates.consolidate(input)
        for(mirror in listOf(false,true))for(scale in listOf(.5f,.8f,1f)) {
            fun transform(d:PanelCandidate):PanelCandidate {
                val p=d.bounds
                fun x(v:Float)=.5f+((if(mirror)1f-v else v)-.5f)*scale
                fun y(v:Float)=.5f+(v-.5f)*scale
                return d.copy(bounds=Panel(x(if(mirror)p.right else p.left),y(p.top),x(if(mirror)p.left else p.right),y(p.bottom)))
            }
            assertEquals(expected.map(::transform).toSet(),PanelCandidates.consolidate(input.map(::transform)).toSet())
        }
    }
    @Test fun invalidPredictionsAndTextCannotCreatePanels() {
        val valid=candidate(Panel(.1f,.1f,.4f,.4f))
        val input=listOf(valid,candidate(Panel(Float.NaN,0f,1f,1f)),candidate(Panel(.4f,.1f,.2f,.9f)),valid.copy(text=true),valid.copy(confidence=Float.NaN))
        assertEquals(listOf(valid),PanelCandidates.consolidate(input))
    }
}
