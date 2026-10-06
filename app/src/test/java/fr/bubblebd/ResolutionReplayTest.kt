package fr.bubblebd

import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** Diagnostic replay of frozen model proposals; never an approved reference. */
class ResolutionReplayTest {
    @Test fun exportFrozenResolutionEvidence() {
        org.junit.Assume.assumeTrue("Optional frozen-proposal diagnostic",System.getenv("BUBBLEBD_RESOLUTION_REPLAY")=="1")
        val root=File(System.getenv("BUBBLEBD_REPLAY_INPUT") ?: "../docs/detection/private/propositions-20261004-1048/resolution-evidence")
        if(!root.exists())return
        val out=File(System.getenv("BUBBLEBD_REPLAY_OUTPUT") ?: File(root,"replay").path).apply {mkdirs()}
        for(source in root.listFiles()!!.filter {it.extension=="rgb"}.sortedBy {it.name}) {
            val input=DataInputStream(source.inputStream());val w=input.readInt();val h=input.readInt()
            val pixels=IntArray(w*h){(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
            val predictions=File(root,source.nameWithoutExtension+".tsv").readLines().filter {it.isNotBlank()}.map {line ->
                val a=line.trim().split(Regex("\\s+")).map {it.toFloat()}
                PanelCandidate(Panel(a[2]/w,a[3]/h,a[4]/w,a[5]/h),a[1],a[0]==1f)
            }
            val sparse=SingleScene.detect(pixels,w,h).ifEmpty {BorderlessPanels.detect(pixels,w,h)}.ifEmpty {CaptionRows.detect(pixels,w,h)}
            val geometry=sparse.ifEmpty {PanelGeometry.detect(pixels,w,h)}
            val stages=mutableListOf<String>()
            val frames=BookRules.orderPanels(if(sparse.isNotEmpty())sparse else DetectionSafety.accept(HybridPanels.combine(geometry,predictions,pixels,w,h,trace={name,panels ->
                stages.add(name+"\n"+panels.joinToString("\n") {p ->val c=p.readingOrderBounds ?: p;"${c.left*w} ${c.top*h} ${c.right*w} ${c.bottom*h}"})
            }),geometry,predictions,pixels,w,h),false)
            File(out,source.nameWithoutExtension+".txt").writeText("geometry=${geometry.size} sparse=${sparse.size} final=${frames.size}\n"+frames.mapIndexed {i,p ->
                val c=p.readingOrderBounds ?: p
                "${i+1}: ${c.left*w} ${c.top*h} ${c.right*w} ${c.bottom*h}; includes=${p.focusIncludes}; joint=${p.jointFocus}"
            }.joinToString("\n"))
            File(out,source.nameWithoutExtension+"-trace.txt").writeText(stages.joinToString("\n\n"))
        }
    }
}
