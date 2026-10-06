package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in real PDF / local detector / ComicView pipeline measurement, not a phone benchmark. */
class ReaderPreparationBenchmarkTest {
    @Test fun measureRealPreparedTransitions()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("readerPreparationBenchmark")!="true")return@runBlocking
        val ctx=instrument.targetContext
        val rows=JSONArray()
        val target=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
        lateinit var view:ComicView
        instrument.runOnMainSync {view=ComicView(ctx).apply {layout(0,0,600,900);focusTransitions=false}}
        try {
            for((album,first) in listOf("vertigeo" to 90,"ile-minuit-t01" to 30)) {
                val original=File(ctx.cacheDir,"prefetch-$album.pdf")
                instrument.context.assets.open("private/pdf-albums/$album.pdf").use {s->original.outputStream().use {s.copyTo(it)}}
                try {ComicDocument(original).use {doc ->
                    for(dwell in listOf(0L,1000L))coroutineScope {
                        var current:Bitmap?=null
                        val preparation=ReaderPrefetch(this,
                            load={page->withContext(Dispatchers.IO) {doc.bitmap(page)}},
                            detect={_,image->PanelDetector.detectAsync(image,ctx)})
                        try {
                            for(page in first until first+6) {
                                val started=System.nanoTime()
                                preparation.prepare(page)
                                if(page>first)delay(dwell)
                                val transfer=preparation.take(page)!!
                                var rendered=0
                                val request=System.nanoTime()
                                try {
                                    val frames=loadReaderPage(true,null,{transfer.image()!!},{transfer.frames()!!}) {image,cases ->
                                        assertTrue("La vue guidée publie les cases avec l'image",cases.isNotEmpty())
                                        val previous=current
                                        instrument.runOnMainSync {
                                            view.setPage(image,BookRules.orderPanels(cases,false),0)
                                            view.draw(Canvas(target))
                                        }
                                        rendered++;current=image
                                        if(previous!==image)previous?.recycle()
                                    }
                                    assertEquals(1,rendered)
                                    rows.put(JSONObject().put("album",album).put("pdf_page",page+1).put("cold_entry",page==first).put("preparation_ms",if(page==first)0 else dwell)
                                        .put("request_to_render_ms",(System.nanoTime()-request)/1e6)
                                        .put("total_pipeline_ms",(System.nanoTime()-started)/1e6)
                                        .put("case_count",frames.size).put("active_bitmap_bytes",current!!.allocationByteCount)
                                        .put("pss_kib",Debug.getPss()))
                                } finally {transfer.cancel()}
                            }
                        } finally {
                            preparation.cancel()
                            instrument.runOnMainSync {view.setPage(null,emptyList())}
                            current?.recycle()
                        }
                    }
                }} finally {original.delete()}
            }
            val file=File(ctx.getExternalFilesDir(null),"qa/reader-preparation.json")
            file.parentFile!!.mkdirs()
            file.writeText(JSONObject().put("scope","Real PDF renderer, ReaderPrefetch, local detector, guided publication and native draw; emulator only, no physical phone claim; zero or 1000 ms simulated reading time").put("results",rows).toString(2))
            instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-reader-preparation.json").let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {s->s.readBytes()}}
        } finally {target.recycle()}
    }
}
