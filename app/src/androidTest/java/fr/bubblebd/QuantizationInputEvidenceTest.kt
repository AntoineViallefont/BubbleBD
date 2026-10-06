package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Export the exact native input values, so FP32/INT8 comparison needs no resampling. */
class QuantizationInputEvidenceTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun exportAllNativeInputsAndRawQuantizedOutputs() {
        val instrument=InstrumentationRegistry.getInstrumentation();val assets=instrument.context.assets
        org.junit.Assume.assumeTrue(assets.list("private").orEmpty().contains("reader-audit"))
        val bytes=instrument.targetContext.assets.open("models/panels-int8.tflite").use {it.readBytes()}
        val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {put(bytes);rewind()}
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa/quantization-inputs").apply {deleteRecursively();mkdirs()}
        val entries=JSONObject(assets.open("private/reader-audit/manifest.json").bufferedReader().use {it.readText()}).getJSONArray("pages")
        val rows=JSONArray()
        Interpreter(model,Interpreter.Options().setNumThreads(2)).use {engine ->
            assertEquals(DataType.FLOAT32,engine.getInputTensor(0).dataType())
            assertEquals(DataType.FLOAT32,engine.getOutputTensor(0).dataType())
            val shape=engine.getInputTensor(0).shape();val h=shape[1];val w=shape[2]
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val pixels=IntArray(w*h)
            val input=ByteBuffer.allocateDirect(engine.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            val output=ByteBuffer.allocateDirect(engine.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            val paint=Paint(Paint.FILTER_BITMAP_FLAG)
            try {for(k in 0 until entries.length()) {
                val n=entries.getJSONObject(k).getInt("example")
                val sourceBytes=assets.open("private/ai/example-$n.png").use {it.readBytes()}
                val source=BitmapFactory.decodeByteArray(sourceBytes,0,sourceBytes.size)
                try {
                    val factor=minOf(w.toFloat()/source.width,h.toFloat()/source.height)
                    val rw=(source.width*factor).roundToInt();val rh=(source.height*factor).roundToInt();val dx=(w-rw)/2;val dy=(h-rh)/2
                    Canvas(bitmap).apply {drawColor(Color.rgb(114,114,114));drawBitmap(source,null,Rect(dx,dy,dx+rw,dy+rh),paint)}
                    bitmap.getPixels(pixels,0,w,0,0,w,h);input.clear()
                    for(p in pixels)for(shift in intArrayOf(16,8,0))input.putFloat(((p shr shift)and 255)/255f)
                    input.rewind();val tensorBytes=ByteArray(input.remaining());input.get(tensorBytes);input.rewind()
                    DataOutputStream(File(directory,"$n.tensor").outputStream().buffered()).use {out ->out.writeInt(w);out.writeInt(h);out.write(tensorBytes)}
                    output.clear();engine.run(input,output);output.rewind()
                    val predictions=JSONArray();val decoded=mutableListOf<PanelCandidate>()
                    repeat(engine.getOutputTensor(0).shape()[1]) {
                        val a=FloatArray(6){output.float};predictions.put(JSONArray(a.map {it.toDouble()}))
                        val kind=a[5].roundToInt()
                        if(a[4]>=.25f && kind in 0..1) {
                            val p=Panel(((a[0]*w-dx)/factor/source.width).coerceIn(0f,1f),((a[1]*h-dy)/factor/source.height).coerceIn(0f,1f),
                                ((a[2]*w-dx)/factor/source.width).coerceIn(0f,1f),((a[3]*h-dy)/factor/source.height).coerceIn(0f,1f))
                            if(p.width>.015f && p.height>.01f)decoded.add(PanelCandidate(p,a[4],kind==1))
                        }
                    }
                    val native=LocalPanelAi.detect(instrument.targetContext,source)
                    assertEquals("Native preprocessing and decoding must agree for $n",native.size,decoded.size)
                    for(i in native.indices) {
                        assertEquals(native[i].text,decoded[i].text)
                        assertEquals(native[i].confidence,decoded[i].confidence,.00001f)
                        assertEquals(native[i].bounds.left,decoded[i].bounds.left,.00001f)
                        assertEquals(native[i].bounds.top,decoded[i].bounds.top,.00001f)
                        assertEquals(native[i].bounds.right,decoded[i].bounds.right,.00001f)
                        assertEquals(native[i].bounds.bottom,decoded[i].bounds.bottom,.00001f)
                    }
                    rows.put(JSONObject().put("example",n).put("source_sha256",sha(sourceBytes)).put("tensor_sha256",sha(tensorBytes))
                        .put("source_width",source.width).put("source_height",source.height).put("tensor_width",w).put("tensor_height",h)
                        .put("scale",factor).put("dx",dx).put("dy",dy).put("raw_normalized_output",predictions))
                }finally {source.recycle()}
            }}finally {bitmap.recycle()}
        }
        File(directory,"report.json").writeText(JSONObject().put("purpose","native input/model comparison; not reader validation")
            .put("model_sha256",sha(bytes)).put("tensor_format","8-byte big-endian width/height header, then native-endian Float32 NHWC RGB")
            .put("byte_order",ByteOrder.nativeOrder().toString()).put("rows",rows).toString(2))
        // A fresh directory avoids mixing this export with an earlier experiment.
        val destination="/sdcard/Download/bubble-quantization-inputs"
        instrument.uiAutomation.executeShellCommand("rm -rf $destination").let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {stream ->stream.readBytes()}}
        instrument.uiAutomation.executeShellCommand("cp -r ${directory.absolutePath} $destination").let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {stream ->stream.readBytes()}}
    }
}
