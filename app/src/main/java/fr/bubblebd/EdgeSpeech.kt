package fr.bubblebd

import kotlin.math.*

/** Recover speech connected to the paper gutter, using text and nearby frame evidence. */
object EdgeSpeech {
    private fun area(p:Panel)=p.width*p.height
    private fun overlap(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    fun expand(frames:List<Panel>,texts:List<Panel>,closed:List<Panel>,pixels:IntArray,w:Int,h:Int,confirmedInserts:List<Panel> = emptyList()):List<Panel> {
        val cores=frames.map {it.readingOrderBounds ?: it}
        val result=frames.toMutableList();val seen=BooleanArray(pixels.size);val queue=IntArray(pixels.size)
        fun white(x:Int,y:Int):Boolean {val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>202}
        for(i in cores.indices) {
            val p=cores[i]
            val inset=p in confirmedInserts
            val near=texts.filter {text -> overlap(p,text)/area(text)>.80f &&
                (p.bottom-text.bottom in -.005f.. .045f || p.right-text.right in -.005f.. .025f)}
            if(near.isEmpty())continue
            val l=(p.left*w).toInt().coerceIn(0,w-1);val r=(p.right*w).toInt().coerceIn(l+1,w)
            val t=(p.top*h).toInt().coerceIn(0,h-1);val b=(p.bottom*h).toInt().coerceIn(t+1,h)
            seen.fill(false)
            for(y0 in t until b)for(x0 in l until r) {
                val seed=y0*w+x0
                if(seen[seed] || !white(x0,y0))continue
                var head=0;var tail=1;queue[0]=seed;seen[seed]=true
                var left=r;var right=l;var top=b;var bottom=t
                while(head<tail) {
                    val k=queue[head++];val x=k%w;val y=k/w
                    left=min(left,x);right=max(right,x+1);top=min(top,y);bottom=max(bottom,y+1)
                    fun push(xx:Int,yy:Int) {if(xx in l until r && yy in t until b) {val id=yy*w+xx;if(!seen[id] && white(xx,yy)) {seen[id]=true;queue[tail++]=id}}}
                    push(x-1,y);push(x+1,y);push(x,y-1);push(x,y+1)
                }
                val size=(right-left)*(bottom-top)
                if(size<=0 || size>w*h*.065 || tail<size*.40 || right-left<w*.025 || bottom-top<h*.015)continue
                val body=Panel(left.toFloat()/w,top.toFloat()/h,right.toFloat()/w,bottom.toFloat()/h)
                val text=near.firstOrNull {overlap(body,it)/area(it)>.80f} ?: continue
                if(inset && closed.any {q ->overlap(q,body)/max(area(q),area(body))>.80f && overlap(q,text)/area(text)>.80f})continue
                var owner=i
                // A balloon crossing an interrupted lower border continues into the following frame.
                val pointsUp=text.top-body.top>max(.008f,(body.bottom-text.bottom)*1.5f)
                // A tall white background beside the speaker is not a protruding balloon.
                val backgroundColumn=pointsUp && body.height>p.height*.55f
                val rowWidths=IntArray(bottom-top)
                for(k in 0 until tail)rowWidths[queue[k]/w-top]++
                val thinRows=rowWidths.takeWhile {it>0 && it<(right-left)*.22f}.size
                // A sustained narrow tip above the text points into the current frame.
                // Short saw-tooth corners of a narration box do not establish a speaker.
                val upwardTail=pointsUp && thinRows>=max(h*.010f,(bottom-top)*.16f)
                if(!backgroundColumn && !upwardTail && bottom>=b-1 && p.bottom-text.bottom<.045f) {
                    val cx=(text.left+text.right)*.5f
                    val below=cores.indices.filter {j->j!=i && cores[j].top>=p.bottom-.01f && cores[j].top-p.bottom<.035f && cx in cores[j].left..cores[j].right}
                        .minByOrNull {cores[it].top}
                    if(below!=null) {
                        val row=((cores[below].top*h).toInt()+3).coerceIn(0,h-1)
                        val a=max(left,(cores[below].left*w).toInt());val z=min(right,(cores[below].right*w).toInt())
                        val caption=texts.any {other ->other!=text && other.top>=cores[below].top && other.top<cores[below].top+.045f && other.right>body.left && other.left<body.right}
                        if(!caption && z>a && (a until z).count {white(it,row)}.toFloat()/(z-a)>.45f)owner=below
                    }
                }
                val envelope=body.copy(left=max(0f,body.left-if(left<=l+1).015f else .008f),top=max(0f,body.top-.008f),
                    right=min(1f,body.right+if(right>=r-1 && p.right-text.right<.025f).025f else .008f),
                    bottom=min(1f,max(body.bottom+.008f,if(pointsUp && p.bottom-text.bottom<.015f)text.bottom+.025f else body.bottom)))
                val old=result[owner]
                result[owner]=old.copy(left=min(old.left,envelope.left),top=min(old.top,envelope.top),right=max(old.right,envelope.right),bottom=max(old.bottom,envelope.bottom))
                if(owner!=i)result[i]=result[i].copy(focusExclusions=result[i].focusExclusions+envelope)
            }
        }
        return result
    }
}
