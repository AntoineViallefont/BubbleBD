package fr.bubblebd

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The shared caption is bright; nearby artwork keeps its own reading turn. */
class SharedCaptionFocusTest {
    @Test fun captionAtThreeCaseJunctionDoesNotLightNeighbouringStrips() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private/ai").orEmpty().contains("example-54.png"))
        val image=instrument.context.assets.open("private/ai/example-54.png").use {BitmapFactory.decodeStream(it)}
        try {
            val panels=BookRules.orderPanels(PanelDetector.detect(image,instrument.targetContext),false)
            assertEquals(4,panels.size)
            val reference=JSONObject().put("required_speech_regions",JSONArray((2..4).map {
                JSONObject().put("panel",it).put("box",JSONArray(listOf(318,382,413,442)))
            })).put("required_dimmed_regions",JSONArray(listOf(
                JSONObject().put("panel",2).put("box",JSONArray(listOf(326,96,352,188))),
                JSONObject().put("panel",4).put("box",JSONArray(listOf(444,390,528,407)))
            )))
            val result=NativeFocusQa.measure(image,panels,reference,60)
            val file=File(instrument.targetContext.getExternalFilesDir(null),"qa/shared-caption-focus.json").apply {parentFile!!.mkdirs()}
            file.writeText(JSONObject().put("reference",54).put("scope","Supplementary native mask checks; approved reference unchanged")
                .put("native_focus",result.first).put("failures",JSONArray(result.second)).toString(2))
            val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa/native-focus")
            for(command in listOf("cp ${file.absolutePath} /sdcard/Download/bubble-shared-caption-focus.json",
                "mkdir -p /sdcard/Download/bubble-shared-caption-focus")+
                directory.listFiles().orEmpty().filter {it.name.startsWith("60-")}.map {"cp ${it.absolutePath} /sdcard/Download/bubble-shared-caption-focus/"}) {
                instrument.uiAutomation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            }
            assertTrue(result.second.joinToString(),result.second.isEmpty())
        } finally {image.recycle()}
    }
}
