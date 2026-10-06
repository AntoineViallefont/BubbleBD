package fr.bubblebd

import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.io.File
import java.io.DataInputStream

class BridgedInsetsReplayTest {
    @Test fun approvedFramesBridgingTwoScenesRemainSeparateAndReadable() {
        val dir=File("../docs/detection/private/october-feedback-demains-20261003")
        assumeTrue(File(dir,"reference-51-replay.tsv").exists())
        val expected=File(dir,"reference-51-replay.tsv").readLines().map {it.split(" ").map(String::toFloat)}
        val input=DataInputStream(File(dir,"retour-6989.rgb").inputStream())
        val w=input.readInt();val h=input.readInt()
        val pixels=IntArray(w*h){(input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
        val predictions=File(dir,"retour-6989-predictions.tsv").readLines().map {line ->val v=line.split("\t")
            PanelCandidate(Panel(v[0].toFloat()/w,v[1].toFloat()/h,v[2].toFloat()/w,v[3].toFloat()/h),v[4].toFloat(),v[5].toBoolean())}
        val panels=BookRules.orderPanels(HybridPanels.combine(PanelGeometry.detect(pixels,w,h),predictions,pixels,w,h),false)
        assertEquals(expected.size,panels.size)
        for((i,e) in expected.withIndex()) {
            val p=GuidedFrames.focusBounds(panels[i])
            assertTrue("Case ${i+1}: enveloppe approuvée",p.left*w<=e[0]+4 && p.top*h<=e[1]+4 && p.right*w>=e[2]-4 && p.bottom*h>=e[3]-4)
        }
        val scenes=panels.filter {it.focusExclusions.size>=4}
        assertEquals("Deux scènes gardent les encarts hors de leur tour",2,scenes.size)
        assertTrue(scenes.all {it.focusExclusions.size>=4})
    }
}
