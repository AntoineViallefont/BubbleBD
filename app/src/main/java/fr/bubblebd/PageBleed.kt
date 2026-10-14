package fr.bubblebd

import kotlin.math.*

/** Recover continuous artwork at the page edge, away from overlapping inserts. */
internal object PageBleed {
    fun expand(input:List<Panel>,pixels:IntArray,w:Int,h:Int):List<Panel> {
        fun ink(x:Int,y:Int):Boolean {
            val c=pixels[y*w+x]
            return minOf(c shr 16 and 255,c shr 8 and 255,c and 255)<235
        }
        return input.map {p ->
            val c=p.readingOrderBounds ?: p
            var core=c
            if(c.top<.015f && c.left<.03f && c.width<.60f && c.height>.35f) {
                val start=(c.top*h).toInt().coerceAtLeast(0)
                val end=min(start+max(4,(h*.025f).toInt()),input.filter {it!==p && it.left<c.right+.08f && it.right>c.right && it.top>c.top}.minOfOrNull {(it.top*h).toInt()-2} ?: h)
                if(end-start>=4) {
                    var edge=(c.right*w).toInt().coerceIn(0,w-1)
                    val limit=min(w,(c.right*w+w*.06f).toInt())
                    while(edge<limit && (start until end).count {ink(edge,it)}>(end-start)*.90f)edge++
                    if(edge>c.right*w+2)core=core.copy(right=edge.toFloat()/w)
                }
            }
            if(c.right>.97f && c.bottom>.95f && c.width>.35f && c.height>.20f) {
                val rows=max(0,h-max(12,(h*.025f).toInt())) until h
                val from=max(0,((c.left-.08f)*w).toInt())
                val active=(from until w).filter {x ->rows.count {ink(x,it)}>=rows.count()*.5f}
                if(active.isNotEmpty() && active.last()>=w-3 && active.size>(w-active.first())*.90f && active.first()<c.left*w-2)
                    core=core.copy(left=active.first().toFloat()/w,bottom=1f)
            }
            if(core==c)p else p.copy(left=min(p.left,core.left),top=min(p.top,core.top),right=max(p.right,core.right),bottom=max(p.bottom,core.bottom),readingOrderBounds=core)
        }
    }
}
