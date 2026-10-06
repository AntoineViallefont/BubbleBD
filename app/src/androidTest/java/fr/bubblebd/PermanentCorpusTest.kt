package fr.bubblebd

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Private references are test inputs only; collect every failure before failing. */
class PermanentCorpusTest {
    @Test fun everyRetainedPageAndCorrection()=checkCorpus(false)
    @Test fun everyRetainedPageWithRectangularHistory()=checkCorpus(true)
    private fun checkCorpus(rectanglePrior:Boolean) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue("Private corpus intentionally omitted from public sources",
            instrument.context.assets.list("private").orEmpty().contains("corpus.json"))
        val entries=JSONObject(instrument.context.assets.open("private/corpus.json").bufferedReader().use {it.readText()}).getJSONArray("pages")
        val output=JSONArray();val failures=mutableListOf<String>()
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i);val n=entry.getInt("example")
            val row=JSONObject().put("example",n).put("expected_count",entry.getInt("expected_count")).put("known_failure",entry.getBoolean("known_failure"))
            try {
                val bitmap=instrument.context.assets.open("private/ai/example-$n.png").use {BitmapFactory.decodeStream(it)}
                try {
                    val times=JSONObject();val start=System.nanoTime()
                    val panels=BookRules.orderPanels(PanelDetector.detect(bitmap,instrument.targetContext,rectanglePrior=rectanglePrior) {stage,seconds ->times.put(stage,seconds)},entry.optString("reading_direction")=="right_to_left")
                    row.put("width",bitmap.width).put("height",bitmap.height).put("seconds",(System.nanoTime()-start)/1e9).put("stages_seconds",times)
                        .put("hybrid",JSONArray(panels.map {p ->listOf(p.left*bitmap.width,p.top*bitmap.height,p.right*bitmap.width,p.bottom*bitmap.height)}))
                        .put("cores",JSONArray(panels.map {p ->val c=p.readingOrderBounds ?: p;listOf(c.left*bitmap.width,c.top*bitmap.height,c.right*bitmap.width,c.bottom*bitmap.height)}))
                        .put("exclusions",JSONArray(panels.map {p ->p.focusExclusions.map {e ->listOf(e.left*bitmap.width,e.top*bitmap.height,e.right*bitmap.width,e.bottom*bitmap.height)}}))
                        .put("outlines",JSONArray(panels.map {p ->p.focusOutline.map {v ->listOf(v.x*bitmap.width,v.y*bitmap.height)}}))
                        .put("includes",JSONArray(panels.map {p ->p.focusIncludes.map {e ->listOf(e.left*bitmap.width,e.top*bitmap.height,e.right*bitmap.width,e.bottom*bitmap.height)}}))
                        .put("guided_views",JSONArray(panels.indices.map {anchor ->GuidedFrames.frame(panels,anchor,bitmap.width,bitmap.height,400,800).visible.map {it+1}}))
                    assertEquals("Page $n: nombre de cases",entry.getInt("expected_count"),panels.size)
                    fun panel(k:Int)=panels[k-1]
                    fun included(k:Int,l:Float,t:Float,r:Float,b:Float) {
                        val p=panel(k);assertTrue("Page $n case $k tronquée : zone attendue [$l,$t,$r,$b]",p.left*bitmap.width<=l && p.top*bitmap.height<=t && p.right*bitmap.width>=r && p.bottom*bitmap.height>=b)
                    }
                    when(n) {
                        3 ->assertTrue("Bulle complète",panel(5).right*bitmap.width>540)
                        4 -> {assertTrue(panel(4).top<.40f);assertTrue(panel(2).bottom<.43f);assertTrue(panel(6).left>.60f)}
                        7 -> {assertTrue(panel(7).bottom>.98f);assertTrue(panel(6).top<.70f)}
                        10 -> {assertTrue(panel(4).right>.96f && panel(4).bottom>.80f);assertTrue(panel(5).top>panel(4).top && panel(6).top>panel(5).top)}
                        11 -> {assertTrue(panel(7).left>.65f && panel(6).left<.05f);assertTrue(panel(7).bottom>.93f)}
                        12 -> {assertTrue((2..4).all {panel(it).left>.55f});assertTrue(panel(8).left<.36f && panel(8).top<.82f);assertTrue(panel(9).left>.60f)}
                        14 ->assertTrue("Deux interlocuteurs dans la quatrième case",panel(4).right>.95f)
                        15 -> {
                            val inset=panels.single {it.width*bitmap.width<200 && it.top*bitmap.height<120 && it.bottom*bitmap.height<270}
                            assertTrue("Encart entier",inset.left*bitmap.width<=22 && inset.top*bitmap.height<=108 && inset.right*bitmap.width>=194 && inset.bottom*bitmap.height>=250)
                            assertTrue("Contour propre à l’encart",inset.right*bitmap.width<200)
                            val scene=panels.single {it.width*bitmap.width>450 && it.top*bitmap.height<100}
                            assertTrue("Grande scène entière",scene.left*bitmap.width<=43 && scene.right*bitmap.width>=548 && scene.bottom*bitmap.height>=291)
                            assertTrue("Éclairage indépendant",scene.focusExclusions.any {it.contains(.18f,.22f)})
                        }
                        16 -> {assertTrue(panel(4).right*bitmap.width<380);assertTrue(panel(5).left*bitmap.width<385 && panel(5).top*bitmap.height<702)}
                        20 -> {assertTrue(panel(6).top*bitmap.height<=484 && panel(6).right*bitmap.width>=657);assertTrue(panel(9).bottom*bitmap.height<900)}
                        21 -> {assertTrue(panel(2).top*bitmap.height<625);assertTrue(panel(3).right*bitmap.width>670);assertTrue(panel(1).focusExclusions.isNotEmpty())}
                        24 -> {included(1,65f,45f,335f,398f);included(4,347f,414f,640f,599f);included(6,406f,614f,644f,877f)}
                        26 -> {
                            assertTrue("Scène de fond entière",panels.any {it.left*bitmap.width<=20 && it.top*bitmap.height<=5 && it.right*bitmap.width>=575 && it.bottom*bitmap.height>=770})
                            assertTrue("Deux encarts au-dessus du fond",panels.count {it.width>.6f && it.height<.2f && it.bottom*bitmap.height<260}==2)
                        }
                        27 -> {assertTrue("Dernier bandeau entier",panels.any {val c=it.readingOrderBounds ?: it;c.top*bitmap.height>=610 && it.left*bitmap.width<=175 && it.right*bitmap.width>=545 && it.bottom*bitmap.height>=718})}
                        28 -> {
                            assertTrue("Deux toilettes indépendantes",panels.count {it.top*bitmap.height in 280f..315f && it.bottom*bitmap.height in 450f..480f && it.width<.4f}==2)
                            assertTrue("Gymnase et décor derrière les encarts",panels.any {it.top*bitmap.height<=260 && it.bottom*bitmap.height>=770 && it.width>.9f})
                        }
                        29 -> {
                            assertTrue("Illustration debout complète",panels.any {it.left*bitmap.width<=55 && it.right*bitmap.width>=225 && it.top*bitmap.height<=390 && it.bottom*bitmap.height>=770})
                            assertTrue("Trois cases à droite",panels.count {val c=it.readingOrderBounds ?: it;c.left*bitmap.width in 225f..245f && c.top*bitmap.height>=380 && c.width>.5f}==3)
                        }
                        35 -> {
                            assertTrue("Bulle supérieure exclue de la case de droite",panel(4).focusExclusions.any {it.contains(295f/bitmap.width,380f/bitmap.height)})
                            assertTrue("Bulle inférieure exclue de la case de gauche",panel(3).focusExclusions.any {it.contains(300f/bitmap.width,450f/bitmap.height)})
                        }
                        37 -> {
                            assertTrue("La bulle du phacochère appartient à la première case",panel(1).bottom*bitmap.height>=261)
                            assertTrue("La quatrième case ne prend pas la bulle supérieure",panel(4).top*bitmap.height>245)
                            assertTrue("Le fond reste derrière ses encarts",panel(5).focusExclusions.size>=2)
                        }
                        38 ->assertTrue("Fond unique derrière les encarts",panel(3).focusExclusions.size>=2)
                        39 -> {
                            assertEquals("Contour incliné des enfants",4,panel(2).focusOutline.size)
                            assertEquals("Contour incliné du robot",4,panel(3).focusOutline.size)
                            val a=panel(2).focusOutline;val b=panel(3).focusOutline
                            assertTrue("La séparation descend à gauche",a[3].y>a[2].y && b[0].y>b[1].y)
                            assertTrue("La dernière bulle reste entière avec les enfants",panel(5).bottom*bitmap.height>=884)
                        }
                        25 -> {included(1,7f,2f,712f,235f);included(2,116f,280f,350f,452f);included(3,425f,278f,550f,450f);included(4,97f,475f,618f,666f);included(7,273f,680f,357f,884f);assertTrue("Encart séparé",panel(5).left>.70f && panel(5).height<.20f)}
                    }
                    if(entry.has("reference_asset")) {
                        val reference=JSONObject(instrument.context.assets.open("private/"+entry.getString("reference_asset")).bufferedReader().use {it.readText()})
                        val amendment=entry.optString("focus_amendment_asset").takeIf {it.isNotEmpty()}?.let {name->JSONObject(instrument.context.assets.open("private/$name").bufferedReader().use {it.readText()})}
                        val optional=amendment?.optJSONArray("optional_background_panels")?.let {a->(0 until a.length()).map {a.getInt(it)}.toSet()} ?: emptySet()
                        row.put("approved_focus_amendment",amendment ?: JSONObject.NULL)
                        val historic=JSONArray();row.put("original_envelope_mismatches",historic)
                        val renderReference=JSONObject(reference.toString())
                        // Extra technical check: the camera may include a neighbouring strip,
                        // but only the overflowing balloon should regain its brightness.
                        if(n==55)renderReference.put("required_dimmed_regions",JSONArray().put(
                            JSONObject().put("panel",2).put("box",JSONArray(listOf(145,150,190,185)))))
                        if(n==59)renderReference.put("required_dimmed_regions",JSONArray().put(
                            JSONObject().put("panel",7).put("box",JSONArray(listOf(446,610,458,635)))))
                        val native=NativeFocusQa.measure(bitmap,panels,renderReference,n)
                        row.put("native_focus",native.first)
                        val boxes=reference.getJSONArray("panels");val frames=reference.optJSONArray("frames") ?: boxes
                        for(k in 0 until boxes.length()) {
                            val box=boxes.getJSONArray(k);val p=panels[k]
                            // Four pixels accommodate the antialiased outline of the manual annotation.
                            val originalMatches=p.left*bitmap.width<=box.getDouble(0)+4 && p.top*bitmap.height<=box.getDouble(1)+4 && p.right*bitmap.width>=box.getDouble(2)-4 && p.bottom*bitmap.height>=box.getDouble(3)-4
                            if(!originalMatches)historic.put(k+1)
                            // Explicit user amendments change only optional background, never
                            // physical contours or speech assertions. Keep the old result visible.
                            if(k+1 in optional) {
                                if(reference.optInt("background_panel",-1)==k+1) {
                                    val speech=reference.optJSONArray("required_speech_regions")
                                    assertTrue("Page $n case ${k+1} : une bulle validée est obligatoire pour accepter un fond partiel",speech!=null && (0 until speech.length()).any {speech.getJSONObject(it).getInt("panel")==k+1})
                                } else {
                                    val physical=frames.getJSONArray(k)
                                    assertTrue("Page $n case ${k+1} : cadre physique tronqué malgré le fond facultatif",p.left*bitmap.width<=physical.getDouble(0)+4 && p.top*bitmap.height<=physical.getDouble(1)+4 && p.right*bitmap.width>=physical.getDouble(2)-4 && p.bottom*bitmap.height>=physical.getDouble(3)-4)
                                }
                            } else assertTrue("Page $n case ${k+1} : enveloppe validée tronquée",originalMatches)
                            if(reference.optInt("background_panel",-1)==k+1)continue
                            val f=frames.getJSONArray(k);val c=p.readingOrderBounds ?: p
                            val l=f.getDouble(0);val t=f.getDouble(1);val r=f.getDouble(2);val b=f.getDouble(3)
                            val intersection=kotlin.math.max(0.0,kotlin.math.min(c.right*bitmap.width.toDouble(),r)-kotlin.math.max(c.left*bitmap.width.toDouble(),l))*kotlin.math.max(0.0,kotlin.math.min(c.bottom*bitmap.height.toDouble(),b)-kotlin.math.max(c.top*bitmap.height.toDouble(),t))
                            val union=c.width*c.height*bitmap.width*bitmap.height+(r-l)*(b-t)-intersection
                            assertTrue("Page $n case ${k+1} : contour ou ordre différent de la référence",intersection/union>.80)
                        }
                        reference.optJSONArray("required_speech_regions")?.let {regions ->
                            for(k in 0 until regions.length()) {
                                val region=regions.getJSONObject(k);val index=region.getInt("panel")-1
                                val box=region.getJSONArray("box");val p=panels[index];val c=p.readingOrderBounds ?: p
                                val l=box.getDouble(0);val t=box.getDouble(1);val r=box.getDouble(2);val b=box.getDouble(3)
                                fun covers(q:Panel)=q.left*bitmap.width<=l+4 && q.top*bitmap.height<=t+4 && q.right*bitmap.width>=r-4 && q.bottom*bitmap.height>=b-4
                                assertTrue("Page $n case ${index+1} : bulle entière dans le cadrage",covers(GuidedFrames.focusBounds(p)))
                                val cx=((l+r)/2/bitmap.width).toFloat();val cy=((t+b)/2/bitmap.height).toFloat()
                                assertTrue("Page $n case ${index+1} : bulle visible, attribution incorrecte ou masquée",p.focusIncludes.any(::covers) || (covers(c) && p.focusOutline.isEmpty() && p.focusExclusions.none {it.contains(cx,cy)}))
                            }
                        }
                        reference.optJSONArray("required_rectangular_panels")?.let {indices ->
                            for(k in 0 until indices.length())assertTrue("Page $n case ${indices.getInt(k)} : rectangle requis",panels[indices.getInt(k)-1].focusOutline.isEmpty())
                        }
                        reference.optJSONArray("outlines")?.let {shapes ->
                            fun inside(x:Double,y:Double,vertices:List<Pair<Double,Double>>):Boolean {
                                var hit=false;var j=vertices.lastIndex
                                for(v in vertices.indices) {
                                    val (ax,ay)=vertices[v];val (bx,by)=vertices[j]
                                    if((ay>y)!=(by>y) && x<(bx-ax)*(y-ay)/(by-ay)+ax)hit=!hit
                                    j=v
                                }
                                return hit
                            }
                            for(k in 0 until shapes.length()) {
                                val shape=shapes.getJSONArray(k);if(shape.length()==0)continue
                                val actual=panels[k].focusOutline
                                assertEquals("Page $n case ${k+1} : contour incliné requis",shape.length(),actual.size)
                                val wanted=(0 until shape.length()).map {v->shape.getJSONArray(v).let {it.getDouble(0) to it.getDouble(1)}}
                                val measured=actual.map {it.x*bitmap.width.toDouble() to it.y*bitmap.height.toDouble()}
                                var intersection=0;var union=0
                                for(y in 0 until bitmap.height step 4)for(x in 0 until bitmap.width step 4) {
                                    val a=inside(x+2.0,y+2.0,wanted);val b=inside(x+2.0,y+2.0,measured)
                                    if(a || b)union++;if(a && b)intersection++
                                }
                                assertTrue("Page $n case ${k+1} : forme inclinée différente de la référence",union>0 && intersection.toDouble()/union>.90)
                            }
                        }
                        reference.optJSONArray("required_joint_views")?.let {pairs ->
                            for(k in 0 until pairs.length()) {
                                val pair=pairs.getJSONArray(k);val a=pair.getInt(0)-1;val b=pair.getInt(1)-1
                                for(anchor in listOf(a,b)) {
                                    val frame=GuidedFrames.frame(panels,anchor,bitmap.width,bitmap.height,400,800)
                                    assertEquals("Page $n : les deux cases ambiguës doivent être affichées et comptées",listOf(a,b),frame.visible)
                                    assertTrue(frame.bounds.width*bitmap.width*frame.scale<=400.1f && frame.bounds.height*bitmap.height*frame.scale<=800.1f)
                                }
                            }
                        }
                        assertTrue("Éclairage natif : ${native.second.joinToString()}",native.second.isEmpty())
                        reference.optJSONArray("required_separate_pairs")?.let {pairs ->
                            for(k in 0 until pairs.length()) {
                                val pair=pairs.getJSONArray(k);val a=pair.getInt(0)-1;val b=pair.getInt(1)-1
                                assertFalse("Page $n : cases non consécutives à garder séparées",b in GuidedFrames.frame(panels,a,bitmap.width,bitmap.height,400,800).visible)
                                assertFalse("Page $n : cases non consécutives à garder séparées",a in GuidedFrames.frame(panels,b,bitmap.width,bitmap.height,400,800).visible)
                            }
                        }
                    }
                    // Existing balloons/captions beside straight gutters must not
                    // acquire a speaker across the gutter from its border alone.
                    val borderPair=when(n) {32 -> 2 to 3;51 -> 3 to 4;else -> null}
                    borderPair?.let {(a,b) ->
                        assertFalse("Page $n : une bordure ne constitue pas une queue de bulle",b in GuidedFrames.frame(panels,a,bitmap.width,bitmap.height,400,800).visible)
                        assertFalse("Page $n : conserver les vues historiques séparées",a in GuidedFrames.frame(panels,b,bitmap.width,bitmap.height,400,800).visible)
                    }
                    row.put("passed",true)
                } finally {bitmap.recycle()}
            } catch(error:Throwable) {
                val message="Page $n : ${error.message ?: error.javaClass.simpleName}"
                row.put("passed",false).put("failure",message);failures.add(message)
            }
            output.put(row)
            android.util.Log.i("BubbleCorpus","Page $n : ${row.optBoolean("passed")}")
        }
        val suffix=if(rectanglePrior)"-rectangular" else ""
        val result=File(instrument.targetContext.getExternalFilesDir(null),"qa/permanent-corpus$suffix.json").apply {parentFile!!.mkdirs()}
        result.writeText(JSONObject().put("pages",output).put("failures",JSONArray(failures)).toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-permanent-corpus$suffix.json")).use {it.readBytes()}
        val nativeDirectory=File(instrument.targetContext.getExternalFilesDir(null),"qa/native-focus")
        for(command in listOf("mkdir -p /sdcard/Download/bubble-native-focus","cp -r ${nativeDirectory.absolutePath}/. /sdcard/Download/bubble-native-focus/"))
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use {it.readBytes()}
        assertTrue("Échecs conservés dans le rapport :\n${failures.joinToString("\n")}",failures.isEmpty())
    }
}
