package fr.bubblebd

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable fun ReaderScreen(book:Book,initialPage:Int,repo:Repository,prefs:Preferences,onProgress:(String,Int,Int)->Unit,close:()->Unit) {
    val activity=LocalActivity.current!!
    val panelCache=remember(book.id) {android.util.LruCache<Int,List<Panel>>(12)}
    val panelStyle=remember(book.id) {repo.loadPanelStyle(book.id)}
    var document by remember(book.id) {mutableStateOf<ComicDocument?>(null)}
    var bitmap by remember {mutableStateOf<Bitmap?>(null)}
    var page by rememberSaveable(book.id) {mutableIntStateOf(initialPage)}
    var total by remember {mutableIntStateOf(book.pages)}
    var panels by remember {mutableStateOf<List<Panel>>(emptyList())}
    var controls by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(true)}
    var input by remember {mutableStateOf<String?>(null)}
    var scrub by remember(page) {mutableFloatStateOf(page.toFloat())}
    var activePanel by remember {mutableStateOf<Pair<List<Int>,Int>?>(null)}
    var showPanelHint by remember {mutableStateOf(false)}
    LaunchedEffect(activePanel,page) {showPanelHint=activePanel!=null;if(showPanelHint) {delay(1500);showPanelHint=false}}
    val configuration=LocalConfiguration.current
    val density=LocalDensity.current
    val scrubWidth=with(density) {configuration.screenWidthDp.dp.toPx()*.75f}
    val scrubHeight=with(density) {configuration.screenHeightDp.dp.toPx()*.65f}
    var scrubAxis by remember {mutableIntStateOf(0)}
    var entryPanel by remember {mutableStateOf<Int?>(null)}
    var comicView by remember {mutableStateOf<ComicView?>(null)}
    var readingDirection by remember {mutableIntStateOf(1)}
    var focusPage by remember {mutableIntStateOf(initialPage)}
    val scope=rememberCoroutineScope()
    var analysisReady by remember {mutableStateOf(false)}
    val prefetch=remember(document) {
        val d=document
        ReaderPrefetch(scope,
            load={target ->withContext(Dispatchers.IO) {checkNotNull(d).bitmap(target)}},
            detect={target,image ->
                val frames=panelCache.get(target) ?: PanelDetector.detectAsync(image,activity.applicationContext,panelStyle.prefersRectangles(target))
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                panelCache.put(target,frames)
                panelStyle.record(target,frames);repo.savePanelStyle(book.id,panelStyle)
                frames
            }
        )
    }

    BackHandler {close()}
    DisposableEffect(Unit) {
        val controller=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {controller.show(WindowInsetsCompat.Type.systemBars()); activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); repo.trimCache(prefs.cacheMb)}
    }
    LaunchedEffect(book.id) {
        try { document=withContext(Dispatchers.IO) {ComicDocument(repo.localFile(book))}; total=document!!.count }
        catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) throw e; error=e.message ?: ui("Album inaccessible"); loading=false}
    }
    DisposableEffect(document) {val d=document; onDispose {d?.close()} }
    DisposableEffect(prefetch) {onDispose {prefetch.cancel()} }
    LaunchedEffect(document,page) {
        val d=document ?: return@LaunchedEffect
        val targetPage=page
        val targetEntry=entryPanel
        loading=true;analysisReady=false; error=null
        val prepared=prefetch.take(targetPage)
        try {
            var progressRecorded=false
            val detected=loadReaderPage(
                guided=targetEntry!=null,
                cached=panelCache.get(targetPage),
                load={prepared?.image() ?: withContext(Dispatchers.IO) {d.bitmap(targetPage)}},
                detect={prepared?.frames() ?: PanelDetector.detectAsync(it,activity.applicationContext,panelStyle.prefersRectangles(targetPage))},
                display={image,frames ->
                    panels=BookRules.orderPanels(frames,prefs.rtl)
                    bitmap=image;loading=false
                    if(!progressRecorded) {onProgress(book.id,targetPage,d.count);progressRecorded=true}
                }
            )
            panelCache.put(targetPage,detected)
            panelStyle.record(targetPage,detected);repo.savePanelStyle(book.id,panelStyle)
            analysisReady=true
        } catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException) throw e; error=e.message ?: ui("Impossible d’afficher la page")}
        finally {prepared?.cancel();if(page==targetPage) loading=false}
    }
    LaunchedEffect(document,page,loading,analysisReady,activePanel,readingDirection) {
        if(document==null || loading || !analysisReady)return@LaunchedEffect
        val target=ReaderPrefetch.nextPage(page,total,activePanel?.first,activePanel?.second ?: 0,readingDirection)
        if(target==null)prefetch.cancel() else prefetch.prepare(target)
    }
    fun changePage(target:Int,guided:Boolean=false):Boolean {
        val next=target.coerceIn(0,maxOf(0,total-1))
        if(loading) return false
        if(next==page) return true
        readingDirection=if(next>page)1 else -1
        prefetch.cancelUnless(next)
        entryPanel=if(guided || comicView?.guidedReading==true) {if(next>page) 0 else -1} else null
        loading=true;analysisReady=false;page=next
        return true
    }
    fun move(delta:Int,guided:Boolean=false) {
        changePage(page+delta,guided)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory={ ctx -> ComicView(ctx).also {comicView=it} },modifier=Modifier.fillMaxSize(),update={ view ->
            view.onTap={controls=!controls}; view.onPanelSelectionChanged={indices,count ->
                val old=activePanel?.first
                if(focusPage==page && indices!=null && old!=null && indices.first()!=old.first())readingDirection=if(indices.first()>old.first())1 else -1
                focusPage=page;activePanel=indices?.let {it to count}
            }; view.onPage={move(it)}; view.onGuidedPage={move(it,true)}; view.rtl=prefs.rtl; view.outsideDim=prefs.outsideDim
            view.setPage(bitmap,panels,entryPanel)
            view.isEnabled=!loading && error==null
        })
        if(activePanel!=null && (showPanelHint || controls)) {
            Text(ui(GuidedFrames.label(activePanel!!.first,activePanel!!.second)),color=Color.White,fontSize=12.sp,
                modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=if(controls)78.dp else 20.dp)
                    .background(Color(0xB31B232D),androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(horizontal=12.dp,vertical=5.dp))
        }
        if(loading) CircularProgressIndicator(Modifier.align(Alignment.Center),color=Blue)
        if(error!=null) Column(Modifier.align(Alignment.Center).padding(32.dp).background(Color(0xE6151A21)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {Text(ui(error!!),color=Color.White); Button(onClick=close) {Text(ui("Revenir à la bibliothèque"))} }
        if(controls && error==null) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xD9151A21)).statusBarsPadding().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={close()}) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,ui("Fermer le lecteur"),tint=Color.White)}
                Text(book.displayTitle,Modifier.weight(1f),color=Color.White,fontSize=14.sp,maxLines=1)
                IconButton(onClick={comicView?.fitPage()}) {Icon(Icons.Outlined.CropFree,ui("Voir toute la page"),tint=Color.White)}
            }
            run {
                Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().background(Color(0xDD151A21),androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={move(-1)},enabled=page>0) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,ui("Page précédente"),tint=if(page>0) Color.White else Color.Gray)}
                    Text("${scrub.roundToInt()+1} / $total",Modifier
                        .semantics {
                            contentDescription=ui("Numéro de page : glissez pour parcourir, touchez pour saisir")
                            progressBarRangeInfo=ProgressBarRangeInfo(scrub,0f..maxOf(1,total-1).toFloat())
                            setProgress {value ->changePage(value.roundToInt())}
                        }
                        .pointerInput(total,loading) {
                            detectDragGestures(onDragStart={scrub=page.toFloat();scrubAxis=0},
                                onDragEnd={changePage(scrub.roundToInt())},
                                onDragCancel={scrub=page.toFloat()},onDrag={change,amount ->
                                    change.consume()
                                    if(!loading) {
                                        if(scrubAxis==0)scrubAxis=if(abs(amount.x)>abs(amount.y))1 else 2
                                        val fraction=if(scrubAxis==1)amount.x/scrubWidth else -amount.y/scrubHeight
                                        scrub=(scrub+fraction*maxOf(0,total-1)).coerceIn(0f,maxOf(0,total-1).toFloat())
                                    }
                                })
                        }.clickable {input=(page+1).toString()}.padding(horizontal=20.dp,vertical=14.dp),color=Color.White,fontSize=16.sp)
                    IconButton(onClick={move(1)},enabled=page<total-1) {Icon(Icons.AutoMirrored.Outlined.ArrowForward,ui("Page suivante"),tint=if(page<total-1) Color.White else Color.Gray)}
                }
            }
        }
    }
    input?.let { value -> AlertDialog(onDismissRequest={input=null},title={Text(ui("Aller à la page"))},text={OutlinedTextField(value,{input=it.filter(Char::isDigit).take(6)},label={Text(ui("De 1 à $total"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)},confirmButton={TextButton(onClick={if(changePage(value.toInt()-1)) input=null},enabled=!loading && value.toIntOrNull() in 1..maxOf(1,total)) {Text(ui("Aller"))}},dismissButton={TextButton(onClick={input=null}) {Text(ui("Annuler"))}}) }
}

class ComicView(context:Context):View(context) {
    var onTap:()->Unit={}; var onPage:(Int)->Unit={};
    var onPanelChanged:(Int,Int)->Unit={_,_->}
    var onPanelGroupChanged:(IntRange?,Int)->Unit={_,_->}
    private var shown:IntRange?=null
    var onPanelSelectionChanged:(List<Int>?,Int)->Unit={_,_->}
    private var illuminated:List<Int>?=null
    private var previousShown:List<Int>?=null
    private var focusFraction=1f
    private var focusAnimator:android.animation.ValueAnimator?=null
    internal var focusTransitions=true
    private fun stopFocusTransition() {
        focusAnimator?.cancel();focusAnimator=null;previousShown=null;focusFraction=1f
    }
    private fun startFocusTransition(previous:List<Int>?) {
        stopFocusTransition()
        if(!focusTransitions || outsideDim==0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        previousShown=previous;focusFraction=0f
        focusAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration=150
            interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener {focusFraction=it.animatedValue as Float;invalidate()}
            start()
        }
    }
    override fun onDetachedFromWindow() {stopFocusTransition();super.onDetachedFromWindow()}
    var onGuidedPage:(Int)->Unit={onPage(it)}
    var outsideDim=60
        set(value) {field=value.coerceIn(0,100);invalidate()}
    var rtl=false
    private var bitmap:Bitmap?=null
    private var panels:List<Panel> = emptyList()
    val detectionReady get()=panels.isNotEmpty()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination=RectF()
    private var scale=1f; private var base=1f; private var x=0f; private var y=0f; private var panel=-1
    private var freeZoom=false; private var pinched=false
    private var caseScale=1f;private var caseBounds:Panel?=null
    private var caseZoomStop=false
    private var autoCases=false
    internal val guidedReading get()=autoCases
    internal val guidedZoomFactor get()=if(panel>=0)scale/caseScale else 1f
    private var downX=0f; private var downY=0f; private var navigated=false
    init {isClickable=true;contentDescription=ui("Page de bande dessinée. Touchez pour afficher les commandes.")}
    private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector:ScaleGestureDetector):Boolean {
            stopFocusTransition();pinched=true;caseZoomStop=false;freeZoom=panel<0 || abs(scale/caseScale-1f)>.02f
            if(panel<0) {caseBounds=null;onPanelChanged(-1,panels.size);shown=null;illuminated=null;onPanelGroupChanged(null,panels.size);onPanelSelectionChanged(null,panels.size)}
            return true
        }
        override fun onScale(d:ScaleGestureDetector):Boolean {
            if(caseZoomStop)return true
            val minimum=if(panel>=0)min(base,caseScale) else base
            val old=scale
            val proposed=(scale*d.scaleFactor).coerceIn(minimum,(if(panel>=0)caseScale else base)*12)
            // A pinch crossing the fitted case stops there until fingers are released.
            // A new pinch starting at that fit can explore either side of the zoom.
            if(panel>=0 && (old>caseScale && proposed<=caseScale || old<caseScale && proposed>=caseScale)) {
                caseZoomStop=true;resetCaseZoom();return true
            }
            scale=proposed
            freeZoom=true
            x=d.focusX-(d.focusX-x)*scale/old;y=d.focusY-(d.focusY-y)*scale/old;clamp();invalidate();return true
        }
        override fun onScaleEnd(detector:ScaleGestureDetector) {
            if(panel>=0) {if(abs(scale/caseScale-1f)<=.02f)resetCaseZoom() else clamp()}
        }
    }).apply { isQuickScaleEnabled=false }
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:MotionEvent)=true
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean {performClick();return true}
        override fun onDoubleTap(e:MotionEvent):Boolean {
            val b=bitmap ?: return true
            if(panel>=0 && freeZoom) resetCaseZoom()
            else if(autoCases || freeZoom) fitPage()
            else {
                val nx=(e.x-x)/scale/b.width; val ny=(e.y-y)/scale/b.height
                val found=panels.indices.filter {panels[it].contains(nx,ny)}.minByOrNull {panels[it].width*panels[it].height}
                if(found!=null && (!panels[found].isWholePage || panels[found].focusExclusions.isNotEmpty())) showPanel(found)
                else zoomAt(e.x,e.y)
            }
            return true
        }
        override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean {
            if(freeZoom && !scaler.isInProgress) {x-=dx;y-=dy;clamp();invalidate()};return true
        }
        override fun onFling(e1:MotionEvent?,e2:MotionEvent,vx:Float,vy:Float):Boolean {
            if(e1==null || pinched || freeZoom) return true
            navigate(e2.x-e1.x,e2.y-e1.y)
            return true
        }
    })
    private fun navigate(dx:Float,dy:Float) {
        if(navigated || pinched || freeZoom) return
        val threshold=40*resources.displayMetrics.density
        if(abs(dx)>abs(dy) && abs(dx)>threshold) {
            val delta=if(if(dx<0) !rtl else rtl) 1 else -1
            navigated=true
            if(panel>=0) {
                val visible=shown ?: panel..panel
                val next=if(delta>0)visible.last+1 else visible.first-1
                if(next in panels.indices) showPanel(next,if(delta>0)next else 0,if(delta<0)next else panels.lastIndex,delta>0) else onGuidedPage(delta)
            } else if(autoCases)onGuidedPage(delta) else onPage(delta)
        } else if(panel<0 && abs(dy)>threshold) {navigated=true;val delta=if(dy<0) 1 else -1;if(autoCases)onGuidedPage(delta) else onPage(delta)}
    }
    fun setPage(b:Bitmap?,p:List<Panel>,entry:Int?=null) {
        val guidedEntry=entry!=null
        if(bitmap!==b) {bitmap=b;panels=p;resetPage(!guidedEntry || p.none {!it.isWholePage});autoCases=guidedEntry;if(guidedEntry && p.any {!it.isWholePage}) showPanel(if(entry!!<0)p.lastIndex else 0,animate=false)}
        else if(panels!=p) {
            panels=p
            // Late detection must preserve a pinch/double-tap zoom already chosen by the reader.
            if(!freeZoom) {panel=-1;resetPage(!guidedEntry || p.none {!it.isWholePage});autoCases=guidedEntry;if(guidedEntry && p.any {!it.isWholePage})showPanel(if(entry!!<0)p.lastIndex else 0,animate=false)}
            else invalidate()
        }
    }
    fun fitPage()=resetPage(true)
    private fun resetPage(notify:Boolean) {autoCases=false;stopFocusTransition();val b=bitmap ?: return; if(width==0 || height==0) return; base=min(width.toFloat()/b.width,height.toFloat()/b.height);scale=base;x=(width-b.width*scale)/2;y=(height-b.height*scale)/2;panel=-1;freeZoom=false;caseBounds=null;shown=null;illuminated=null;if(notify) {onPanelChanged(-1,panels.size);onPanelGroupChanged(null,panels.size);onPanelSelectionChanged(null,panels.size)};invalidate()}
    private fun zoomAt(focusX:Float,focusY:Float) {
        // Le point touché reste sous le doigt, sauf contrainte des bords de page.
        stopFocusTransition()
        val old=scale;scale=base*2.5f
        x=focusX-(focusX-x)*scale/old;y=focusY-(focusY-y)*scale/old
        panel=-1;freeZoom=true;clamp();invalidate()
    }
    private fun showPanel(i:Int,minimum:Int=0,maximum:Int=panels.lastIndex,forward:Boolean=true,animate:Boolean=true) {
        val b=bitmap ?: return
        if(width<=0 || height<=0)return
        val frame=GuidedFrames.frame(panels,i,b.width,b.height,width,height,minimum,maximum,forward)
        val previous=illuminated
        autoCases=true;panel=i;shown=frame.indices;illuminated=frame.visible;freeZoom=false;scale=frame.scale
        caseScale=frame.scale;caseBounds=frame.bounds
        x=width/2f-(frame.bounds.left+frame.bounds.right)*.5f*b.width*scale
        y=height/2f-(frame.bounds.top+frame.bounds.bottom)*.5f*b.height*scale
        if(animate) startFocusTransition(previous) else stopFocusTransition()
        onPanelChanged(i,panels.size);onPanelGroupChanged(shown,panels.size);onPanelSelectionChanged(illuminated,panels.size);invalidate()
    }
    private fun resetCaseZoom() {
        val b=bitmap ?: return;val bounds=caseBounds ?: return
        scale=caseScale;freeZoom=false
        x=width/2f-(bounds.left+bounds.right)*.5f*b.width*scale
        y=height/2f-(bounds.top+bounds.bottom)*.5f*b.height*scale
        invalidate()
    }
    private fun clamp() {
        val b=bitmap ?: return
        val bounds=caseBounds?.takeIf {panel>=0}
        if(bounds!=null) {
            if(scale==caseScale) {resetCaseZoom();return}
            freeZoom=true
            // Below the fitted case, pan across the page while retaining its focus.
            if(scale<caseScale) {
                val w=b.width*scale;val h=b.height*scale
                x=if(w<=width)(width-w)/2 else x.coerceIn(width-w,0f)
                y=if(h<=height)(height-h)/2 else y.coerceIn(height-h,0f)
                return
            }
            val l=bounds.left*b.width*scale;val r=bounds.right*b.width*scale
            val t=bounds.top*b.height*scale;val bottom=bounds.bottom*b.height*scale
            x=if(r-l<=width)(width-l-r)/2 else x.coerceIn(width-r,-l)
            y=if(bottom-t<=height)(height-t-bottom)/2 else y.coerceIn(height-bottom,-t)
            return
        }
        val w=b.width*scale;val h=b.height*scale
        x=if(w<=width)(width-w)/2 else x.coerceIn(width-w,0f)
        y=if(h<=height)(height-h)/2 else y.coerceIn(height-h,0f)
        if(scale<=base*1.02f) {freeZoom=false;val automatic=autoCases;fitPage();autoCases=automatic}
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {val selected=panel;val automatic=autoCases;fitPage();autoCases=automatic;if(selected in panels.indices) showPanel(selected)}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);val b=bitmap ?: return
        destination.set(x,y,x+b.width*scale,y+b.height*scale)
        canvas.drawBitmap(b,null,destination,paint)
        illuminated?.takeIf {panel>=0}?.let {indices ->
            fun path(group:List<Int>)=android.graphics.Path().apply {
                fun shape(p:Panel)=android.graphics.Path().apply {
                    if(p.focusOutline.isEmpty())addRect(x+p.left*b.width*scale,y+p.top*b.height*scale,x+p.right*b.width*scale,y+p.bottom*b.height*scale,android.graphics.Path.Direction.CW)
                    else {p.focusOutline.forEachIndexed {index,v ->if(index==0)moveTo(x+v.x*b.width*scale,y+v.y*b.height*scale) else lineTo(x+v.x*b.width*scale,y+v.y*b.height*scale)};close()}
                }
                fun core(p:Panel)=p.readingOrderBounds ?: p
                group.forEach {i ->if(i in panels.indices) {
                    val p=panels[i];val lit=shape(p)
                    // Artwork follows its contour before other physical cases are removed.
                    p.focusIncludes.filter {it.focusOutline.isNotEmpty()}.forEach {lit.op(shape(it),android.graphics.Path.Op.UNION)}
                    val c=core(p)
                    // An overlapping inset belongs to its own physical case, even
                    // when the detector has not attached an explicit exclusion.
                    val children=panels.indices.filter {j ->j !in group && j!=i}.mapNotNull {j ->
                        val other=panels[j];val q=core(other)
                        val shared=maxOf(0f,minOf(c.right,q.right)-maxOf(c.left,q.left))*maxOf(0f,minOf(c.bottom,q.bottom)-maxOf(c.top,q.top))
                        if(q.width*q.height<c.width*c.height*.80f && shared>q.width*q.height*.80f)
                            q.copy(focusOutline=other.focusOutline) else null
                    }
                    (p.focusExclusions+children).forEach {hole ->
                        if(group.none {j->j!=i && j in panels.indices && core(panels[j]).contains((hole.left+hole.right)*.5f,(hole.top+hole.bottom)*.5f)})
                            lit.op(shape(hole),android.graphics.Path.Op.DIFFERENCE)
                    }
                    op(lit,android.graphics.Path.Op.UNION)
                }}
                // Restore speech and confirmed foreground crossing an earlier frame.
                // Shared balloons stay bright without lighting the other case.
                group.forEach {i ->if(i in panels.indices)panels[i].focusIncludes.filter {it.focusOutline.isEmpty() || it.foregroundOverhang}.forEach {part ->
                    op(shape(part),android.graphics.Path.Op.UNION)
                }}
            }
            val illuminated=path(indices)
            val alpha=outsideDim*255/100
            val previous=previousShown?.takeIf {focusFraction<1f}?.let {path(it)}
            // New case stays fully lit. Only the previous case fades; distant background stays steady.
            canvas.save();canvas.clipOutPath(illuminated)
            if(previous!=null) {
                canvas.save();canvas.clipOutPath(previous);canvas.drawColor(alpha shl 24);canvas.restore()
                canvas.clipPath(previous);canvas.drawColor((alpha*focusFraction).toInt() shl 24)
            } else canvas.drawColor((alpha*focusFraction).toInt() shl 24)
            canvas.restore()
        }

    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled) return true

        if(event.actionMasked==MotionEvent.ACTION_DOWN) {pinched=false;navigated=false;downX=event.x;downY=event.y}
        scaler.onTouchEvent(event);if(!scaler.isInProgress) gestures.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP) navigate(event.x-downX,event.y-downY)
        return true
    }
    override fun performClick():Boolean {super.performClick();onTap();return true}
}
