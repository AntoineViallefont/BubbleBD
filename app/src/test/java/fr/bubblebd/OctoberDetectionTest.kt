package fr.bubblebd

import org.junit.Test
import java.io.File
import java.io.DataInputStream
import org.junit.Assert.*

class OctoberDetectionTest {
    @Test fun replayPrivateCorpusWithGeneralRules() {
        val out=File("../docs/detection/private/october-2026/replay").apply {mkdirs()}
        for(n in (24..25)+(1..23)) {
            val source=File("src/test/resources/private/october-replay-$n.rgb")
            if(!source.exists() || !File("src/test/resources/private/october-replay-$n.tsv").exists())continue
            val input=DataInputStream(source.inputStream());val w=input.readInt();val h=input.readInt()
            val pixels=IntArray(w*h) {(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
            val pred=File("src/test/resources/private/october-replay-$n.tsv").readLines().filter {it.isNotBlank()}.map {line ->
                val v=line.split(" ").map {it.toFloat()};PanelCandidate(Panel(v[2]/w,v[3]/h,v[4]/w,v[5]/h),v[1],v[0]==1f)
            }
            val sparse=BorderlessPanels.detect(pixels,w,h)
            val geometry=PanelGeometry.detect(pixels,w,h)
            val found=BookRules.orderPanels(sparse.ifEmpty {HybridPanels.combine(geometry,pred,pixels,w,h)},false)
            println("Replay $n: sparse=${sparse.size}, hybrid=${found.size}")
            if(n==15) {
                assertEquals("Protruding inset and scene are distinct",8,found.size)
                val inset=found.single {it.width*w<200 && it.top*h<120 && it.bottom*h<270}
                assertTrue("Inset includes all four physical borders",inset.left*w<=22 && inset.top*h<=108 && inset.right*w>=194 && inset.bottom*h>=250)
                assertTrue("Inset ends at its own border",inset.right*w<200)
                val scene=found.single {it.width*w>450 && it.top*h<100}
                assertTrue("Main scene remains complete",scene.left*w<=43 && scene.right*w>=548 && scene.bottom*h>=291)
                assertTrue("Inset has independent highlighting",scene.focusExclusions.any {it.contains(.18f,.22f)})
            }
            if(n==3)assertTrue("Closed balloon beyond a frame remains usable even with weak text confidence",found[4].right*w>540)
            File(out,"$n.txt").writeText(found.joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
            if(n>=24) {
                File("../docs/detection/private/october-afternoon-2026/frames-$n.txt").writeText(PanelGeometry.detect(pixels,w,h,true).joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
                File("../docs/detection/private/october-afternoon-2026/replay-$n.txt").writeText(found.joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
                assertEquals("Afternoon panel count",if(n==24)6 else 9,found.size)
                if(n==24) {
                    assertTrue("Upper portrait is complete",found[0].top*h<45)
                    assertTrue("Middle right contour is complete",found[3].right*w>=640)
                    assertTrue("Lower right portrait is not split into eyes and mouth",found[5].top*h<614 && found[5].bottom*h>877)
                } else {
                    assertTrue("Full-bleed scene retains far-right bird",found[0].right*w>=712)
                    assertTrue("Pale heads are included",found[1].top*h<=280 && found[2].top*h<=278)
                    assertTrue("Thin bottom frame survives",found[6].width*w>80)
                }
            }
            if(n==21) {
                File("../docs/detection/private/october-followup-2026/replay-21.txt").writeText(found.joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
                assertEquals(3,found.size);assertTrue("Upward balloon belongs to second frame",found[1].top*h<625)
                assertTrue("Third frame includes its protruding balloon",found[2].right*w>670)
                assertTrue("Balloon owned by second case is excluded from first highlight",found[0].focusExclusions.isNotEmpty())
            }
            if(n in 16..20) {
                assertEquals("October screenshot ${n-15}",listOf(5,7,7,5,9)[n-16],found.size)
                if(n==16) {assertTrue(found[3].right*w<380);assertTrue(found[4].left*w<385 && found[4].top*h<702)}
                if(n==20) {
                    assertTrue(found[5].top*h<=484 && found[5].right*w>=657)
                    assertTrue("Isolated low-confidence footer is not a balloon",found.last().bottom*h<900)
                }
            }
        }
    }
    @Test fun followupScenesOnLightAndDarkPaper() {
        for(n in 22..23) {
            val source=File("src/test/resources/private/october-replay-$n.rgb")
            if(!source.exists())continue
            val input=DataInputStream(source.inputStream());val w=input.readInt();val h=input.readInt()
            val pixels=IntArray(w*h) {(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
            val found=BookRules.orderPanels(BorderlessPanels.detect(pixels,w,h),false)
            File("../docs/detection/private/october-followup-2026/replay-$n.txt").writeText(found.joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
            assertEquals("Light or dark independent scenes",8,found.size)
        }
    }
    @Test fun preserveOneUsableFrameWithoutCreatingSceneFromFooter() {
        val w=400;val h=600;val pixels=IntArray(w*h) {0xffffffff.toInt()}
        for(y in 20 until 340)for(x in 20 until 380)pixels[y*w+x]=0xff444444.toInt()
        for(y in 520 until 530)for(x in 150 until 250)pixels[y*w+x]=0xff444444.toInt()
        val found=PanelGeometry.detect(pixels,w,h)
        assertEquals(1,found.size);assertFalse(found.single().isWholePage);assertTrue(found.single().bottom<.65f)
    }
    @Test fun sparseIllustrationIsNotInventedAsMultipleCases() {
        assertTrue(BorderlessPanels.detect(IntArray(240*320){0xffffffff.toInt()},240,320).isEmpty())
    }
}
