package fr.bubblebd

import kotlin.math.*

/** An unframed scene can complete a row beside two independently closed frames. */
internal object PaperRowPanels {
    fun repair(input:List<Panel>,proposals:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,texts:List<Panel>):List<Panel> {
        val contours=FrameContours(pixels,w,h,texts)
        val result=input.toMutableList()
        val strong=proposals.filter {it.confidence>.85f && it.bounds.width<.5f && it.bounds.height in .12f.. .45f}
        for(seed in strong) {
            val row=strong.filter {abs(it.bounds.top-seed.bounds.top)<.015f && abs(it.bounds.bottom-seed.bounds.bottom)<.02f}.sortedBy {it.bounds.left}
            if(row.size<3 || row.zipWithNext().any {(a,b)->b.bounds.left-a.bounds.right !in -.004f.. .03f})continue
            val framed=row.filter {contours.fit(it.bounds)!=null}
            val free=row.filter {it !in framed}
            if(framed.size<2 || free.size!=1)continue
            val p=free.single().bounds
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            fun white(x:Int,y:Int):Boolean {val c=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>230}
            val seen=(l until r step 3).sumOf {x ->(t until b step 3).count()}
            val paper=(l until r step 3).sumOf {x ->(t until b step 3).count {y->white(x,y)}}
            if(seen==0 || paper<seen*.50f)continue
            if(listOf(l to t,r to t,l to b,r to b).count {(x,y)->white(x,y)}<3)continue
            val old=result.filter {val c=it.readingOrderBounds ?: it;abs(c.top-p.top)<.02f && abs(c.bottom-p.bottom)<.025f}
            if(old.size>=row.size || old.size<2)continue
            result.removeAll(old.toSet());result.addAll(row.map {contours.fit(it.bounds) ?: it.bounds})
        }
        // Beside a closed frame, a figure on white paper can share the row even
        // when geometric trimming found only its head and omitted its speech.
        for(i in result.indices) {
            val c=result[i].readingOrderBounds ?: result[i]
            val proposed=proposals.filter {it.confidence>.60f && it.bounds.width<.30f &&
                it.bounds.top<c.top-.04f && abs(it.bounds.bottom-c.bottom)<.025f &&
                abs(it.bounds.left-c.left)<.025f && abs(it.bounds.right-c.right)<.025f}
                .minByOrNull {it.bounds.top}?.bounds ?: continue
            if(contours.fit(proposed)!=null)continue
            val peer=result.any {other ->val q=other.readingOrderBounds ?: other
                abs(q.top-proposed.top)<.02f && abs(q.bottom-proposed.bottom)<.025f &&
                    (q.left-proposed.right in 0f.. .04f || proposed.left-q.right in 0f.. .04f) && contours.fit(q)!=null}
            if(!peer || texts.none {it.top>=proposed.top && it.bottom<c.top+.02f && it.left<proposed.right && it.right>proposed.left})continue
            result[i]=proposed.copy(readingOrderBounds=proposed)
        }
        return result
    }
}
