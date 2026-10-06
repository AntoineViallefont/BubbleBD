package fr.bubblebd

import kotlin.math.*

/** Recover thin motion marks on paper beside the last frame in a row. */
internal object OuterMarks {
    fun expand(frames:List<Panel>,pixels:IntArray,w:Int,h:Int):List<Panel> {
        val cores=frames.map {it.readingOrderBounds ?: it}
        return frames.mapIndexed {i,p ->
            val c=cores[i]
            if(c.width>=.70f || p.focusExclusions.isNotEmpty())return@mapIndexed p
            val l=(c.right*w).toInt().coerceIn(0,w-1);val r=min(w,l+max(4,(w*.05f).toInt()))
            val t=(c.top*h).toInt().coerceIn(0,h-1);val b=(c.bottom*h).toInt().coerceIn(t+1,h)
            if(cores.indices.any {j ->j!=i && cores[j].left>=c.right && cores[j].left*w<r && min(c.bottom,cores[j].bottom)>max(c.top,cores[j].top)})return@mapIndexed p
            val seen=BooleanArray((r-l)*(b-t));val queue=IntArray(seen.size)
            fun dark(x:Int,y:Int):Boolean {val v=pixels[y*w+x];return maxOf((v shr 16)and 255,(v shr 8)and 255,v and 255)<190}
            var right=p.right
            for(y0 in t until b)for(x0 in l until r) {
                val seed=(y0-t)*(r-l)+x0-l
                if(seen[seed] || !dark(x0,y0))continue
                var head=0;var tail=0;var x1=r;var y1=b;var x2=l;var y2=t
                fun push(x:Int,y:Int) {
                    if(x !in l until r || y !in t until b)return
                    val k=(y-t)*(r-l)+x-l
                    if(!seen[k] && dark(x,y)) {seen[k]=true;queue[tail++]=k}
                }
                push(x0,y0)
                while(head<tail) {
                    val k=queue[head++];val x=l+k%(r-l);val y=t+k/(r-l)
                    x1=min(x1,x);x2=max(x2,x+1);y1=min(y1,y);y2=max(y2,y+1)
                    push(x-1,y);push(x+1,y);push(x,y-1);push(x,y+1)
                }
                if(tail<8 || y2-y1<h*.01f || y2-y1>h*.10f || x2>=r || y1<=t || y2>=b)continue
                val a=max(0,x1-4);val z=min(w,x2+5);val top=max(0,y1-4);val bottom=min(h,y2+5)
                val paper=(a until z).sumOf {x ->(top until bottom).count {y ->val v=pixels[y*w+x];minOf((v shr 16)and 255,(v shr 8)and 255,v and 255)>202}}.toFloat()/((z-a)*(bottom-top))
                if(paper>.65f)right=max(right,(x2+3f)/w)
            }
            p.copy(right=min(1f,right))
        }
    }
}
