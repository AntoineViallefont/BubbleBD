package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RectangularFocusRenderingTest {
    @Test fun solidRectangleLightsCornersBalloonAndInnerInsetButDimsOutside() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            Canvas(image).drawRect(390f,60f,540f,180f,Paint().apply {color=Color.YELLOW})
            val panel=Panel(.1f,.3f,.7f,.9f,focusIncludes=listOf(Panel(.65f,.1f,.9f,.3f)),
                focusOutline=listOf(PanelPoint(.1f,.3f),PanelPoint(.7f,.5f),PanelPoint(.7f,.9f)),
                focusExclusions=listOf(Panel(.3f,.4f,.4f,.5f)))
            val panels=listOf(panel)
            var selected:List<Int>?=null
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;onPanelSelectionChanged={indices,_->selected=indices};layout(0,0,600,900)}
            view.setPage(image,panels,0)
            val output=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            val frame=GuidedFrames.frame(panels,0,600,600,600,900)
            val dx=(600-frame.bounds.width*600*frame.scale)/2-frame.bounds.left*600*frame.scale
            val dy=(900-frame.bounds.height*600*frame.scale)/2-frame.bounds.top*600*frame.scale
            fun colour(x:Float,y:Float)=output.getPixel((dx+x*600*frame.scale).toInt(),(dy+y*600*frame.scale).toInt())
            assertEquals("Corner between panel and balloon is lit",Color.BLUE,colour(.15f,.15f))
            assertEquals("Former inset hole is filled",Color.BLUE,colour(.35f,.45f))
            assertEquals("Attached balloon is lit",Color.YELLOW,colour(.75f,.2f))
            assertEquals("Outside top edge is dim",Color.BLACK,colour(.5f,.05f))
            assertEquals("Outside bottom edge is dim",Color.BLACK,colour(.5f,.95f))
            assertEquals("Selection is unchanged",listOf(0),selected)
            image.recycle();output.recycle()
        }
    }
    @Test fun exportFictionalComicPreview() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val image=instrument.context.assets.open("demo/page.png").use {android.graphics.BitmapFactory.decodeStream(it)}
        val panels=BookRules.orderPanels(PanelDetector.detect(image),false)
        assertEquals(5,panels.size)
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=65;layout(0,0,600,900)}
            view.setPage(image,panels,1)
            val output=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            val file=java.io.File(instrument.targetContext.getExternalFilesDir(null),"rectangular-focus-demo.png")
            file.outputStream().use {output.compress(Bitmap.CompressFormat.PNG,100,it)}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-rectangular-focus-demo.png")).use {it.readBytes()}
            output.recycle()
        }
        image.recycle()
    }

}
