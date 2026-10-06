package fr.bubblebd

import kotlin.math.*

/** Reconcile layout against visible gutters, never against a title or a reference page. */
object LayoutEvidence {
    private fun area(p:Panel)=p.width*p.height
    private fun shared(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    private fun union(a:Panel,b:Panel)=Panel(min(a.left,b.left),min(a.top,b.top),max(a.right,b.right),max(a.bottom,b.bottom))
    /** Three shared outer edges identify a repeated crop only when its inner
     * edge does not close a separate frame. Scores alone cannot erase an inset. */
    fun removeCrops(input:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int):List<PanelCandidate> {
        val speech=input.filter {it.text && it.confidence>.45f}.map {it.bounds}
        val contours=FrameContours(pixels,w,h,speech)
        val panels=input.filter {!it.text && it.confidence>.45f}
        return input.filterNot {d ->
            if(d.text || d.confidence<=.45f)return@filterNot false
            val p=d.bounds
            panels.any {other ->
                val q=other.bounds
                if(other===d || area(p)/area(q) !in .30f.. .80f || shared(p,q)/area(p)<.97f)return@any false
                val aligned=listOf(abs(p.left-q.left)*w,abs(p.right-q.right)*w,
                    abs(p.top-q.top)*h,abs(p.bottom-q.bottom)*h).count {it<=max(3f,w*.008f)}
                aligned>=3 && contours.fit(p)==null && contours.fitColour(p)==null
            }
        }
    }
    fun separatedStack(frames:List<Panel>,pixels:IntArray,w:Int,h:Int):Boolean {
        if(frames.size !in 2..3)return false
        val sorted=frames.sortedBy {it.top}
        if(sorted.any {it.width<.75f || it.height<.12f})return false
        if(sorted.any {kotlin.math.abs(it.left-sorted.first().left)>.025f || kotlin.math.abs(it.right-sorted.first().right)>.025f})return false
        fun paper(x:Int,y:Int):Boolean {
            if(x !in 0 until w || y !in 0 until h)return false
            val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>230
        }
        for((a,b) in sorted.zipWithNext()) {
            val gap=b.top-a.bottom
            if(gap !in .006f.. .08f)return false
            val l=(maxOf(a.left,b.left)*w).toInt();val r=(minOf(a.right,b.right)*w).toInt()
            val y=((a.bottom+b.top)*.5f*h).toInt()
            if((l until r).count {paper(it,y)}<(r-l)*.96f)return false
        }
        return sorted.all {p->
            val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            val l=(p.left*w).toInt()-3;val r=(p.right*w).toInt()+3
            (t until b).count {paper(l,it) && paper(r,it)}>(b-t)*.90f
        }
    }
    
    fun continuousScene(a:Panel,b:Panel,pixels:IntArray,w:Int,h:Int):Boolean {
        val left=if(a.left<b.left)a else b;val right=if(left===a)b else a
        if(abs(a.top-b.top)>.012f || abs(a.bottom-b.bottom)>.012f || right.left-left.right !in .003f.. .025f || min(a.width,b.width)/max(a.width,b.width)>=.45f)return false
        val x=((left.right+right.left)*.5f*w).toInt();val t=(max(a.top,b.top)*h).toInt()+3;val bottom=(min(a.bottom,b.bottom)*h).toInt()-3
        if(bottom-t<h*.07f)return false
        fun rgb(xx:Int,y:Int)=pixels[y.coerceIn(0,h-1)*w+xx.coerceIn(0,w-1)]
        fun difference(u:Int,v:Int)=maxOf(abs(((u shr 16)and 255)-((v shr 16)and 255)),abs(((u shr 8)and 255)-((v shr 8)and 255)),abs((u and 255)-(v and 255)))
        if((t until bottom).count {val c=rgb(x,it);minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>242}.toFloat()/(bottom-t)>.80f)return false
        val blend=(t until bottom).count {y ->difference(rgb(x,y),rgb((right.left*w).toInt()+4,y))<30}.toFloat()/(bottom-t)
        val edge=(t until bottom).count {y ->difference(rgb(x,y),rgb((left.right*w).toInt()-3,y))>45}.toFloat()/(bottom-t)
        return blend>.90f && edge>.90f
    }
    fun repair(input:List<Panel>,geometry:List<Panel>,proposals:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int):List<Panel> {
        val result=input.toMutableList()
        fun rgb(x:Int,y:Int)=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)]
        fun difference(a:Int,b:Int)=maxOf(abs(((a shr 16)and 255)-((b shr 16)and 255)),abs(((a shr 8)and 255)-((b shr 8)and 255)),abs((a and 255)-(b and 255)))
        fun paper(x:Int,y:Int):Boolean {val c=rgb(x,y);return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>242}
        fun core(p:Panel)=p.readingOrderBounds ?: p
        // A complete tall illustration can be missed while its face fragment is found.
        for(d in proposals.filter {it.confidence>.75f}) {
            val p=d.bounds
            val pieces=result.filter {shared(core(it),p)/area(core(it))>.90f}
            if(pieces.size==1 && area(p)>area(core(pieces.single()))*2.8f && result.none {it !in pieces && shared(core(it),p)/area(core(it))>.25f}) {
                result.removeAll(pieces.toSet());result.add(p)
            }
        }
        // A column of neural frames and an adjacent tall illustration explain the
        // empty remainder of a geometric region; do not merge the column into one case.
        for(parent in result.toList()) {
            val p=core(parent)
            val children=proposals.filter {it.confidence>.80f && area(it.bounds)<area(p)*.70f && shared(it.bounds,p)/area(it.bounds)>.85f}.sortedBy {it.bounds.top}
            if(children.size !in 2..3 || children.sumOf {area(it.bounds).toDouble()}<area(p)*.45f)continue
            if(!children.zipWithNext().all {(a,b)->abs(a.bounds.left-b.bounds.left)<.02f && abs(a.bounds.right-b.bounds.right)<.02f && b.bounds.top>=a.bounds.bottom-.01f})continue
            val columnLeft=children.minOf {it.bounds.left}
            val remainder=Panel(p.left,p.top,columnLeft,p.bottom)
            if(remainder.width<.05f || proposals.none {it.confidence>.70f && shared(it.bounds,p)/area(it.bounds)<.65f && shared(it.bounds,remainder)/area(remainder)>.75f})continue
            result.remove(parent);result.addAll(children.map {it.bounds})
        }
        // A colored wall can imitate a white gutter. Keep it in the scene when the
        // supposed separator blends into the interior on one side along its height.
        var merged=true
        while(merged) {
            merged=false
            loop@for(i in result.indices)for(j in i+1 until result.size) {
                val a=core(result[i]);val b=core(result[j]);val left=if(a.left<b.left)a else b;val right=if(left===a)b else a
                if(abs(a.top-b.top)>.012f || abs(a.bottom-b.bottom)>.012f || right.left-left.right !in .003f.. .025f)continue
                val x=((left.right+right.left)*.5f*w).toInt();val t=(max(a.top,b.top)*h).toInt()+3;val bottom=(min(a.bottom,b.bottom)*h).toInt()-3
                if(bottom-t<h*.07f)continue
                if((t until bottom).count {paper(x,it)}.toFloat()/(bottom-t)>.80f)continue
                val blend=(t until bottom).count {y ->difference(rgb(x,y),rgb((right.left*w).toInt()+4,y))<30}.toFloat()/(bottom-t)
                val edge=(t until bottom).count {y ->difference(rgb(x,y),rgb((left.right*w).toInt()-3,y))>45}.toFloat()/(bottom-t)
                val narrow=min(left.width,right.width)/max(left.width,right.width)<.45f
                if(blend>.90f && edge>.90f && narrow) {result[i]=union(result[i],result[j]);result.removeAt(j);merged=true;break@loop}
            }
        }
        // Two straight contrast edges enclosing a narrow strip of the surrounding
        // background reveal adjacent inserts even when the gutter is not white.
        for(parent in result.toList()) {
            val p=core(parent);if(p.width<.55f || p.height !in .12f.. .35f)continue
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt()+3;val b=(p.bottom*h).toInt()-3
            if(b-t<12)continue
            fun edge(x:Int)=(t until b).count {difference(rgb(x-2,it),rgb(x+2,it))>45}.toFloat()/(b-t)
            val edges=(l+(r-l)/4..r-(r-l)/4).filter {edge(it)>.73f}
            val pair=edges.flatMap {x ->edges.filter {it-x in 5..max(6,(w*.025f).toInt())}.map {x to it}}
                .filter {(a,z) ->
                    val x=(a+z)/2
                    val exteriorPaper=(t until b).count {paper(l-5,it) && paper(r+5,it)}.toFloat()/(b-t)>.80f
                    if(exteriorPaper)(t until b).count {paper(x,it)}.toFloat()/(b-t)>.85f
                    else (t until b).count {y ->min(difference(rgb(x,y),rgb(l-5,y)),difference(rgb(x,y),rgb(r+5,y)))<30}.toFloat()/(b-t)>.45f
                }.maxByOrNull {(a,z)->edge(a)+edge(z)} ?: continue
            result.remove(parent)
            result.add(Panel(p.left,p.top,pair.first.toFloat()/w,p.bottom));result.add(Panel(pair.second.toFloat()/w,p.top,p.right,p.bottom))
        }
        // Overlapping crops of the same background can surround the insert row.
        // They share the lower edge and have no paper divider at the cropped top.
        merged=true
        while(merged) {
            merged=false
            loop@for(i in result.indices)for(j in i+1 until result.size) {
                val a=result[i];val b=result[j]
                if(a.width<.75f || b.width<.75f || min(a.height,b.height)<.30f || abs(a.bottom-b.bottom)>.02f || shared(a,b)/min(area(a),area(b))<.75f)continue
                val large=if(area(a)>area(b))a else b;val small=if(large===a)b else a
                if(area(large)<area(small)*1.2f || result.none {q->q!==a && q!==b && area(q)<area(large)*.4f && shared(q,large)/area(q)>.9f && shared(q,small)/area(q)<.1f})continue
                val l=(max(a.left,b.left)*w).toInt();val r=(min(a.right,b.right)*w).toInt();val y=(small.top*h).toInt()
                if((-2..2).any {dy->(l until r).count {paper(it,y+dy)}.toFloat()/(r-l)>.85f})continue
                var full=union(a,b)
                geometry.filter {it.width>.90f && shared(it,full)/area(full)>.90f}.minByOrNull(::area)?.let {g->full=full.copy(left=min(full.left,g.left),right=max(full.right,g.right))}
                val inserts=result.filter {q ->q!==a && q!==b && area(q)<area(full)*.40f && shared(q,full)/area(q)>.90f}
                result[i]=full.copy(focusExclusions=inserts);result.removeAt(j);merged=true;break@loop
            }
        }
        // Keep a background behind framed inserts, including artwork above the
        // gutter-trimmed geometric region. A paper-wide gap stops the extension.
        for(g in geometry.filter {it.width>.75f && it.height>.50f && !it.isWholePage}) {
            val p=core(g)
            if(result.any {shared(core(it),p)/area(p)>.50f})continue
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();var top=(p.top*h).toInt()
            while(top>0 && (l until r).count {paper(it,top-1)}.toFloat()/(r-l)<.85f)top--
            val full=Panel(p.left,top.toFloat()/h,p.right,p.bottom)
            val inserts=result.filter {it.width>.35f && it.height in .07f.. .25f && shared(core(it),full)/area(core(it))>.90f}
            if(inserts.size<2 || inserts.zipWithNext().any {(a,b)->abs(a.left-b.left)>.04f || abs(a.right-b.right)>.04f})continue
            result.removeAll {q ->q !in inserts && shared(core(q),full)/area(core(q))>.90f && area(core(q))<area(full)*.12f}
            result.add(full.copy(readingOrderBounds=p,focusExclusions=inserts))
        }
        return result
    }
}
