package fr.bubblebd

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Opt-in evidence collection. AI proposals are never user-approved references. */
class WebExplorationTest {
    @Test fun measureRequestedExamples() {
        val requested=InstrumentationRegistry.getArguments().getString("webExamples") ?: return
        val debug=InstrumentationRegistry.getArguments().getString("webDebugExamples")?.split(",").orEmpty().toSet()
        val rtl=InstrumentationRegistry.getArguments().getString("webRtlExamples")?.split(",").orEmpty().toSet()
        val instrument=InstrumentationRegistry.getInstrumentation()
        val rows=JSONArray();val errors=mutableListOf<String>()
        for(id in requested.split(",")) {
            require(id.matches(Regex("[a-z0-9-]+")))
            val row=JSONObject().put("id",id).put("user_validated",false)
            try {
                val bitmap=instrument.context.assets.open("private/exploration-web/$id.png").use {BitmapFactory.decodeStream(it)}
                try {
                    val times=JSONObject();val start=System.nanoTime()
                    val frames=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext) {stage,time->times.put(stage,time)},id in rtl)
                    fun boxes(items:List<Panel>)=JSONArray(items.map {p->listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)})
                    row.put("width",bitmap.width).put("height",bitmap.height).put("reading_direction",if(id in rtl)"right_to_left" else "left_to_right")
                        .put("seconds",(System.nanoTime()-start)/1e9).put("stages_seconds",times)
                        .put("frames",boxes(frames)).put("cores",boxes(frames.map {it.readingOrderBounds ?: it}))
                        .put("includes",JSONArray(frames.map {boxes(it.focusIncludes)}))
                        .put("exclusions",JSONArray(frames.map {boxes(it.focusExclusions)}))
                        .put("outlines",JSONArray(frames.map {p->p.focusOutline.map {listOf(it.x*bitmap.width,it.y*bitmap.height)}}))
                        .put("guided_views",JSONArray(frames.indices.map {GuidedFrames.frame(frames,it,bitmap.width,bitmap.height,400,800).visible.map {i->i+1}}))
                    if(id in debug)row.put("diagnostic",diagnostics(id,bitmap))
                } finally {bitmap.recycle()}
            } catch(error:Exception) {row.put("error",error.stackTraceToString());errors.add("$id: ${error.message}")}
            rows.put(row)
        }
        val file=File(instrument.targetContext.getExternalFilesDir(null),"qa/web-exploration.json").apply {parentFile!!.mkdirs()}
        file.writeText(rows.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-web-exploration.json")).use {it.readBytes()}
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    internal fun diagnostics(id:String,bitmap:android.graphics.Bitmap):JSONObject {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val w=minOf(720,bitmap.width);val h=(bitmap.height.toFloat()/bitmap.width*w).toInt().coerceIn(1,2000)
        val small=android.graphics.Bitmap.createScaledBitmap(bitmap,w,h,true)
        try {
            val pixels=IntArray(w*h);small.getPixels(pixels,0,w,0,0,w,h)
            val sparse=SingleScene.detect(pixels,w,h).ifEmpty {BorderlessPanels.detect(pixels,w,h)}.ifEmpty {CaptionRows.detect(pixels,w,h)}
            val geometry=sparse.ifEmpty {PanelGeometry.detect(pixels,w,h)}
            val full=LocalPanelAi.detect(instrument.targetContext,small)
            val detail=if(PanelDetail.needed(geometry,pixels,w,h))LocalPanelAi.detailProposals(instrument.targetContext,small).filter {d->
                d.text || full.none {f->
                    val a=f.bounds;val b=d.bounds
                    val shared=maxOf(0f,minOf(a.right,b.right)-maxOf(a.left,b.left))*maxOf(0f,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
                    !f.text && f.confidence>.50f && a.width*a.height>b.width*b.height && shared/(b.width*b.height)>.9f && d.confidence<.8f
                }
            } else emptyList()
            val predictions=full+detail;val trace=JSONObject()
            fun boxes(items:List<Panel>)=JSONArray(items.map {p->listOf(p.left*w,p.top*h,p.right*w,p.bottom*h)})
            val result=HybridPanels.combine(geometry,predictions,pixels,w,h) {stage,frames->trace.put(stage,boxes(frames))}
            val raw=File(instrument.targetContext.getExternalFilesDir(null),"qa/web-$id.rgb").apply {parentFile!!.mkdirs()}
            java.io.DataOutputStream(raw.outputStream()).use {out->
                out.writeInt(w);out.writeInt(h)
                pixels.forEach {c->out.writeByte((c shr 16)and 255);out.writeByte((c shr 8)and 255);out.writeByte(c and 255)}
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${raw.absolutePath} /sdcard/Download/bubble-web-$id.rgb")).use {it.readBytes()}
            return JSONObject().put("width",w).put("height",h).put("geometry",boxes(geometry)).put("geometry_cores",boxes(geometry.map {it.readingOrderBounds ?: it}))
                .put("predictions",JSONArray(predictions.map {d->listOf(d.bounds.left*w,d.bounds.top*h,d.bounds.right*w,d.bounds.bottom*h,d.confidence,d.text)}))
                .put("trace",trace).put("result",boxes(result))
        } finally {if(small!==bitmap)small.recycle()}
    }
}
