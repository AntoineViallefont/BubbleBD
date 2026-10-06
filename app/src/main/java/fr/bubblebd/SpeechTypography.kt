package fr.bubblebd

import kotlin.math.*

/** Separate ordinary speech from large reaction lettering sharing white paper.
 * Both font sizes and two established neighbouring frames must corroborate it. */
internal object SpeechTypography {
    data class Part(val body:Panel,val text:Panel,val owner:Int)
    private fun area(p:Panel)=p.width*p.height
    private fun shared(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    fun split(body:Panel,texts:List<Panel>,cores:List<Panel>,pixels:IntArray,w:Int,h:Int):List<Part>? {
        val candidates=cores.indices.filter {i ->area(cores[i])<.25f && shared(cores[i],body)/area(body)>.15f}
        if(candidates.size!=2 || !GuidedFrames.sameRow(cores[candidates[0]],cores[candidates[1]]))return null
        val l=(body.left*w).toInt().coerceIn(0,w-1);val r=(body.right*w).toInt().coerceIn(l+1,w)
        val t=(body.top*h).toInt().coerceIn(0,h-1);val b=(body.bottom*h).toInt().coerceIn(t+1,h)
        if(r-l<30 || b-t<20)return null
        val seen=BooleanArray((r-l)*(b-t));val queue=IntArray(seen.size);val glyphs=mutableListOf<Panel>()
        fun ink(x:Int,y:Int):Boolean {val c=pixels[y*w+x];return maxOf(c shr 16 and 255,c shr 8 and 255,c and 255)<180}
        fun white(x:Int,y:Int):Boolean {val c=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)];return minOf(c shr 16 and 255,c shr 8 and 255,c and 255)>202}
        for(y0 in t until b)for(x0 in l until r) {
            val seed=(y0-t)*(r-l)+x0-l
            if(seen[seed] || !ink(x0,y0))continue
            var head=0;var tail=0;var x1=r;var x2=l;var y1=b;var y2=t
            fun push(x:Int,y:Int) {
                if(x !in l until r || y !in t until b)return
                val id=(y-t)*(r-l)+x-l
                if(!seen[id] && ink(x,y)) {seen[id]=true;queue[tail++]=id}
            }
            push(x0,y0)
            while(head<tail) {
                val id=queue[head++];val x=l+id%(r-l);val y=t+id/(r-l)
                x1=min(x1,x);x2=max(x2,x+1);y1=min(y1,y);y2=max(y2,y+1)
                push(x-1,y);push(x+1,y);push(x,y-1);push(x,y+1)
            }
            if(tail<5 || x1<=l || x2>=r || y1<=t || y2>=b || x2-x1>(r-l)*.4f)continue
            val border=(x1-2..x2+1).map {it to y1-2}+(x1-2..x2+1).map {it to y2+1}+
                (y1-2..y2+1).map {x1-2 to it}+(y1-2..y2+1).map {x2+1 to it}
            if(border.count {(x,y)->white(x,y)}<border.size*.55f)continue
            glyphs.add(Panel(x1.toFloat()/w,y1.toFloat()/h,x2.toFloat()/w,y2.toFloat()/h))
        }
        fun owner(g:Panel)=candidates.filter {shared(cores[it],g)/area(g)>.85f}.minByOrNull {area(cores[it])}
        val ordinary=glyphs.filter {it.height in body.height*.035f..body.height*.18f}
        if(ordinary.isEmpty())return null
        val typical=ordinary.map {it.height}.sorted()[ordinary.size/2]
        val large=glyphs.filter {it.height>=max(body.height*.18f,typical*2.2f) && it.height<body.height*.55f}
        val reaction=large.groupBy(::owner).entries.singleOrNull {it.key!=null && it.value.size>=2} ?: return null
        val reactionOwner=reaction.key!!;val speechOwner=candidates.single {it!=reactionOwner}
        val small=ordinary.filter {owner(it)==speechOwner}
        if(small.isEmpty() || (small.size==1 && small.single().width/small.single().height<3f) ||
            (small.size<6 && small.sumOf {it.width.toDouble()}<body.width*.25f) || small.maxOf {it.right}-small.minOf {it.left}<body.width*.20f)return null
        val reactionLeft=cores[reactionOwner].left<cores[speechOwner].left
        val a=if(reactionLeft)reaction.value else small;val z=if(reactionLeft)small else reaction.value
        val end=a.maxOf {it.right};val start=z.minOf {it.left}
        if(start-end<body.width*.035f)return null
        val divider=(start+end)*.5f
        val leftBody=body.copy(right=divider);val rightBody=body.copy(left=divider)
        val words=texts.filter {shared(body,it)/area(it)>.6f}
        if(words.isEmpty())return null
        fun text(glyphs:List<Panel>)=Panel(glyphs.minOf {it.left},glyphs.minOf {it.top},glyphs.maxOf {it.right},glyphs.maxOf {it.bottom})
        return listOf(Part(if(reactionLeft)leftBody else rightBody,text(reaction.value),reactionOwner),
            Part(if(reactionLeft)rightBody else leftBody,text(small),speechOwner))
    }
}
