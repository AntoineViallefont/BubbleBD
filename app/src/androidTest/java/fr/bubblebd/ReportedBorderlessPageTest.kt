package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Approved eight-scene reference; native views remain distinct from full corpus QA. */
class ReportedBorderlessPageTest {
    @Test fun observeReportedPageInProductionReader() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private").orEmpty().contains("reported-7475.jpg"))
        val original=instrument.context.assets.open("private/reported-7475.jpg").use {BitmapFactory.decodeStream(it)}
        val reference=JSONObject(instrument.context.assets.open("private/reference-61-validee.json").bufferedReader().use {it.readText()}).getJSONArray("frames")
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa/reported-7475").apply {mkdirs()}
        val archive=File(instrument.targetContext.cacheDir,"reported-7475.cbz")
        try {
            ZipOutputStream(archive.outputStream()).use {zip->zip.putNextEntry(ZipEntry("001.jpg"));instrument.context.assets.open("private/reported-7475.jpg").use {it.copyTo(zip)};zip.closeEntry()}
            ComicDocument(archive).use {doc->val image=doc.bitmap(0)
                try {
                    val result=JSONArray()
                    for(prior in listOf(false,true)) {
                        val panels=BookRules.orderPanels(PanelDetector.detect(image,instrument.targetContext,rectanglePrior=prior),false)
                        assertEquals("Eight scenes approved independently of engine output",reference.length(),panels.size)
                        for(i in panels.indices) {
                            val p=panels[i].readingOrderBounds ?: panels[i];val expected=reference.getJSONArray(i)
                            val a=listOf(p.left*image.width,p.top*image.height,p.right*image.width,p.bottom*image.height)
                            val b=(0..3).map {expected.getDouble(it).toFloat()}
                            val shared=maxOf(0f,minOf(a[2],b[2])-maxOf(a[0],b[0]))*maxOf(0f,minOf(a[3],b[3])-maxOf(a[1],b[1]))
                            val union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-shared
                            assertTrue("Scene ${i+1}: physical bounds/order",shared/union>=.80f)
                        }
                        result.put(JSONObject().put("prior",prior).put("count",panels.size).put("width",image.width).put("height",image.height)
                            .put("cores",JSONArray(panels.map {val p=it.readingOrderBounds ?: it;listOf(p.left*image.width,p.top*image.height,p.right*image.width,p.bottom*image.height)}))
                            .put("boxes",JSONArray(panels.map {listOf(it.left*image.width,it.top*image.height,it.right*image.width,it.bottom*image.height)})))
                        instrument.runOnMainSync {
                            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;layout(0,0,600,900);setPage(image,panels,0)}
                            assertTrue("Automatic reading stays requested",view.guidedReading)
                            val output=Bitmap.createBitmap(600,300*maxOf(1,(panels.size+2)/3),Bitmap.Config.ARGB_8888)
                            val snapshot=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
                            val canvas=Canvas(output)
                            repeat(maxOf(1,panels.size)) {step->
                                view.draw(Canvas(snapshot));canvas.drawBitmap(snapshot,null,android.graphics.Rect(step%3*200,step/3*300,(step%3+1)*200,(step/3+1)*300),null)
                                val start=SystemClock.uptimeMillis()
                                for((action,time,x) in listOf(Triple(MotionEvent.ACTION_DOWN,start,500f),Triple(MotionEvent.ACTION_MOVE,start+100,300f),Triple(MotionEvent.ACTION_UP,start+200,100f))) {
                                    val event=MotionEvent.obtain(start,time,action,x,450f,0);view.onTouchEvent(event);event.recycle()
                                }
                            }
                            File(directory,"reader-$prior.png").outputStream().use {output.compress(Bitmap.CompressFormat.PNG,100,it)}
                            snapshot.recycle();output.recycle();view.setPage(null,emptyList())
                        }
                    }
                    File(directory,"report.json").writeText(JSONObject().put("observations",result).put("approved_reference",true).put("scope","original capture, native reader views and eight scene bounds/order; not complete corpus regression").toString(2))
                }finally {image.recycle()}
            }
        }finally {original.recycle();archive.delete()}
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("rm -rf /sdcard/Download/bubble-reported-7475")).use {it.readBytes()}
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp -r ${directory.absolutePath} /sdcard/Download/bubble-reported-7475")).use {it.readBytes()}
    }
    @Test fun automaticModeSurvivesMissingPanelsAndReaderCanDisableIt() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            val view=ComicView(instrument.targetContext).apply {layout(0,0,400,600);focusTransitions=false}
            try {
                view.setPage(image,emptyList(),0);assertTrue(view.guidedReading)
                var next=0;view.onGuidedPage={next=it}
                val start=SystemClock.uptimeMillis()
                for((action,time,x) in listOf(Triple(MotionEvent.ACTION_DOWN,start,350f),Triple(MotionEvent.ACTION_MOVE,start+100,200f),Triple(MotionEvent.ACTION_UP,start+200,50f))) {
                    val event=MotionEvent.obtain(start,time,action,x,300f,0);view.onTouchEvent(event);event.recycle()
                }
                assertEquals(1,next)
                val panels=listOf(Panel(.05f,.05f,.9f,.4f),Panel(.05f,.5f,.9f,.95f))
                view.setPage(image,panels,0);assertTrue(view.guidedReading)
                view.fitPage();assertFalse(view.guidedReading)
            }finally {view.setPage(null,emptyList());image.recycle()}
        }
    }
}
