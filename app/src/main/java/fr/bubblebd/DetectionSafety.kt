package fr.bubblebd

import kotlin.math.*

/** Preserve partial detection; abandon a result only when every case lacks evidence. */
object DetectionSafety {
    private fun core(p:Panel)=p.readingOrderBounds ?: p
    private fun iou(a:Panel,b:Panel):Float {
        val shared=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        return shared/(a.width*a.height+b.width*b.height-shared).coerceAtLeast(.000001f)
    }
    /** A crop can remove the paper outside a real frame; four ink edges still prove it. */
    private fun croppedFrame(p:Panel,pixels:IntArray,w:Int,h:Int):Boolean {
        if(p.width<.10f || p.height<.075f)return false
        val l=(p.left*w).toInt().coerceIn(0,w-1);val r=(p.right*w).toInt().coerceIn(0,w-1)
        val t=(p.top*h).toInt().coerceIn(0,h-1);val b=(p.bottom*h).toInt().coerceIn(0,h-1)
        if(minOf(l,t,w-1-r,h-1-b)>3)return false
        fun level(x:Int,y:Int):Int {val c=pixels[y*w+x];return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)}
        fun dark(x:Int,y:Int)=level(x,y)<190
        fun edge(v:Int,a:Int,z:Int,vertical:Boolean):Boolean {
            val limit=if(vertical)w else h
            return (max(0,v-3)..min(limit-1,v+3)).any {q->
                val samples=(a+2 until z-1)
                val contrasting=samples.count {u->
                    val x=if(vertical)q else u;val y=if(vertical)u else q
                    dark(x,y) && listOf(-4,4).any {offset->
                        val nx=x+if(vertical)offset else 0;val ny=y+if(vertical)0 else offset
                        nx in 0 until w && ny in 0 until h && level(nx,ny)-level(x,y)>25
                    }
                }
                samples.count()>8 && samples.count {u->dark(if(vertical)q else u,if(vertical)u else q)}>samples.count()*.88f && contrasting>samples.count()*.45f
            }
        }
        return edge(l,t,b,true) && edge(r,t,b,true) && edge(t,l,r,false) && edge(b,l,r,false)
    }
    fun accept(frames:List<Panel>,geometry:List<Panel>,predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int):List<Panel> {
        if(frames.isEmpty())return frames
        if(frames.any {it.focusOutline.size>=3})return frames
        val supported=predictions.any {d->!d.text && d.confidence>=.75f && frames.any {iou(core(it),d.bounds)>.60f}}
        if(supported)return frames
        if(frames.any {croppedFrame(core(it),pixels,w,h)})return frames
        val contours=FrameContours(pixels,w,h,predictions.filter {it.text && it.confidence>.45f}.map {it.bounds})
        return if(frames.any {p->geometry.any {iou(core(p),core(it))>.65f} && contours.fit(core(p))!=null})frames else emptyList()
    }
}
