package fr.bubblebd

import kotlin.math.*

/** Dense rows of painted frames followed by short paper caption strips. */
object CaptionRows {
    fun detect(pixels:IntArray,w:Int,h:Int):List<Panel> {
        if(w<100 || h<200)return emptyList()
        val white=BooleanArray(pixels.size);val pale=BooleanArray(pixels.size);val level=IntArray(pixels.size)
        for(i in pixels.indices) {
            val c=pixels[i];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255
            white[i]=minOf(r,g,b)>230 && maxOf(r,g,b)-minOf(r,g,b)<25
            pale[i]=minOf(r,g,b)>200
            level[i]=(r*3+g*6+b)/10
        }
        fun groups(values:List<Int>,gap:Int=1):List<IntRange> {
            val out=mutableListOf<IntRange>()
            for(v in values)if(out.isNotEmpty() && v<=out.last().last+gap)out[out.lastIndex]=out.last().first..v else out.add(v..v)
            return out
        }
        val strips=groups((0 until h).filter {y->(0 until w).count {white[y*w+it]}.toFloat()/w in .25f.. .90f},2).filter {band->
            band.first>h*.18f && band.count() in (h*.008f).toInt()..(h*.035f).toInt() &&
                (0 until w).count {x->band.any {y->white[y*w+x]}}>w*.88f
        }
        if(strips.size !in 4..8)return emptyList()
        val periods=strips.zipWithNext().map {(a,b)->b.first-a.first}.sorted()
        val period=periods[periods.size/2]
        if(period !in (h*.10f).toInt()..(h*.25f).toInt() || periods.any {abs(it-period)>h*.012f})return emptyList()
        fun support(v:Int,lo:Int,hi:Int,vertical:Boolean,ink:Boolean=false):Float {
            var seen=0;var hits=0
            for(u in lo until hi) {
                if(u !in 3 until (if(vertical)h else w)-3)continue
                seen++
                if((-2..2).any {d->val x=if(vertical)v+d else u;val y=if(vertical)u else v+d
                    x in 3 until w-3 && y in 3 until h-3 && (!ink || level[y*w+x]<190) && abs(level[(y+if(vertical)0 else 3)*w+x+if(vertical)3 else 0]-level[(y-if(vertical)0 else 3)*w+x-if(vertical)3 else 0])>25
                })hits++
            }
            return if(seen==0)0f else hits.toFloat()/seen
        }
        val l=3;val r=w-5
        val frames=mutableListOf<Panel>()
        var firstTop=0
        for((row,strip) in strips.withIndex()) {
            val expected=if(row==0)strip.last+1-period else strips[row-1].last+1
            val top=(max(3,expected-6)..min(h-4,expected+6)).maxByOrNull {y->support(y,l,r,false)-abs(y-expected)*.005f} ?: return emptyList()
            if(strip.first-top<h*.075f || support(top,l,r,false)<.90f || support(strip.first,l,r,false)<.90f)return emptyList()
            val gutters=groups((l+1 until r).filter {x->
                (top+5 until strip.first-3).count {y->pale[y*w+x]}>((strip.first-3)-(top+5))*.80f
            }).filter {it.count()<w*.025f && it.first>w*.05f && it.last<w*.95f}.map {(it.first+it.last+1)/2}
                .filter {support(it,top+3,strip.first-2,true)>.80f}
            val cuts=listOf(l)+gutters+listOf(r)
            if(cuts.size !in 3..6 || cuts.zipWithNext().any {(a,b)->b-a<w*.14f})return emptyList()
            if(cuts.count {support(it,top+3,strip.first-2,true,ink=true)>.70f}<2)return emptyList()
            if(minOf(support(l,top+3,strip.first-2,true),support(r,top+3,strip.first-2,true))<.80f)return emptyList()
            val bottom=if(row==strips.lastIndex && strip.last>h*.98f)h else strip.last+1
            for((a,b) in cuts.zipWithNext())frames.add(Panel(max(0f,(a-2f)/w),max(0f,(top-2f)/h),min(1f,(b+2f)/w),min(1f,(bottom+2f)/h)))
            if(row==0)firstTop=top
        }
        // A full-width introductory frame precedes the repeated caption rows.
        // Interior architectural columns do not split it without a paper gutter.
        if(firstTop>h*.10f) {
            if(firstTop>h*.25f || minOf(support(l,3,firstTop-3,true),support(r,3,firstTop-3,true),support(3,l,r,false),support(firstTop-5,l,r,false))<.80f)return emptyList()
            frames.add(0,Panel(0f,0f,1f,firstTop.toFloat()/h))
        }
        return frames
    }
}
