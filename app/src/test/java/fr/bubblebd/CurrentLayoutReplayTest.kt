package fr.bubblebd

import org.junit.Test
import java.io.File
import java.io.DataInputStream

class CurrentLayoutReplayTest {
    @Test fun exportAllCurrentPrivateEvidence() {
        val out=File("../docs/detection/private/current-replay").apply {mkdirs()}
        val failures=mutableListOf<String>()
        val countFile=File("src/test/resources/private/counts.tsv")
        if(!countFile.exists())return
        val counts=countFile.readLines().associate {val v=it.split(" ");v[0].toInt() to v[1].toInt()}
        for(n in counts.keys.sorted()) {
            val source=File("src/test/resources/private/october-replay-$n.rgb")
            val predictions=File("src/test/resources/private/october-replay-$n.tsv")
            if(!source.exists() || !predictions.exists())continue
            val input=DataInputStream(source.inputStream());val w=input.readInt();val h=input.readInt()
            val pixels=IntArray(w*h) {(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
            val pred=predictions.readLines().filter {it.isNotBlank()}.map {line ->val v=line.split(" ").map {it.toFloat()};PanelCandidate(Panel(v[2]/w,v[3]/h,v[4]/w,v[5]/h),v[1],v[0]==1f)}
            val masks=pred.filter {it.text && it.confidence>.45f}.map { val p=it.bounds;Panel(p.left-.025f,p.top-.025f,p.right+.025f,p.bottom+.025f)}
            val contours=FrameContours(pixels,w,h,masks)
            File(out,"$n-contours.txt").writeText(pred.filter {!it.text}.joinToString("\n") {d -> "${d.confidence} ${d.bounds} fit=${contours.fit(d.bounds)} split=${contours.fit(d.bounds)?.let {contours.split(it)}}"})
            if(n==35)File(out,"divider.txt").writeText((275..305).joinToString("\n") {"$it ${contours.support(it,342,496,true)}"})
            val geometry=PanelGeometry.detect(pixels,w,h)
            if(n>=25)File(out,"$n-rectangles.txt").writeText(PanelGeometry.detect(pixels,w,h,true).joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
            val lines=mutableListOf<String>()
            val sparse=BorderlessPanels.detect(pixels,w,h)
            val found=BookRules.orderPanels(sparse.ifEmpty {HybridPanels.combine(geometry,pred,pixels,w,h) {stage,frames ->
                lines.add(stage+"\n"+frames.joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
            }},false)
            File(out,"$n-trace.txt").writeText(lines.joinToString("\n\n"))
            File(out,"$n.txt").writeText(found.joinToString("\n") {"${it.left*w} ${it.top*h} ${it.right*w} ${it.bottom*h}"})
            File(out,"$n-joint-views.txt").writeText(found.mapIndexed {i,p -> "${i+1}: "+p.jointFocus.joinToString {target ->(found.indexOfFirst {(it.readingOrderBounds ?: it)==target}+1).toString()}}.joinToString("\n"))
            if(found.size!=counts[n])failures.add("Page $n : ${found.size}/${counts[n]}")
            val reference=File("src/test/resources/private/reference-$n.tsv")
            if(reference.exists()) try {
                val rows=reference.readLines().map {it.split(" ").map(String::toFloat)}
                org.junit.Assert.assertEquals("Page $n : nombre validé",rows.size,found.size)
                for((i,row) in rows.withIndex()) {
                    val p=found[i];val c=p.readingOrderBounds ?: p
                    org.junit.Assert.assertTrue("Page $n case ${i+1} : enveloppe tronquée",p.left*w<=row[1]+4 && p.top*h<=row[2]+4 && p.right*w>=row[3]-4 && p.bottom*h>=row[4]-4)
                    if(row[9]==1f)continue
                    val intersection=kotlin.math.max(0f,kotlin.math.min(c.right*w,row[7])-kotlin.math.max(c.left*w,row[5]))*kotlin.math.max(0f,kotlin.math.min(c.bottom*h,row[8])-kotlin.math.max(c.top*h,row[6]))
                    val union=c.width*c.height*w*h+(row[7]-row[5])*(row[8]-row[6])-intersection
                    org.junit.Assert.assertTrue("Page $n case ${i+1} : contour ou ordre",intersection/union>.80f)
                }
            }
            catch(error:AssertionError) {failures.add(error.message ?: "Référence $n")}
            println("Current replay $n: ${found.size}")
        }
        org.junit.Assert.assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
}
