package fr.bubblebd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureTest {
    @Test fun foregroundHandIsLitOnlyDuringItsOwnCase() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val hand=Panel(.3f,.35f,.4f,.52f,focusOutline=listOf(PanelPoint(.3f,.35f),PanelPoint(.4f,.35f),PanelPoint(.4f,.52f),PanelPoint(.3f,.52f)),foregroundOverhang=true)
            val upper=Panel(.1f,.1f,.9f,.5f,focusExclusions=listOf(hand))
            val physical=Panel(.1f,.5f,.9f,.9f)
            val lower=physical.copy(top=.35f,readingOrderBounds=physical,focusOutline=listOf(PanelPoint(.1f,.5f),PanelPoint(.9f,.5f),PanelPoint(.9f,.9f),PanelPoint(.1f,.9f)),focusIncludes=listOf(hand),focusExclusions=listOf(upper))
            val frames=listOf(upper,lower)
            val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            fun colour(index:Int,x:Float,y:Float):Int {
                val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,400,600)}
                var selected:Int?=null
                view.onPanelChanged={active,_->selected=active}
                view.setPage(image,frames,if(index==1)-1 else 0)
                assertEquals("Le test doit afficher la case demandée",index,selected)
                view.draw(Canvas(output))
                val frame=GuidedFrames.frame(frames,index,400,600,400,600)
                val dx=(400-frame.bounds.width*400*frame.scale)/2-frame.bounds.left*400*frame.scale
                val dy=(600-frame.bounds.height*600*frame.scale)/2-frame.bounds.top*600*frame.scale
                return output.getPixel((dx+x*400*frame.scale).toInt(),(dy+y*600*frame.scale).toInt())
            }
            assertEquals(Color.BLUE,colour(1,.35f,.42f))
            assertEquals(Color.BLACK,colour(1,.7f,.42f))
            assertEquals(Color.BLACK,colour(0,.35f,.42f))
            assertEquals(Color.BLUE,colour(0,.7f,.42f))
            image.recycle();output.recycle()
        }
    }
    @Test fun artworkBehindAnInsetStaysDimButSharedSpeechRemainsVisible() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            Canvas(image).drawRect(260f,180f,300f,240f,Paint().apply {color=Color.YELLOW})
            val physical=Panel(.1f,.2f,.55f,.65f)
            val peer=Panel(.6f,.2f,.9f,.65f)
            val art=Panel(.45f,.4f,.8f,.9f,focusOutline=listOf(PanelPoint(.45f,.4f),PanelPoint(.8f,.4f),PanelPoint(.8f,.9f),PanelPoint(.45f,.9f)))
            val owner=Panel(.1f,.2f,.8f,.9f,readingOrderBounds=physical,focusOutline=listOf(PanelPoint(.1f,.2f),PanelPoint(.55f,.2f),PanelPoint(.55f,.65f),PanelPoint(.1f,.65f)),focusIncludes=listOf(art,Panel(.65f,.3f,.75f,.4f)),focusExclusions=listOf(peer))
            val frames=listOf(owner,peer)
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,400,600)}
            view.setPage(image,frames,0)
            val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);view.draw(Canvas(output))
            val frame=GuidedFrames.frame(frames,0,400,600,400,600)
            val dx=(400-frame.bounds.width*400*frame.scale)/2-frame.bounds.left*400*frame.scale
            val dy=(600-frame.bounds.height*600*frame.scale)/2-frame.bounds.top*600*frame.scale
            fun colour(x:Float,y:Float)=output.getPixel((dx+x*400*frame.scale).toInt(),(dy+y*600*frame.scale).toInt())
            assertEquals(Color.BLUE,colour(.25f,.35f))
            assertEquals("Le débordement extérieur reste éclairé",Color.BLUE,colour(.7f,.8f))
            assertEquals("L'encart masque le dessin derrière lui",Color.BLACK,colour(.7f,.55f))
            assertEquals("La bulle partagée reste entière",Color.YELLOW,colour(.7f,.35f))
            image.recycle();output.recycle()
        }
    }
    @Test fun preparedGuidedPageReusesItsImageAndCases()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val image=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        val frames=listOf(Panel(0f,0f,.5f,1f),Panel(.5f,0f,1f,1f))
        var decodes=0;var detections=0;var publications=0
        val prepared=ReaderPrefetch(this,load={decodes++;image},detect={_,_ ->detections++;frames})
        prepared.prepare(1);kotlinx.coroutines.yield()
        val page=prepared.take(1)!!
        loadReaderPage(true,null,{page.image()!!},{page.frames()!!},{bitmap,panels ->
            publications++
            instrument.runOnMainSync {
                val view=ComicView(instrument.targetContext).apply {layout(0,0,100,300)}
                view.setPage(bitmap,panels,0)
                assertTrue(view.guidedReading)
            }
        })
        assertEquals(1,decodes);assertEquals(1,detections);assertEquals(1,publications)
        image.recycle()
    }

    @Test fun preparedFullPageDoesNotWaitForItsRunningAnalysis()=runBlocking {
        val image=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888)
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        val prefetch=ReaderPrefetch(this,load={image},detect={_,_->entered.complete(Unit);release.await();listOf(Panel(0f,0f,1f,1f))})
        prefetch.prepare(1);entered.await();val prepared=prefetch.take(1)!!
        var publications=0
        val load=launch(start=CoroutineStart.UNDISPATCHED) {
            loadReaderPage(false,null,{prepared.image()!!},{prepared.frames()!!},{bitmap,panels->
                assertSame(image,bitmap);publications++
                if(publications==1)assertTrue(panels.isEmpty())
            })
        }
        assertEquals("Prepared image appears while cases are still computing",1,publications)
        assertFalse(load.isCompleted)
        release.complete(Unit);load.join();assertEquals(2,publications)
        prepared.cancel();image.recycle()
    }
    @Test fun slantedCaseKeepsItsBalloonBrightAndDimsTheNeighbourCorner() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val outline=listOf(PanelPoint(0f,0f),PanelPoint(1f,0f),PanelPoint(1f,.5f),PanelPoint(0f,1f))
            val frame=Panel(0f,0f,.999f,1f,focusOutline=outline,focusIncludes=listOf(Panel(.8f,.8f,.95f,.95f)))
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,400,400)}
            view.setPage(image,listOf(frame),0)
            val output=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);view.draw(Canvas(output))
            assertEquals("La case reste éclairée",Color.BLUE,output.getPixel(100,100))
            assertEquals("Le coin appartenant à l’autre case est assombri",Color.BLACK,output.getPixel(350,280))
            assertEquals("La bulle débordante reste éclairée",Color.BLUE,output.getPixel(350,350))
            image.recycle();output.recycle()
        }
    }
    @Test fun guidedPageWaitsForCasesAndReplacesThePreviousFocusDirectly()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        lateinit var view:ComicView
        val frames=listOf(Panel(0f,0f,.48f,1f),Panel(.52f,0f,1f,1f))
        val old=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.YELLOW)}
        val next=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        Canvas(next).drawRect(0f,0f,192f,600f,Paint().apply {color=Color.RED})
        val output=Bitmap.createBitmap(200,600,Bitmap.Config.ARGB_8888)
        val selections=mutableListOf<List<Int>?>()
        instrument.runOnMainSync {
            view=ComicView(instrument.targetContext).apply {outsideDim=100;layout(0,0,200,600)}
            view.setPage(old,frames,-1)
            view.onPanelSelectionChanged={group,_->selections.add(group)}
        }
        val ready=CompletableDeferred<List<Panel>>()
        var detections=0;var publications=0
        val loading=launch(start=CoroutineStart.UNDISPATCHED) {
            loadReaderPage(true,null,{next},{detections++;ready.await()},{image,panels ->
                publications++
                instrument.runOnMainSync {view.setPage(image,panels,0)}
            })
        }
        assertEquals(1,detections);assertEquals(0,publications);assertTrue(selections.isEmpty())
        instrument.runOnMainSync {view.draw(Canvas(output));assertTrue(view.guidedReading)}
        assertEquals("Keep the old case while detection is pending",Color.YELLOW,output.getPixel(100,300))
        ready.complete(frames);loading.join()
        assertEquals(1,publications)
        assertEquals("Never announce a full-page frame between cases",listOf(listOf(0)),selections)
        instrument.runOnMainSync {view.draw(Canvas(output))}
        assertEquals("First rendered frame is the first case",Color.RED,output.getPixel(100,300))
        selections.clear()
        loadReaderPage(true,frames,{next.copy(Bitmap.Config.ARGB_8888,false)},{error("Cached page must not run detection")},{image,panels ->
            instrument.runOnMainSync {view.setPage(image,panels,-1);view.draw(Canvas(output))}
        })
        assertEquals("Going backwards selects the last case",listOf(listOf(1)),selections)
        assertEquals(Color.BLUE,output.getPixel(100,300))
        old.recycle();next.recycle();output.recycle()
    }

    @Test fun fullPageStillAppearsBeforeDetectionAndCachedPagesSkipIt()=runBlocking {
        val image=Bitmap.createBitmap(20,30,Bitmap.Config.ARGB_8888)
        val frames=listOf(Panel(0f,0f,.5f,1f))
        val ready=CompletableDeferred<List<Panel>>()
        val publications=mutableListOf<List<Panel>>()
        val loading=launch(start=CoroutineStart.UNDISPATCHED) {
            loadReaderPage(false,null,{image},{ready.await()},{_,panels ->publications.add(panels)})
        }
        assertEquals(listOf(emptyList<Panel>()),publications)
        ready.complete(frames);loading.join()
        assertEquals(listOf(emptyList<Panel>(),frames),publications)
        publications.clear()
        loadReaderPage(false,frames,{image},{error("Cached detection must be reused")},{_,panels ->publications.add(panels)})
        assertEquals(listOf(frames),publications)
        image.recycle()
    }

    @Test fun cancelledGuidedLoadDoesNotReplaceTheDisplayedPage()=runBlocking {
        val image=Bitmap.createBitmap(20,30,Bitmap.Config.ARGB_8888)
        val ready=CompletableDeferred<List<Panel>>()
        var publications=0
        val loading=launch(start=CoroutineStart.UNDISPATCHED) {
            loadReaderPage(true,null,{image},{ready.await()},{_,_->publications++})
        }
        loading.cancel();loading.join()
        ready.complete(listOf(Panel(0f,0f,.5f,1f)))
        assertEquals("An obsolete load cannot clear the current focus",0,publications)
        image.recycle()
    }

    @Test fun onlyConsecutiveUncertainPartnersAreBothLitAndCounted() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,400,600)}
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
            val a=Panel(.125f,.1f,.45f,.4f);val b=Panel(.55f,.1f,.95f,.4f);val c=Panel(.125f,.55f,.45f,.9f)
            val frames=SpeechOwnership.linkViews(listOf(a,b,c),listOf(a,b,c),setOf(0 to 1))
            val canvas=Canvas(image);val brush=Paint()
            listOf(a to Color.RED,b to Color.YELLOW,c to Color.GREEN).forEach {(p,color)->brush.color=color;canvas.drawRect(p.left*400,p.top*600,p.right*400,p.bottom*600,brush)}
            var selected=-1;var visible:List<Int>?=null
            view.onPanelChanged={i,_->selected=i};view.onPanelSelectionChanged={group,_->visible=group}
            view.setPage(image,frames,0)
            assertEquals(listOf(0,1),visible)
            assertEquals("Cases 1 et 2/3",GuidedFrames.label(visible!!,3))
            val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);view.draw(Canvas(output))
            assertEquals(Color.RED,output.getPixel(80,300));assertEquals(Color.YELLOW,output.getPixel(300,300))
            assertEquals("Unrelated case stays dimmed",Color.BLACK,output.getPixel(80,500))
            val t=SystemClock.uptimeMillis()
            listOf(Triple(0L,MotionEvent.ACTION_DOWN,350f),Triple(100L,MotionEvent.ACTION_MOVE,50f),Triple(140L,MotionEvent.ACTION_UP,50f)).forEach {(dt,action,x)->val e=MotionEvent.obtain(t,t+dt,action,x,300f,0);view.onTouchEvent(e);e.recycle()}
            assertEquals("The next reading step follows the pair",2,selected)
            view.fitPage();assertNull(visible)
            view.setPage(image,listOf(a.copy(jointFocus=listOf(c)),b,c.copy(jointFocus=listOf(a))),0)
            assertEquals("Non-consecutive partners remain separate",listOf(0),visible)
            image.recycle();output.recycle()
        }
    }
    @Test fun stackedSharedBalloonStaysBrightInEachCaseWithoutLightingTheNeighbour() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val top=Panel(.1f,.1f,.9f,.48f);val bottom=Panel(.1f,.52f,.9f,.9f)
            val speech=Panel(.30f,.43f,.60f,.57f)
            Canvas(image).drawRect(120f,258f,240f,342f,Paint().apply {color=Color.YELLOW})
            val frames=SpeechOwnership.linkViews(listOf(top,bottom),listOf(top,bottom),setOf(0 to 1),mapOf(0 to listOf(speech),1 to listOf(speech)))
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,400,600)}
            val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            var visible:List<Int>?=null;view.onPanelSelectionChanged={group,_->visible=group}
            for(i in frames.indices) {
                view.setPage(image.copy(Bitmap.Config.ARGB_8888,false),frames,i)
                // Entry selects the first case; navigate once for the lower case.
                if(i==1) {
                    val t=SystemClock.uptimeMillis()
                    listOf(Triple(0L,MotionEvent.ACTION_DOWN,350f),Triple(100L,MotionEvent.ACTION_MOVE,50f),Triple(140L,MotionEvent.ACTION_UP,50f)).forEach {(dt,action,x)->val e=MotionEvent.obtain(t,t+dt,action,x,300f,0);view.onTouchEvent(e);e.recycle()}
                }
                assertEquals(listOf(i),visible)
                val bounds=GuidedFrames.frame(frames,i,400,600,400,600).bounds
                val scale=minOf(400/(bounds.width*400),600/(bounds.height*600))
                fun point(nx:Float,ny:Float)=Pair((200+(nx-(bounds.left+bounds.right)*.5f)*400*scale).toInt(),(300+(ny-(bounds.top+bounds.bottom)*.5f)*600*scale).toInt())
                view.draw(Canvas(output))
                for(y in listOf(.445f,.555f)) {
                    val (x,py)=point(.45f,y);assertEquals("Entire shared body remains lit for case ${i+1}",Color.YELLOW,output.getPixel(x,py))
                }
                val (x,py)=point(.8f,if(i==0).55f else .45f)
                assertEquals("Other case stays dimmed",Color.BLACK,output.getPixel(x,py))
            }
            image.recycle();output.recycle()
        }
    }
    @Test fun insetWithoutAnExplicitExclusionStaysDimAndItsSharedSpeechIsRestored() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val inset=Panel(.1f,.4f,.45f,.65f);val balloon=Panel(.25f,.38f,.5f,.48f)
            Canvas(image).drawRect(40f,160f,180f,260f,Paint().apply {color=Color.RED})
            Canvas(image).drawRect(100f,152f,200f,192f,Paint().apply {color=Color.YELLOW})
            val scene=Panel(0f,0f,.999f,.999f,focusIncludes=listOf(balloon))
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,400,400)}
            view.setPage(image,listOf(scene,inset),0)
            val output=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);view.draw(Canvas(output))
            assertEquals("Main scene remains lit",Color.BLUE,output.getPixel(300,300))
            assertEquals("Inset remains dimmed",Color.BLACK,output.getPixel(70,220))
            assertEquals("Balloon over the excluded inset remains entirely visible",Color.YELLOW,output.getPixel(140,175))
            image.recycle();output.recycle()
        }
    }
    @Test fun fullPageBackgroundCanBeSelectedWithoutLightingItsInset() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val inset=Panel(.1f,.4f,.45f,.65f)
            Canvas(image).drawRect(40f,160f,180f,260f,Paint().apply {color=Color.RED})
            val scene=Panel(0f,0f,1f,1f,readingOrderBounds=Panel(0f,0f,1f,.001f),focusExclusions=listOf(inset))
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,400,400)}
            var selected=-1;view.onPanelChanged={i,_->selected=i}
            view.setPage(image,listOf(scene,inset))
            val t=SystemClock.uptimeMillis()
            listOf(0L to MotionEvent.ACTION_DOWN,40L to MotionEvent.ACTION_UP,100L to MotionEvent.ACTION_DOWN,140L to MotionEvent.ACTION_UP).forEach {(dt,action)->
                val e=MotionEvent.obtain(t,t+dt,action,300f,300f,0);view.onTouchEvent(e);e.recycle()
            }
            assertTrue("Full-page scene is a usable guided case",view.guidedReading);assertEquals(0,selected)
            val output=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);view.draw(Canvas(output))
            assertEquals("Background stays bright",Color.BLUE,output.getPixel(300,300))
            assertEquals("Inset waits for its reading turn",Color.BLACK,output.getPixel(70,220))
            image.recycle();output.recycle()
        }
    }
    @Test fun changingCachedCasesDoesNotWaitForDetection() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val elapsed=mutableListOf<Double>()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext);view.layout(0,0,720,1080)
            val image=Bitmap.createBitmap(720,1080,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
            val panels=(0..2).flatMap {row ->(0..1).map {column ->Panel(column*.5f,row/3f,(column+1)*.5f,(row+1)/3f)}}
            var selected=-1;var turns=0;view.onPanelChanged={index,_ ->selected=index};view.onPage={turns++}
            view.setPage(image,panels,0)
            val output=Bitmap.createBitmap(720,1080,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            val t=SystemClock.uptimeMillis()
            for(n in 1..5) {
                val start=System.nanoTime()
                listOf(Triple(0L,MotionEvent.ACTION_DOWN,650f),Triple(100L,MotionEvent.ACTION_MOVE,50f),Triple(140L,MotionEvent.ACTION_UP,50f)).forEach {(dt,action,x) ->
                    val event=MotionEvent.obtain(t+n*1000,t+n*1000+dt,action,x,540f,0);view.onTouchEvent(event);event.recycle()
                }
                view.draw(Canvas(output));elapsed.add((System.nanoTime()-start)/1e6)
                assertEquals(n,selected)
            }
            assertEquals(0,turns);image.recycle();output.recycle()
        }
        assertTrue("Dispatch and draw must remain below one second",elapsed.all {it<1000})
        val file=java.io.File(instrument.targetContext.getExternalFilesDir(null),"qa/case-navigation.json").apply {parentFile!!.mkdirs()}
        file.writeText(org.json.JSONObject().put("milliseconds",org.json.JSONArray(elapsed)).put("scope","synthetic swipe dispatch and software drawing; not touch-to-display latency").toString(2))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-case-navigation.json")).use {it.readBytes()}
    }
    @Test fun swipesRespectMangaAndSlowScroll() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext);view.layout(0,0,400,600)
            view.setPage(Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888),listOf(Panel(0f,0f,1f,1f)))
            val turns=mutableListOf<Int>();view.onPage={turns.add(it)}
            fun swipe(t:Long,x1:Float,y1:Float,x2:Float,y2:Float) {
                listOf(Triple(0L,MotionEvent.ACTION_DOWN,x1 to y1),Triple(1500L,MotionEvent.ACTION_MOVE,x2 to y2),Triple(2000L,MotionEvent.ACTION_UP,x2 to y2)).forEach {(dt,action,p) ->val e=MotionEvent.obtain(t,t+dt,action,p.first,p.second,0);view.onTouchEvent(e);e.recycle()}
            }
            val t=SystemClock.uptimeMillis();swipe(t,350f,300f,50f,300f)
            view.rtl=true;swipe(t+3000,350f,300f,50f,300f)
            swipe(t+6000,200f,500f,200f,100f)
            assertEquals(listOf(1,-1,1),turns)
        }
    }
    @Test fun guidedPinchAndPanKeepTheCaseUntilItsInitialZoomIsRestored() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GREEN)}
            val paint=Paint();val canvas=Canvas(image)
            paint.color=Color.RED;canvas.drawRect(0f,0f,192f,600f,paint)
            paint.color=Color.BLUE;canvas.drawRect(75f,0f,125f,600f,paint)
            val frames=listOf(Panel(0f,0f,.48f,1f),Panel(.52f,0f,1f,1f))
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,1080,2160)}
            var selected=-1;var visible:List<Int>?=null;var turns=0
            view.onPanelChanged={i,_->selected=i};view.onPanelSelectionChanged={group,_->visible=group};view.onGuidedPage={turns++}
            view.setPage(image,frames,0)
            val output=Bitmap.createBitmap(1080,2160,Bitmap.Config.ARGB_8888)
            fun pixel():Int {view.draw(Canvas(output));return output.getPixel(705,1080)}
            assertEquals(Color.RED,pixel())
            fun pinch(time:Long,distances:List<Float>) {
                fun send(action:Int,dt:Long,gap:Float,count:Int) {
                    val props=Array(count) {MotionEvent.PointerProperties().apply {id=it;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                    val coords=Array(count) {MotionEvent.PointerCoords().apply {x=540f+(if(it==0)-gap else gap)/2;y=1080f;pressure=1f;size=1f}}
                    val e=MotionEvent.obtain(time,time+dt,action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                    view.onTouchEvent(e);e.recycle()
                }
                send(MotionEvent.ACTION_DOWN,0,distances.first(),1)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),50,distances.first(),2)
                distances.drop(1).forEachIndexed {i,gap ->send(MotionEvent.ACTION_MOVE,100+i*50L,gap,2)}
                val end=150+distances.size*50L
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),end,distances.last(),2)
                send(MotionEvent.ACTION_UP,end+50,distances.last(),1)
            }
            fun swipe(time:Long) {
                listOf(Triple(0L,MotionEvent.ACTION_DOWN,950f),Triple(1000L,MotionEvent.ACTION_MOVE,50f),Triple(1400L,MotionEvent.ACTION_UP,50f)).forEach {(dt,action,x)->val e=MotionEvent.obtain(time,time+dt,action,x,1080f,0);view.onTouchEvent(e);e.recycle()}
            }
            val time=SystemClock.uptimeMillis()
            pinch(time,listOf(300f,600f,900f,1000f))
            assertTrue("Zoom keeps guided mode",view.guidedReading)
            assertEquals(listOf(0),visible);assertEquals(Color.BLUE,pixel())
            swipe(time+1000)
            assertEquals("A zoomed swipe pans instead of advancing",0,selected);assertEquals(0,turns)
            assertEquals("Pan changes the visible part of the case",Color.RED,pixel())
            pinch(time+3000,listOf(1000f,900f,850f,800f,750f,700f,650f,600f,550f,520f))
            assertEquals("Returning to base zoom keeps the case",0,selected);assertTrue(view.guidedReading)
            assertEquals("Pinch must actually restore the base zoom",1f,view.guidedZoomFactor,.01f)
            assertEquals("Initial framing is restored",Color.RED,pixel())
            swipe(time+4000)
            assertEquals("A new swipe at initial zoom advances",1,selected);assertEquals(listOf(1),visible)
            assertEquals(Color.GREEN,pixel())
            // Double tap after another guided pinch restores the selected case, not the whole page.
            pinch(time+6000,listOf(300f,600f,900f,1000f))
            listOf(0L to MotionEvent.ACTION_DOWN,40L to MotionEvent.ACTION_UP,100L to MotionEvent.ACTION_DOWN,140L to MotionEvent.ACTION_UP).forEach {(dt,action)->val e=MotionEvent.obtain(time+7000,time+7000+dt,action,540f,1080f,0);view.onTouchEvent(e);e.recycle()}
            assertTrue(view.guidedReading);assertEquals(1,selected);assertEquals(listOf(1),visible)
            swipe(time+8000)
            assertEquals("Next page is reachable after restoring the case",1,turns)
            image.recycle();output.recycle()
        }
    }
    @Test fun pinchEnlargesWithoutTurningPage() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext);view.layout(0,0,1080,1620)
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE)
            Canvas(image).drawRect(0f,0f,100f,600f,Paint().apply {color=Color.RED})
            view.setPage(image,listOf(Panel(0f,0f,1f,1f)))
            var turns=0;view.onPage={turns++}
            fun pixel():Int {val output=Bitmap.createBitmap(1080,1620,Bitmap.Config.ARGB_8888);view.draw(Canvas(output));return output.getPixel(240,810).also {output.recycle()}}
            assertEquals(Color.RED,pixel())
            val t=SystemClock.uptimeMillis()
            fun send(action:Int,time:Long,left:Float,right:Float,count:Int) {
                val props=Array(count) {MotionEvent.PointerProperties().apply {id=it;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                val coords=Array(count) {MotionEvent.PointerCoords().apply {x=if(it==0) left else right;y=810f;pressure=1f;size=1f}}
                val e=MotionEvent.obtain(t,t+time,action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                view.onTouchEvent(e);e.recycle()
            }
            send(MotionEvent.ACTION_DOWN,0,390f,690f,1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),50,390f,690f,2)
            send(MotionEvent.ACTION_MOVE,100,240f,840f,2)
            send(MotionEvent.ACTION_MOVE,150,90f,990f,2)
            send(MotionEvent.ACTION_MOVE,180,40f,1040f,2)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),220,40f,1040f,2)
            send(MotionEvent.ACTION_UP,270,40f,1040f,1)
            assertEquals(Color.BLUE,pixel());assertEquals(0,turns)
            // A subsequent, separate gesture must pan the zoomed page, not turn it.
            listOf(Triple(700L,MotionEvent.ACTION_DOWN,900f),Triple(2200L,MotionEvent.ACTION_MOVE,200f),Triple(2700L,MotionEvent.ACTION_UP,200f)).forEach {(dt,action,px) ->
                val e=MotionEvent.obtain(t+700,t+dt,action,px,810f,0);view.onTouchEvent(e);e.recycle()
            }
            assertEquals("A drag after pinch must not turn the page",0,turns)
        }
    }
    @Test fun doubleTapFitsDetectedPanelAndReturnsToPage() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext);view.layout(0,0,400,600)
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);val c=Canvas(image);image.eraseColor(Color.BLUE)
            c.drawRect(0f,0f,200f,300f,Paint().apply {color=Color.RED})
            view.setPage(image,listOf(Panel(0f,0f,.5f,.5f),Panel(.5f,0f,1f,.5f),Panel(0f,.5f,1f,1f)))
            fun pixel():Int {val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);view.draw(Canvas(output));return output.getPixel(300,450).also {output.recycle()}}
            fun doubleTap(t:Long) {listOf(0L to MotionEvent.ACTION_DOWN,40L to MotionEvent.ACTION_UP,100L to MotionEvent.ACTION_DOWN,140L to MotionEvent.ACTION_UP).forEach {(offset,action) ->val e=MotionEvent.obtain(t,t+offset,action,100f,100f,0);view.onTouchEvent(e);e.recycle()}}
            assertEquals(Color.BLUE,pixel())
            val time=SystemClock.uptimeMillis();doubleTap(time)
            assertEquals(Color.RED,pixel())
            doubleTap(time+700)
            assertEquals(Color.BLUE,pixel())
        }
    }
    @Test fun doubleTapWithoutPanelsZoomsAtTouchAndReturnsToPage() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext);view.layout(0,0,400,600)
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE)
            Canvas(image).drawRect(70f,270f,130f,330f,Paint().apply {color=Color.RED})
            view.setPage(image,listOf(Panel(0f,0f,1f,1f)))
            var turns=0;view.onPage={turns++}
            fun pixel(px:Int,py:Int):Int {val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);view.draw(Canvas(output));return output.getPixel(px,py).also {output.recycle()}}
            fun doubleTap(t:Long) {listOf(0L to MotionEvent.ACTION_DOWN,40L to MotionEvent.ACTION_UP,100L to MotionEvent.ACTION_DOWN,140L to MotionEvent.ACTION_UP).forEach {(offset,action) ->val e=MotionEvent.obtain(t,t+offset,action,100f,300f,0);view.onTouchEvent(e);e.recycle()}}
            assertEquals(Color.BLUE,pixel(150,300))
            val time=SystemClock.uptimeMillis();doubleTap(time)
            assertEquals("The touched region stays under the finger",Color.RED,pixel(100,300))
            assertEquals("The touched region is enlarged",Color.RED,pixel(150,300))
            view.setPage(image,listOf(Panel(0f,0f,.5f,.5f),Panel(0f,.5f,1f,1f)))
            assertEquals("Late case detection must preserve the current free zoom",Color.RED,pixel(150,300))
            doubleTap(time+700)
            assertEquals(Color.BLUE,pixel(150,300));assertEquals(0,turns)
        }
    }

    @Test fun guidedReadingContinuesOnNextPageAndFallsBackWhenUncertain() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext);view.layout(0,0,200,600)
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
            val panels=listOf(Panel(0f,0f,.48f,1f),Panel(.52f,0f,1f,1f))
            var selected=-1;var changed=0
            view.onPanelChanged={index,_->selected=index}
            view.setPage(image,panels,0);assertEquals(0,selected)
            view.onGuidedPage={delta ->
                assertEquals(1,delta);changed++
                val next=image.copy(Bitmap.Config.ARGB_8888,false)
                view.setPage(next,if(changed==1)panels else listOf(Panel(0f,0f,1f,1f)),0)
            }
            fun swipe(t:Long) {listOf(Triple(0L,MotionEvent.ACTION_DOWN,175f),Triple(1500L,MotionEvent.ACTION_MOVE,25f),Triple(2000L,MotionEvent.ACTION_UP,25f)).forEach {(dt,action,x)->val e=MotionEvent.obtain(t,t+dt,action,x,300f,0);view.onTouchEvent(e);e.recycle()}}
            val t=SystemClock.uptimeMillis()
            swipe(t);assertEquals(1,selected)
            swipe(t+3000);assertEquals(1,changed);assertEquals(0,selected)
            swipe(t+6000);assertEquals(1,selected)
            swipe(t+9000);assertEquals(2,changed);assertEquals(-1,selected)
        }
    }

    @Test fun oneRecognizedCaseRemainsUsableAndRestOfPageKeepsManualZoom() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext);view.layout(0,0,400,600)
            val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE)
            Canvas(image).drawRect(0f,0f,200f,300f,Paint().apply {color=Color.RED})
            var selected=-1;view.onPanelChanged={index,_->selected=index}
            val partial=listOf(Panel(0f,0f,.5f,.5f))
            view.setPage(image,partial)
            fun doubleTap(t:Long,x:Float,y:Float) {listOf(0L to MotionEvent.ACTION_DOWN,40L to MotionEvent.ACTION_UP,100L to MotionEvent.ACTION_DOWN,140L to MotionEvent.ACTION_UP).forEach {(offset,action)->val e=MotionEvent.obtain(t,t+offset,action,x,y,0);view.onTouchEvent(e);e.recycle()}}
            val t=SystemClock.uptimeMillis();doubleTap(t,100f,100f)
            assertEquals(0,selected)
            val output=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);view.draw(Canvas(output))
            assertEquals(Color.RED,output.getPixel(300,450))
            doubleTap(t+700,100f,100f);assertEquals(-1,selected)
            doubleTap(t+1400,300f,450f);assertEquals(-1,selected)
            view.setPage(image.copy(Bitmap.Config.ARGB_8888,false),partial,0)
            assertEquals("Guided navigation may enter a page with only one detected case",0,selected)
            output.recycle()
        }
    }

    @Test fun insetAndMainSceneKeepSeparateFocus() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            val view=ComicView(instrument.targetContext).apply {focusTransitions=false;outsideDim=100;layout(0,0,800,800)}
            val image=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val inset=Panel(.07f,.20f,.32f,.45f)
            Canvas(image).drawRect(56f,160f,256f,360f,Paint().apply {color=Color.RED})
            val main=Panel(.10f,.10f,.90f,.60f,focusExclusions=listOf(inset))
            var selected=-1;var shown:IntRange?=null
            view.onPanelChanged={index,_->selected=index};view.onPanelGroupChanged={group,_->shown=group}
            view.setPage(image,listOf(main,inset),0)
            val output=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
            assertEquals(0,selected);assertEquals(0..0,shown)
            assertEquals("Inset is dimmed when main scene is active",Color.BLACK,output.getPixel(95,375))
            assertEquals("Main scene keeps its luminosity",Color.BLUE,output.getPixel(500,400))
            view.fitPage()
            val t=SystemClock.uptimeMillis()
            listOf(0L to MotionEvent.ACTION_DOWN,40L to MotionEvent.ACTION_UP,100L to MotionEvent.ACTION_DOWN,140L to MotionEvent.ACTION_UP).forEach {(offset,action) ->
                val event=MotionEvent.obtain(t,t+offset,action,156f,260f,0);view.onTouchEvent(event);event.recycle()
            }
            assertEquals("Touching the overlap selects the inset",1,selected);assertEquals(1..1,shown)
            view.draw(Canvas(output))
            assertEquals("Inset keeps its own luminosity",Color.RED,output.getPixel(400,400))
            image.recycle();output.recycle()
        }
    }

}
