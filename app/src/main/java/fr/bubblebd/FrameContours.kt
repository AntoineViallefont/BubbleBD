package fr.bubblebd

import kotlin.math.*

/** Four straight edges, with speech interruptions excluded from their support. */
internal class FrameContours(val pixels:IntArray,val w:Int,val h:Int,val speech:List<Panel>) {
    private fun level(x:Int,y:Int):Int {val c=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)];return (((c shr 16)and 255)*3+((c shr 8)and 255)*6+(c and 255))/10}
    private val masked=BooleanArray(w*h).also {mask ->
        for(p in speech)for(y in max(0,(p.top*h).toInt()) until min(h,(p.bottom*h).toInt()+1))
            for(x in max(0,(p.left*w).toInt()) until min(w,(p.right*w).toInt()+1))mask[y*w+x]=true
    }
    private fun covered(x:Int,y:Int)=masked[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)]
    private val fitted=mutableMapOf<Panel,Panel?>()
    internal fun support(v:Int,a:Int,b:Int,vertical:Boolean):Float {
        var seen=0;var hits=0
        for(u in a until b) {
            val x=if(vertical)v else u;val y=if(vertical)u else v
            if(x !in 2 until w-2 || y !in 2 until h-2 || covered(x,y))continue
            seen++
            val c=level(x,y)
            if((c<190 || c>225) && (-3..3 step 6).any {d->abs(c-level(x+if(vertical)d else 0,y+if(vertical)0 else d))>25})hits++
        }
        return if(seen<max(4,(b-a)/20))0f else hits.toFloat()/seen
    }
    fun fit(p:Panel):Panel? {
        if(fitted.containsKey(p))return fitted[p]
        return fitUncached(p).also {fitted[p]=it}
    }
    /** A caption is not a lower border when both side frames continue below it. */
    fun continueBelowCaption(p:Panel):Panel? {
        if(p.width !in .10f.. .35f || p.height !in .12f.. .40f || p.focusOutline.isNotEmpty())return null
        val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
        fun ink(x:Int,y:Int)=x in 3 until w-3 && y in 3 until h-3 && level(x,y)<190
        fun side(x:Int,y:Int,out:Int)=ink(x,y) && level(x+out*3,y)>235
        fun best(v:Int,out:Int)=(v-3..v+3).filter {it in 3 until w-3}.maxByOrNull {x->(t+4 until b-3).count {side(x,it,out)}} ?: v
        val left=best(l,-1);val right=best(r,1)
        if((t+4 until b-3).count {side(left,it,-1)}<(b-t-7)*.70f || (t+4 until b-3).count {side(right,it,1)}<(b-t-7)*.70f)return null
        var bottom=-1
        for(y in b+max(5,(h*.018f).toInt())..min(h-4,b+(h*.16f).toInt())) {
            fun continuous(x:Int,out:Int):Boolean {
                var gap=0;var hits=0
                for(v in b-2 until y-2) {
                    if(side(x,v,out)) {gap=0;hits++} else if(++gap>2)return false
                }
                return hits>(y-b)*.85f
            }
            if(!continuous(left,-1) || !continuous(right,1))continue
            val xs=left+3 until right-2
            if(xs.count {ink(it,y)}<xs.count()*.88f || xs.count {level(it,y+3)>235}<xs.count()*.88f)continue
            bottom=y+1
        }
        return if(bottom<0)null else p.copy(bottom=bottom.toFloat()/h,readingOrderBounds=null)
    }
    private fun fitUncached(p:Panel):Panel? {
        if(p.width<.10f || p.height<.075f || p.width*p.height>.65f)return null
        val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
        fun best(v:Int,a:Int,z:Int,vertical:Boolean,radius:Int):Pair<Int,Float> {
            val limit=if(vertical)w else h
            return (max(2,v-radius)..min(limit-3,v+radius)).map {x->x to support(x,a,z,vertical)}
                .maxByOrNull {it.second-abs(it.first-v).toFloat()/max(1,radius)*.15f + (a until z).filterNot {u -> covered(if(vertical)it.first else u,if(vertical)u else it.first)}.let {us -> if(us.isEmpty())0f else us.count {u ->level(if(vertical)it.first else u,if(vertical)u else it.first)<190}.toFloat()/us.size*.12f}} ?: (v to 0f)
        }
        val left=best(l,t,b,true,max(5,(w*.025f).toInt()));val right=best(r,t,b,true,max(5,(w*.05f).toInt()))
        if(min(left.second,right.second)<.76f)return null
        val top=best(t,left.first,right.first,false,max(5,(h*.018f).toInt()));val bottom=best(b,left.first,right.first,false,max(5,(h*.018f).toInt()))
        if(min(top.second,bottom.second)<.72f || max(top.second,bottom.second)<.85f)return null
        return Panel(left.first.toFloat()/w,top.first.toFloat()/h,(right.first+1f)/w,(bottom.first+1f)/h)
    }
    /** Coloured frames can have similar luminance on both sides of their edge. */
    fun fitColour(p:Panel):Panel? {
        if(p.width<.10f || p.height<.075f || p.width*p.height>.65f)return null
        fun difference(x:Int,y:Int,dx:Int,dy:Int):Int {
            val a=pixels[y*w+x];val b=pixels[(y+dy)*w+x+dx]
            return maxOf(abs(((a shr 16)and 255)-((b shr 16)and 255)),abs(((a shr 8)and 255)-((b shr 8)and 255)),abs((a and 255)-(b and 255)))
        }
        fun score(v:Int,a:Int,b:Int,vertical:Boolean):Float {
            var seen=0;var hits=0
            for(u in a until b) {
                val x=if(vertical)v else u;val y=if(vertical)u else v
                if(x !in 4 until w-4 || y !in 4 until h-4 || covered(x,y))continue
                seen++
                if(listOf(-3,3).any {d->difference(x,y,if(vertical)d else 0,if(vertical)0 else d)>25})hits++
            }
            return if(seen<max(6,(b-a)/4))0f else hits.toFloat()/seen
        }
        fun edge(v:Int,a:Int,b:Int,vertical:Boolean):Pair<Int,Float> {
            val radius=max(4,((if(vertical)w else h)*.012f).toInt())
            return (max(4,v-radius)..min((if(vertical)w else h)-5,v+radius)).map {it to score(it,a,b,vertical)}
                .maxByOrNull {it.second-abs(it.first-v).toFloat()/radius*.12f} ?: (v to 0f)
        }
        val left=edge((p.left*w).toInt(),(p.top*h).toInt(),(p.bottom*h).toInt(),true)
        val right=edge((p.right*w).toInt(),(p.top*h).toInt(),(p.bottom*h).toInt(),true)
        val top=edge((p.top*h).toInt(),left.first,right.first,false)
        val bottom=edge((p.bottom*h).toInt(),left.first,right.first,false)
        if(minOf(left.second,right.second,top.second,bottom.second)<.80f)return null
        return Panel(left.first.toFloat()/w,top.first.toFloat()/h,(right.first+1f)/w,(bottom.first+1f)/h)
    }
    fun tonalSplit(p:Panel):List<Panel>? {
        if(p.width !in .45f.. .70f || p.height !in .12f.. .28f)return null
        val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt()+3;val b=t+(p.height*h*.65f).toInt()
        val d=max(5,(w*.013f).toInt())
        fun strength(x:Int):Float {
            val ys=(t until b).filterNot {covered(x,it)}
            if(ys.size<(b-t)*.6f)return 0f
            val differences=ys.map {abs(level(x-d,it)-level(x+d,it))}
            if(differences.count {it>55}<ys.size*.8f)return 0f
            if(ys.count {min(level(x-d,it),level(x+d,it))<85}<ys.size*.75f)return 0f
            return differences.average().toFloat()
        }
        val scores=(l+(r-l)/3..r-(r-l)/3).map {it to strength(it)}
        val peak=scores.maxOfOrNull {it.second} ?: return null
        if(peak<110f)return null
        val strong=scores.filter {it.second>=peak*.95f}
        val x=(strong.first().first+strong.last().first)/2
        // A strong colour transition is only a lead. A physical divider must
        // continue to the lower border, unlike a face, coat or lighted wall.
        val fullTop=(p.top*h).toInt()+3;val fullBottom=(p.bottom*h).toInt()-3
        val divider=(x-d..x+d).any {edge ->
            val ys=(fullTop until fullBottom).filterNot {covered(edge,it)}
            ys.size>(fullBottom-fullTop)*.50f && ys.count {level(edge,it)<80}>ys.size*.94f
        }
        if(!divider)return null
        return listOf(Panel(p.left,p.top,x.toFloat()/w,p.bottom),Panel(x.toFloat()/w,p.top,p.right,p.bottom))
    }
    fun split(p:Panel):List<Panel>? {
        if(p.width<.35f || p.height !in .10f.. .40f)return null
        val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt()+2;val b=(p.bottom*h).toInt()-2
        // Look for a continuous gutter, including the two rows just outside
        // the frame. This rejects pale walls that stop at the lower border.
        fun tone(x:Int):Int {
            val ys=(t until b).filterNot {covered(x,it)}
            if(ys.size<4)return 0
            if(ys.count {level(x,it)>220}.toFloat()/ys.size>.90f &&
                level(x,max(0,t-2))>220 && level(x,min(h-1,b+1))>220)return 1
            if(ys.count {level(x,it)<35}.toFloat()/ys.size>.94f && level(x,max(0,t-4))<65 && level(x,min(h-1,b+3))<65)return -1
            return 0
        }
        val runs=mutableListOf<Pair<Int,Int>>()
        var start=-1;var color=0
        for(x in l+(r-l)/4..r-(r-l)/4) {
            val c=tone(x)
            if(c!=color) {if(color!=0 && start>=0)runs.add(start to x);start=x;color=c}
        }
        val pair=runs.filter {(a,z)->z-a in 2..max(5,(w*.025f).toInt()) &&
            (support(a-2,t,b,true)>.30f || support(a,t,b,true)>.30f || support(z+1,t,b,true)>.30f)}
            .maxByOrNull {(a,z)->(z-a).toFloat()/w + max(support(a-2,t,b,true),support(z+1,t,b,true))} ?: return null
        return listOf(Panel(p.left,p.top,pair.first.toFloat()/w,p.bottom),Panel(pair.second.toFloat()/w,p.top,p.right,p.bottom))
    }
}
