package fr.bubblebd

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LocalAiTest {
    @Test fun diagnoseRequestedCorpusPages() {
        val requested=InstrumentationRegistry.getArguments().getString("privateExamples") ?: return
        val instrument=InstrumentationRegistry.getInstrumentation();val output=JSONArray()
        for(value in requested.split(",")) {
            val n=value.toInt();require(n in 1..1000)
            val bitmap=instrument.context.assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
            try {output.put(JSONObject().put("example",n).put("diagnostic",WebExplorationTest().diagnostics("corpus-$n",bitmap)))}finally {bitmap.recycle()}
        }
        val file=File(instrument.targetContext.getExternalFilesDir(null),"qa/corpus-diagnostic.json").apply {parentFile!!.mkdirs()}
        file.writeText(output.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-corpus-diagnostic.json")).use {it.readBytes()}
    }
    @Test fun previewRequestedPrivatePages() {
        val requested=InstrumentationRegistry.getArguments().getString("privateExamples") ?: return
        val assetSet=InstrumentationRegistry.getArguments().getString("privateAssetSet") ?: "ai"
        require(assetSet in setOf("ai","proposals"))
        val instrument=InstrumentationRegistry.getInstrumentation();val output=JSONArray()
        for(n in requested.split(",").map(String::toInt)) {
            val bitmap=instrument.context.assets.open("private/$assetSet/example-$n.png").use {BitmapFactory.decodeStream(it)}
            try {
                val w=bitmap.width;val h=bitmap.height;val pixels=IntArray(w*h);bitmap.getPixels(pixels,0,w,0,0,w,h)
                val geometry=PanelGeometry.detect(pixels,w,h);val full=LocalPanelAi.detect(instrument.targetContext,bitmap)
                val predictions=full+if(geometry.size in 2..3)LocalPanelAi.detailProposals(instrument.targetContext,bitmap).filter {d ->
                    d.text || full.none {f ->val a=f.bounds;val b=d.bounds
                        val overlap=kotlin.math.max(0f,kotlin.math.min(a.right,b.right)-kotlin.math.max(a.left,b.left))*kotlin.math.max(0f,kotlin.math.min(a.bottom,b.bottom)-kotlin.math.max(a.top,b.top))
                        !f.text && f.confidence>.50f && a.width*a.height>b.width*b.height && overlap/(b.width*b.height)>.9f && d.confidence<.8f}
                } else emptyList()
                fun boxes(list:List<Panel>)=JSONArray(list.map {p->listOf(p.left*w,p.top*h,p.right*w,p.bottom*h)})
                val trace=JSONArray();val found=BookRules.orderPanels(HybridPanels.combine(geometry,predictions,pixels,w,h) {stage,frames->trace.put(JSONObject().put("stage",stage).put("boxes",boxes(frames)))},false)
                fun native(prior:Boolean):JSONObject {
                    val panels=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext,rectanglePrior=prior),false)
                    return JSONObject().put("frames",boxes(panels)).put("cores",boxes(panels.map {it.readingOrderBounds ?: it}))
                        .put("includes",JSONArray(panels.map {boxes(it.focusIncludes)}))
                        .put("exclusions",JSONArray(panels.map {boxes(it.focusExclusions)}))
                        .put("outlines",JSONArray(panels.map {p->p.focusOutline.map {v->listOf(v.x*w,v.y*h)}}))
                }
                output.put(JSONObject().put("example",n).put("geometry",boxes(geometry)).put("trace",trace).put("hybrid",boxes(found))
                    .put("accepted",native(false)).put("rectangular_prior",native(true))
                    .put("fitted",JSONArray(predictions.filter {!it.text}.map {d ->
                        val fitted=FrameContours(pixels,w,h,predictions.filter {it.text && it.confidence>.45f}.map {it.bounds}).fit(d.bounds)
                        JSONObject().put("confidence",d.confidence.toDouble()).put("raw",boxes(listOf(d.bounds))).put("fit",boxes(listOfNotNull(fitted)))
                    }))
                    .put("predictions",JSONArray(predictions.map {d ->JSONObject().put("text",d.text).put("confidence",d.confidence.toDouble()).put("box",JSONArray(listOf(d.bounds.left*w,d.bounds.top*h,d.bounds.right*w,d.bounds.bottom*h)))})))
                val dir=File(instrument.targetContext.getExternalFilesDir(null),"qa").apply {mkdirs()}
                val replay=File(dir,"october-replay-$n.tsv");replay.writeText(predictions.joinToString("\n") {d->"${if(d.text)1 else 0} ${d.confidence} ${d.bounds.left*w} ${d.bounds.top*h} ${d.bounds.right*w} ${d.bounds.bottom*h}"})
                val rgb=File(dir,"october-replay-$n.rgb")
                java.io.DataOutputStream(rgb.outputStream()).use {out ->out.writeInt(w);out.writeInt(h);pixels.forEach {c ->out.writeByte(c shr 16);out.writeByte(c shr 8);out.writeByte(c)}}
                for(file in listOf(rgb,replay))android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-${file.name}")).use {it.readBytes()}
            } finally {bitmap.recycle()}
        }
        val file=File(instrument.targetContext.getExternalFilesDir(null),"qa/requested-pages.json");file.writeText(output.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-requested-pages.json")).use {it.readBytes()}
    }
    @Test fun previewNightScreenshots() {
        val instrument=InstrumentationRegistry.getInstrumentation();val output=JSONArray()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private/ai").orEmpty().contains("example-37.png"))
        val errors=mutableListOf<String>()
        for(n in 37..39) {
            val row=JSONObject().put("example",n)
            try {
                val bitmap=instrument.context.assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
                try {
                    val times=JSONObject();val start=System.nanoTime()
                    val panels=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext) {stage,seconds->times.put(stage,seconds)},false)
                    val elapsed=(System.nanoTime()-start)/1e9
                    fun boxes(list:List<Panel>)=JSONArray(list.map {p->listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)})
                    val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    val geometry=PanelGeometry.detect(pixels,bitmap.width,bitmap.height)
                    val full=LocalPanelAi.detect(instrument.targetContext,bitmap)
                    val predictions=full+if(geometry.size in 2..3)LocalPanelAi.detailProposals(instrument.targetContext,bitmap).filter {d ->
                        d.text || full.none {f ->val a=f.bounds;val b=d.bounds
                            val shared=kotlin.math.max(0f,kotlin.math.min(a.right,b.right)-kotlin.math.max(a.left,b.left))*kotlin.math.max(0f,kotlin.math.min(a.bottom,b.bottom)-kotlin.math.max(a.top,b.top))
                            !f.text && f.confidence>.50f && a.width*a.height>b.width*b.height && shared/(b.width*b.height)>.9f && d.confidence<.8f}
                    } else emptyList()
                    val trace=JSONArray()
                    HybridPanels.combine(geometry,predictions,pixels,bitmap.width,bitmap.height) {stage,frames ->trace.put(JSONObject().put("stage",stage).put("boxes",boxes(frames)))}
                    row.put("trace",trace).put("geometry",boxes(geometry))
                    row.put("width",bitmap.width).put("height",bitmap.height).put("seconds_detector",elapsed).put("stages_seconds",times)
                        .put("hybrid",boxes(panels)).put("cores",boxes(panels.map {it.readingOrderBounds ?: it}))
                        .put("guided_views",JSONArray(panels.indices.map {GuidedFrames.frame(panels,it,bitmap.width,bitmap.height,400,800).visible.map {i->i+1}}))
                        .put("exclusions",JSONArray(panels.map {p->p.focusExclusions.map {e->listOf(e.left*bitmap.width,e.top*bitmap.height,e.right*bitmap.width,e.bottom*bitmap.height)}}))
                        .put("predictions",JSONArray(predictions.map {d->JSONObject().put("text",d.text).put("confidence",d.confidence.toDouble()).put("box",JSONArray(listOf(d.bounds.left*bitmap.width,d.bounds.top*bitmap.height,d.bounds.right*bitmap.width,d.bounds.bottom*bitmap.height)))}))
                } finally {bitmap.recycle()}
            } catch(e:Exception) {row.put("diagnostic_error",e.stackTraceToString());errors.add("$n: ${e.message}")}
            output.put(row)
        }
        val result=File(instrument.targetContext.getExternalFilesDir(null),"qa/october-night.json").apply {parentFile!!.mkdirs()}
        result.writeText(output.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-october-night.json")).use {it.readBytes()}
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun previewEveningScreenshots() {
        val instrument=InstrumentationRegistry.getInstrumentation();val output=JSONArray()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private/ai").orEmpty().contains("example-26.png"))
        for(n in listOf(36)) try {
            val bitmap=instrument.context.assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
            val px=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(px,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val geometry=PanelGeometry.detect(px,bitmap.width,bitmap.height)
            val full=LocalPanelAi.detect(instrument.targetContext,bitmap)
            val predictions=full+if(geometry.size in 2..3)LocalPanelAi.detailProposals(instrument.targetContext,bitmap).filter {d ->
                d.text || full.none {f ->
                    val a=f.bounds;val b=d.bounds
                    val shared=kotlin.math.max(0f,kotlin.math.min(a.right,b.right)-kotlin.math.max(a.left,b.left))*kotlin.math.max(0f,kotlin.math.min(a.bottom,b.bottom)-kotlin.math.max(a.top,b.top))
                    !f.text && f.confidence>.50f && a.width*a.height>b.width*b.height && shared/(b.width*b.height)>.9f && d.confidence<.8f
                }
            } else emptyList()
            val times=JSONObject();val start=System.nanoTime()
            val panels=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext) {stage,elapsed->times.put(stage,elapsed)},false)
            val rendered=mutableSetOf<List<Int>>()
            for(i in panels.indices.filter {panels[it].jointFocus.isNotEmpty()})instrument.runOnMainSync {
                val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=60;layout(0,0,597,1280)}
                var visible:List<Int>?=null;view.onPanelSelectionChanged={group,_->visible=group}
                view.setPage(bitmap,panels)
                val c=panels[i].readingOrderBounds ?: panels[i];val base=minOf(597f/bitmap.width,1280f/bitmap.height)
                val x=(597-bitmap.width*base)/2+(c.left+c.right)*.5f*bitmap.width*base
                val y=(1280-bitmap.height*base)/2+(c.top+c.bottom)*.5f*bitmap.height*base
                val now=android.os.SystemClock.uptimeMillis()
                listOf(0L to android.view.MotionEvent.ACTION_DOWN,40L to android.view.MotionEvent.ACTION_UP,100L to android.view.MotionEvent.ACTION_DOWN,140L to android.view.MotionEvent.ACTION_UP).forEach {(dt,action)->val e=android.view.MotionEvent.obtain(now,now+dt,action,x,y,0);view.onTouchEvent(e);e.recycle()}
                visible?.takeIf {it.size==2 && rendered.add(it)}?.let {group ->
                    val screenshot=android.graphics.Bitmap.createBitmap(597,1280,android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas=android.graphics.Canvas(screenshot);view.draw(canvas)
                    val brush=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=0xff25313a.toInt()}
                    canvas.drawRoundRect(145f,1200f,452f,1255f,28f,28f,brush);brush.color=android.graphics.Color.WHITE;brush.textSize=22f;brush.textAlign=android.graphics.Paint.Align.CENTER
                    canvas.drawText(GuidedFrames.label(group,panels.size),298.5f,1235f,brush)
                    val file=File(instrument.targetContext.getExternalFilesDir(null),"qa/joint-$n-${group.joinToString("-") {"${it+1}"}}.png").apply {parentFile!!.mkdirs()}
                    file.outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-${file.name}")).use {it.readBytes()}
                }
            }
            fun boxes(list:List<Panel>)=JSONArray(list.map {p->listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)})
            val trace=JSONObject()
            HybridPanels.combine(geometry,predictions,px,bitmap.width,bitmap.height) {stage,frames->trace.put(stage,boxes(frames))}
            output.put(JSONObject().put("example",n).put("seconds_detector_warm",(System.nanoTime()-start)/1e9).put("stages_seconds",times)
                .put("hybrid",boxes(panels)).put("cores",boxes(panels.map {it.readingOrderBounds ?: it})).put("geometry",boxes(geometry)).put("trace",trace)
                .put("joint_views",JSONArray(panels.mapIndexed {i,p ->listOf(i+1,p.jointFocus.map {target ->panels.indexOfFirst {(it.readingOrderBounds ?: it)==target}+1})}))
                .put("exclusions",JSONArray(panels.map {p->p.focusExclusions.map {e->listOf(e.left*bitmap.width,e.top*bitmap.height,e.right*bitmap.width,e.bottom*bitmap.height)}}))
                .put("predictions",JSONArray(predictions.map {d->JSONObject().put("text",d.text).put("confidence",d.confidence.toDouble()).put("box",JSONArray(listOf(d.bounds.left*bitmap.width,d.bounds.top*bitmap.height,d.bounds.right*bitmap.width,d.bounds.bottom*bitmap.height)))})))
            bitmap.recycle()
        } catch(e:Exception) {
            output.put(JSONObject().put("example",n).put("diagnostic_error",e.stackTraceToString()))
        }
        val result=File(instrument.targetContext.getExternalFilesDir(null),"qa/october-evening.json").apply {parentFile!!.mkdirs()};result.writeText(output.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-october-evening.json")).use {it.readBytes()}
    }
    @Test fun previewAfternoonScreenshots() {
        val instrument=InstrumentationRegistry.getInstrumentation();val output=JSONArray()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private/ai").orEmpty().contains("example-24.png"))
        for(n in 24..25) {
            val bitmap=instrument.context.assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
            val px=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(px,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val geometry=PanelGeometry.detect(px,bitmap.width,bitmap.height)
            val predictions=LocalPanelAi.detect(instrument.targetContext,bitmap)+if(geometry.size in 2..3)LocalPanelAi.detailProposals(instrument.targetContext,bitmap) else emptyList()
            val times=JSONObject();val start=System.nanoTime()
            val panels=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext) {stage,elapsed->times.put(stage,elapsed)},false)
            fun boxes(list:List<Panel>)=JSONArray(list.map {p->listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)})
            output.put(JSONObject().put("example",n).put("seconds_detector_warm",(System.nanoTime()-start)/1e9).put("stages_seconds",times)
                .put("hybrid",boxes(panels)).put("cores",boxes(panels.map {it.readingOrderBounds ?: it})).put("geometry",boxes(geometry)).put("geometryCores",boxes(geometry.map {it.readingOrderBounds ?: it}))
                .put("predictions",JSONArray(predictions.map {d->JSONObject().put("text",d.text).put("confidence",d.confidence.toDouble()).put("box",JSONArray(listOf(d.bounds.left*bitmap.width,d.bounds.top*bitmap.height,d.bounds.right*bitmap.width,d.bounds.bottom*bitmap.height)))})))
            bitmap.recycle()
        }
        val result=File(instrument.targetContext.getExternalFilesDir(null),"qa/october-afternoon.json").apply {parentFile!!.mkdirs()};result.writeText(output.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-october-afternoon.json")).use {it.readBytes()}
    }
    @get:org.junit.Rule val checks=org.junit.rules.ErrorCollector()
    @Test fun benchmarkPrivateExamples() = exportExamples(1..15,"ai-tflite.json")
    @Test fun previewOctoberScreenshots() = exportExamples(16..20,"october-detection.json")
    @Test fun exportFollowupNeuralEvidence() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private/ai").orEmpty().contains("example-21.png"))
        val bitmap=instrument.context.assets.open("private/ai/example-21.png").use {BitmapFactory.decodeStream(it)}
        val found=LocalPanelAi.detect(instrument.targetContext,bitmap)+LocalPanelAi.detailProposals(instrument.targetContext,bitmap)
        val output=JSONArray(found.map {d->JSONObject().put("text",d.text).put("confidence",d.confidence.toDouble()).put("box",JSONArray(listOf(d.bounds.left*bitmap.width,d.bounds.top*bitmap.height,d.bounds.right*bitmap.width,d.bounds.bottom*bitmap.height)))})
        val result=File(instrument.targetContext.getExternalFilesDir(null),"qa/followup-neural.json").apply {parentFile!!.mkdirs()};result.writeText(output.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-followup-neural.json")).use {it.readBytes()}
        bitmap.recycle()
    }
    @Test fun previewSecondOctoberScreenshots() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(instrument.context.assets.list("private/ai").orEmpty().contains("example-21.png"))
        val output=JSONArray()
        for(n in 21..23) {
            val bitmap=instrument.context.assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
            val times=JSONObject();val start=System.nanoTime()
            val panels=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext) {stage,elapsed->times.put(stage,elapsed)},false)
            assertEquals("Followup scenes",listOf(3,8,8)[n-21],panels.size)
            if(n==21) {
                assertTrue("Upward balloon belongs to second frame",panels[1].top*bitmap.height<625)
                assertTrue("Third frame includes the right balloon",panels[2].right*bitmap.width>670)
                assertTrue("First frame excludes the balloon owned by second",panels[0].focusExclusions.isNotEmpty())
            }
            output.put(JSONObject().put("example",n).put("seconds_detector_warm",(System.nanoTime()-start)/1e9).put("stages_seconds",times)
                .put("hybrid",JSONArray(panels.map {p->listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)}))
                .put("exclusions",JSONArray(panels.map {p->p.focusExclusions.map {e->listOf(e.left*bitmap.width,e.top*bitmap.height,e.right*bitmap.width,e.bottom*bitmap.height)}})))
            bitmap.recycle()
        }
        val result=File(instrument.targetContext.getExternalFilesDir(null),"qa/october-followup.json").apply {parentFile!!.mkdirs()}
        result.writeText(output.toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-october-followup.json")).use {it.readBytes()}
    }
    private fun exportExamples(examples:IntRange,filename:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val assets=instrumentation.context.assets
        val output=JSONArray()
        for(n in examples) {
            val stream=runCatching {assets.open("private/ai/example-$n.png")}.getOrNull() ?: continue
            val bitmap=stream.use {BitmapFactory.decodeStream(it)}
            val start=System.nanoTime()
            val found=LocalPanelAi.detect(instrumentation.targetContext,bitmap) + if(n>=13) LocalPanelAi.detailProposals(instrumentation.targetContext,bitmap) else emptyList()
            val seconds=(System.nanoTime()-start)/1e9
            val stageTimes=JSONObject()
            val detectorStart=System.nanoTime()
            val hybrid=BookRules.orderPanels(PanelDetector.detect(bitmap,instrumentation.targetContext) {stage,elapsed ->stageTimes.put(stage,elapsed)},false)
            val detectorSeconds=(System.nanoTime()-detectorStart)/1e9
            checks.checkSucceeds {
            if(n<=11) assertEquals("Hybrid panel count on approved fixture $n",listOf(4,5,10,7,4,7,7,4,8,7,7)[n-1],hybrid.size)
            if(n==4) {
                assertTrue("Central upper balloon is included",hybrid[3].top<.40f)
                assertTrue("Previous panel no longer includes the central balloon",hybrid[1].bottom<.43f)
                assertTrue("Right tall panel remains sixth",hybrid[5].left>.60f)
            }
            if(n==7) {
                assertTrue("Foot outside the lower frame is retained",hybrid[6].bottom>.98f)
                assertTrue("Cross-frame lettering is retained above lower left",hybrid[5].top<.70f)
            }
            if(n==10) {
                assertTrue("L-shaped scene includes its right continuation",hybrid[3].right>.96f && hybrid[3].bottom>.80f)
                assertTrue("Insets follow the L-shaped scene",hybrid[4].top>hybrid[3].top && hybrid[5].top>hybrid[4].top)
            }
            if(n==11) {
                assertTrue("Bottom inset follows its background scene",hybrid[6].left>.65f && hybrid[5].left<.05f)
                assertTrue("Final inset includes its speech balloon",hybrid[6].bottom>.93f)
            }
            if(n==12) {
                assertEquals("Inset column and lower reading sequence",9,hybrid.size)
                assertTrue("Three top-right inserts follow the large scene",(1..3).all {hybrid[it].left>.55f})
                assertTrue("Lower central speech belongs to the lower inset",hybrid[7].left<.36f && hybrid[7].top<.82f)
                assertTrue("Tall right panel follows both central panels",hybrid[8].left>.60f)
            }
            if(n==14) {
                assertEquals("User-confirmed ten panels, no invented eleventh",10,hybrid.size)
                assertTrue("Fourth panel includes both helmeted speakers",hybrid[3].right>.95f)
            }
            Unit
            }
            val hybridJson=JSONArray()
            hybrid.forEach {p ->hybridJson.put(JSONArray(listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)))}
            val px=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(px,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val edges=if(n>=13)PanelGeometry.detect(px,bitmap.width,bitmap.height,true) else emptyList()
            val detections=JSONArray()
            found.forEach {d ->detections.put(JSONObject().put("text",d.text).put("confidence",d.confidence.toDouble())
                .put("box",JSONArray(listOf(d.bounds.left*bitmap.width,d.bounds.top*bitmap.height,d.bounds.right*bitmap.width,d.bounds.bottom*bitmap.height))))}
            output.put(JSONObject().put("example",n).put("seconds_emulator",seconds).put("seconds_detector_warm",detectorSeconds).put("stages_seconds",stageTimes).put("detections",detections).put("hybrid",hybridJson).put("edges",JSONArray(edges.map {p ->listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)})).put("geometry",JSONArray(PanelDetector.detect(bitmap).map {p -> "${p.left},${p.top},${p.right},${p.bottom} core=${p.readingOrderBounds}"})))
            if(n in 16..20)checks.checkSucceeds {
                assertEquals("October general detector count",listOf(5,7,7,5,9)[n-16],hybrid.size)
                if(n==16) {assertTrue("Bottom left follows its black frame",hybrid[3].right*bitmap.width<380);assertTrue("Bottom right frame is complete",hybrid[4].left*bitmap.width<385 && hybrid[4].top*bitmap.height<702)}
                if(n==20) {
                    assertTrue("Upper caption belongs to sixth reading frame",hybrid[5].top*bitmap.height<=484 && hybrid[5].right*bitmap.width>=657)
                    assertTrue("Footer is not a protruding balloon",hybrid.last().bottom*bitmap.height<900)
                }
                Unit
            }
            if(n<=15)assertTrue("Model returns at least a proposal on fixture $n",found.any {!it.text})
            bitmap.recycle()
        }
        val dir=File(instrumentation.targetContext.getExternalFilesDir(null),"qa").apply {mkdirs()}
        val result=File(dir,filename)
        result.writeText(output.toString(2))
        // The test runner uninstalls the test app: retain measured output on the dedicated emulator.
        if(filename=="october-detection.json" || filename=="ai-tflite.json") {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-$filename")).use {it.readBytes()}
        }
    }
}
