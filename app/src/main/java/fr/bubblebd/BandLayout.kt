package fr.bubblebd

import kotlin.math.*

/** Full-width paper gaps and closed outer borders outrank rectangular décor. */
internal object BandLayout {
    fun confirm(geometry:List<Panel>,pixels:IntArray,w:Int,h:Int,speech:List<Panel>,proposals:List<PanelCandidate> = emptyList()):List<Panel> {
        var cells=geometry.filterNot {it.isWholePage}.map {it.readingOrderBounds ?: it}.sortedBy {it.top}
        if(cells.isEmpty())return emptyList()
        val left=cells.minOf {it.left};val right=cells.maxOf {it.right}
        if(right-left<.75f || cells.last().bottom-cells.first().top<.55f)return emptyList()
        val masks=BooleanArray(w*h)
        for(p in speech)for(y in max(0,((p.top-.025f)*h).toInt()) until min(h,((p.bottom+.025f)*h).toInt()+1))
            for(x in max(0,((p.left-.025f)*w).toInt()) until min(w,((p.right+.025f)*w).toInt()+1))masks[y*w+x]=true
        fun paper(x:Int,y:Int):Boolean {
            if(x !in 0 until w || y !in 0 until h)return false
            val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>225
        }
        val l=(left*w).toInt();val r=(right*w).toInt()
        // Derive rows from full-width paper, independently of décor fragments.
        val startY=(cells.minOf {it.top}*h).toInt().coerceIn(0,h-1)
        val endY=(cells.maxOf {it.bottom}*h).toInt().coerceIn(startY+1,h)
        val gaps=mutableListOf<IntRange>();var gapStart=-1
        for(y in startY until endY) {
            val unmasked=(l until r).filterNot {masks[y*w+it]}
            val blank=unmasked.size>(r-l)*.65f && unmasked.count {paper(it,y)}>unmasked.size*.97f
            if(blank && gapStart<0)gapStart=y
            if(gapStart>=0 && (!blank || y==endY-1)) {
                val end=if(blank)y+1 else y
                if(end-gapStart>=2)gaps.add(gapStart until end)
                gapStart=-1
            }
        }
        val rows=mutableListOf<Pair<Int,Int>>();var from=startY
        for(gap in gaps) {if(gap.first-from>h*.10f)rows.add(from to gap.first);from=gap.last+1}
        if(endY-from>h*.10f)rows.add(from to endY)
        if(rows.size in 2..3)cells=rows.map {(t,b)->Panel(left,t.toFloat()/h,right,b.toFloat()/h)}
        if(cells.size !in 2..3 || cells.any {it.height<.12f})return emptyList()
        for((a,b) in cells.zipWithNext()) {
            if(b.top-a.bottom !in .003f.. .035f)return emptyList()
            val y=((a.bottom+b.top)*h*.5f).toInt()
            if((l until r).count {paper(it,y)}<(r-l)*.97f)return emptyList()
        }
        val contours=FrameContours(pixels,w,h,speech)
        // Broad rows cannot erase independently closed smaller frames, including
        // inserts that interrupt the row boundary. Neural proposals only locate
        // candidates; all four physical borders must corroborate each child.
        fun area(p:Panel)=p.width*p.height
        fun shared(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        for(row in cells) {
            val children=proposals.filter {d ->d.confidence>.85f && area(d.bounds)<area(row)*.70f &&
                shared(row,d.bounds)/area(d.bounds)>.85f}.mapNotNull {contours.fit(it.bounds)}
            if(children.indices.any {i ->(i+1 until children.size).any {j ->
                val a=children[i];val b=children[j]
                val first=if(a.left<b.left)a else b;val second=if(first===a)b else a
                val top=max(a.top,b.top);val bottom=min(a.bottom,b.bottom)
                if(second.left-first.right !in .001f.. .035f || bottom-top<min(a.height,b.height)*.65f)false
                else {
                    val x=((first.right+second.left)*.5f*w).toInt()
                    val ys=((top*h).toInt() until (bottom*h).toInt()).filterNot {masks[it*w+x]}
                    ys.size>(bottom-top)*h*.50f && ys.count {paper(x,it)}>ys.size*.92f
                }
            }})return emptyList()
        }
        val result=mutableListOf<Panel>()
        for(p in cells) {
            val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            fun edge(v:Int,from:Int,to:Int,vertical:Boolean):Int? = (v-max(4,((if(vertical)w else h)*.025f).toInt())..v+max(4,((if(vertical)w else h)*.025f).toInt()))
                .filter {it in 4 until (if(vertical)w else h)-4}
                .maxByOrNull {contours.support(it,from,to,vertical)}
                ?.takeIf {contours.support(it,from,to,vertical)>.80f}
            val x1=edge(l,t,b,true) ?: return emptyList()
            val x2=edge(r,t,b,true) ?: return emptyList()
            val y1=edge(t,x1,x2,false) ?: return emptyList()
            val y2=edge(b,x1,x2,false) ?: return emptyList()
            for((x,d) in listOf(x1 to -4,x2 to 4)) {
                val ys=(y1 until y2).filterNot {masks[it*w+x]}
                if(ys.size<(y2-y1)*.50f || ys.count {paper(x+d,it)}<ys.size*.92f)return emptyList()
            }
            // An actual column gutter keeps its independent frames. A carved
            // door or a pale wall has no continuous exterior paper strip.
            for(x in x1+max(5,(w*.10f).toInt()) until x2-max(5,(w*.10f).toInt())) {
                val ys=(y1 until y2).filterNot {masks[it*w+x]}
                if(ys.size>(y2-y1)*.65f && ys.count {paper(x,it)}>=ys.size*.96f &&
                    contours.support(x-3,y1,y2,true)>.78f && contours.support(x+3,y1,y2,true)>.78f)return emptyList()
            }
            for(y in y1+max(5,(h*.08f).toInt()) until y2-max(5,(h*.08f).toInt())) {
                val xs=(x1 until x2).filterNot {masks[y*w+it]}
                if(xs.size>(x2-x1)*.80f && xs.count {paper(it,y)}>=xs.size*.97f &&
                    contours.support(y-3,x1,x2,false)>.78f && contours.support(y+3,x1,x2,false)>.78f)return emptyList()
            }
            result.add(Panel(x1.toFloat()/w,y1.toFloat()/h,(x2+1f)/w,(y2+1f)/h))
        }
        // A mostly white portrait may be trimmed away while its thin grey
        // frame continues the same stack. Recover only closed outer borders,
        // never an isolated face or page number.
        val last=result.last();val x1=(last.left*w).toInt();val x2=(last.right*w).toInt()-1
        val firstY=(last.bottom*h).toInt()+max(5,(h*.006f).toInt())
        val limit=min(h-5,firstY+(h*.35f).toInt())
        val lines=(firstY..limit).filter {y->contours.support(y,x1,x2,false)>.90f}
        val groups=mutableListOf<IntRange>()
        for(y in lines)if(groups.isNotEmpty() && y<=groups.last().last+4)groups[groups.lastIndex]=groups.last().first..y else groups.add(y..y)
        if(groups.size==2 && groups.all {it.last-it.first<h*.012f}) {
            val top=groups[0].first;val bottom=groups[1].last+1
            fun side(v:Int):Int = (max(4,v-6)..min(w-5,v+6))
                .maxByOrNull {contours.support(it,top,bottom,true)} ?: v
            val extraLeft=side(x1);val extraRight=side(x2)
            if(top-last.bottom*h<h*.035f && bottom-top in (h*.065f).toInt()..(h*.30f).toInt() &&
                (x1 until x2).count {paper(it,firstY)}>(x2-x1)*.97f &&
                contours.support(extraLeft,top,bottom,true)>.85f && contours.support(extraRight,top,bottom,true)>.85f &&
                (top until bottom).count {paper(extraLeft-4,it) && paper(extraRight+4,it)}>(bottom-top)*.92f) {
                result.add(Panel(extraLeft.toFloat()/w,top.toFloat()/h,(extraRight+1f)/w,bottom.toFloat()/h))
            }
        }
        return result
    }
}
