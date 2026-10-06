package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Compare actual ComicView pixels with the same transform and dimming disabled. */
internal object NativeFocusQa {
    fun measure(image:Bitmap,panels:List<Panel>,reference:JSONObject,example:Int):Pair<JSONArray,List<String>> {
        val regions=JSONArray()
        for((key,dim) in listOf("required_speech_regions" to false,"required_dimmed_regions" to true)) {
            val source=reference.optJSONArray(key) ?: continue
            for(k in 0 until source.length())regions.put(JSONObject(source.getJSONObject(k).toString()).put("expected_dimmed",dim))
        }
        val instrument=InstrumentationRegistry.getInstrumentation()
        val output=JSONArray();val failures=mutableListOf<String>()
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa/native-focus").apply {mkdirs()}
        for(k in 0 until regions.length()) {
            val region=regions.getJSONObject(k);val index=region.getInt("panel")-1
            val expectedDimmed=region.getBoolean("expected_dimmed")
            if(index !in panels.indices)continue
            val roi=region.getJSONArray("box")
            instrument.runOnMainSync {
                val width=600;val height=900
                val view=ComicView(instrument.targetContext).apply {focusTransitions=false;layout(0,0,width,height)}
                view.setPage(image,panels,0)
                val select=ComicView::class.java.getDeclaredMethod("showPanel",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply {isAccessible=true}
                select.invoke(view,index,0,panels.lastIndex,true,false)
                val frame=GuidedFrames.frame(panels,index,image.width,image.height,width,height)
                val dx=width/2f-(frame.bounds.left+frame.bounds.right)*.5f*image.width*frame.scale
                val dy=height/2f-(frame.bounds.top+frame.bounds.bottom)*.5f*image.height*frame.scale
                val plain=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                val focused=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                view.outsideDim=0;view.draw(Canvas(plain))
                view.outsideDim=60;view.draw(Canvas(focused))
                var compared=0;var dimmed=0;var cropped=0
                for(y in (roi.getDouble(1)+4).toInt() until (roi.getDouble(3)-4).toInt() step 2)
                    for(x in (roi.getDouble(0)+4).toInt() until (roi.getDouble(2)-4).toInt() step 2) {
                        val xx=(dx+x*frame.scale).roundToInt();val yy=(dy+y*frame.scale).roundToInt()
                        if(xx !in 0 until width || yy !in 0 until height) {cropped++;continue}
                        val a=plain.getPixel(xx,yy);val b=focused.getPixel(xx,yy)
                        fun channel(c:Int,shift:Int)=(c shr shift) and 255
                        if(listOf(0,8,16).maxOf {channel(a,it)}<24)continue // Black ink has no measurable dimming.
                        compared++
                        if(listOf(0,8,16).maxOf {channel(a,it)-channel(b,it)}>8)dimmed++
                    }
                val metric=JSONObject().put("panel",index+1).put("box",roi).put("expected_dimmed",expectedDimmed).put("compared_pixels",compared).put("dimmed_pixels",dimmed).put("cropped_pixels",cropped)
                output.put(metric)
                if((if(expectedDimmed)dimmed!=compared else dimmed>0) || cropped>0 || compared==0)
                    failures.add("Page $example case ${index+1} : rendu natif ${if(expectedDimmed)"hors case" else "de la bulle"} ($dimmed/$compared pixels assombris, $cropped hors écran)")
                File(directory,"$example-case-${index+1}-focused.png").outputStream().use {focused.compress(Bitmap.CompressFormat.PNG,100,it)}
                File(directory,"$example-case-${index+1}-plain.png").outputStream().use {plain.compress(Bitmap.CompressFormat.PNG,100,it)}
                if(k==0)for(i in panels.indices)if(i!=index) {
                    select.invoke(view,i,0,panels.lastIndex,true,false)
                    view.draw(Canvas(focused))
                    File(directory,"$example-case-${i+1}-focused.png").outputStream().use {focused.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                view.setPage(null,emptyList());plain.recycle();focused.recycle()
            }
        }
        return output to failures
    }
}
