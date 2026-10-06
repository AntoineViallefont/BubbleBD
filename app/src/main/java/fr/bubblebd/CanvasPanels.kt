package fr.bubblebd

import kotlin.math.*

/** Artwork at both page edges identifies a background behind framed inserts. */
internal object CanvasPanels {
    private fun area(p:Panel)=p.width*p.height
    private fun shared(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))

    /** On black paper, an illustrated background may contain a row of inserts.
     * Require aligned proposals, real dark seams and an independent large scene. */
    private fun darkCanvas(input:List<Panel>,predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,texts:List<Panel>):List<Panel>? {
        fun dark(x:Int,y:Int):Boolean {val c=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)];return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<35}
        if((0 until h/2 step 4).count {dark(w/40,it) && dark(w-w/40-1,it)}<h/8*.75f)return null
        val cores=input.map {it.readingOrderBounds ?: it}
        var headers=cores.filter {it.width in .25f.. .6f && it.top<.15f && it.height in .08f.. .23f}.sortedBy {it.left}
        if(headers.size!=2) {
            val edge=FrameContours(pixels,w,h,texts)
            headers=cores.filter {it.width>.65f && it.top<.15f && it.height in .08f.. .23f}
                .mapNotNull {p ->edge.split(p)}.firstOrNull {parts ->parts.size==2 && parts.all {it.width in .25f.. .6f}} ?: return null
        }
        // Geometric headers can be lost when the model proposes their whole row.
        if(headers.size!=2 || abs(headers[0].top-headers[1].top)>.025f || abs(headers[0].bottom-headers[1].bottom)>.025f)return null
        val large=cores.any {it.width>.90f && it.height>.55f} || predictions.any {it.confidence>.80f && it.bounds.width>.75f && it.bounds.height>.35f}
        if(!large)return null
        val small=predictions.filter {it.confidence>.50f && it.bounds.width in .10f.. .40f && it.bounds.height in .20f.. .45f && it.bounds.top>=headers.maxOf {p->p.bottom}-.02f}
        for(seed in small) {
            val detected=small.filter {abs(it.bounds.top-seed.bounds.top)<.025f && abs(it.bounds.bottom-seed.bounds.bottom)<.03f}.sortedBy {it.bounds.left}
            val row=detected.toMutableList()
            // Recover only a gap with four independently supported frame edges.
            // The network can miss a narrow cell between two aligned peers.
            val edges=FrameContours(pixels,w,h,texts)
            for((a,b) in detected.zipWithNext())if(b.bounds.left-a.bounds.right in .06f.. .25f) {
                val gap=Panel(a.bounds.right,a.bounds.top,b.bounds.left,a.bounds.bottom)
                edges.fit(gap)?.let {frame ->row.add(PanelCandidate(frame,.60f,false))}
            }
            row.sortBy {it.bounds.left}
            if(row.size<3 || row.sumOf {it.bounds.width.toDouble()}<.70 || row.zipWithNext().any {(a,b)->b.bounds.left-a.bounds.right !in -.006f.. .03f})continue
            if(predictions.none {it.confidence>.70f && it.bounds.width>.70f && it.bounds.height>.35f && it.bounds.top>=row.maxOf {q->q.bounds.bottom}-.04f})continue
            fun seam(a:Panel,b:Panel,lowerPart:Boolean=false):Int? {
                val low=(min(a.right,b.left)*w).toInt()-8;val high=(max(a.right,b.left)*w).toInt()+8
                val top=max(a.top,b.top);val bottom=min(a.bottom,b.bottom)
                val t=((if(lowerPart)top+(bottom-top)*.45f else top)*h).toInt()+4;val z=(bottom*h).toInt()-4
                if(z-t<20)return null
                return (max(2,low)..min(w-3,high)).filter {x ->
                    val samples=(t until z).filter {y->texts.none {p->x.toFloat()/w in p.left-.015f..p.right+.015f && y.toFloat()/h in p.top-.015f..p.bottom+.015f}}
                    samples.size>(z-t)*.40f && samples.count {y ->
                        val c=pixels[y*w+x]
                        maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<80
                    }>samples.size*.85f
                }
                    .minByOrNull {abs(it-(a.right+b.left)*.5f*w)}
            }
            val supportedSeams=row.zipWithNext().count {(a,b)->seam(a.bounds,b.bounds)!=null}
            // Foreground can hide one divider; the remaining seams establish the row.
            if(supportedSeams<max(2,row.size-2))continue
            val divider=seam(headers[0],headers[1],lowerPart=true) ?: continue
            val first=headers[0];val second=headers[1]
            // Include coloured artwork protruding from the initial geometric edge.
            val t=(first.top*h).toInt();val z=(first.bottom*h).toInt()
            val left=(max(8,(first.left*w).toInt()-w/16)..(first.left*w).toInt()).firstOrNull {x ->
                (t until z).count {y ->val c=pixels[y*w+x];val channels=listOf((c shr 16)and 255,(c shr 8)and 255,c and 255);channels.max()-channels.min()>12 && channels.max()>35}>(z-t)*.06f
            } ?: (first.left*w).toInt()
            val topRow=listOf(first.copy(left=min(left.toFloat()/w,row.first().bounds.left),right=(divider-1f)/w),second.copy(left=(divider+1f)/w))
            val inserts=topRow+row.map {val p=it.bounds;p.copy(left=max(0f,p.left-2f/w),top=max(0f,p.top-2f/h),right=min(1f,p.right+2f/w),bottom=min(1f,p.bottom+3f/h))}
            val bottom=row.maxOf {it.bounds.bottom}
            val scene=Panel(0f,0f,1f,1f,readingOrderBounds=Panel(0f,bottom,1f,1f),focusExclusions=inserts)
            return inserts+scene
        }
        return null
    }

    /** A row of framed inserts can bridge the gutter between two broad scenes.
     * Confirm both scenes and every insert before replacing an unsplit region. */
    private fun recoverBridgedRows(input:List<Panel>,predictions:List<PanelCandidate>,contours:()->FrameContours):List<Panel>? {
        val broad=predictions.filter {it.confidence>.80f && it.bounds.width>.75f && it.bounds.height>.18f}.sortedBy {it.bounds.top}
        if(broad.zipWithNext().none {(x,y)->y.bounds.top-x.bounds.bottom in -.035f.. .035f} ||
            predictions.count {it.confidence>.80f && it.bounds.width<.35f}<3)return null
        val edges=contours()
        val framed=predictions.filter {it.confidence>.80f}.mapNotNull {d ->edges.fit(d.bounds)?.let {d.bounds to it}}
        val scenes=framed.filter {it.first.width>.75f && it.first.height>.18f}.sortedBy {it.first.top}
        for((upper,lower) in scenes.zipWithNext()) {
            val a=upper.first;val b=lower.first
            if(abs(a.left-b.left)>.025f || abs(a.right-b.right)>.025f || b.top-a.bottom !in -.035f.. .035f)continue
            val inserts=framed.filter {(raw,_) ->raw.width<.35f && area(raw)<min(area(a),area(b))*.30f &&
                raw.top<a.bottom && raw.bottom>b.top && shared(raw,a)>0f && shared(raw,b)/area(raw)>.50f}.sortedBy {it.first.left}
            if(inserts.size<3 || inserts.zipWithNext().any {(x,y)->y.first.left<x.first.right-.005f || abs(x.first.top-y.first.top)>.015f || abs(x.first.bottom-y.first.bottom)>.015f})continue
            val region=Panel(a.left,a.top,a.right,b.bottom)
            if(input.none {p ->shared(p,region)/area(region)>.90f && area(p)>area(a)*1.5f})continue
            val holes=inserts.map {it.second}
            val insetPanels=inserts.map {(raw,frame)->raw.copy(left=min(raw.left,frame.left),top=min(raw.top,frame.top),right=max(raw.right,frame.right),bottom=max(raw.bottom,frame.bottom),readingOrderBounds=frame)}
            val upperScene=a.copy(readingOrderBounds=a,focusExclusions=holes)
            val lowerScene=b.copy(readingOrderBounds=b.copy(top=holes.maxOf {it.bottom}),focusExclusions=holes)
            return input.filter {shared(it,region)/area(it)<.50f}+upperScene+insetPanels+lowerScene
        }
        return null
    }

    /** A near-page scene with sparse, independently framed inserts is one canvas.
     * Do not let ordering crops turn its free space into a separate panel. */
    private fun recoverFullCanvas(input:List<Panel>,predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,contours:FrameContours):List<Panel>? {
        val background=predictions.filter {it.confidence>.80f && it.bounds.width>.80f && it.bounds.height>.80f && area(it.bounds)>.65f}
            .maxByOrNull {area(it.bounds)} ?: return null
        if(contours.fit(background.bounds)!=null)return null
        val small=predictions.filter {it.confidence>.85f && area(it.bounds)<.10f && shared(it.bounds,background.bounds)/area(it.bounds)>.60f}
        if(small.size<3)return null
        val insets=small.mapNotNull {d ->contours.fit(d.bounds)?.let {frame ->d.bounds to insetBorder(frame,pixels,w,h)}}
        if(insets.size!=small.size || insets.size<3 || insets.sumOf {area(it.second).toDouble()}>.35)return null
        if(insets.indices.any {i ->(i+1 until insets.size).any {j ->shared(insets[i].second,insets[j].second)>0f}})return null
        if(insets.maxOf {it.second.bottom}-insets.minOf {it.second.top}<.65f)return null
        // Empty paper margins around a framed scene remain part of its page view.
        val holes=insets.map {it.second}
        val scene=Panel(0f,0f,1f,1f,readingOrderBounds=Panel(0f,0f,1f,.001f),focusExclusions=holes)
        val independent=input.filter {p ->
            val c=p.readingOrderBounds ?: p
            predictions.any {it.confidence>.80f && shared(c,it.bounds)/max(area(c),area(it.bounds))>.85f} &&
                shared(c,background.bounds)/area(c)<.15f
        }
        return listOf(scene)+insets.map {(raw,frame)->
            val overhang=foregroundOverhang(raw,frame,pixels,w,h)
            raw.copy(top=min(raw.top,overhang?.top ?: raw.top),readingOrderBounds=frame,
                focusIncludes=overhang?.let {listOf(it)} ?: emptyList())
        }+independent
    }


    /** Follow a distinct coloured foreground across an occluded inset border.
     * A fixed seed colour prevents the flood from drifting into the background. */
    private fun foregroundOverhang(raw:Panel,frame:Panel,pixels:IntArray,w:Int,h:Int):Panel? {
        if(raw.right-frame.right<.04f)return null
        val l=(raw.left*w).toInt().coerceIn(0,w-1);val r=(raw.right*w).toInt().coerceIn(l+1,w)
        val edge=(frame.top*h).toInt();val bottom=(raw.bottom*h).toInt().coerceIn(1,h)
        val from=(frame.right*w).toInt().coerceIn(l,r-1)
        fun channels(c:Int)=intArrayOf((c shr 16)and 255,(c shr 8)and 255,c and 255)
        var seed=-1;var brightness=0
        for(y in max(0,edge-5)..min(bottom-1,edge+8))for(x in from until r) {
            val c=channels(pixels[y*w+x]);val light=c.sum()
            if(c.max()-c.min()>35 && light>brightness) {seed=y*w+x;brightness=light}
        }
        if(seed<0)return null
        val colour=channels(pixels[seed]);val seen=BooleanArray((r-l)*bottom);val queue=IntArray(seen.size)
        var head=0;var tail=0;var top=edge;var left=r;var right=l
        fun push(x:Int,y:Int) {
            if(x !in l until r || y !in 0 until bottom)return
            val id=y*(r-l)+x-l;if(seen[id])return
            val c=pixels[y*w+x]
            if(maxOf(abs(((c shr 16)and 255)-colour[0]),abs(((c shr 8)and 255)-colour[1]),abs((c and 255)-colour[2]))>=60)return
            seen[id]=true;queue[tail++]=y*w+x
        }
        push(seed%w,seed/w)
        while(head<tail) {
            val id=queue[head++];val x=id%w;val y=id/w;top=min(top,y);left=min(left,x);right=max(right,x+1)
            push(x-1,y);push(x+1,y);push(x,y-1);push(x,y+1)
        }
        if(edge-top<h*.025f || tail<w*h*.001f || right-left<w*.025f)return null
        return Panel(max(0f,(left-3f)/w),max(0f,(top-3f)/h),min(1f,(right+3f)/w),raw.bottom)
    }
    /** A foreground object can hide one side: the end of its straight upper
     * border corroborates the left and lower sides, rather than the outer scene. */
    private fun insetBorder(p:Panel,pixels:IntArray,w:Int,h:Int):Panel {
        val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
        fun dark(x:Int,y:Int):Boolean {if(x !in 0 until w || y !in 0 until h)return false;val c=pixels[y*w+x];return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<130}
        val top=(max(0,t-5)..min(h-1,t+5)).mapNotNull {y ->
            var last=l;var missed=0
            for(x in l until r) {
                if(dark(x,y)) {last=x+1;missed=0} else missed++
                if(missed>3)break
            }
            if(last-l>(r-l)*.55f && last<r-(r-l)*.15f)y to last else null
        }.maxByOrNull {it.second} ?: return p
        val edge=top.second
        if((t until b).count {dark(l,it)}<(b-t)*.85f)return p
        if((top.first until b).count {dark(edge-1,it)}<(b-top.first)*.25f)return p
        if((l until edge).count {x ->(-3..3).any {dark(x,b-1+it)}}<(edge-l)*.70f)return p
        return Panel(p.left,min(p.top,top.first.toFloat()/h),(edge+1f)/w,p.bottom)
    }
    /** Coloured artwork at both paper edges establishes a canvas behind inserts.
     * Closed frames still need independent evidence; gaps can recover a missed peer. */
    private fun edgeCanvas(input:List<Panel>,predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,contours:FrameContours):List<Panel>? {
        fun coloured(x:Int,y:Int):Boolean {
            val c=pixels[y*w+x];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255
            return maxOf(r,g,b)>50 && minOf(r,g,b)<205 && maxOf(r,g,b)-minOf(r,g,b)>12
        }
        val strip=max(4,(w*.016f).toInt())
        val ys=(0 until h).filter {y ->
            (max(2,(w*.013f).toInt()) until max(2,(w*.013f).toInt())+strip).any {coloured(it,y)} &&
                (w-max(2,(w*.013f).toInt())-strip until w-max(2,(w*.013f).toInt())).any {coloured(it,y)}
        }
        if(ys.size<h*.025f)return null
        // A broad, independently detected opening scene followed by a complete
        // row is not a canvas behind that row.
        if(predictions.any {it.confidence>.80f && it.bounds.top<.05f && it.bounds.width>.90f && it.bounds.height>.40f})return null
        fun darkFrame(p:Panel,occluded:Boolean=false):Panel? {
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            if(r-l<w*.09f || b-t<h*.07f)return null
            fun score(v:Int,a:Int,z:Int,vertical:Boolean):Float {
                val points=(a until z).filter {u ->val x=if(vertical)v else u;val y=if(vertical)u else v
                    x in 0 until w && y in 0 until h && contours.speech.none {q->q.contains(x.toFloat()/w,y.toFloat()/h)}}
                if(points.size<(z-a)*.25f)return 0f
                return points.count {u ->val c=pixels[if(vertical)u*w+v else v*w+u];maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<80}.toFloat()/points.size
            }
            fun edge(v:Int,a:Int,z:Int,vertical:Boolean)=((v-5)..(v+5)).filter {it in 2 until (if(vertical)w else h)-2}
                .map {it to score(it,a,z,vertical)}.maxByOrNull {it.second-abs(it.first-v)*.003f} ?: (v to 0f)
            val left=edge(l,t,b,true);val right=edge(r,t,b,true)
            val upper=edge(t,l,r,false);val lower=edge(b,l,r,false)
            val sides=listOf(left.second,right.second,upper.second,lower.second)
            if(if(occluded) sides.count {it>.85f}<3 || sides.min()<.25f else sides.min()<.85f)return null
            return Panel(left.first.toFloat()/w,upper.first.toFloat()/h,(right.first+1f)/w,(lower.first+1f)/h)
        }
        val frames=predictions.filter {d ->d.confidence>.35f && !(d.confidence<.65f && predictions.any {q ->q!==d && q.confidence>d.confidence+.30f && area(q.bounds)<area(d.bounds)*.65f && shared(q.bounds,d.bounds)/area(q.bounds)>.80f})}.mapNotNull {d ->
            val occluders=predictions.filter {q->q!==d && q.confidence>.85f && area(q.bounds)<area(d.bounds)*.25f && shared(q.bounds,d.bounds)/area(q.bounds)>.15f}.map {it.bounds}
            val fit=(if(occluders.isEmpty())contours else FrameContours(pixels,w,h,contours.speech+occluders)).fit(d.bounds) ?: if(d.confidence>.60f)darkFrame(d.bounds) else null
            (fit ?: d.bounds.takeIf {d.confidence>.85f}).let {f ->f?.let {d to it}}
        }
        if(frames.size<4)return null
        val topBleed=ys.first()<h*.02f
        val firstWide=predictions.filter {it.confidence>.75f && it.bounds.width>.60f && it.bounds.top>ys.first().toFloat()/h+.03f}.minByOrNull {it.bounds.top}
        val broad=predictions.filter {it.confidence>.70f && it.bounds.width>.80f && it.bounds.height>.25f &&
            ys.count {y ->y.toFloat()/h in it.bounds.top..it.bounds.bottom}>ys.size*.65f}.maxByOrNull {area(it.bounds)}
        val top:Float;val bottom:Float
        if(topBleed && firstWide!=null && ys.last().toFloat()/h<firstWide.bounds.top && ys.last()<h*.25f) {
            top=0f;bottom=firstWide.bounds.top
        } else if(topBleed && ys.last()>h*.30f) {
            top=0f;bottom=frames.maxOf {it.second.bottom}
        } else if(broad!=null) {
            val crossingInset=predictions.any {small ->small.confidence>.85f && area(small.bounds)<.025f &&
                predictions.count {large ->large!==small && large.confidence>.80f && area(large.bounds)>area(small.bounds)*3f &&
                    shared(small.bounds,large.bounds)/area(small.bounds)>.15f}>=2}
            if(!crossingInset)return null
            top=broad.bounds.top;bottom=frames.filter {it.second.top>broad.bounds.top}.maxOfOrNull {it.second.bottom} ?: return null
        } else return null
        val scene=Panel(.01f,top,.99f,bottom)
        val inserts=frames.filter {(d,f)->d!=broad && area(f)<area(scene)*.65f && shared(f,scene)/area(f)>.35f &&
            (d.confidence>.70f || topBleed && firstWide!=null && ys.last().toFloat()/h<firstWide.bounds.top && f.width>.40f || frames.any {(other,q)->other!=d && (abs(q.left-f.left)<.025f && abs(q.right-f.right)<.025f || abs(q.top-f.top)<.02f && abs(q.bottom-f.bottom)<.02f)})}
            .sortedByDescending {it.first.confidence}.map {it.second}.fold(mutableListOf<Panel>()) {acc,p ->
                if(acc.none {shared(it,p)/max(area(it),area(p))>.70f})acc.add(p);acc
            }
        if(inserts.isEmpty())return null
        // A narrow inset may cross two neighbours, but an enlarged duplicate cannot.
        val clean=inserts.toMutableList()
        // Recover a missing end cell using a closed contour, a known outer column
        // and the matching top/bottom of two independently detected lower peers.
        for(peer in clean.toList()) {
            if(peer.top<.4f || peer.height<.2f)continue
            val outer=frames.map {it.second.left}.filter {it<peer.left-.12f}.minOrNull() ?: continue
            val raw=Panel(outer,peer.top,max(outer+.10f,peer.left-.01f),peer.bottom)
            if(clean.any {shared(it,raw)/area(raw)>.50f})continue
            val aligned=clean.count {abs(it.top-peer.top)<.025f && abs(it.bottom-peer.bottom)<.025f}
            if(aligned<2)continue
            val fit=contours.fit(raw) ?: darkFrame(raw,true) ?: continue
            if(abs(fit.top-peer.top)>.02f || abs(fit.bottom-peer.bottom)>.02f || fit.width>.40f)continue
            clean.add(fit)
        }
        val holes=clean.filter {shared(it,scene)>0f}
        val cuts=(listOf(scene.top,scene.bottom)+holes.flatMap {listOf(max(scene.top,it.top),min(scene.bottom,it.bottom))}).distinct().sorted()
        val free=cuts.zipWithNext().filter {(a,b)->b-a>.025f && holes.none {it.top<b-.002f && it.bottom>a+.002f}}.maxByOrNull {it.second-it.first}
        val order=if(free!=null)Panel(scene.left,free.first,scene.right,free.second) else scene.copy(bottom=min(scene.bottom,scene.top+.001f))
        val outside=input.filter {p ->val core=p.readingOrderBounds ?: p;shared(core,scene)/area(core)<.15f}
        return outside+clean.map {p ->p.copy(readingOrderBounds=p,focusExclusions=clean.filter {q ->q!=p && area(q)<area(p)*.4f && shared(q,p)/area(q)>.10f})}+scene.copy(readingOrderBounds=order,focusExclusions=holes)
    }

    fun repair(input:List<Panel>,predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,contours:FrameContours,
               textBounds:List<Panel> = emptyList(),geometry:List<Panel> = emptyList()):List<Panel> {
        edgeCanvas(input,predictions,pixels,w,h,contours)?.let {return it}
        darkCanvas(geometry,predictions,pixels,w,h,textBounds)?.let {return it}
        // Tight text masks leave enough border to corroborate narrow captioned inserts.
        recoverBridgedRows(input,predictions) {if(textBounds.isEmpty())contours else FrameContours(pixels,w,h,textBounds)}?.let {return it}
        recoverFullCanvas(input,predictions,pixels,w,h,contours)?.let {return it}
        val wideRows=input.map {it.readingOrderBounds ?: it}.filter {it.width>.90f && it.height in .07f.. .40f}
        if(wideRows.size>=2 && wideRows.zipWithNext().any {(a,b)->shared(a,b)<min(area(a),area(b))*.05f})return input
        val observed=predictions.filter {it.confidence>.65f}.mapNotNull {d -> contours.fit(d.bounds)?.let {d.bounds to it}}
        val geometric=input.filter {it.width<.75f && it.height<.50f}.mapNotNull {p ->
            val c=p.readingOrderBounds ?: p
            val pair=input.any {other ->val q=other.readingOrderBounds ?: other
                q!==c && abs(q.top-c.top)<.02f && abs(q.bottom-c.bottom)<.02f && (q.left-c.right in .005f.. .03f || c.left-q.right in .005f.. .03f) &&
                    predictions.any {d ->d.confidence>.70f && shared(c,d.bounds)/area(c)>.90f && shared(q,d.bounds)/area(q)>.90f}}
            val fit=contours.fit(c) ?: if(pair)c else null
            fit?.let {c to it}
        }
        val framed=(observed+geometric.filter {(raw,_) -> observed.none {shared(it.first,raw)/max(area(it.first),area(raw))>.85f}}).filterNot {(raw,_) ->
            val children=geometric.filter {area(it.second)<area(raw)*.75f && shared(it.second,raw)/area(it.second)>.90f}
            children.size>=2 && children.sumOf {area(it.second).toDouble()}>area(raw)*.70f && children.indices.all {i ->(i+1 until children.size).all {j ->shared(children[i].second,children[j].second)/min(area(children[i].second),area(children[j].second))<.15f}}
        }
        if(framed.size<2)return input
        fun ink(x:Int,y:Int):Boolean {val c=pixels[y*w+x];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255;return minOf(r,g,b)<247 || maxOf(r,g,b)-minOf(r,g,b)>8}
        val columns=(0 until w).filter {x -> (0 until h).count {y ->ink(x,y)}>h*.15f}
        if(columns.isEmpty())return input
        val left=columns.first();val right=columns.last()+1
        if(right-left<w*.90f)return input
        val strip=max(3,(w*.01f).toInt())
        val sideActive=BooleanArray(h) {y ->
            (left+1 until left+1+strip).count {ink(it,y)}>.55f*strip ||
                (right-strip-1 until right-1).count {ink(it,y)}>.55f*strip
        }
        val active=BooleanArray(h) {y ->
            (left+1 until left+1+strip).count {ink(it,y)}>.55f*strip &&
                (right-strip-1 until right-1).count {ink(it,y)}>.55f*strip
        }
        // A paper-wide gutter separates scenes even when their side artwork is close.
        // Inset gutters leave the surrounding background visible at the page edges.
        val paperRows=BooleanArray(h) {y ->(left until right).count {x ->
            val c=pixels[y*w+x];minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>242
        }>(right-left)*.94f}
        fun paperSeam(y:Int)=y in 1 until h-1 && paperRows[y] && (paperRows[y-1] || paperRows[y+1])
        val runs=mutableListOf<Pair<Int,Int>>();var start=-1;var last=-1
        for(y in 0 until h) {
            if(active[y]) {if(start<0)start=y;last=y}
            if(start>=0 && (y-last>5 || y==h-1)) {if(last-start>h*.20f)runs.add(start to last+1);start=-1}
        }
        val result=input.toMutableList()
        val colourFrames by lazy {predictions.filter {it.confidence>.85f}.mapNotNull {d -> contours.fitColour(d.bounds)?.let {d.bounds to it}}}
        val diagonalFrames by lazy {SlantedPanels.find(predictions,pixels,w,h)}
        for((initialTop,initialBottom) in runs) {
            var top=initialTop;var bottom=initialBottom
            while(top>0 && !paperSeam(top-1) && (max(0,top-max(5,(h*.04f).toInt())) until top).any {sideActive[it]})top--
            while(bottom<h && !paperSeam(bottom) && (bottom until min(h,bottom+max(5,(h*.04f).toInt()))).any {sideActive[it]})bottom++
            val completeRow=input.map {it.readingOrderBounds ?: it}.filter {it.width<.85f && it.height<.40f && abs(it.top*h-top)<h*.025f}.sortedBy {it.left}
            if(completeRow.size>=2 && completeRow.sumOf {it.width.toDouble()}>.90*(right-left)/w && completeRow.zipWithNext().all {(a,b)->b.left>=a.right-.015f})continue
            // Flat black page margins are gutters, not a continuous background.
            fun variation(x:Int):Int {val values=(top until bottom step 3).map {y ->val c=pixels[y*w+x];(((c shr 16)and 255)+((c shr 8)and 255)+(c and 255))/3};return values.max()-values.min()}
            if(variation(left+strip/2)<25 || variation(right-strip/2-1)<25)continue
            fun artwork(x:Int)=(top until bottom).count {y -> val c=pixels[y*w+x];maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>40}.toFloat()/(bottom-top)
            if(artwork(left+strip/2)<.25f || artwork(right-strip/2-1)<.25f)continue
            val upper=framed.map {it.second}.filter {it.top*h<top && it.bottom*h>top && it.width in .20f.. .70f}.minOfOrNull {it.top} ?: top.toFloat()/h
            val scene=Panel(left.toFloat()/w,upper,right.toFloat()/w,bottom.toFloat()/h)
            val supported=(framed+colourFrames+diagonalFrames).distinctBy {it.first}
            val independent=supported.filterNot {(raw,_) ->
                val children=supported.filter {(other,f)->other!=raw && area(f)<area(raw)*.75f && shared(f,raw)/area(f)>.90f}
                children.size>=2 && children.sumOf {area(it.second).toDouble()}>area(raw)*.70f && children.indices.all {i ->(i+1 until children.size).all {j ->shared(children[i].second,children[j].second)/min(area(children[i].second),area(children[j].second))<.15f}}
            }
            val separate=independent.filter {(_,f)->independent.none {(_,other)->area(other)>area(f)*1.25f && shared(f,other)/area(f)>.95f}}
            val inserts=separate.filter {(raw,frame)->area(frame)<area(scene)*.60f && shared(frame,scene)/area(frame)>.15f && !(frame.width>.75f && frame.height>.30f && frame.bottom>=scene.bottom-.02f)}.toMutableList()
            for(d in predictions.filter {it.confidence>.80f && it.bounds.width<.70f && area(it.bounds)<area(scene)*.6f && shared(it.bounds,scene)/area(it.bounds)>.15f}) {
                if(inserts.any {(_,f)->area(f)>area(d.bounds)*1.25f && shared(f,d.bounds)/area(d.bounds)>.95f})continue
                val children=inserts.filter {(_,f)->area(f)<area(d.bounds)*.75f && shared(f,d.bounds)/area(f)>.90f}
                if(children.size>=2 && children.sumOf {area(it.second).toDouble()}>area(d.bounds)*.70f)continue
                if(inserts.none {(raw,_) ->shared(raw,d.bounds)/max(area(raw),area(d.bounds))>.70f} &&
                    (inserts.count {(_,frame)->abs(frame.top-d.bounds.top)<.02f || abs(frame.bottom-d.bounds.bottom)<.02f}>=(if(d.confidence>.85f)1 else 2) || (d.confidence>.90f && d.bounds.width>.30f && d.bounds.height>.15f && d.bounds.top<scene.top+.04f && d.bounds.bottom<scene.bottom-.08f)))inserts.add(d.bounds to d.bounds)
            }
            if(inserts.isEmpty())continue
            // A background must also have a large proposal or geometric region.
            val regions=input+predictions.map {it.bounds}
            val fragmented=regions.indices.any {i ->(i+1 until regions.size).any {j ->
                val a=regions[i];val b=regions[j]
                val unionWidth=max(a.right,b.right)-min(a.left,b.left)
                a.height>.25f && b.height>.25f && unionWidth>.85f && shared(a,b)/min(area(a),area(b))>.10f &&
                    shared(a,scene)/area(a)>.65f && shared(b,scene)/area(b)>.65f
            }}
            val layered=scene.height>.30f && inserts.size>=3 && inserts.any {it.second.top<scene.top+.15f} && inserts.any {it.second.bottom>scene.bottom-.15f}
            if(!fragmented && !layered && regions.none {it.width>.75f && it.height>.25f && shared(it,scene)/min(area(it),area(scene))>.65f})continue
            val holes=inserts.map {it.second}.distinct()
            val cuts=(listOf(scene.top,scene.bottom)+holes.flatMap {listOf(max(scene.top,it.top),min(scene.bottom,it.bottom))}).filter {it in scene.top..scene.bottom}.distinct().sorted()
            val free=cuts.zipWithNext().filter {(a,b)->holes.none {it.top<b-.002f && it.bottom>a+.002f}}
                .maxByOrNull {it.second-it.first}
            val order=if(free!=null)Panel(scene.left,free.first,scene.right,free.second) else scene
            val existing=result.filter {p ->shared(p,scene)/area(p)>.15f || (p.width>.80f && shared(p,scene)/area(scene)>.65f)}
            result.removeAll(existing.toSet())
            for((raw,frame) in inserts) {
                val present=result.any {shared(it,frame)/max(area(it),area(frame))>.85f}
                if(!present)result.add(raw.copy(left=min(raw.left,frame.left),top=min(raw.top,frame.top),right=max(raw.right,frame.right),bottom=max(raw.bottom,frame.bottom),readingOrderBounds=frame,focusOutline=frame.focusOutline))
            }
            result.add(scene.copy(readingOrderBounds=order,focusExclusions=holes))
        }
        return result
    }
}
