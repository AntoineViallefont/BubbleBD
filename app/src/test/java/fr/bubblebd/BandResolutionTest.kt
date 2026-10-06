package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class BandResolutionTest {
    @Test fun framedRowsOutrankDoorDecorAtBothPreservedResolutions() {
        for(n in listOf(16,51,53))for(target in listOf(597,720)) {
            val root=File("../docs/detection/private/propositions-20261004-1048/resolution-evidence")
            val file=File(root,"$n-$target.rgb")
            if(!file.exists())continue
            val input=DataInputStream(file.inputStream());val w=input.readInt();val h=input.readInt()
            val pixels=IntArray(w*h){(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
            val geometry=PanelGeometry.detect(pixels,w,h)
            val candidates=File(root,"$n-$target.tsv").readLines().map {it.trim().split(Regex("\\s+")).map(String::toFloat)}
                .map {PanelCandidate(Panel(it[2]/w,it[3]/h,it[4]/w,it[5]/h),it[1],it[0]==1f)}
            val text=candidates.filter {it.text && it.confidence>.45f}.map {it.bounds}
            val found=BandLayout.confirm(geometry,pixels,w,h,text,PanelCandidates.consolidate(candidates))
            if(n==53)assertEquals("Three approved physical bands, not carved door columns ($target)",3,found.size)
            else assertTrue("Independent cases and inserts must not become broad rows ($n/$target)",found.isEmpty())
        }
    }
}
