package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Evidence from production archive decoding, detection and ComicView gestures.
 * Missing annotations remain explicitly unverified, never inferred from output. */
class ReaderCorpusAuditTest {
    @Test fun allReferencesThroughDocumentAndReader() {
        val runId=java.util.UUID.randomUUID().toString()
        val instrument=InstrumentationRegistry.getInstrumentation()
        val assets=instrument.context.assets
        org.junit.Assume.assumeTrue(assets.list("private").orEmpty().contains("reader-audit"))
        val entries=JSONObject(assets.open("private/reader-audit/manifest.json").bufferedReader().use {it.readText()}).getJSONArray("pages")
        val selected=InstrumentationRegistry.getArguments().getString("corpusExamples")?.split(",")?.map {it.toInt()}?.toSet()
        val output=JSONArray();val failures=mutableListOf<String>()
        val directory=File(instrument.targetContext.getExternalFilesDir(null),"qa/reader-corpus").apply {deleteRecursively();mkdirs()}
        for(k in 0 until entries.length()) {
            val entry=entries.getJSONObject(k);val n=entry.getInt("example")
            if(selected!=null && n !in selected)continue
            val source=assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
            val reference=entry.optString("reference").takeIf {it.isNotEmpty()}?.let {name ->JSONObject(assets.open("private/reader-audit/$name").bufferedReader().use {it.readText()})}
            try {for(target in listOf(source.width,720,1800).distinct()) {
                val raster=if(target==source.width)source else Bitmap.createScaledBitmap(source,target,(source.height*target.toFloat()/source.width).roundToInt(),true)
                val archive=File(instrument.targetContext.cacheDir,"corpus-reader.cbz")
                try {
                    ZipOutputStream(archive.outputStream()).use {zip ->zip.putNextEntry(ZipEntry("001.png"));raster.compress(Bitmap.CompressFormat.PNG,100,zip);zip.closeEntry()}
                    ComicDocument(archive).use {document ->
                        val image=document.bitmap(0)
                        try {for(prior in listOf(false,true)) {
                            val row=JSONObject().put("example",n).put("target_width",target).put("prior",prior)
                                .put("source_kind","preserved raster in generated CBZ; resizing adds no original detail")
                                .put("phone_verified",false).put("original_document_verified",false)
                            val issues=mutableListOf<String>()
                            val started=System.nanoTime()
                            val panels=BookRules.orderPanels(PanelDetector.detect(image,instrument.targetContext,rectanglePrior=prior),entry.optString("reading_direction")=="right_to_left")
                            row.put("seconds",(System.nanoTime()-started)/1e9).put("count",panels.size).put("expected_count",entry.getInt("expected_count"))
                                .put("width",image.width).put("height",image.height)
                                .put("frames",JSONArray(panels.map {val c=it.readingOrderBounds ?: it;listOf(c.left*image.width,c.top*image.height,c.right*image.width,c.bottom*image.height)}))
                                .put("outlines",JSONArray(panels.map {p ->p.focusOutline.map {v ->listOf(v.x*image.width,v.y*image.height)}}))
                            if(panels.size!=entry.getInt("expected_count"))issues.add("Nombre ${panels.size}/${entry.getInt("expected_count")}")
                            val frames=reference?.optJSONArray("frames")
                            row.put("physical_reference_available",frames!=null).put("speech_reference_available",reference?.optJSONArray("required_speech_regions")!=null)
                                .put("dimming_reference_available",reference?.optJSONArray("required_dimmed_regions")!=null)
                            if(frames!=null && panels.size==frames.length())for(i in panels.indices)if(i+1!=reference.optInt("background_panel",-1)) {
                                val box=frames.getJSONArray(i);val c=panels[i].readingOrderBounds ?: panels[i]
                                val expected=Panel((box.getDouble(0)/source.width).toFloat(),(box.getDouble(1)/source.height).toFloat(),(box.getDouble(2)/source.width).toFloat(),(box.getDouble(3)/source.height).toFloat())
                                val intersection=max(0f,min(c.right,expected.right)-max(c.left,expected.left))*max(0f,min(c.bottom,expected.bottom)-max(c.top,expected.top))
                                val iou=intersection/(c.width*c.height+expected.width*expected.height-intersection)
                                if(iou<.80f)issues.add("Contour/ordre case ${i+1}: IoU=$iou")
                            }
                            if(reference!=null && panels.size==entry.getInt("expected_count")) {
                                reference.optJSONArray("required_rectangular_panels")?.let {indices ->for(j in 0 until indices.length()) {
                                    val index=indices.getInt(j)-1
                                    if(panels[index].focusOutline.isNotEmpty())issues.add("Case ${index+1}: rectangle requis")
                                }}
                                // Repeat the historical polygon criterion at every reader
                                // resolution; a correct bounding box is not a correct contour.
                                reference.optJSONArray("outlines")?.let {shapes ->for(i in 0 until shapes.length()) {
                                    val shape=shapes.getJSONArray(i);if(shape.length()==0)continue
                                    val actual=panels[i].focusOutline
                                    if(actual.size!=shape.length()) {
                                        issues.add("Case ${i+1}: contour requis ${shape.length()} sommets, ${actual.size} mesurés")
                                        continue
                                    }
                                    val expected=(0 until shape.length()).map {j ->shape.getJSONArray(j).let {it.getDouble(0) to it.getDouble(1)}}
                                    val measured=actual.map {it.x*source.width.toDouble() to it.y*source.height.toDouble()}
                                    fun inside(x:Double,y:Double,vertices:List<Pair<Double,Double>>):Boolean {
                                        var hit=false;var j=vertices.lastIndex
                                        for(k in vertices.indices) {
                                            val (ax,ay)=vertices[k];val (bx,by)=vertices[j]
                                            if((ay>y)!=(by>y) && x<(bx-ax)*(y-ay)/(by-ay)+ax)hit=!hit
                                            j=k
                                        }
                                        return hit
                                    }
                                    var intersection=0;var union=0
                                    for(y in 0 until source.height step 4)for(x in 0 until source.width step 4) {
                                        val a=inside(x+2.0,y+2.0,expected);val b=inside(x+2.0,y+2.0,measured)
                                        if(a || b)union++;if(a && b)intersection++
                                    }
                                    if(union==0 || intersection.toDouble()/union<=.90)issues.add("Case ${i+1}: contour polygonal différent de la référence")
                                }}
                                for((key,joint) in listOf("required_joint_pairs" to true,"required_separate_pairs" to false))reference.optJSONArray(key)?.let {pairs ->for(j in 0 until pairs.length()) {
                                    val pair=pairs.getJSONArray(j);val a=pair.getInt(0)-1;val b=pair.getInt(1)-1
                                    val common=panels.indices.any {anchor ->val visible=GuidedFrames.frame(panels,anchor,image.width,image.height,600,900).visible;a in visible && b in visible}
                                    if(common!=joint)issues.add("Vue ${pair.getInt(0)}+${pair.getInt(1)}: commune=$common attendu=$joint")
                                }}
                                val scaled=JSONObject(reference.toString())
                                for(key in listOf("required_speech_regions","required_dimmed_regions"))scaled.optJSONArray(key)?.let {regions ->for(j in 0 until regions.length()) {
                                    val box=regions.getJSONObject(j).getJSONArray("box")
                                    for(axis in 0..3)box.put(axis,box.getDouble(axis)*(if(axis%2==0)image.width.toDouble()/source.width else image.height.toDouble()/source.height))
                                }}
                                val (metrics,nativeFailures)=NativeFocusQa.measure(image,panels,scaled,n)
                                row.put("native_regions",metrics);issues.addAll(nativeFailures)
                            }
                            val views=JSONArray()
                            instrument.runOnMainSync {
                                val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=60;rtl=entry.optString("reading_direction")=="right_to_left";layout(0,0,600,900)}
                                var selected=-1;var visible=emptyList<Int>();var leave=false
                                view.onPanelChanged={index,_->selected=index};view.onPanelSelectionChanged={indices,_->visible=indices.orEmpty()};view.onGuidedPage={leave=true}
                                view.setPage(image,panels,0)
                                val sheet=Bitmap.createBitmap(600,300*max(1,(panels.size+2)/3),Bitmap.Config.ARGB_8888)
                                val canvas=Canvas(sheet);val snapshot=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
                                var steps=0
                                while(selected in panels.indices && !leave && steps<=panels.size) {
                                    val before=selected;views.put(JSONArray(visible.map {it+1}));view.draw(Canvas(snapshot))
                                    canvas.drawBitmap(snapshot,null,android.graphics.Rect((steps%3)*200,(steps/3)*300,(steps%3+1)*200,(steps/3+1)*300),null)
                                    val start=SystemClock.uptimeMillis();val from=if(view.rtl)100f else 500f;val to=600f-from
                                    for((action,time,x) in listOf(Triple(MotionEvent.ACTION_DOWN,start,from),Triple(MotionEvent.ACTION_MOVE,start+80,(from+to)/2),Triple(MotionEvent.ACTION_UP,start+160,to))) {
                                        val event=MotionEvent.obtain(start,time,action,x,450f,0);view.onTouchEvent(event);event.recycle()
                                    }
                                    if(!leave && selected<=before) {issues.add("Glissement bloqué après case ${before+1}");break}
                                    steps++
                                }
                                if(panels.any {!it.isWholePage} && !leave)issues.add("Fin du parcours de lecture non atteinte")
                                File(directory,"$n-$target-$prior.png").outputStream().use {sheet.compress(Bitmap.CompressFormat.PNG,100,it)}
                                view.setPage(null,emptyList());snapshot.recycle();sheet.recycle()
                            }
                            row.put("reader_views",views).put("failures",JSONArray(issues)).put("passed_checked_criteria",issues.isEmpty())
                            output.put(row);failures.addAll(issues.map {"Planche $n largeur $target priorité $prior: $it"})
                        }}finally {image.recycle()}
                    }
                }finally {archive.delete();if(raster!==source)raster.recycle()}
            }}finally {source.recycle()}
        }
        val report=JSONObject().put("run_id",runId).put("version_name",BuildConfig.VERSION_NAME).put("created_at_ms",System.currentTimeMillis()).put("rows",output).put("failures",JSONArray(failures)).put("complete_user_validation",false).put("selected_examples",selected?.let {JSONArray(it.toList())} ?: JSONObject.NULL)
        File(directory,"report.json").writeText(report.toString(2))
        for(command in listOf("rm -rf /sdcard/Download/bubble-reader-corpus","cp -r ${directory.absolutePath} /sdcard/Download/bubble-reader-corpus"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
}
