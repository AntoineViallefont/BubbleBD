package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class CoverRecognitionTest {
    @Test fun bundledOcrReadsFirstPageTextOffline() {
        val b=Bitmap.createBitmap(900,1300,Bitmap.Config.ARGB_8888)
        val c=Canvas(b);c.drawColor(Color.WHITE)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=52f}
        c.drawText("SYLVAIN RUNBERG",50f,100f,p)
        c.drawText("LUC BRAHY",50f,180f,p)
        p.textSize=96f;c.drawText("ATOMKA",70f,500f,p)
        p.textSize=44f;c.drawText("ISBN 9782491467029",50f,1100f,p)
        val lines=CoverRecognition.readBitmap(InstrumentationRegistry.getInstrumentation().targetContext,b)
        b.recycle()
        assertTrue(lines.joinToString(" "),lines.any {it.contains("ATOMKA",true)})
        assertTrue(lines.joinToString(" "),lines.any {it.contains("9782491467029")})
    }
}
