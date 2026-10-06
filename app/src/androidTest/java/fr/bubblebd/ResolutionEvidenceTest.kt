package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import kotlin.math.*

/** Diagnostic inputs for offline replay. Observations are never reference labels. */
class ResolutionEvidenceTest {
    @Test fun exportSamplingEvidenceForWholeCorpus() {
        val instrument=InstrumentationRegistry.getInstrumentation();val assets=instrument.context.assets
        org.junit.Assume.assumeTrue(assets.list("private").orEmpty().contains("reader-audit"))
        val entries=JSONObject(assets.open("private/reader-audit/manifest.json").bufferedReader().use {it.readText()}).getJSONArray("pages")
        val output=JSONArray();val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa/resolution-evidence").apply {deleteRecursively();mkdirs()}
        for(k in 0 until entries.length()) {
            val entry=entries.getJSONObject(k);val n=entry.getInt("example")
            val original=assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
            try {for(target in listOf(original.width,600,720,1800).distinct()) {
                val input=if(target==original.width)original else Bitmap.createScaledBitmap(original,target,(original.height*target.toFloat()/original.width).roundToInt(),true)
                try {
                    val w=min(720,input.width);val h=(input.height.toFloat()/input.width*w).toInt().coerceIn(1,2000)
                    val small=Bitmap.createScaledBitmap(input,w,h,true)
                    try {
                        val pixels=IntArray(w*h);small.getPixels(pixels,0,w,0,0,w,h)
                        DataOutputStream(File(directory,"$n-$target.rgb").outputStream().buffered()).use {out ->out.writeInt(w);out.writeInt(h);for(c in pixels) {out.writeByte(c shr 16);out.writeByte(c shr 8);out.writeByte(c)}}
                        val sparse=SingleScene.detect(pixels,w,h).ifEmpty {BorderlessPanels.detect(pixels,w,h)}.ifEmpty {CaptionRows.detect(pixels,w,h)}
                        val geometry=sparse.ifEmpty {PanelGeometry.detect(pixels,w,h)}
                        val full=LocalPanelAi.detect(instrument.targetContext,small)
                        val extra=if(PanelDetail.needed(geometry,pixels,w,h))LocalPanelAi.detailProposals(instrument.targetContext,small).filter {d ->d.text || full.none {f ->val a=f.bounds;val b=d.bounds
                            val shared=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
                            !f.text && f.confidence>.50f && a.width*a.height>b.width*b.height && shared/(b.width*b.height)>.9f && d.confidence<.8f}} else emptyList()
                        val predictions=full+extra
                        File(directory,"$n-$target.tsv").writeText(predictions.joinToString("\n") {d ->"${if(d.text)1 else 0} ${d.confidence} ${d.bounds.left*w} ${d.bounds.top*h} ${d.bounds.right*w} ${d.bounds.bottom*h}"})
                        File(directory,"$n-$target-geometry.tsv").writeText(geometry.joinToString("\n") {p ->"${p.left*w} ${p.top*h} ${p.right*w} ${p.bottom*h}"})
                        val found=BookRules.orderPanels(PanelDetector.detect(input,instrument.targetContext),entry.optString("reading_direction")=="right_to_left")
                        output.put(JSONObject().put("example",n).put("width",target).put("sparse",sparse.isNotEmpty()).put("count",found.size).put("expected",entry.getInt("expected_count"))
                            .put("frames",JSONArray(found.map {p ->val c=p.readingOrderBounds ?: p;listOf(c.left*w,c.top*h,c.right*w,c.bottom*h)}))
                            .put("views",JSONArray(found.indices.map {GuidedFrames.frame(found,it,input.width,input.height,600,900).visible.map {v->v+1}})))
                    }finally {if(small!==input)small.recycle()}
                }finally {if(input!==original)input.recycle()}
            }}finally {original.recycle()}
        }
        File(directory,"report.json").writeText(JSONObject().put("observations",output).put("purpose","diagnostic; not validation").toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp -r ${directory.absolutePath} /sdcard/Download/bubble-resolution-evidence")).use {it.readBytes()}
    }
}
