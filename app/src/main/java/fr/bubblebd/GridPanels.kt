package fr.bubblebd

import kotlin.math.*

/** Closed rows/columns corroborated by a white gutter between every pair of borders. */
object GridPanels {
    fun detect(pixels:IntArray,w:Int,h:Int):List<Panel> {
        fun ink(x:Int,y:Int):Boolean {val c=pixels[y*w+x];return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<170}
        fun paper(x:Int,y:Int):Boolean {val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>220}
        fun groups(lines:List<Int>):List<IntRange> {
            val out=mutableListOf<IntRange>()
            for(v in lines)if(out.isNotEmpty() && v<=out.last().last+1)out[out.lastIndex]=out.last().first..v else out.add(v..v)
            return out
        }
        val rows=groups((0 until h).filter {y->(0 until w).count {x->ink(x,y)}>w*.86})
        if(rows.size !in 2..8 || rows.size%2!=0 || rows.indices.any {it%2==0 && rows[it].last-rows[it].first>h*.012})return emptyList()
        val left=rows.minOf {line->(0 until w).firstOrNull {ink(it,line.first)} ?: w}
        val right=rows.maxOf {line->(0 until w).lastOrNull {ink(it,line.last)} ?: 0}
        val top=rows.first().first;val bottom=rows.last().last
        if(right-left<w*.80 || bottom-top<h*.30)return emptyList()
        if(rows.indices.any {i->val y=if(i%2==0)rows[i].first else rows[i].last;(left..right).count {ink(it,y)}<(right-left)*.92})return emptyList()
        fun gaps(lines:List<IntRange>,horizontal:Boolean,from:Int,to:Int):Boolean {
            for(i in 1 until lines.size-1 step 2) {
                val a=lines[i].last+1;val b=lines[i+1].first
                if(b-a<1 || b-a>(if(horizontal)h else w)*.08)return false
                var bright=0;var count=0
                val clearance=minOf(2,(b-a-1)/2)
                for(v in a+clearance until b-clearance)for(u in from..to) {count++;if(if(horizontal)paper(u,v) else paper(v,u))bright++}
                if(bright<count*.94)return false
            }
            return true
        }
        if(!gaps(rows,true,left,right))return emptyList()
        val result=mutableListOf<Panel>()
        for(yi in rows.indices step 2) {
            val rowTop=rows[yi].first;val rowBottom=rows[yi+1].last
            val columns=groups((left..right).filter {x->(rowTop..rowBottom).count {y->ink(x,y)}>(rowBottom-rowTop)*.90})
            if(columns.size !in 2..8 || columns.size%2!=0 || columns.any {it.last-it.first>w*.012} || !gaps(columns,false,rowTop,rowBottom))return emptyList()
            for(xi in columns.indices step 2) {
            val l=columns[xi].first;val r=columns[xi+1].last+1
            val t=rows[yi].first;val b=rows[yi+1].last+1
            if(r-l<w*.10 || b-t<h*.065)return emptyList()
            fun v(x:Int)=(t until b).count {y->ink(x,y)}.toFloat()/(b-t)
            fun hor(y:Int)=(l until r).count {x->ink(x,y)}.toFloat()/(r-l)
            if(minOf(v(l),v(r-1),hor(t),hor(b-1))<.88f)return emptyList()
            result.add(Panel(l.toFloat()/w,t.toFloat()/h,r.toFloat()/w,b.toFloat()/h))
            }
        }
        return result.takeIf {it.size>=2}.orEmpty()
    }
}
