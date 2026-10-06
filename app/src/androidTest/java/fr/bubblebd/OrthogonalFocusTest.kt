package fr.bubblebd

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Supplement the approved seven rectangles with native speech and framing checks. */
class OrthogonalFocusTest {
    @Test fun upperOpenSpeechDoesNotBecomeTheFollowingRowsForeground() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val image=instrument.context.assets.open("private/ai/example-52.png").use {BitmapFactory.decodeStream(it)}
        try {
            val panels=BookRules.orderPanels(PanelDetector.detect(image,instrument.targetContext),false)
            assertEquals(7,panels.size)
            val fifth=panels[4];val core=fifth.readingOrderBounds ?: fifth
            assertTrue("La case 5 ne reprend pas la case 3 au-dessus",fifth.top>=core.top-.05f)
            assertTrue("La queue vers le haut ne retire pas la bulle du personnage en case 6",panels[5].focusExclusions.none {it.contains(500f/image.width,835f/image.height)})
            val reference=JSONObject().put("required_speech_regions",JSONArray()
                .put(JSONObject().put("panel",3).put("box",JSONArray(listOf(106,430,238,543))))
                .put(JSONObject().put("panel",5).put("box",JSONArray(listOf(103,801,204,854))))
                .put(JSONObject().put("panel",6).put("box",JSONArray(listOf(453,818,548,853))))
                .put(JSONObject().put("panel",7).put("box",JSONArray(listOf(131,888,331,999)))))
            val result=NativeFocusQa.measure(image,panels,reference,59)
            val report=JSONObject().put("reference",52).put("scope","Native rendering supplement; approved reference unchanged")
                .put("native_focus",result.first).put("failures",JSONArray(result.second))
            val file=File(instrument.targetContext.getExternalFilesDir(null),"qa/orthogonal-focus.json")
            file.writeText(report.toString(2))
            instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-orthogonal-focus.json").let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {s->s.readBytes()}}
            val images=File(instrument.targetContext.getExternalFilesDir(null),"qa/native-focus")
            for(command in listOf("mkdir -p /sdcard/Download/bubble-orthogonal-focus","cp -r ${images.absolutePath}/. /sdcard/Download/bubble-orthogonal-focus/"))
                instrument.uiAutomation.executeShellCommand(command).let {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use {s->s.readBytes()}}
            assertTrue(result.second.joinToString(),result.second.isEmpty())
        } finally {image.recycle()}
    }
}
