package fr.bubblebd

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Opt-in comparison only; external weights live in private test assets, never in the app. */
class AlternativePanelModelTest {
    @Test fun measureDeepPanelOnApprovedCorpus() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("alternativeModel")!="deeppanel")return
        val assets=instrumentation.context.assets
        val bytes=assets.open("private/model-comparison/deeppanel.tflite").use {it.readBytes()}
        val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {put(bytes);rewind()}
        val outputDir=File(instrumentation.targetContext.getExternalFilesDir(null),"qa/deeppanel").apply {mkdirs()}
        val rows=JSONArray()
        Interpreter(model,Interpreter.Options().setNumThreads(2)).use {engine ->
            assertArrayEquals(intArrayOf(1,224,224,3),engine.getInputTensor(0).shape())
            assertArrayEquals(intArrayOf(1,224,224,3),engine.getOutputTensor(0).shape())
            val input=ByteBuffer.allocateDirect(224*224*3*4).order(ByteOrder.nativeOrder())
            val output=ByteBuffer.allocateDirect(224*224*3*4).order(ByteOrder.nativeOrder())
            for(example in 1..52) {
                val bitmap=assets.open("private/ai/example-$example.png").use {BitmapFactory.decodeStream(it)}
                try {
                    val start=System.nanoTime()
                    val small=Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888)
                    val transform=Matrix().apply {setRectToRect(RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat()),RectF(0f,0f,224f,224f),Matrix.ScaleToFit.CENTER)}
                    Canvas(small).apply {drawColor(Color.BLACK);drawBitmap(bitmap,transform,Paint())}
                    val pixels=IntArray(224*224);small.getPixels(pixels,0,224,0,0,224,224)
                    input.clear();output.clear()
                    pixels.forEach {c ->input.putFloat(Color.red(c)/255f);input.putFloat(Color.green(c)/255f);input.putFloat(Color.blue(c)/255f)}
                    input.rewind();val neuralStart=System.nanoTime();engine.run(input,output)
                    val neuralSeconds=(System.nanoTime()-neuralStart)/1e9
                    output.rewind();val values=FloatArray(224*224*3);output.asFloatBuffer().get(values)
                    val content=BooleanArray(224*224) {i ->values[i*3+2]>values[i*3] && values[i*3+2]>values[i*3+1]}
                    val labels=IntArray(content.size);val queue=IntArray(content.size);val boxes=JSONArray();var label=0
                    val scale=max(bitmap.width,bitmap.height)/224f
                    val dx=(224*scale-bitmap.width)/2;val dy=(224*scale-bitmap.height)/2
                    val border=if(bitmap.height>bitmap.width)bitmap.width*30/3056 else bitmap.height*30/1988
                    for(seed in content.indices) {
                        if(!content[seed] || labels[seed]!=0)continue
                        label++;var head=0;var tail=0;var l=224;var t=224;var r=0;var b=0
                        fun push(x:Int,y:Int) {if(x !in 0..223 || y !in 0..223)return;val i=y*224+x;if(content[i] && labels[i]==0) {labels[i]=label;queue[tail++]=i}}
                        push(seed%224,seed/224)
                        while(head<tail) {val i=queue[head++];val x=i%224;val y=i/224;l=min(l,x);t=min(t,y);r=max(r,x);b=max(b,y);push(x-1,y);push(x+1,y);push(x,y-1);push(x,y+1)}
                        // Published DeepPanel algorithm uses a 3% component minimum and physical border expansion.
                        if(tail<(224*224*.03).toInt())continue
                        boxes.put(JSONArray(listOf((l*scale-border-dx).toInt().coerceIn(0,bitmap.width),(t*scale-border-dy).toInt().coerceIn(0,bitmap.height),
                            (r*scale+border-dx).toInt().coerceIn(0,bitmap.width),(b*scale+border-dy).toInt().coerceIn(0,bitmap.height))))
                    }
                    val seconds=(System.nanoTime()-start)/1e9
                    for(i in content.indices)pixels[i]=if(content[i])Color.GREEN else Color.BLUE
                    small.setPixels(pixels,0,224,0,0,224,224)
                    File(outputDir,"mask-$example.png").outputStream().use {small.compress(Bitmap.CompressFormat.PNG,100,it)};small.recycle()
                    rows.put(JSONObject().put("example",example).put("frames",boxes).put("width",bitmap.width).put("height",bitmap.height).put("seconds",seconds).put("neural_seconds",neuralSeconds))
                } finally {bitmap.recycle()}
            }
        }
        File(outputDir,"measured.json").writeText(JSONObject().put("scope","DeepPanel raw model with published 4-connected components / 3% minimum; no BubbleBD repairs or user validation inferred").put("model_bytes",bytes.size).put("results",rows).toString(2))
        for(command in listOf("mkdir -p /sdcard/Download/bubble-deeppanel","cp -r ${outputDir.absolutePath}/. /sdcard/Download/bubble-deeppanel/"))
            instrumentation.uiAutomation.executeShellCommand(command).let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {stream ->stream.readBytes()}}
    }
}
