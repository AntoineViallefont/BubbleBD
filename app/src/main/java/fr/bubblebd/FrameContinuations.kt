package fr.bubblebd

import kotlin.math.*

/** A neural split across a wooden beam is not a paper gutter. */
internal object FrameContinuations {
    fun repair(input:List<Panel>,pixels:IntArray,w:Int,h:Int,speech:List<Panel>):List<Panel> {
        val result=input.toMutableList();val contours=FrameContours(pixels,w,h,speech)
        fun core(p:Panel)=p.readingOrderBounds ?: p
        fun paper(x:Int,y:Int):Boolean {
            if(x !in 0 until w || y !in 0 until h)return false
            val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>230
        }
        fun masked(x:Int,y:Int)=speech.any {x.toFloat()/w in it.left..it.right && y.toFloat()/h in it.top..it.bottom}
        var merged=true
        while(merged) {
            merged=false
            loop@for(i in result.indices)for(j in i+1 until result.size) {
                val a=core(result[i]);val b=core(result[j]);val upper=if(a.top<b.top)a else b;val lower=if(upper===a)b else a
                if(result[i].focusExclusions.isNotEmpty() || result[j].focusExclusions.isNotEmpty() ||
                    abs(a.left-b.left)>.008f || abs(a.right-b.right)>.008f || lower.top-upper.bottom !in -.04f.. .005f ||
                    min(a.height,b.height)<.10f || upper.width<.25f || lower.bottom-upper.top>.50f)continue
                val joined=Panel(min(a.left,b.left),upper.top,max(a.right,b.right),lower.bottom)
                val fitted=contours.fit(joined) ?: continue
                val t=(fitted.top*h).toInt();val bottom=(fitted.bottom*h).toInt()
                fun exterior(x:Int):Boolean {
                    val ys=(t until bottom).filterNot {masked(x,it)}
                    return ys.size>(bottom-t)*.60f && ys.count {paper(x,it)}>ys.size*.90f
                }
                if(!exterior((fitted.left*w).toInt()-4) || !exterior((fitted.right*w).toInt()+4))continue
                val l=(fitted.left*w).toInt();val r=(fitted.right*w).toInt()
                val start=(lower.top*h).toInt()-3;val end=(upper.bottom*h).toInt()+3
                val realGutter=(min(start,end)..max(start,end)).any {y ->
                    y in 0 until h && (l until r).count {paper(it,y)}>(r-l)*.92f
                }
                if(realGutter)continue
                // The complete outer frame corroborates continuation. A caption
                // may move one fitted edge inward; keep the neural extent there.
                result[i]=Panel(if(abs(fitted.left-joined.left)<.006f)fitted.left else joined.left,
                    if(abs(fitted.top-joined.top)<.006f)fitted.top else joined.top,
                    if(abs(fitted.right-joined.right)<.006f)fitted.right else joined.right,
                    if(abs(fitted.bottom-joined.bottom)<.006f)fitted.bottom else joined.bottom)
                result.removeAt(j);merged=true;break@loop
            }
        }
        return result
    }
}
