package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reproduce document decoding and production resizing, not just a pre-cropped fixture. */
class ReportedPageValidationTest {
    @Test fun reportedPageThroughArchiveAtReaderSizes() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private/reported").orEmpty().contains("56-original.jpg"))
        val original=instrument.context.assets.open("private/reported/56-original.jpg").use {BitmapFactory.decodeStream(it)}
        val page=instrument.context.assets.open("private/ai/example-56.png").use {BitmapFactory.decodeStream(it)}
        val report=JSONArray();val failures=mutableListOf<String>()
        for((label,source,offset) in listOf(Triple("capture-complete",original,253f),Triple("page-597",page,0f),Triple("page-1800",Bitmap.createScaledBitmap(page,1800,2333,true),0f))) {
            val archive=File(instrument.targetContext.cacheDir,"qa-$label.cbz")
            try {
                ZipOutputStream(archive.outputStream()).use {zip ->zip.putNextEntry(ZipEntry("001.png"));source.compress(Bitmap.CompressFormat.PNG,100,zip);zip.closeEntry()}
                ComicDocument(archive).use {document ->
                    val image=document.bitmap(0)
                    try {for(prior in listOf(false,true)) {
                        val panels=BookRules.orderPanels(PanelDetector.detect(image,instrument.targetContext,rectanglePrior=prior),false)
                        val row=JSONObject().put("variant",label).put("prior",prior).put("width",image.width).put("height",image.height)
                            .put("frames",JSONArray(panels.map {p ->listOf(p.left*image.width,p.top*image.height,p.right*image.width,p.bottom*image.height)}))
                            .put("cores",JSONArray(panels.map {p ->val c=p.readingOrderBounds ?: p;listOf(c.left*image.width,c.top*image.height,c.right*image.width,c.bottom*image.height)}))
                            .put("views",JSONArray(panels.indices.map {GuidedFrames.frame(panels,it,image.width,image.height,600,900).visible.map {v->v+1}}))
                        if(!prior && label=="page-1800") {
                            val small=Bitmap.createScaledBitmap(image,720,(image.height*720f/image.width).toInt(),true)
                            val pixels=IntArray(small.width*small.height);small.getPixels(pixels,0,small.width,0,0,small.width,small.height)
                            row.put("geometry",JSONArray(PanelGeometry.detect(pixels,small.width,small.height).map {p ->listOf(p.left*720,p.top*small.height,p.right*720,p.bottom*small.height)}))
                            row.put("predictions",JSONArray(LocalPanelAi.detect(instrument.targetContext,small).map {d ->JSONObject().put("text",d.text).put("confidence",d.confidence.toDouble()).put("box",JSONArray(listOf(d.bounds.left*720,d.bounds.top*small.height,d.bounds.right*720,d.bounds.bottom*small.height)))}))
                            val geometry=PanelGeometry.detect(pixels,small.width,small.height)
                            val full=LocalPanelAi.detect(instrument.targetContext,small)
                            val extra=LocalPanelAi.detailProposals(instrument.targetContext,small).filter {d ->full.none {f ->val a=f.bounds;val b=d.bounds
                                val shared=kotlin.math.max(0f,kotlin.math.min(a.right,b.right)-kotlin.math.max(a.left,b.left))*kotlin.math.max(0f,kotlin.math.min(a.bottom,b.bottom)-kotlin.math.max(a.top,b.top))
                                !d.text && !f.text && f.confidence>.50f && a.width*a.height>b.width*b.height && shared/(b.width*b.height)>.9f && d.confidence<.8f}}
                            val traces=JSONArray()
                            HybridPanels.combine(geometry,full+extra,pixels,small.width,small.height,trace={stage,frames ->traces.put(JSONObject().put("stage",stage).put("frames",JSONArray(frames.map {listOf(it.left*720,it.top*small.height,it.right*720,it.bottom*small.height)})))})
                            row.put("traces",traces).put("consolidated",JSONArray(PanelCandidates.consolidate(full+extra).map {d ->JSONObject().put("confidence",d.confidence.toDouble()).put("box",JSONArray(listOf(d.bounds.left*720,d.bounds.top*small.height,d.bounds.right*720,d.bounds.bottom*small.height)))}))
                            small.recycle()
                        }
                        report.put(row)
                        try {
                            assertEquals("$label : sept cases",7,panels.size)
                            val reference=JSONObject(instrument.context.assets.open("private/reference-56-validee.json").bufferedReader().use {it.readText()})
                            val frames=reference.getJSONArray("frames");val sx=image.width/597f;val sy=if(offset==0f)image.height/774f else image.height/1280f
                            for(i in panels.indices) {
                                val c=panels[i].readingOrderBounds ?: panels[i];val box=frames.getJSONArray(i)
                                val wanted=Panel((box.getDouble(0)*sx/image.width).toFloat(),((box.getDouble(1)+offset)*sy/image.height).toFloat(),(box.getDouble(2)*sx/image.width).toFloat(),((box.getDouble(3)+offset)*sy/image.height).toFloat())
                                if(i!=6) {
                                    val intersection=kotlin.math.max(0f,kotlin.math.min(c.right,wanted.right)-kotlin.math.max(c.left,wanted.left))*kotlin.math.max(0f,kotlin.math.min(c.bottom,wanted.bottom)-kotlin.math.max(c.top,wanted.top))
                                    val union=c.width*c.height+wanted.width*wanted.height-intersection
                                    assertTrue("$label case ${i+1} : contour divergent",intersection/union>.90f)
                                }
                            }
                            row.put("passed",true)
                        } catch(e:AssertionError) {row.put("passed",false).put("failure",e.message);failures.add("$label/$prior : ${e.message}")}
                    }}finally {image.recycle()}
                }
            } finally {archive.delete();if(source!==original && source!==page)source.recycle()}
        }
        original.recycle();page.recycle()
        val out=File(instrument.targetContext.getExternalFilesDir(null),"qa/reported-validation.json").apply {parentFile!!.mkdirs()}
        out.writeText(JSONObject().put("variants",report).put("failures",JSONArray(failures)).toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${out.absolutePath} /sdcard/Download/bubble-reported-validation.json")).use {it.readBytes()}
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
}
