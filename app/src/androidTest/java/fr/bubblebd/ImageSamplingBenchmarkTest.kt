package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Isolated sampling experiment: no production rule or approved label is changed. */
class ImageSamplingBenchmarkTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun overlap(a:Panel,b:Panel):Float {
        val intersection=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        return intersection/(a.width*a.height+b.width*b.height-intersection)
    }

    /** Area integration for reduction; equal or larger inputs use Android filtering.
     * Two separable passes keep memory bounded and account for fractional pixels. */
    private fun areaReduced(source:Bitmap,width:Int,height:Int):Bitmap {
        if(width>=source.width || height>=source.height)return Bitmap.createScaledBitmap(source,width,height,true)
        val input=IntArray(source.width*source.height);source.getPixels(input,0,source.width,0,0,source.width,source.height)
        val horizontal=FloatArray(width*source.height*3)
        val sx=source.width.toDouble()/width;val sy=source.height.toDouble()/height
        val shifts=intArrayOf(16,8,0)
        for(y in 0 until source.height)for(x in 0 until width) {
            val start=x*sx;val end=(x+1)*sx
            for(xx in floor(start).toInt() until ceil(end).toInt()) {
                val weight=(min(end,xx+1.0)-max(start,xx.toDouble()))/sx
                val pixel=input[y*source.width+xx.coerceAtMost(source.width-1)]
                for(c in 0..2)horizontal[(y*width+x)*3+c]+=(((pixel shr shifts[c])and 255)*weight).toFloat()
            }
        }
        val output=IntArray(width*height)
        for(y in 0 until height)for(x in 0 until width) {
            val start=y*sy;val end=(y+1)*sy;val channels=DoubleArray(3)
            for(yy in floor(start).toInt() until ceil(end).toInt()) {
                val weight=(min(end,yy+1.0)-max(start,yy.toDouble()))/sy
                for(c in 0..2)channels[c]+=horizontal[(yy.coerceAtMost(source.height-1)*width+x)*3+c]*weight
            }
            output[y*width+x]=(255 shl 24) or (channels[0].roundToInt().coerceIn(0,255) shl 16) or
                (channels[1].roundToInt().coerceIn(0,255) shl 8) or channels[2].roundToInt().coerceIn(0,255)
        }
        return Bitmap.createBitmap(output,width,height,Bitmap.Config.ARGB_8888)
    }

    @Test fun compareRawDetectorSamplingAcrossAllPreservedPages() {
        val instrument=InstrumentationRegistry.getInstrumentation();val assets=instrument.context.assets
        org.junit.Assume.assumeTrue(assets.list("private").orEmpty().contains("reader-audit"))
        val model=instrument.targetContext.assets.open("models/panels-int8.tflite").use {it.readBytes()}
        val buffer=ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).apply {put(model);rewind()}
        val tensors=Interpreter(buffer,Interpreter.Options().setNumThreads(2)).use {engine ->
            JSONObject().put("input_shape",JSONArray(engine.getInputTensor(0).shape().toList()))
                .put("input_type",engine.getInputTensor(0).dataType().toString())
                .put("output_shape",JSONArray(engine.getOutputTensor(0).shape().toList()))
                .put("output_type",engine.getOutputTensor(0).dataType().toString())
        }
        val entries=JSONObject(assets.open("private/reader-audit/manifest.json").bufferedReader().use {it.readText()}).getJSONArray("pages")
        val rows=JSONArray()
        for(k in 0 until entries.length()) {
            val entry=entries.getJSONObject(k);val n=entry.getInt("example")
            val sourceBytes=assets.open("private/ai/example-$n.png").use {it.readBytes()}
            val source=BitmapFactory.decodeByteArray(sourceBytes,0,sourceBytes.size)
            val reference=entry.optString("reference").takeIf {it.isNotEmpty()}?.let {name ->
                JSONObject(assets.open("private/reader-audit/$name").bufferedReader().use {it.readText()})
            }
            val expected=reference?.optJSONArray("frames")?.let {frames ->(0 until frames.length()).filter {it+1!=reference.optInt("background_panel",-1)}.map {i ->
                val p=frames.getJSONArray(i);Panel((p.getDouble(0)/source.width).toFloat(),(p.getDouble(1)/source.height).toFloat(),(p.getDouble(2)/source.width).toFloat(),(p.getDouble(3)/source.height).toFloat())
            }}
            val high=Bitmap.createScaledBitmap(source,1800,(source.height*1800.0/source.width).roundToInt(),true)
            try {for(mode in listOf("source_direct","source_720_bilinear","enlarged_720_bilinear","enlarged_720_area")) {
                val started=System.nanoTime()
                val raster=when(mode) {
                    "source_direct"->source
                    "source_720_bilinear"->Bitmap.createScaledBitmap(source,720,(source.height*720.0/source.width).toInt().coerceAtLeast(1),true)
                    "enlarged_720_bilinear"->Bitmap.createScaledBitmap(high,720,(high.height*720.0/high.width).toInt().coerceAtLeast(1),true)
                    else->areaReduced(high,720,(high.height*720.0/high.width).toInt().coerceAtLeast(1))
                }
                try {
                    val prepared=System.nanoTime();val predictions=LocalPanelAi.detect(instrument.targetContext,raster)
                    val finished=System.nanoTime();val panels=predictions.filterNot {it.text}
                    // Unique maximum-cardinality matching avoids a duplicate box
                    // counting several reference cases as recovered.
                    val matches=IntArray(panels.size){-1}
                    fun augment(i:Int,seen:BooleanArray):Boolean {
                        for(j in panels.indices)if(!seen[j] && overlap(expected!![i],panels[j].bounds)>=.50f) {
                            seen[j]=true
                            if(matches[j]<0 || augment(matches[j],seen)) {matches[j]=i;return true}
                        }
                        return false
                    }
                    val matched=expected?.indices?.count {augment(it,BooleanArray(panels.size))}
                    rows.put(JSONObject().put("example",n).put("mode",mode).put("source_sha256",sha(sourceBytes))
                        .put("physical_reference_available",expected!=null).put("eligible_reference_frames",expected?.size ?: JSONObject.NULL)
                        .put("matched_frames_iou50",matched ?: JSONObject.NULL).put("raw_panel_count",panels.size)
                        .put("preparation_seconds",(prepared-started)/1e9).put("inference_seconds",(finished-prepared)/1e9)
                        .put("predictions",JSONArray(predictions.map {p ->JSONObject().put("text",p.text).put("score",p.confidence)
                            .put("box",JSONArray(listOf(p.bounds.left,p.bounds.top,p.bounds.right,p.bounds.bottom)))})))
                }finally {if(raster!==source && raster!==high)raster.recycle()}
            }}finally {high.recycle();source.recycle()}
        }
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa/sampling-benchmark").apply {mkdirs()}
        File(directory,"report.json").writeText(JSONObject().put("purpose","raw model sampling experiment; not reader validation")
            .put("model_sha256",sha(model)).put("tensors",tensors).put("rows",rows).toString(2))
        instrument.uiAutomation.executeShellCommand("cp ${directory.absolutePath}/report.json /sdcard/Download/bubble-sampling-benchmark.json")
            .let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {stream ->stream.readBytes()}}
    }
}
