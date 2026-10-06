package fr.bubblebd

import kotlin.math.*

/** Two aligned inserts need a continuous diagonal paper gutter, not just AI boxes. */
internal object SlantedPanels {
    fun find(predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int):List<Pair<Panel,Panel>> {
        val candidates=predictions.filter {!it.text && it.confidence>.90f && it.bounds.width>.65f && it.bounds.height in .10f.. .28f}
        val result=mutableListOf<Pair<Panel,Panel>>()
        fun white(x:Int,y:Int):Boolean {
            if(x !in 0 until w || y !in 0 until h)return false
            val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>230
        }
        for(a in candidates)for(b in candidates) {
            val p=a.bounds;val q=b.bounds
            if(q.top<=p.top || q.top>=p.bottom || abs(p.left-q.left)>.025f || abs(p.right-q.right)>.025f)continue
            val l=(max(p.left,q.left)*w).toInt()+6;val r=(min(p.right,q.right)*w).toInt()-6
            if(r<=l)continue
            val lo=max(0,(q.top*h).toInt()-12);val hi=min(h-1,(p.bottom*h).toInt()+12)
            var best=0f;var start=0;var end=0
            for(y0 in lo..hi step 2)for(y1 in lo..hi step 2) {
                if(abs(y1-y0)<h*.025f || abs(y1-y0)>h*.12f)continue
                var hits=0;var seen=0;var paper=0
                for(x in l..r step 4) {
                    val y=(y0+(y1-y0)*(x-l).toFloat()/(r-l)).roundToInt()
                    seen++
                    val clear=(-2..2).any {white(x,y+it)}
                    if(clear)paper++
                    fun difference(d:Int):Int {
                        val a=pixels[y.coerceIn(0,h-1)*w+x];val c=pixels[(y+d).coerceIn(0,h-1)*w+x]
                        return maxOf(abs(((a shr 16)and 255)-((c shr 16)and 255)),abs(((a shr 8)and 255)-((c shr 8)and 255)),abs((a and 255)-(c and 255)))
                    }
                    val flat=difference(-1)<20 && difference(1)<20
                    if(clear || (flat && maxOf(difference(-8),difference(8))>35))hits++
                }
                val score=if(paper.toFloat()/seen>.45f)hits.toFloat()/seen else 0f
                if(score>best) {best=score;start=y0;end=y1}
            }
            if(best<.86f)continue
            val slope=(end-start).toFloat()/(r-l)
            val left=p.left;val right=p.right
            val yl=(start+slope*(left*w-l))/h;val yr=(start+slope*(right*w-l))/h
            val gap=3f/h
            val upper=p.copy(focusOutline=listOf(PanelPoint(left,p.top),PanelPoint(right,p.top),PanelPoint(right,yr-gap),PanelPoint(left,yl-gap)))
            val lower=q.copy(focusOutline=listOf(PanelPoint(q.left,yl+gap),PanelPoint(q.right,yr+gap),PanelPoint(q.right,q.bottom),PanelPoint(q.left,q.bottom)))
            result.add(p to upper);result.add(q to lower)
        }
        return result.distinctBy {it.first}
    }
}
