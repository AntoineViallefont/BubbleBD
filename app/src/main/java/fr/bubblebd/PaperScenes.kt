package fr.bubblebd

import kotlin.math.*

/** Sparse monochrome scenes on unevenly lit paper, anchored by real closed frames. */
internal object PaperScenes {
    private data class Box(val l:Int,val t:Int,val r:Int,val b:Int) {
        val area get()=(r-l)*(b-t)
    }
    fun detect(rgb:IntArray,w:Int,h:Int,pixels:IntArray,width:Int,height:Int):List<Panel> {
        fun channel(c:Int,k:Int)=(c shr (16-k*8)) and 255
        fun paper(c:Int)=minOf(channel(c,0),channel(c,1),channel(c,2))>170 &&
            maxOf(channel(c,0),channel(c,1),channel(c,2))-minOf(channel(c,0),channel(c,1),channel(c,2))<50
        val light=rgb.filter(::paper)
        if(light.size<rgb.size*.55)return emptyList()
        fun tone(values:List<Int>)=IntArray(3) {k->values.map {channel(it,k)}.sorted()[(values.size*.80).toInt().coerceAtMost(values.lastIndex)]}
        val global=tone(light)
        val cell=24;val gw=(w+cell-1)/cell;val gh=(h+cell-1)/cell
        val tones=Array(gw*gh) {index ->
            val l=index%gw*cell;val t=index/gw*cell
            val values=(t until min(h,t+cell)).flatMap {y->(l until min(w,l+cell)).map {rgb[y*w+it]}}.filter(::paper)
            if(values.size<8)global else tone(values)
        }
        val mask=BooleanArray(w*h);val edge=BooleanArray(w*h)
        for(y in 0 until h)for(x in 0 until w) {
            val fx=(x.toFloat()/cell-.5f).coerceIn(0f,(gw-1).toFloat());val fy=(y.toFloat()/cell-.5f).coerceIn(0f,(gh-1).toFloat())
            val lx=fx.toInt();val ly=fy.toInt();val rx=min(gw-1,lx+1);val by=min(gh-1,ly+1)
            mask[y*w+x]=(0..2).any {k->
                val upper=tones[ly*gw+lx][k]*(1-(fx-lx))+tones[ly*gw+rx][k]*(fx-lx)
                val lower=tones[by*gw+lx][k]*(1-(fx-lx))+tones[by*gw+rx][k]*(fx-lx)
                upper*(1-(fy-ly))+lower*(fy-ly)-channel(rgb[y*w+x],k)>32
            }
            // Minimum sampling retains a fine black frame that averaging would erase.
            edge[y*w+x]=(y*height/h until max(y*height/h+1,(y+1)*height/h)).any {yy->
                (x*width/w until max(x*width/w+1,(x+1)*width/w)).any {xx->
                    val c=pixels[yy*width+xx];maxOf(channel(c,0),channel(c,1),channel(c,2))<200
                }
            }
        }
        if(mask.count {it}>mask.size*.34)return emptyList()
        val margin=max(2,(w*.02f).toInt())
        for(y in 0 until h)for(x in 0 until w)if(x<margin || x>=w-margin || y<margin || y>=h-margin) {mask[y*w+x]=false;edge[y*w+x]=false}
        data class Line(val l:Int,val y:Int,val r:Int)
        val lines=mutableListOf<Line>()
        for(y in 0 until h) {
            var start=-1
            for(x in 0..w) {
                val dark=x<w && (max(0,y-1)..min(h-1,y+1)).any {edge[it*w+x]}
                if(dark && start<0)start=x
                if(!dark && start>=0) {if(x-start>w*.18)lines.add(Line(start,y,x));start=-1}
            }
        }
        if(lines.size>256)return emptyList()
        val frames=mutableListOf<Box>()
        for(a in lines)for(b in lines) {
            val l=max(a.l,b.l);val r=min(a.r,b.r);val t=a.y;val bottom=b.y+1
            if(bottom-t<h*.09 || r-l<w*.18 || (r-l)*(bottom-t)>w*h*.65)continue
            // A page-wide footer cannot close several smaller frames.
            if(a.r-a.l>(r-l)*1.25f || b.r-b.l>(r-l)*1.25f)continue
            fun vertical(x:Int)=(t until bottom).count {y->(max(0,x-1)..min(w-1,x+1)).any {edge[y*w+it]}}.toFloat()/(bottom-t)
            if(min(vertical(l),vertical(r-1))<.85f)continue
            val box=Box(l,t,r,bottom)
            if(frames.none {q->abs(q.l-l)<3 && abs(q.r-r)<3 && abs(q.t-t)<5 && abs(q.b-bottom)<5})frames.add(box)
        }
        fun shared(a:Box,b:Box)=max(0,min(a.r,b.r)-max(a.l,b.l))*max(0,min(a.b,b.b)-max(a.t,b.t))
        val distinct=mutableListOf<Box>()
        for(p in frames.sortedByDescending {it.area})if(distinct.none {q->shared(p,q).toFloat()/(p.area+q.area-shared(p,q))>.85f})distinct.add(p)
        val closed=distinct.filter {p->distinct.none {q->q.area>p.area*1.1 && shared(p,q)>p.area*.95}}
        if(closed.size<3 || closed.none {it.r-it.l>w*.65})return emptyList()
        // The enclosing page border is not a connector between all its scenes.
        val verticals=(margin until w-margin).filter {x->(margin until h-margin).count {mask[it*w+x]}>h*.70}
        val left=verticals.filter {it<w*.12}.maxOrNull()?.plus(2) ?: margin
        val right=verticals.filter {it>w*.88}.minOrNull() ?: w-margin
        for(y in 0 until h)for(x in 0 until w)if(x<left || x>=right)mask[y*w+x]=false
        for(p in closed)for(y in p.t until p.b)for(x in p.l until p.r)mask[y*w+x]=true
        val groups=mutableListOf<Pair<Int,Int>>()
        for(p in closed.sortedBy {it.t}) {
            val index=groups.indexOfFirst {(t,b)->max(0,min(b,p.b)-max(t,p.t))>min(b-t,p.b-p.t)*.65}
            if(index<0)groups.add(p.t to p.b) else groups[index]=min(groups[index].first,p.t) to max(groups[index].second,p.b)
        }
        val rows=groups.sortedBy {it.first};val bands=mutableListOf<Pair<Int,Int>>()
        for((index,row) in rows.withIndex()) {
            val previous=rows.getOrNull(index-1)
            if(previous!=null && row.first-previous.second>h*.09)bands.add(previous.second to row.first)
            val t=if(previous!=null && previous.second>row.first)(previous.second+row.first)/2 else row.first
            val next=rows.getOrNull(index+1)
            val b=if(next!=null && row.second>next.first)(row.second+next.first)/2 else row.second
            bands.add(t to b)
        }
        if(bands.size !in 3..5)return emptyList()
        val boxes=mutableListOf<Box>()
        fun columns(l:Int,t:Int,r:Int,b:Int,depth:Int=0) {
            if(depth>5)return
            val anchor=closed.filter {q->q.l>=l && q.r<=r && min(q.b,b)-max(q.t,t)>(b-t)*.8 && q.r-q.l<r-l-w*.02}.minByOrNull {it.l}
            if(anchor!=null) {
                if(anchor.l-l>w*.12)columns(l,t,anchor.l,b,depth+1)
                boxes.add(Box(anchor.l,t,anchor.r,b))
                if(r-anchor.r>w*.12)columns(anchor.r,t,r,b,depth+1)
                return
            }
            val hist=IntArray(r-l) {x->(t until b).count {mask[it*w+x+l]}}
            var start=-1;val gaps=mutableListOf<IntRange>()
            for(x in 0..hist.size) {
                val empty=x<hist.size && hist[x]<=max(1.0,(b-t)*.03)
                if(empty && start<0)start=x
                if(!empty && start>=0) {if(x-start>=1 && start>w*.12 && r-l-x>w*.12)gaps.add(start until x);start=-1}
            }
            val gap=gaps.maxByOrNull {it.count()}
            if(gap!=null) {val mid=l+(gap.first+gap.last+1)/2;columns(l,t,mid,b,depth+1);columns(mid,t,r,b,depth+1);return}
            var x0=r;var x1=l;var inkTop=b;var inkBottom=t
            for(y in t until b)for(x in l until r)if(mask[y*w+x]) {x0=min(x0,x);x1=max(x1,x+1);inkTop=min(inkTop,y);inkBottom=max(inkBottom,y+1)}
            if(x1-x0>w*.08 && inkBottom-inkTop>h*.09)boxes.add(Box(x0,t,x1,b))
        }
        for((t,b) in bands)columns(left,t,right,b)
        if(boxes.size<=closed.size)return emptyList()
        return boxes.map {b->val core=Panel(b.l.toFloat()/w,b.t.toFloat()/h,b.r.toFloat()/w,b.b.toFloat()/h)
            core.copy(left=max(0f,core.left-.025f),right=min(1f,core.right+.025f),top=max(0f,core.top-.015f),bottom=min(1f,core.bottom+.015f),readingOrderBounds=core)
        }
    }
}
