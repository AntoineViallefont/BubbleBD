package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** OCR feasibility observations on approved speech regions, never model labels. */
class SpeechContextOcrBenchmarkTest {
    @Test fun measureExistingFrenchOcrOnAmbiguousSpeech() {
        val instrument=InstrumentationRegistry.getInstrumentation();val assets=instrument.context.assets
        org.junit.Assume.assumeTrue(assets.list("private").orEmpty().contains("reader-audit"))
        val rows=JSONArray()
        for(n in listOf(41,55,56,59)) {
            val source=assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
            try {
                val reference=JSONObject(assets.open("private/reader-audit/reference-$n.json").bufferedReader().use {it.readText()})
                val regions=reference.optJSONArray("required_speech_regions") ?: continue
                for(i in 0 until regions.length()) {
                    val region=regions.getJSONObject(i);val box=region.getJSONArray("box")
                    val l=(box.getInt(0)-5).coerceAtLeast(0);val t=(box.getInt(1)-5).coerceAtLeast(0)
                    val r=(box.getInt(2)+5).coerceAtMost(source.width);val b=(box.getInt(3)+5).coerceAtMost(source.height)
                    val crop=Bitmap.createBitmap(source,l,t,r-l,b-t)
                    val enlarged=Bitmap.createScaledBitmap(crop,crop.width*3,crop.height*3,true)
                    try {for(pass in 0..1) {
                        val started=System.nanoTime();val text=CoverRecognition.readBitmap(instrument.targetContext,enlarged)
                        rows.put(JSONObject().put("example",n).put("panel",region.getInt("panel")).put("pass",pass)
                            .put("seconds",(System.nanoTime()-started)/1e9).put("lines",JSONArray(text))
                            .put("input_width",enlarged.width).put("input_height",enlarged.height))
                    }}finally {enlarged.recycle();crop.recycle()}
                }
            }finally {source.recycle()}
        }
        val report=File(instrument.targetContext.getExternalFilesDir(null),"qa/speech-context-ocr.json")
        report.parentFile!!.mkdirs();report.writeText(JSONObject().put("rows",rows)
            .put("scope","Diagnostic OCR local sur régions de référence ; aucun raisonnement sémantique ni validation de locuteur.").toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${report.absolutePath} /sdcard/Download/bubble-speech-context-ocr.json")).use {it.readBytes()}
    }
}
