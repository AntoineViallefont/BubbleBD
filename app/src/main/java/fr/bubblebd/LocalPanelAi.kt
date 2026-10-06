package fr.bubblebd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Offline inference only. No image, token or detection is sent to a server. */
object LocalPanelAi {
    fun detailProposals(context:Context,bitmap:Bitmap,stop:()->Boolean={false}):List<PanelCandidate> = buildList {
        val regions=listOf(Panel(0f,0f,1f,.40f),Panel(0f,0f,1f,.60f),Panel(0f,.40f,1f,1f),
            Panel(0f,0f,.60f,.60f),Panel(.40f,0f,1f,.60f),
            Panel(0f,.40f,.60f,1f),Panel(.40f,.40f,1f,1f),Panel(0f,0f,.45f,.40f))
        for(region in regions) {
            if(stop())break
            val x=(region.left*bitmap.width).toInt();val y=(region.top*bitmap.height).toInt()
            val width=(region.width*bitmap.width).toInt().coerceAtMost(bitmap.width-x)
            val height=(region.height*bitmap.height).toInt().coerceAtMost(bitmap.height-y)
            val tile=Bitmap.createBitmap(bitmap,x,y,width,height)
            try {
                addAll(detect(context,tile).filter {d -> val p=d.bounds
                    (region.left==0f || p.left>.025f) && (region.right==1f || p.right<.975f) &&
                    (region.top==0f || p.top>.025f) && (region.bottom==1f || p.bottom<.975f)
                }.map {d -> d.copy(bounds=Panel((x+d.bounds.left*width)/bitmap.width,(y+d.bounds.top*height)/bitmap.height,(x+d.bounds.right*width)/bitmap.width,(y+d.bounds.bottom*height)/bitmap.height))})
            } finally {if(tile!==bitmap)tile.recycle()}
        }
    }
    private var interpreter:Interpreter?=null
    private var tensorBitmap:Bitmap?=null
    private var tensorPixels:IntArray?=null
    private var inputBuffer:ByteBuffer?=null
    private var outputBuffer:ByteBuffer?=null
    private val samplingPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val channelShifts=intArrayOf(16,8,0)
    @Synchronized fun detect(context:Context,bitmap:Bitmap):List<PanelCandidate> {
        val engine=interpreter ?: context.applicationContext.assets.open("models/panels-int8.tflite").use {stream ->
            val bytes=stream.readBytes()
            val buffer=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {put(bytes);rewind()}
            Interpreter(buffer,Interpreter.Options().setNumThreads(2)).also {interpreter=it}
        }
        val inputTensor=engine.getInputTensor(0)
        val shape=inputTensor.shape()
        require(shape.size==4 && shape[0]==1 && shape[3]==3)
        val h=shape[1];val w=shape[2]
        val factor=minOf(w.toFloat()/bitmap.width,h.toFloat()/bitmap.height)
        val rw=(bitmap.width*factor).roundToInt();val rh=(bitmap.height*factor).roundToInt()
        val dx=(w-rw)/2;val dy=(h-rh)/2
        val scaled=tensorBitmap?.takeIf {it.width==w && it.height==h} ?: Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {tensorBitmap?.recycle();tensorBitmap=it}
        Canvas(scaled).apply {
            drawColor(Color.rgb(114,114,114))
            drawBitmap(bitmap,null,Rect(dx,dy,dx+rw,dy+rh),samplingPaint)
        }
        val pixels=tensorPixels?.takeIf {it.size==w*h} ?: IntArray(w*h).also {tensorPixels=it}
        scaled.getPixels(pixels,0,w,0,0,w,h)
        val input=inputBuffer?.takeIf {it.capacity()==inputTensor.numBytes()} ?: ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder()).also {inputBuffer=it}
        input.clear()
        val quant=inputTensor.quantizationParams()
        val inputType=inputTensor.dataType()
        for(pixel in pixels)for(shift in channelShifts) {
            val normalized=((pixel shr shift)and 255)/255f
            when(inputType) {
                DataType.FLOAT32->input.putFloat(normalized)
                DataType.INT8->input.put((normalized/quant.scale+quant.zeroPoint).roundToInt().coerceIn(-128,127).toByte())
                DataType.UINT8->input.put((normalized/quant.scale+quant.zeroPoint).roundToInt().coerceIn(0,255).toByte())
                else->error("Unsupported model input")
            }
        }
        input.rewind()
        val outputTensor=engine.getOutputTensor(0)
        val output=outputBuffer?.takeIf {it.capacity()==outputTensor.numBytes()} ?: ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder()).also {outputBuffer=it}
        output.clear()
        engine.run(input,output);output.rewind()
        val outputShape=outputTensor.shape()
        require(outputShape.size==3 && outputShape[2]==6) {"Unexpected model output: ${outputShape.toList()}"}
        val q=outputTensor.quantizationParams()
        val outputType=outputTensor.dataType()
        fun number():Float=when(outputType) {
            DataType.FLOAT32->output.float
            DataType.INT8->(output.get().toInt()-q.zeroPoint)*q.scale
            DataType.UINT8->((output.get().toInt()and 255)-q.zeroPoint)*q.scale
            else->error("Unsupported model output")
        }
        return buildList {
            repeat(outputShape[1]) {
                // The pinned TFLite export returns normalized coordinates (unlike the ONNX export).
                val x1=number()*w;val y1=number()*h;val x2=number()*w;val y2=number()*h
                val confidence=number();val kind=number().roundToInt()
                if(confidence>=.25f && kind in 0..1) {
                    val p=Panel(((x1-dx)/factor/bitmap.width).coerceIn(0f,1f),((y1-dy)/factor/bitmap.height).coerceIn(0f,1f),
                        ((x2-dx)/factor/bitmap.width).coerceIn(0f,1f),((y2-dy)/factor/bitmap.height).coerceIn(0f,1f))
                    if(p.width>.015f && p.height>.01f)add(PanelCandidate(p,confidence,kind==1))
                }
            }
        }
    }
}
