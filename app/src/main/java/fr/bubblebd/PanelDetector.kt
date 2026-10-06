package fr.bubblebd

import android.graphics.Bitmap
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android image adapter; geometry is shared with desktop regression previews. */
object PanelDetector {
    private val processing=Mutex()
    suspend fun detectAsync(bitmap:Bitmap,context:android.content.Context,rectanglePrior:Boolean=false):List<Panel> = withContext(Dispatchers.Default) {
        // Queue cancellation prevents obsolete pages from starting expensive neural work.
        processing.withLock {
            val task=currentCoroutineContext();task.ensureActive()
            val tid=android.os.Process.myTid();val priority=android.os.Process.getThreadPriority(tid)
            runCatching {android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)}
            try {detect(bitmap,context,cancelled={!task.isActive},rectanglePrior=rectanglePrior)}
            finally {runCatching {android.os.Process.setThreadPriority(priority)}}
        }
    }
    fun detect(bitmap:Bitmap,context:android.content.Context?=null,cancelled:()->Boolean={false},rectanglePrior:Boolean=false,timing:((String,Double)->Unit)?=null):List<Panel> {
        // Screen captures can contain uniform black letterboxing. Analyze their
        // page area, then map every contour/mask back to the untouched image.
        val row=IntArray(bitmap.width)
        fun black(y:Int):Boolean {
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            return row.count {c ->maxOf(c shr 16 and 255,c shr 8 and 255,c and 255)<=24}>bitmap.width*.995f
        }
        var top=0;while(top<bitmap.height/3 && black(top))top++
        var bottom=bitmap.height;while(bottom>bitmap.height*2/3 && black(bottom-1))bottom--
        if(top>bitmap.height*.06f && bitmap.height-bottom>bitmap.height*.06f && bottom-top>bitmap.height*.35f) {
            val page=Bitmap.createBitmap(bitmap,0,top,bitmap.width,bottom-top)
            try {
                val offset=top.toFloat()/bitmap.height;val ratio=(bottom-top).toFloat()/bitmap.height
                fun map(p:Panel):Panel=p.copy(top=offset+p.top*ratio,bottom=offset+p.bottom*ratio,
                    readingOrderBounds=p.readingOrderBounds?.let(::map),focusExclusions=p.focusExclusions.map(::map),
                    focusIncludes=p.focusIncludes.map(::map),jointFocus=p.jointFocus.map(::map),
                    focusOutline=p.focusOutline.map {it.copy(y=offset+it.y*ratio)})
                return detectPage(page,context,cancelled,rectanglePrior,timing).map(::map)
            } finally {page.recycle()}
        }
        return detectPage(bitmap,context,cancelled,rectanglePrior,timing)
    }
    private fun detectPage(bitmap:Bitmap,context:android.content.Context?,cancelled:()->Boolean,rectanglePrior:Boolean,timing:((String,Double)->Unit)?):List<Panel> {
        var stageStart=if(timing!=null)System.nanoTime() else 0L
        fun stage(name:String) {if(timing!=null) {val now=System.nanoTime();timing(name,(now-stageStart)/1e9);stageStart=now}}
        val width=min(720,bitmap.width)
        val height=(bitmap.height.toFloat()/bitmap.width*width).toInt().coerceIn(1,2000)
        val small=Bitmap.createScaledBitmap(bitmap,width,height,true)
        try {
        val pixels=IntArray(width*height);small.getPixels(pixels,0,width,0,0,width,height)
        val sparse=SingleScene.detect(pixels,width,height).ifEmpty {BorderlessPanels.detect(pixels,width,height)}.ifEmpty {CaptionRows.detect(pixels,width,height)}
        stage("paper")
        val geometry=sparse.ifEmpty {PanelGeometry.detect(pixels,width,height)}
        stage("geometry")
        val result=if(context==null || sparse.isNotEmpty() || cancelled())geometry else runCatching {
            val full=LocalPanelAi.detect(context,small)
            val extra=if(PanelDetail.needed(geometry,pixels,width,height))LocalPanelAi.detailProposals(context,small,stop=cancelled).filter {d ->
                d.text || full.none {f ->
                    val a=f.bounds;val b=d.bounds
                    val shared=kotlin.math.max(0f,kotlin.math.min(a.right,b.right)-kotlin.math.max(a.left,b.left))*kotlin.math.max(0f,kotlin.math.min(a.bottom,b.bottom)-kotlin.math.max(a.top,b.top))
                    !f.text && f.confidence>.50f && a.width*a.height>b.width*b.height && shared/(b.width*b.height)>.9f && d.confidence<.8f
                }
            } else emptyList()
            stage("neural")
            (if(cancelled())geometry else DetectionSafety.accept(HybridPanels.combine(geometry,full+extra,pixels,width,height,stop={if(cancelled())throw kotlinx.coroutines.CancellationException("Analyse remplacée par la page demandée")},rectanglePrior=rectanglePrior),geometry,full+extra,pixels,width,height)).also {stage("merge")}
        }.getOrElse {if(it is kotlinx.coroutines.CancellationException)throw it;geometry}
        return result
        } finally {if(small!==bitmap)small.recycle()}
    }
}
