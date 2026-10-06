package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderIntegrationTest {
    @Test fun rectangularStyleSurvivesReopeningOnlyForItsAlbum() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=Repository(ctx);val history=PanelStyleHistory()
        val panels=listOf(Panel(.05f,.05f,.3f,.4f),Panel(.35f,.05f,.6f,.4f),Panel(.65f,.05f,.95f,.4f))
        history.record(0,panels);history.record(1,panels)
        repo.savePanelStyle("style-test",history)
        assertTrue(Repository(ctx).loadPanelStyle("style-test").prefersRectangles(2))
        assertFalse(Repository(ctx).loadPanelStyle("different-style-test").prefersRectangles(2))
        assertFalse(repo.loadPanelStyle("style-test").prefersRectangles(0))
    }
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun cbzNaturalOrderMetadataAndRendering() {
        val file=File(ctx.cacheDir,"integration.cbz")
        val b=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888)
        ZipOutputStream(file.outputStream()).use {z ->
            listOf("10.png" to Color.BLUE,"2.png" to Color.GREEN,"1.png" to Color.RED).forEach { (name,color) ->z.putNextEntry(ZipEntry(name));b.eraseColor(color);b.compress(Bitmap.CompressFormat.PNG,100,z);z.closeEntry()}
            z.putNextEntry(ZipEntry("ComicInfo.xml"));z.write("<ComicInfo><Title>Échos</Title><Series>Saga</Series><Number>2</Number></ComicInfo>".toByteArray());z.closeEntry()
        }
        ComicDocument(file).use { doc ->assertEquals(3,doc.count);assertEquals("Échos",doc.metadata()["Title"]);assertEquals(Color.RED,doc.bitmap(0).getPixel(0,0));assertEquals(Color.GREEN,doc.bitmap(1).getPixel(0,0)) }
        b.recycle();file.delete()
    }
    @Test fun pdfRendersAndCountsPages() {
        val f=File(ctx.cacheDir,"integration.pdf")
        val pdf=PdfDocument()
        try {repeat(2) {i ->val p=pdf.startPage(PdfDocument.PageInfo.Builder(100,150,i).create());p.canvas.drawColor(if(i==0) Color.RED else Color.BLUE);pdf.finishPage(p)};f.outputStream().use {pdf.writeTo(it)}} finally {pdf.close()}
        ComicDocument(f).use {doc ->assertEquals(2,doc.count);assertEquals(Color.BLUE,doc.bitmap(1).getPixel(10,10))};f.delete()
    }
    @Test fun uncertainPageKeepsFullPage() {
        val b=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);b.eraseColor(Color.GRAY)
        assertEquals(listOf(Panel(0f,0f,1f,1f)),PanelDetector.detect(b));b.recycle()
    }
    @Test fun demoPageDetectsFiveSafePanels() {
        val b=InstrumentationRegistry.getInstrumentation().context.assets.open("demo/page.png").use {BitmapFactory.decodeStream(it)}
        val p=PanelDetector.detect(b)
        assertEquals("The regular five-panel example must segment correctly",5,p.size)
        assertTrue(p.all {it.left>=0 && it.right<=1 && it.top>=0 && it.bottom<=1})
        b.recycle()
    }
    @Test fun invalidArchiveFailsCleanly() {
        val f=File(ctx.cacheDir,"invalid.cbr");f.writeText("Not an archive")
        try {ArchiveBridge.entries(f.path);fail("Invalid archives must be rejected")} catch(expected:java.io.IOException) {assertTrue(expected.message!!.isNotBlank())} finally {f.delete()}
    }
    @Test fun rar4AndRar5DecodeRealFiles() {
        val testContext=InstrumentationRegistry.getInstrumentation().context
        listOf("rar4.rar","rar5.rar").forEach {name ->
            val f=File(ctx.cacheDir,name);testContext.assets.open(name).use {input ->f.outputStream().use {input.copyTo(it)}}
            val entries=ArchiveBridge.entries(f.path);assertTrue(entries.isNotEmpty())
            assertTrue(ArchiveBridge.read(f.path,entries.first()).isNotEmpty());f.delete()
        }
    }
    @Test fun keepOneDetectedCaseWhenOtherRegionsAreUnusable() {
        val b=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE)
        val canvas=android.graphics.Canvas(b);val paint=android.graphics.Paint().apply {color=Color.DKGRAY}
        canvas.drawRect(20f,20f,380f,340f,paint)
        canvas.drawRect(150f,520f,250f,530f,paint)
        val panels=PanelDetector.detect(b)
        assertEquals(1,panels.size)
        assertFalse("A usable isolated case must not be replaced by the whole page",panels.single().isWholePage)
        assertTrue(panels.single().bottom<.65f);b.recycle()
    }

}
