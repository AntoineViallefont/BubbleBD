package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in private album benchmark through the same PDF renderer as the reader. */
class UserPdfCorpusTest {
    @Test fun measureWholeRequestedPdf() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val id=InstrumentationRegistry.getArguments().getString("privatePdfAlbum") ?: return
        require(id.matches(Regex("[a-z0-9-]+")))
        val original=File(instrument.targetContext.cacheDir,"qa-$id.pdf")
        instrument.context.assets.open("private/pdf-albums/$id.pdf").use {source->original.outputStream().use {source.copyTo(it)}}
        val digest=MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") {"%02x".format(it)}
        val output=File(instrument.targetContext.getExternalFilesDir(null),"qa/pdf-$id").apply {mkdirs()}
        val rows=JSONArray();val errors=mutableListOf<String>()
        val requested=InstrumentationRegistry.getArguments().getString("privatePdfPages")?.split(",")?.map {it.toInt()}?.toSet()
        val debug=InstrumentationRegistry.getArguments().getString("privatePdfDebugPages")?.split(",")?.map {it.toInt()}?.toSet().orEmpty()
        try {
            ComicDocument(original).use {document->
                for(index in 0 until document.count) {
                    if(requested!=null && index+1 !in requested)continue
                    val row=JSONObject().put("pdf_page",index+1).put("id","$id-${index+1}").put("user_validated",false)
                    try {
                        val start=System.nanoTime();val bitmap=document.bitmap(index)
                        try {
                            val decoded=(System.nanoTime()-start)/1e9
                            val times=JSONObject();val detectStart=System.nanoTime()
                            val frames=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext) {stage,time->times.put(stage,time)},false)
                            val detectedSeconds=(System.nanoTime()-detectStart)/1e9
                            if(index+1 in debug)row.put("diagnostic",WebExplorationTest().diagnostics("$id-${index+1}",bitmap))
                            val width=720;val height=(bitmap.height.toFloat()/bitmap.width*width).toInt()
                            val preview=Bitmap.createScaledBitmap(bitmap,width,height,true)
                            try {
                                File(output,"page-${index+1}.png").outputStream().use {preview.compress(Bitmap.CompressFormat.PNG,100,it)}
                                val marked=preview.copy(Bitmap.Config.ARGB_8888,true)
                                try {
                                    val canvas=Canvas(marked);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(245,120,0);strokeWidth=3f;style=Paint.Style.STROKE}
                                    frames.forEachIndexed {i,p->
                                        val core=p.readingOrderBounds ?: p
                                        if(p.focusOutline.isEmpty())canvas.drawRect(core.left*width,core.top*height,core.right*width,core.bottom*height,paint)
                                        else {
                                            val path=android.graphics.Path();p.focusOutline.forEachIndexed {j,v->if(j==0)path.moveTo(v.x*width,v.y*height) else path.lineTo(v.x*width,v.y*height)};path.close();canvas.drawPath(path,paint)
                                        }
                                        paint.style=Paint.Style.FILL;paint.textSize=23f;canvas.drawText("${i+1}",core.left*width+5,core.top*height+24,paint);paint.style=Paint.Style.STROKE
                                    }
                                    File(output,"measured-${index+1}.png").outputStream().use {marked.compress(Bitmap.CompressFormat.PNG,100,it)}
                                } finally {marked.recycle()}
                            } finally {if(preview!==bitmap)preview.recycle()}
                            fun boxes(items:List<Panel>)=JSONArray(items.map {p->listOf(p.left*width,p.top*height,p.right*width,p.bottom*height)})
                            row.put("width",width).put("height",height).put("native_width",bitmap.width).put("native_height",bitmap.height)
                                .put("decode_seconds",decoded).put("detection_seconds",detectedSeconds).put("stages_seconds",times)
                                .put("frames",boxes(frames)).put("cores",boxes(frames.map {it.readingOrderBounds ?: it}))
                                .put("includes",JSONArray(frames.map {boxes(it.focusIncludes)})).put("exclusions",JSONArray(frames.map {boxes(it.focusExclusions)}))
                                .put("outlines",JSONArray(frames.map {p->p.focusOutline.map {listOf(it.x*width,it.y*height)}}))
                                .put("guided_views",JSONArray(frames.indices.map {GuidedFrames.frame(frames,it,bitmap.width,bitmap.height,400,800).visible.map {i->i+1}}))
                            // Optional pixel observations are kept separate from user-approved references.
                            InstrumentationRegistry.getArguments().getString("privatePdfFocusRegions")?.let {argument ->
                                JSONObject(argument).optJSONObject((index+1).toString())?.let {regions ->
                                    for(key in listOf("required_speech_regions","required_dimmed_regions")) {
                                        val items=regions.optJSONArray(key) ?: JSONArray()
                                        for(k in 0 until items.length()) {
                                            val box=items.getJSONObject(k).getJSONArray("box")
                                            for(j in 0..3)box.put(j,box.getDouble(j)*if(j%2==0)bitmap.width.toDouble()/width else bitmap.height.toDouble()/height)
                                        }
                                    }
                                    val observation=NativeFocusQa.measure(bitmap,frames,regions,700+index+1)
                                    if(InstrumentationRegistry.getArguments().getString("privatePdfFocusStrict")=="true")
                                        assertTrue(observation.second.joinToString(),observation.second.isEmpty())
                                    row.put("focus_observation_scope","Relecture IA à résolution native du PDF ; distincte du corpus approuvé")
                                        .put("focus_observations",observation.first).put("focus_findings",JSONArray(observation.second))
                                    File(instrument.targetContext.getExternalFilesDir(null),"qa/native-focus").listFiles().orEmpty()
                                        .filter {it.name.startsWith("${700+index+1}-")}.forEach {it.copyTo(File(output,it.name),overwrite=true)}
                                }
                            }
                        } finally {bitmap.recycle()}
                    } catch(error:Exception) {row.put("error",error.stackTraceToString());errors.add("Page ${index+1}: ${error.message}")}
                    rows.put(row)
                }
                File(output,"measured.json").writeText(JSONObject().put("source_sha256",digest).put("pages",document.count).put("renderer","ComicDocument / Android PdfRenderer, rendu du lecteur").put("results",rows).toString(2))
            }
            for(command in listOf("mkdir -p /sdcard/Download/bubble-pdf-$id","cp -r ${output.absolutePath}/. /sdcard/Download/bubble-pdf-$id/"))
                instrument.uiAutomation.executeShellCommand(command).let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {input->input.readBytes()}}
            assertTrue(errors.joinToString("\n"),errors.isEmpty())
        } finally {original.delete()}
    }
}
