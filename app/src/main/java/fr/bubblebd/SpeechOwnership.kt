package fr.bubblebd

import kotlin.math.*

/** For an ambiguous balloon spanning adjacent frames, compare its white body area. */
internal object SpeechOwnership {
    private fun area(p:Panel)=p.width*p.height
    private fun overlap(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    private fun openBody(text:Panel)=Panel(max(0f,text.left-max(.016f,text.width*.14f)),max(0f,text.top-.022f),
        min(1f,text.right+max(.016f,text.width*.14f)),min(1f,text.bottom+.028f))

    /** A rectangular narration box contained by one frame is not a shared balloon.
     * Boxes joining page paper need a straight lower edge and a straight side. */
    fun captionOwner(text:Panel,body:Panel,cores:List<Panel>,pixels:IntArray,w:Int,h:Int,open:Boolean):Int? {
        val owners=cores.indices.filter {overlap(cores[it],text)/area(text)>.98f}
        val owner=owners.minByOrNull {area(cores[it])} ?: return null
        if(owners.any {it!=owner && area(cores[it])<area(cores[owner])*1.2f})return null
        fun white(x:Int,y:Int):Boolean {
            if(x !in 0 until w || y !in 0 until h)return false
            val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>202
        }
        if(!open) {
            return owner.takeIf {listOf(.08f to .08f,.92f to .08f,.08f to .92f,.92f to .92f).all {(x,y)->white(((body.left+body.width*x)*w).toInt(),((body.top+body.height*y)*h).toInt())}}
        }
        val c=cores[owner]
        if(text.top-c.top !in -.002f.. .035f || text.width<.15f)return null
        val left=(text.left*w).toInt();val right=(text.right*w).toInt()
        val top=(text.top*h).toInt();val bottom=(text.bottom*h).toInt()
        if(right-left<12 || bottom-top<6)return null
        val lower=(bottom..min(h-4,bottom+max(4,(h*.025f).toInt()))).any {y ->
            (left until right).count {x->white(x,y-2) && !white(x,y+2)}>(right-left)*.88f
        }
        if(!lower)return null
        val side=(right..min(w-4,right+max(4,(w*.03f).toInt()))).any {x ->
            (top until bottom).count {y->white(x-2,y) && !white(x+2,y)}>(bottom-top)*.88f
        }
        return owner.takeIf {side}
    }

    /** Keep physical frames separate; uncertainty only changes the guided view. */
    fun uncertainPair(text:Panel,body:Panel,cores:List<Panel>,open:Boolean):Pair<Int,Int>? {
        val envelope=if(open)openBody(text) else body
        val candidates=cores.indices.filter {overlap(cores[it],text)/area(text)>.15f || overlap(cores[it],envelope)/area(envelope)>.10f}
        if(candidates.size!=2)return null
        val a=cores[candidates[0]];val b=cores[candidates[1]]
        val sideBySide=abs(a.top-b.top)<.035f && abs(a.bottom-b.bottom)<.035f && min(abs(a.right-b.left),abs(b.right-a.left))<.035f
        val verticalGap=min(abs(a.bottom-b.top),abs(b.bottom-a.top))
        val stacked=verticalGap<max(.035f,min(.08f,envelope.height*.8f)) && min(a.right,b.right)-max(a.left,b.left)>.035f
        if(!sideBySide && !stacked)return null
        // A long closed vertical tail is stronger evidence than the body's overlap.
        if(stacked && !open) {
            val upper=text.top-envelope.top;val lower=envelope.bottom-text.bottom
            if(lower>max(.014f,upper*1.8f) || upper>max(.014f,lower*1.8f))return null
        }
        return candidates[0] to candidates[1]
    }

    /** An undirected caption can cover a junction of three physical cases. */
    fun uncertainCases(text:Panel,body:Panel,cores:List<Panel>,open:Boolean):List<Int> {
        uncertainPair(text,body,cores,open)?.let {return listOf(it.first,it.second)}
        val envelope=if(open)openBody(text) else body
        val candidates=cores.indices.filter {overlap(cores[it],text)/area(text)>.15f || overlap(cores[it],envelope)/area(envelope)>.10f}
        if(candidates.size<3)return emptyList()
        if(!open) {
            val upper=text.top-envelope.top;val lower=envelope.bottom-text.bottom
            if(lower>max(.014f,upper*1.8f) || upper>max(.014f,lower*1.8f))return emptyList()
        }
        // Overlap with the small caption itself is required; distant cases never join.
        return candidates
    }

    /** Follow an outlined shaft above or below a text body, including curved tips.
     * Text anchors the search; a sustained ridge crossing a frame establishes ownership. */
    fun verticalContext(text:Panel,body:Panel,cores:List<Panel>,pixels:IntArray,w:Int,h:Int):Assignment? {
        val bw=body.width*w;val bh=body.height*h
        if(bw<20 || bh<12 || bw/bh !in .45f..3f)return null
        fun light(x:Int,y:Int):Int {
            val c=pixels[y*w+x];return ((c shr 16 and 255)+(c shr 8 and 255)+(c and 255))/3
        }
        // A rectangular caption may legitimately be shared by several frames.
        // Straight surrounding artwork must not turn it into directed speech.
        val captionCorners=listOf(.08f to .08f,.92f to .08f,.08f to .92f,.92f to .92f)
        if(captionCorners.all {(dx,dy) ->
            val x=((body.left+body.width*dx)*w).toInt().coerceIn(0,w-1)
            val y=((body.top+body.height*dy)*h).toInt().coerceIn(0,h-1)
            val c=pixels[y*w+x]
            minOf(c shr 16 and 255,c shr 8 and 255,c and 255)>202
        })return null
        fun ridge(x:Int,y:Int):Boolean {
            if(x !in 5 until w-5 || y !in 0 until h)return false
            val center=light(x,y)
            return center>50 && (1..4).any {d ->
                val a=light(x-d,y);val b=light(x+d,y)
                center-a>18 && center-b>18 && center-(a+b)/2>25
            }
        }
        var longest=0;var best:Assignment?=null
        for(direction in listOf(-1,1)) {
            val edge=((if(direction<0)body.top else body.bottom)*h).toInt()-direction*2
            val reach=min(h*.18f,max(bh*3f,text.height*h*4f)).toInt()
            for(seed in (body.left*w).toInt()..(body.right*w).toInt()) {
                var x=seed;var misses=0;var good=0;var lastX=x;var lastY=edge
                for(step in 0..reach) {
                    val y=edge+direction*step;if(y !in 0 until h)break
                    val next=(x-1..x+1).filter {ridge(it,y)}.maxByOrNull {light(it,y)}
                    if(next==null) {misses++;if(misses>if(good<3)max(3,(h*.008f).toInt()) else 3)break}
                    else {x=next;good++;misses=0;lastX=x;lastY=y}
                }
                val length=abs(lastY-edge)
                if(length<=longest || length<max(18f,max(h*.025f,text.height*h*1.2f)) || good<length*.80f)continue
                val owners=cores.indices.filter {cores[it].contains(lastX.toFloat()/w,lastY.toFloat()/h)}
                val owner=owners.minByOrNull {area(cores[it])} ?: continue
                // This contextual repair resolves a body already disputed by neighbouring
                // frames. A remote ridge in the artwork cannot introduce another owner.
                if(overlap(cores[owner],body)/area(body)<.25f)continue
                val anchor=cores.indices.filter {cores[it].contains((text.left+text.right)/2,(text.top+text.bottom)/2)}
                    .minByOrNull {area(cores[it])}
                if(anchor!=null && anchor!=owner) {
                    val a=cores[anchor];val b=cores[owner]
                    // A vertical shaft cannot resolve two frames on the same row.
                    // Their shared balloon keeps the existing lateral ownership rules.
                    if(abs((a.top+a.bottom-b.top-b.bottom)/2)<max(.025f,min(a.height,b.height)*.15f))continue
                }
                // Evidence must cross a physical border into a different speaker's frame.
                if(cores[owner].contains((text.left+text.right)/2,(text.top+text.bottom)/2))continue
                val envelope=body.copy(left=max(0f,min(body.left,(min(seed,lastX)-4f)/w)),
                    right=min(1f,max(body.right,(max(seed,lastX)+4f)/w)),
                    top=max(0f,min(body.top,(lastY-4f)/h)),bottom=min(1f,max(body.bottom,(lastY+4f)/h)))
                longest=length;best=Assignment(owner,envelope)
            }
        }
        return best
    }

    /** Fallback for a balloon ending at a gutter: trace its outlined shaft
     * across two independently closed neighbouring rows, never over a known owner. */
    fun adjacentShaftContext(text:Panel,body:Panel,cores:List<Panel>,pixels:IntArray,w:Int,h:Int):Assignment? {
        val bw=body.width*w;val bh=body.height*h
        if(bw<20 || bh<12 || bw/bh !in .45f..3f)return null
        fun light(x:Int,y:Int):Int {
            val c=pixels[y*w+x];return ((c shr 16 and 255)+(c shr 8 and 255)+(c and 255))/3
        }
        // A rectangular caption may legitimately be shared by several frames.
        // Straight surrounding artwork must not turn it into directed speech.
        val captionCorners=listOf(.08f to .08f,.92f to .08f,.08f to .92f,.92f to .92f)
        if(captionCorners.all {(dx,dy) ->
            val x=((body.left+body.width*dx)*w).toInt().coerceIn(0,w-1)
            val y=((body.top+body.height*dy)*h).toInt().coerceIn(0,h-1)
            val c=pixels[y*w+x]
            minOf(c shr 16 and 255,c shr 8 and 255,c and 255)>202
        })return null
        fun ridge(x:Int,y:Int,allowDark:Boolean=false):Boolean {
            if(x !in 5 until w-5 || y !in 0 until h)return false
            val center=light(x,y)
            val bright=center>50 && (1..4).any {d ->
                val a=light(x-d,y);val b=light(x+d,y)
                center-a>18 && center-b>18 && center-(a+b)/2>25
            }
            // Resampling can collapse two dark outlines around a white shaft
            // into a dark centre. Only continue it after a light shaft began.
            val collapsed=allowDark && center<150 && (1..3).any {d ->
                light(x-d,y)-center>35 && light(x+d,y)-center>35
            }
            return bright || collapsed
        }
        // Frame verification is costly: build its pixel mask only after a shaft
        // has reached a geometrically admissible neighbour, and fit those two cores.
        val contours by lazy {FrameContours(pixels,w,h,listOf(body,text))}
        val closed=mutableMapOf<Int,Boolean>()
        fun framed(i:Int)=closed.getOrPut(i) {contours.fit(cores[i])!=null || contours.fitColour(cores[i])!=null}
        var longest=0;var best:Assignment?=null
        for(direction in listOf(-1,1)) {
            // Do not leave the rounded cap opposite a longer visible protrusion.
            val extension=if(direction<0)text.top-body.top else body.bottom-text.bottom
            val opposite=if(direction<0)body.bottom-text.bottom else text.top-body.top
            if(extension<max(3f/h,.005f) || extension<opposite*.65f)continue
            val edge=((if(direction<0)body.top else body.bottom)*h).toInt()-direction*2
            val reach=min(h*.18f,max(bh*3f,text.height*h*4f)).toInt()
            for(seed in max((body.left*w).toInt(),(text.left*w).toInt()-3)..min((body.right*w).toInt(),(text.right*w).toInt()+3)) {
                var x=seed;var misses=0;var good=0;var lastX=x;var lastY=edge
                for(step in 0..reach) {
                    val y=edge+direction*step;if(y !in 0 until h)break
                    val next=(x-1..x+1).filter {ridge(it,y,good>=3)}.maxByOrNull {light(it,y)}
                    if(next==null) {misses++;if(misses>if(good<3)max(3,(h*.008f).toInt()) else if(cores.any {c ->abs(y/h.toFloat()-c.bottom)<.012f || abs(y/h.toFloat()-c.top)<.012f})max(3,(h*.012f).toInt()) else 3)break}
                    else {x=next;good++;misses=0;lastX=x;lastY=y}
                }
                val length=abs(lastY-edge)
                if(length<=longest || length<max(18f,max(h*.025f,text.height*h*1.2f)) || good<length*.80f)continue
                val owners=cores.indices.filter {cores[it].contains(lastX.toFloat()/w,lastY.toFloat()/h)}
                val owner=owners.minByOrNull {area(cores[it])} ?: continue
                // This contextual repair resolves a body already disputed by neighbouring
                // frames. A remote ridge in the artwork cannot introduce another owner.
                val anchor=cores.indices.filter {cores[it].contains((text.left+text.right)/2,(text.top+text.bottom)/2)}
                    .minByOrNull {area(cores[it])}
                // A closed body may end at a gutter while its thin shaft continues.
                // Require an adjacent row and a sustained path into that row; a
                // remote line in the scenery still cannot introduce a speaker.
                val adjacentShaft=anchor!=null && anchor!=owner && good>length*.90f && run {
                    val a=cores[anchor];val b=cores[owner]
                    val gap=if(direction>0)b.top-a.bottom else a.top-b.bottom
                    val edgeDistance=if(direction>0)abs(body.bottom-b.top) else abs(body.top-b.bottom)
                    val penetration=if(direction>0)lastY/h.toFloat()-b.top else b.bottom-lastY/h.toFloat()
                    gap in -.005f.. .025f && edgeDistance<.04f && penetration>.025f &&
                        seed/w.toFloat() in max(a.left,b.left)..min(a.right,b.right)
                }
                if(!adjacentShaft || overlap(cores[owner],body)/area(body)>=.25f)continue
                if(!framed(anchor) || !framed(owner))continue
                val a=cores[anchor];val b=cores[owner]
                if(abs((a.top+a.bottom-b.top-b.bottom)/2)<max(.025f,min(a.height,b.height)*.15f))continue
                // Evidence must cross a physical border into a different speaker's frame.
                if(cores[owner].contains((text.left+text.right)/2,(text.top+text.bottom)/2))continue
                val envelope=body.copy(left=max(0f,min(body.left,(min(seed,lastX)-4f)/w)),
                    right=min(1f,max(body.right,(max(seed,lastX)+4f)/w)),
                    top=max(0f,min(body.top,(lastY-if(direction<0)h*.025f else 4f)/h)),
                    bottom=min(1f,max(body.bottom,(lastY+if(direction>0)h*.025f else 4f)/h)))
                longest=length;best=Assignment(owner,envelope)
            }
        }
        return best
    }

    /** Follow a long, narrow light shaft even when JPEG antialiasing breaks its white interior. */
    fun longLowerTail(body:Panel,cores:List<Panel>,pixels:IntArray,w:Int,h:Int):Assignment? {
        val bw=body.width*w;val bh=body.height*h
        if(bw<20 || bh<12 || bw/bh !in .7f..3f)return null
        val top=(body.bottom*h).toInt()-3
        val bottom=min(h-1,top+min(h*.18f,bh*3f).toInt())
        fun light(x:Int,y:Int):Int {val c=pixels[y*w+x];return ((c shr 16 and 255)+(c shr 8 and 255)+(c and 255))/3}
        fun ridge(x:Int,y:Int):Boolean {
            if(x<4 || x>=w-4)return false
            val center=light(x,y)
            return center>115 && (1..3).any {d ->
                val a=light(x-d,y);val b=light(x+d,y)
                center-a>25 && center-b>25 && center-(a+b)/2>50
            }
        }
        var bestLength=0;var bestX=0;var bestY=0
        for(seed in ((body.left+body.width*.25f)*w).toInt()..((body.right-body.width*.25f)*w).toInt()) {
            var x=seed;var misses=0;var good=0;var lastY=top;var lastX=x
            for(y in top..bottom) {
                val next=(x-1..x+1).filter {ridge(it,y)}.maxByOrNull {light(it,y)}
                if(next==null) {misses++;if(misses>3 || good<3 && misses>1)break}
                else {x=next;good++;misses=0;lastY=y;lastX=x}
            }
            val length=lastY-top
            if(length>bestLength && good>length*.80f && abs(lastX-seed)<bw*.35f) {
                bestLength=length;bestX=lastX;bestY=lastY
            }
        }
        if(bestLength<max(18f,max(h*.025f,bh*.75f)))return null
        val owner=cores.indices.filter {cores[it].contains(bestX.toFloat()/w,bestY.toFloat()/h)}.singleOrNull() ?: return null
        // The tip must cross into another physical frame, rather than follow a detail inside its owner.
        if(cores[owner].contains((body.left+body.right)/2,(body.top+body.bottom)/2))return null
        return Assignment(owner,body.copy(bottom=min(1f,(bestY+h*.025f)/h)))
    }

    /** Follow an outlined lateral shaft across a gutter; plain paper has no ridge. */
    fun longSideTail(body:Panel,cores:List<Panel>,pixels:IntArray,w:Int,h:Int):Assignment? {
        val bw=body.width*w;val bh=body.height*h
        if(bw<20 || bh<12 || bw/bh !in .7f..3f)return null
        fun light(x:Int,y:Int):Int {val c=pixels[y*w+x];return ((c shr 16 and 255)+(c shr 8 and 255)+(c and 255))/3}
        fun ridge(x:Int,y:Int):Boolean {
            if(x !in 0 until w || y<5 || y>=h-5)return false
            val center=light(x,y)
            return center>115 && (1..4).any {d ->
                val a=light(x,y-d);val b=light(x,y+d)
                center-a>25 && center-b>25 && center-(a+b)/2>50
            }
        }
        var best:Assignment?=null;var longest=0
        for(direction in listOf(-1,1)) {
            val edge=if(direction<0)(body.left*w).toInt() else (body.right*w).toInt()
            val reach=max(8,(bw*.20f).toInt())
            val length=min(w*.24f,bw*3f).toInt()
            for(start in edge-reach..edge+reach)for(seedY in (body.top*h).toInt()..(body.bottom*h).toInt()) {
                var y=seedY;var good=0;var misses=0;var lastX=start;var lastY=y
                for(step in 0..length) {
                    val x=start+direction*step;if(x !in 0 until w)break
                    val next=(y-1..y+1).filter {ridge(x,it)}.maxByOrNull {light(x,it)}
                    if(next==null) {misses++;if(misses>3 || good<3 && misses>1)break}
                    else {y=next;good++;misses=0;lastX=x;lastY=y}
                }
                val distance=abs(lastX-start)
                if(distance<=longest || distance<max(18f,bw*.55f) || good<distance*.80f || abs(lastY-seedY)>bh*.6f)continue
                val owner=cores.indices.filter {cores[it].contains(lastX.toFloat()/w,lastY.toFloat()/h)}.singleOrNull() ?: continue
                if(cores[owner].contains((body.left+body.right)/2,(body.top+body.bottom)/2))continue
                val speaker=cores[owner]
                val sameRow=cores.indices.any {j ->j!=owner && cores[j].contains((body.left+body.right)/2,(body.top+body.bottom)/2) &&
                    abs(speaker.top-cores[j].top)<.035f && abs(speaker.bottom-cores[j].bottom)<.035f &&
                    min(abs(speaker.right-cores[j].left),abs(cores[j].right-speaker.left))<.035f}
                if(!sameRow)continue
                longest=distance
                best=Assignment(owner,body.copy(left=min(body.left,(lastX-4f)/w),right=max(body.right,(lastX+4f)/w),
                    top=min(body.top,(min(seedY,lastY)-4f)/h),bottom=max(body.bottom,(max(seedY,lastY)+4f)/h)))
            }
        }
        return best
    }

    /** A directed balloon crossing two aligned cells keeps its owner and shares their view. */
    fun crossingPair(body:Panel,owner:Int,cores:List<Panel>):Pair<Int,Int>? {
        val a=cores[owner]
        val neighbours=cores.indices.filter {i ->i!=owner && overlap(cores[i],body)/area(body)>.80f &&
            abs(a.top-cores[i].top)<.035f && abs(a.bottom-cores[i].bottom)<.035f &&
            min(abs(a.right-cores[i].left),abs(cores[i].right-a.left))<.035f}
        if(neighbours.size!=1 || overlap(a,body)/area(body)>.15f)return null
        return owner to neighbours.single()
    }

    /** A thin protrusion of a closed white body contributes the speaker's side. */
    fun tailOwner(text:Panel,body:Panel,cores:List<Panel>,pixels:IntArray,w:Int,h:Int):Int? {
        val candidates=cores.indices.filter {overlap(cores[it],body)/area(body)>.10f}
        if(candidates.size<2)return null
        // Aligned neighbours keep the existing ambiguity fallback. A radial
        // extremity alone cannot distinguish an oval corner from a short tail.
        if(!SpeechTail.thinLobe(body,pixels,w,h) && candidates.indices.all {i ->(i+1 until candidates.size).all {j ->
            val a=cores[candidates[i]];val b=cores[candidates[j]]
            abs(a.top-b.top)<.035f && abs(a.bottom-b.bottom)<.035f
        }})return null
        val l=(body.left*w).toInt().coerceIn(0,w-1);val r=(body.right*w).toInt().coerceIn(l+1,w)
        val t=(body.top*h).toInt().coerceIn(0,h-1);val b=(body.bottom*h).toInt().coerceIn(t+1,h)
        val bw=r-l;val bh=b-t;if(bw<8 || bh<8)return null
        fun white(x:Int,y:Int):Boolean {val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>202}
        val cx=(text.left+text.right)*w/2;val cy=(text.top+text.bottom)*h/2
        var seed=-1;var nearest=Float.MAX_VALUE
        for(y in max(t,(cy-bh*.25f).toInt()) until min(b,(cy+bh*.25f).toInt()+1))
            for(x in max(l,(cx-bw*.25f).toInt()) until min(r,(cx+bw*.25f).toInt()+1)) {
                val distance=(x-cx)*(x-cx)+(y-cy)*(y-cy)
                if(distance<nearest && white(x,y)) {nearest=distance;seed=(y-t)*bw+x-l}
            }
        if(seed<0)return null
        val seen=BooleanArray(bw*bh);val queue=IntArray(bw*bh);var head=0;var tail=1
        queue[0]=seed;seen[queue[0]]=true
        var best=0f;var tipX=0;var tipY=0;var outside=0
        while(head<tail) {
            val id=queue[head++];val x=l+id%bw;val y=t+id/bw
            val distance=((x-cx)/(bw*.5f)).pow(2)+((y-cy)/(bh*.5f)).pow(2)
            if(distance>1.35f)outside++
            if(distance>best) {best=distance;tipX=x;tipY=y}
            fun push(xx:Int,yy:Int) {if(xx in l until r && yy in t until b) {val j=(yy-t)*bw+xx-l;if(!seen[j] && white(xx,yy)) {seen[j]=true;queue[tail++]=j}}}
            push(x-1,y);push(x+1,y);push(x,y-1);push(x,y+1)
        }
        // Rectangular captions have broad corners; they provide no directed tail.
        if(best<1.45f || outside>tail*.08f)return null
        val owners=candidates.filter {cores[it].contains(tipX.toFloat()/w,tipY.toFloat()/h)}
        return owners.singleOrNull()
    }

    fun linkViews(frames:List<Panel>,cores:List<Panel>,pairs:Set<Pair<Int,Int>>,sharedBalloons:Map<Int,List<Panel>> = emptyMap()):List<Panel> = frames.map {p ->
        val c=p.readingOrderBounds ?: p
        val i=cores.indices.maxByOrNull {overlap(c,cores[it])/max(area(c),area(cores[it]))} ?: return@map p
        if(overlap(c,cores[i])/max(area(c),area(cores[i]))<.80f)return@map p
        val partners=pairs.mapNotNull {(a,b)->when(i){a->b;b->a;else->null}}.distinct()
        val shared=sharedBalloons[i].orEmpty()
        // At a junction of three or more cells, the caption becomes part of
        // each visible envelope. Two-cell ambiguity keeps its established crop.
        val junction=shared.filter {balloon ->sharedBalloons.values.count {balloon in it}>=3}
        val left=min(p.left,junction.minOfOrNull {it.left} ?: p.left);val top=min(p.top,junction.minOfOrNull {it.top} ?: p.top)
        val right=max(p.right,junction.maxOfOrNull {it.right} ?: p.right);val bottom=max(p.bottom,junction.maxOfOrNull {it.bottom} ?: p.bottom)
        val margins=if(junction.isEmpty())emptyList() else buildList {
            if(left<c.left)add(Panel(left,top,c.left,bottom))
            if(right>c.right)add(Panel(c.right,top,right,bottom))
            if(top<c.top)add(Panel(left,top,right,c.top))
            if(bottom>c.bottom)add(Panel(left,c.bottom,right,bottom))
        }
        // Expanding the camera for a shared caption must not light an entire
        // neighbouring strip. Speech is restored separately after these holes.
        p.copy(left=left,top=top,right=right,bottom=bottom,readingOrderBounds=c,
            jointFocus=partners.map {cores[it]},focusExclusions=p.focusExclusions+margins,
            focusIncludes=(p.focusIncludes+shared).distinct())
    }
    data class Assignment(val owner:Int,val body:Panel)
    fun adjacent(text:Panel,body:Panel,cores:List<Panel>,pixels:IntArray,w:Int,h:Int,open:Boolean):Assignment? {
        fun shared(p:Panel)=max(0f,min(p.right,text.right)-max(p.left,text.left))*max(0f,min(p.bottom,text.bottom)-max(p.top,text.top))
        val candidates=cores.indices.filter {shared(cores[it])>text.width*text.height*.15f}
        if(candidates.size!=2)return null
        val a=cores[candidates[0]];val b=cores[candidates[1]]
        if(abs(a.top-b.top)>.03f || abs(a.bottom-b.bottom)>.03f || abs(a.right-b.left)>.05f && abs(b.right-a.left)>.05f)return null
        val envelope=if(open)openBody(text) else body
        val cx=(envelope.left+envelope.right)*.5f*w;val cy=(envelope.top+envelope.bottom)*.5f*h
        val rx=envelope.width*w*.5f;val ry=envelope.height*h*.5f
        val areas=candidates.map {i ->
            val frame=cores[i];var count=0
            for(y in max(0,(envelope.top*h).toInt()) until min(h,(envelope.bottom*h).toInt()))
                for(x in max(0,(envelope.left*w).toInt()) until min(w,(envelope.right*w).toInt())) {
                    if(((x-cx)/rx).pow(2)+((y-cy)/ry).pow(2)>1f || !frame.contains(x.toFloat()/w,y.toFloat()/h))continue
                    val c=pixels[y*w+x]
                    if(minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>202)count++
                }
            count
        }
        if(areas.sum()==0)return null
        val best=areas.indices.maxBy {areas[it]}
        return Assignment(candidates[best],envelope)
    }
}
