package fr.bubblebd

import kotlin.math.*

/** Recover a stepped scene boundary only beside two verified paper gutters. */
internal object OrthogonalNotches {
    fun attach(input:List<Panel>,pixels:IntArray,w:Int,h:Int,speech:List<Panel>):List<Panel> {
        fun core(p:Panel)=p.readingOrderBounds ?: p
        val contours=FrameContours(pixels,w,h,speech)
        fun masked(x:Int,y:Int)=speech.any {x.toFloat()/w in it.left..it.right && y.toFloat()/h in it.top..it.bottom}
        fun paper(x:Int,y:Int):Boolean {
            if(x !in 0 until w || y !in 0 until h)return false
            val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>225
        }
        return input.map {panel ->
            val p=core(panel)
            if(panel.focusOutline.isNotEmpty() || panel.focusExclusions.isNotEmpty())return@map panel
            val neighbours=input.filter {it!==panel}.map(::core).filter {q ->
                q.left>p.left+p.width*.45f && q.left<p.right-.025f && q.right>p.right+.10f &&
                    q.top>p.top+p.height*.20f && q.top<p.bottom-p.height*.20f && abs(q.bottom-p.bottom)<.015f
            }
            val q=neighbours.singleOrNull() ?: return@map panel
            val b=(p.bottom*h).toInt();val r=(p.right*w).toInt()
            val gx=(q.left*w).toInt();val gy=(q.top*h).toInt()
            fun bright(v:Int,from:Int,to:Int,vertical:Boolean):Float {
                val us=(from until to).filterNot {masked(if(vertical)v else it,if(vertical)it else v)}
                if(us.size<(to-from)*.25f)return 0f
                return us.count {paper(if(vertical)v else it,if(vertical)it else v)}.toFloat()/us.size
            }
            val x=(max(4,gx-(w*.025f).toInt()) until gx-2).filter {v ->
                bright(v+3,gy+4,b,true)>.93f && contours.support(v,gy+4,b,true)>.80f
            }.maxByOrNull {contours.support(it,gy+4,b,true)} ?: return@map panel
            // A caption can pull the neighbouring neural top above the real
            // junction. Search on both sides, still requiring paper and ink.
            val y=(max(4,gy-(h*.025f).toInt())..min(h-5,gy+(h*.012f).toInt())).filter {v ->
                bright(v+3,x+4,r,false)>.93f && contours.support(v,x+4,r,false)>.80f
            }.maxByOrNull {contours.support(it,x+4,r,false)} ?: return@map panel
            val nx=(x+1f)/w;val ny=(y+1f)/h
            panel.copy(focusOutline=listOf(PanelPoint(p.left,p.top),PanelPoint(p.right,p.top),
                PanelPoint(p.right,ny),PanelPoint(nx,ny),PanelPoint(nx,p.bottom),PanelPoint(p.left,p.bottom)))
        }
    }
}
