package fr.bubblebd

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Partial, already dimmed feedback: test ownership on approved physical frames.
 * This does not assert detection of the unavailable complete original page. */
class CaptionFocusTest {
    @Test fun narrationIsLitOnlyWithItsOwnFrame() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private").orEmpty().contains("feedback-demains"))
        val reference=JSONObject(instrument.context.assets.open("private/feedback-demains/reference-cartouche-validee.json").bufferedReader().use {it.readText()})
        val image=instrument.context.assets.open("private/feedback-demains/capture-cartouche.png").use {BitmapFactory.decodeStream(it)}
        try {
            val boxes=reference.getJSONArray("frames")
            val physical=(0 until 5).map {k ->boxes.getJSONArray(k).let {b->Panel((b.getDouble(0)/image.width).toFloat(),(b.getDouble(1)/image.height).toFloat(),(b.getDouble(2)/image.width).toFloat(),(b.getDouble(3)/image.height).toFloat())}}
            val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
            val text=LocalPanelAi.detect(instrument.targetContext,image).filter {it.text && it.bounds.bottom<=physical.maxOf {p->p.bottom}}
            val panels=BookRules.orderPanels(HybridPanels.combine(physical,physical.map {PanelCandidate(it,.99f,false)}+text,pixels,image.width,image.height),false)
            fun matched(k:Int):Int {
                val target=physical[k]
                return panels.indices.minBy {i ->val p=panels[i].readingOrderBounds ?: panels[i]
                    kotlin.math.abs(p.left-target.left)+kotlin.math.abs(p.top-target.top)+kotlin.math.abs(p.right-target.right)+kotlin.math.abs(p.bottom-target.bottom)
                }
            }
            val bar=matched(2);val caption=matched(3);assertNotEquals(bar,caption)
            val qaReference=JSONObject()
            for(key in listOf("required_speech_regions","required_dimmed_regions")) {
                val source=reference.getJSONArray(key);val regions=JSONArray()
                for(k in 0 until source.length())regions.put(JSONObject(source.getJSONObject(k).toString()).put("panel",if(key=="required_speech_regions")caption+1 else bar+1))
                qaReference.put(key,regions)
            }
            val result=NativeFocusQa.measure(image,panels,qaReference,58)
            val report=JSONObject().put("source_complete",false).put("input_already_dimmed",true)
                .put("physical_frames_supplied_from_approved_reference",true).put("native_focus",result.first).put("failures",JSONArray(result.second))
            val file=File(instrument.targetContext.getExternalFilesDir(null),"qa/caption-focus.json").apply {parentFile!!.mkdirs()}
            file.writeText(report.toString(2))
            instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-caption-focus.json").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            val renders=File(instrument.targetContext.getExternalFilesDir(null),"qa/native-focus")
            val files=panels.indices.map {"58-case-${it+1}-focused.png"}+listOf("58-case-${bar+1}-plain.png","58-case-${caption+1}-plain.png")
            for(command in listOf("mkdir -p /sdcard/Download/bubble-caption-focus")+files.distinct().map {name->
                assertTrue("Rendu natif absent : $name",File(renders,name).isFile)
                "cp ${renders.absolutePath}/$name /sdcard/Download/bubble-caption-focus/"
            }) {
                val message=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use {String(it.readBytes())}
                assertTrue("Export du rendu natif : $message",message.isBlank())
            }
            assertTrue(result.second.joinToString(),result.second.isEmpty())
        } finally {image.recycle()}
    }
}
