package fr.bubblebd

import kotlin.math.*

data class PanelCandidate(val bounds:Panel,val confidence:Float,val text:Boolean)

/** Combines geometric evidence with local neural proposals; no page-specific coordinates. */
object HybridPanels {
    private fun area(p:Panel)=p.width*p.height
    private fun overlap(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    private fun union(a:Panel,b:Panel)=a.copy(left=min(a.left,b.left),top=min(a.top,b.top),right=max(a.right,b.right),bottom=max(a.bottom,b.bottom))
    fun combine(geometry:List<Panel>,predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,stop:()->Unit={},rectanglePrior:Boolean=false,trace:((String,List<Panel>)->Unit)?=null):List<Panel> {
        stop()
        val speechMasks=predictions.filter {it.text && it.confidence>.45f}.map {d ->val p=d.bounds;Panel(max(0f,p.left-.025f),max(0f,p.top-.025f),min(1f,p.right+.025f),min(1f,p.bottom+.025f))}
        val contours=FrameContours(pixels,w,h,speechMasks)
        val recovered=predictions.filter {!it.text && it.confidence in .20f.. .30f}.mapNotNull {d ->contours.fit(d.bounds)?.let {PanelCandidate(it,.71f,false)}}
        val proposals=PanelCandidates.consolidate(LayoutEvidence.removeCrops(predictions+recovered,pixels,w,h))
        if(proposals.isEmpty())return InsetPanels.recover(geometry,pixels,w,h)
        fun core(p:Panel)=p.readingOrderBounds ?: p
        val frameCache=mutableMapOf<Pair<Panel,List<Panel>>,Boolean>()
        // A neural rectangle can repair an inaccurate split only when its black frame is visible.
        fun framed(p:Panel,occluding:List<Panel> = emptyList()):Boolean {
            val key=p to occluding
            frameCache[key]?.let {return it}
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            if(r-l<w*.10 || b-t<h*.08)return false
            fun dark(x:Int,y:Int):Boolean {if(x !in 0 until w || y !in 0 until h)return false;val c=pixels[y*w+x];return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<190}
            fun paper(x:Int,y:Int):Boolean {if(x !in 0 until w || y !in 0 until h)return false;val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>220}
            fun vertical(x:Int)=(-5..5).maxOf {dx ->
                val ys=(t until b).filter {y ->occluding.none {q ->(x+dx).toFloat()/w in q.left..q.right && y.toFloat()/h in q.top..q.bottom}}
                if(ys.size<(b-t)*.35f)0f else ys.count {y ->dark(x+dx,y) && listOf(-3,-2,2,3).any {paper(x+dx+it,y)}}.toFloat()/ys.size
            }
            fun horizontal(y:Int)=(-5..5).maxOf {dy ->(l until r).count {dark(it,y+dy)}.toFloat()/(r-l)}
            return (vertical(l)>.72f && vertical(r)>.72f && (horizontal(t)>.65f || horizontal(b)>.65f)).also {frameCache[key]=it}
        }
        val original=geometry.filterNot {it.isWholePage}
        val crowded=original.indices.any {i ->(i+1 until original.size).any {j ->
            val a=core(original[i]);val b=core(original[j]);val shared=overlap(a,b)
            shared/min(area(a),area(b)) in .20f.. .88f
        }}
        val coherent=original.none {p ->
            val children=proposals.filter {d ->d.confidence>.65f && area(d.bounds)<area(core(p))*.75f && overlap(d.bounds,core(p))/area(d.bounds)>.80f}
            children.size>=3 && children.maxOf {it.bounds.left}-children.minOf {it.bounds.left}>.10f &&
                children.indices.all {i ->(i+1 until children.size).all {j ->overlap(children[i].bounds,children[j].bounds)/min(area(children[i].bounds),area(children[j].bounds))<.20f}}
        }
        // Two independently framed cases or broad scenes separated by paper remain usable.
        val shortLayout=original.size in 2..3 && (original.all {framed(core(it))} || LayoutEvidence.separatedStack(original.map(::core),pixels,w,h))
        val reliable=(original.size>=4 || shortLayout) && !crowded && coherent
        val rawSpeech=predictions.filter {it.text && it.confidence>.45f}.map {it.bounds}
        val rectangular=BandLayout.confirm(geometry,pixels,w,h,rawSpeech,proposals).ifEmpty {RectangularLayout.confirm(proposals,pixels,w,h,rawSpeech,geometry)}
        val base=(rectangular.takeIf {it.isNotEmpty()} ?: LayoutEvidence.repair(if(reliable)original else proposals.map {it.bounds},geometry,proposals,pixels,w,h)).toMutableList()
        val inkEnvelopes=mutableMapOf<Panel,Panel>()
        if(rectangular.isEmpty()) {
        trace?.invoke("layout reliable=$reliable",base.toList())
        fun paperSeparation(a:Panel,b:Panel):Boolean {
            for(horizontal in listOf(true,false)) {
                val first=if((if(horizontal)a.top else a.left)<(if(horizontal)b.top else b.left))a else b
                val second=if(first===a)b else a
                val end=if(horizontal)first.bottom else first.right
                val start=if(horizontal)second.top else second.left
                if(start-end !in -.005f.. .035f)continue
                val low=max(if(horizontal)a.left else a.top,if(horizontal)b.left else b.top)
                val high=min(if(horizontal)a.right else a.bottom,if(horizontal)b.right else b.bottom)
                if(high-low<.10f)continue
                val span=if(horizontal)w else h;val axis=if(horizontal)h else w
                val from=(low*span).toInt().coerceIn(0,span-1);val to=(high*span).toInt().coerceIn(from+1,span)
                val center=((end+start)*.5f*axis).toInt()
                if((-3..3).any {delta ->
                    val v=center+delta
                    v in 0 until axis && (from until to).count {u ->
                        val c=pixels[if(horizontal)v*w+u else u*w+v]
                        minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>202
                    }.toFloat()/(to-from)>.85f
                })return true
            }
            return false
        }
        // A confident full panel can repair two fragments caused by a balloon crossing a dark gutter.
        fun dividingContour(a:Panel,b:Panel):Boolean {
            val first=if(a.left<b.left)a else b;val second=if(first===a)b else a
            if(second.left-first.right !in -.005f.. .035f)return false
            val t=(max(a.top,b.top)*h).toInt();val bottom=(min(a.bottom,b.bottom)*h).toInt()
            if(bottom<=t)return false
            return listOf(first.right,second.left).any {edge ->(-5..5).any {dx ->
                val x=(edge*w).toInt()+dx
                x in 3 until w-3 && (t until bottom).count {y ->
                    val c=pixels[y*w+x]
                    maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<190 && listOf(-3,-2,2,3).any {d ->
                        val p=pixels[y*w+x+d];minOf((p shr 16)and 255,(p shr 8)and 255,p and 255)>220
                    }
                }.toFloat()/(bottom-t)>.30f
            }}
        }
        if(reliable)for(d in proposals.filter {it.confidence>.85f}) {
            val pieces=base.filter {it.focusExclusions.isEmpty() && overlap(core(it),d.bounds)/area(core(it))>.85f}
            val separateFrames=pieces.indices.any {i ->(i+1 until pieces.size).any {j ->
                    val a=core(pieces[i]);val b=core(pieces[j])
                    abs(a.top-b.top)<.025f && abs(a.bottom-b.bottom)<.025f && paperSeparation(a,b) && dividingContour(a,b)
                }}
            if(separateFrames && pieces.size==2 && framed(d.bounds)) {
                // A whole-row observation corroborates the outer contour without
                // erasing the physical divider between its two frames.
                val ordered=pieces.sortedBy {core(it).left}
                for((k,piece) in ordered.withIndex()) {
                    val p=core(piece)
                    if(abs(p.top-d.bounds.top)<.025f && abs(p.bottom-d.bounds.bottom)<.025f) {
                        val grown=Panel(if(k==0)min(p.left,d.bounds.left) else p.left,p.top,
                            if(k==1)max(p.right,d.bounds.right) else p.right,p.bottom)
                        base[base.indexOf(piece)]=grown
                    }
                }
            }
            if(pieces.size>=2 && (pieces.sumOf {area(core(it)).toDouble()}>area(d.bounds)*.65 || framed(d.bounds)) && !separateFrames) {
                base.removeAll(pieces.toSet());base.add(d.bounds)
            }
        }
        // Recover multiple inset panels inside an unsplit geometric region.
        if(reliable)for(p in base.toList()) {
            stop()
            if(p.focusExclusions.isNotEmpty())continue
            val children=proposals.filter {it.confidence>.70f && area(it.bounds)<area(core(p))*.78f && overlap(it.bounds,core(p))/area(it.bounds)>.88f}
            // A tall scene can contain a column of framed inserts. Preserve the scene
            // and add the inserts instead of discarding either narrative level.
            val stack=children.filter {it.confidence>.85f}.sortedBy {it.bounds.top}
            val insetColumn=stack.size>=3 && stack.zipWithNext().all {(a,b) ->
                val xOverlap=min(a.bounds.right,b.bounds.right)-max(a.bounds.left,b.bounds.left)
                xOverlap/min(a.bounds.width,b.bounds.width)>.80f &&
                    b.bounds.top>=a.bounds.bottom-.015f
            }
            val separateScene=proposals.any {d -> d.confidence>.55f && d !in children &&
                area(d.bounds)>area(core(p))*.25f && overlap(d.bounds,core(p))/area(d.bounds)>.90f &&
                stack.all {overlap(d.bounds,it.bounds)/area(it.bounds)<.20f}}
            if(insetColumn && separateScene) {
                base.addAll(stack.map {it.bounds});continue
            }
            val strongRow=children.size>=3 && children.indices.any {i ->(i+1 until children.size).any {j ->
                val a=children[i];val b=children[j]
                a.confidence>.90f && b.confidence>.90f && abs(a.bounds.top-b.bounds.top)<.02f &&
                    overlap(a.bounds,b.bounds)/min(area(a.bounds),area(b.bounds))<.05f &&
                    min(area(a.bounds),area(b.bounds))>area(core(p))*.10f
            }}
            if(children.size>=2 && (strongRow || children.indices.any {i ->(i+1 until children.size).any {j ->paperSeparation(children[i].bounds,children[j].bounds) && !LayoutEvidence.continuousScene(children[i].bounds,children[j].bounds,pixels,w,h)}}) && children.sumOf {area(it.bounds).toDouble()}>area(core(p))*.65) {
                base.remove(p);base.addAll(children.map {it.bounds})
            }
        }
        // Inset scenes are read after their upper inserts and before lower inserts.
        trace?.invoke("insets",base.toList())
        if(!reliable)for(i in base.indices) {
            if(base[i].focusExclusions.isNotEmpty())continue
            val p=base[i]
            val contained=base.filter {it!==p && area(it)<area(p)*.70f && (overlap(it,p)/area(it)>.78f || (it.top>p.top+p.height*.50f && it.top<p.bottom && it.left>=p.left-.03f && it.right<=p.right+.03f))}
            val top=contained.filter {it.top<p.top+p.height*.30f}.maxOfOrNull {it.bottom}
            val bottom=contained.filter {it.top>p.top+p.height*.50f}.minOfOrNull {it.top}
            val t=top ?: p.top;val b=bottom ?: p.bottom
            if(contained.isNotEmpty() && b-t>p.height*.25f) {
                val crop=Panel(p.left,t,p.right,b)
                base[i]=if(p.focusExclusions.isNotEmpty())p.copy(readingOrderBounds=crop) else crop
            }
        }
        fun dividerBetween(a:Panel,b:Panel):Boolean {
            val top=(max(a.top,b.top)*h).toInt().coerceIn(0,h-1)
            val bottom=(min(a.bottom,b.bottom)*h).toInt().coerceIn(top+1,h)
            val left=(min(a.right,b.left)*w).toInt().coerceIn(0,w-1)
            val right=(max(a.right,b.left)*w).toInt().coerceIn(left,w-1)
            return (max(0,left-2)..min(w-1,right+2)).any {x ->
                var blank=0
                for(y in top until bottom) {
                    val c=pixels[y*w+x];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val bl=c and 255
                    if(minOf(r,g,bl)>202 || maxOf(r,g,bl)<62)blank++
                }
                blank.toFloat()/(bottom-top)>.82f
            }
        }
        trace?.invoke("ordering crops",base.toList())
        // Merge a short upper scene and its tall side continuation only when no gutter separates them.
        var merged=true
        while(merged) {
            merged=false
            loop@for(i in base.indices)for(j in base.indices) {
                if(i==j)continue
                val a=core(base[i]);val b=core(base[j])
                if(abs(a.right-b.left)<.025f && abs(a.top-b.top)<.025f && a.height<b.height*.72f &&
                    a.height>.12f && proposals.any {it.confidence>.85f && overlap(it.bounds,b)/max(area(it.bounds),area(b))>.90f} && !dividerBetween(a,b)) {
                    val combined=union(a,b)
                    base[i]=combined;base.removeAt(j);merged=true;break@loop
                }
            }
        }
        // Recover the missing side of a weak portrait crop between two aligned wide rows.
        for(i in base.indices) {
            stop()
            val p=core(base[i])
            val confidence=proposals.filter {overlap(it.bounds,p)/max(area(it.bounds),area(p))>.85f}.maxOfOrNull {it.confidence} ?: 1f
            if(confidence>=.60f)continue
            val above=base.map(::core).filter {it.bottom<=p.top+.015f && p.top-it.bottom<.035f && abs(it.left-p.left)<.04f && it.width>p.width*1.65f}.maxByOrNull {it.bottom}
            val below=base.map(::core).filter {it.top>=p.bottom-.015f && it.top-p.bottom<.035f && abs(it.left-p.left)<.04f && it.width>p.width*1.65f}.minByOrNull {it.top}
            if(above!=null && below!=null && abs(above.right-below.right)<.04f &&
                base.indices.none {j ->j!=i && core(base[j]).left>=p.right-.015f && overlap(core(base[j]),Panel(p.right,p.top,min(above.right,below.right),p.bottom))>0f})
                base[i]=Panel(min(p.left,min(above.left,below.left)),p.top,min(above.right,below.right),p.bottom)
        }
        val insetCache=mutableMapOf<Panel,List<Panel>>()
        fun physicalInsets(p:Panel)=insetCache.getOrPut(p) {proposals.filter {d ->d.confidence>.40f && area(d.bounds)<area(p)*.40f &&
            overlap(d.bounds,p)/area(d.bounds)>.65f && framed(d.bounds)}.map {it.bounds}}
        val replacements=mutableSetOf<Panel>()
        val continuousRegions=geometry.indices.flatMap {i ->(i+1 until geometry.size).mapNotNull {j ->
            if(LayoutEvidence.continuousScene(core(geometry[i]),core(geometry[j]),pixels,w,h))union(geometry[i],geometry[j]) else null
        }}
        for(i in base.indices) {
            stop()
            val p=core(base[i])
            // The ordering crop of a background does not limit its visible artwork.
            if(base[i].focusExclusions.isNotEmpty() && area(base[i])>area(p)*1.35f)continue
            val matched=proposals.filter {d ->d.confidence>.85f && overlap(p,d.bounds)/min(area(p),area(d.bounds))>.75f &&
                proposals.none {other ->other!==d && LayoutEvidence.continuousScene(d.bounds,other.bounds,pixels,w,h)} &&
                continuousRegions.none {region ->area(region)>area(d.bounds)*1.2f && overlap(region,d.bounds)/area(d.bounds)>.90f} &&
                max(area(p),area(d.bounds))/min(area(p),area(d.bounds))<2.8f && framed(d.bounds,physicalInsets(d.bounds)) &&
                base.indices.none {j ->j!=i && overlap(core(base[j]),d.bounds)/area(core(base[j]))>.50f &&
                    physicalInsets(d.bounds).none {q ->overlap(q,core(base[j]))/max(area(q),area(core(base[j])))>.65f}}}
                .maxByOrNull {overlap(p,it.bounds)/max(area(p),area(it.bounds))} ?: continue
            if(matched.bounds !in replacements && (abs(p.left-matched.bounds.left)>.02f || abs(p.right-matched.bounds.right)>.02f ||
                abs(p.top-matched.bounds.top)>.02f || abs(p.bottom-matched.bounds.bottom)>.02f)) {
                base[i]=matched.bounds;replacements.add(matched.bounds)
            }
        }
        // Keep a confident independently framed panel even when its pale interior was
        // trimmed into a narrow figure or removed by the geometric minimum size.
        for(d in proposals.filter {it.confidence>.85f && framed(it.bounds)}) {
            if(base.none {p ->overlap(core(p),d.bounds)/min(area(core(p)),area(d.bounds))>.65f})base.add(d.bounds)
        }
        for(d in proposals.filter {it.confidence>.70f}) {
            val frame=contours.fit(d.bounds) ?: continue
            val children=contours.split(frame)
            val matched=base.filter {p ->val c=core(p);overlap(c,frame)/area(c)>.70f || (overlap(c,frame)/area(frame)>.85f && area(c)<area(frame)*2.8f)}
            val established=matched.map(::core).sortedBy {it.left}
            val stablePair=established.size==2 && abs(established[0].top-established[1].top)<.025f && abs(established[0].bottom-established[1].bottom)<.025f && established[1].left>=established[0].right-.005f
            val conflicting=children!=null && stablePair && abs((children[0].right+children[1].left-established[0].right-established[1].left)*.5f)>.05f
            val confirmedChildren=children?.all {child ->proposals.any {q ->q!==d && q.confidence>.65f &&
                overlap(q.bounds,child)/max(area(q.bounds),area(child))>.70f}} ?: false
            val unsupportedDarkSplit=children!=null && d.confidence>.90f && !confirmedChildren &&
                children[1].left-children[0].right<.012f
            if(children!=null && matched.isNotEmpty() && !conflicting && !unsupportedDarkSplit) {
                base.removeAll(matched.toSet());base.addAll(children)
            } else if(d.confidence<.85f && matched.size>=2 && matched.all {core(it).width<frame.width*.90f} && matched.sumOf {area(core(it)).toDouble()}>area(frame)*.30f) {
                base.removeAll(matched.toSet());base.add(frame)
            }
        }
        trace?.invoke("contour repair",base.toList())
        val canvases=CanvasPanels.repair(base.toList(),proposals,pixels,w,h,contours,predictions.filter {it.text && it.confidence>.45f}.map {it.bounds},geometry)
        trace?.invoke("canvas repair",canvases)
        val paper=PaperRowPanels.repair(canvases,proposals,pixels,w,h,rawSpeech)
        trace?.invoke("paper repair",paper)
        base.clear();base.addAll(FrameContinuations.repair(paper,pixels,w,h,rawSpeech))
        if(base.any {it.width>.90f && it.focusExclusions.isNotEmpty()})for(p in base.toList()) {
            if(p.focusExclusions.isNotEmpty())continue
            val fitted=contours.fit(core(p)) ?: continue
            val split=contours.tonalSplit(fitted) ?: continue
            base.remove(p);base.addAll(split)
        }
        // A caption can hide the bottom of one cell in an otherwise aligned row.
        // Keep that caption within its column, rather than treating it as a gutter.
        for(i in base.indices) {
            stop()
            val p=core(base[i])
            if(base[i].focusExclusions.isNotEmpty() || p.width !in .10f.. .35f || p.height !in .12f.. .40f)continue
            val neighbours=base.filterIndexed {j,q ->j!=i && abs(core(q).top-p.top)<.015f && core(q).width in .10f.. .35f &&
                (core(q).right<=p.left+.005f || core(q).left>=p.right-.005f)}.map(::core)
            if(neighbours.size<2)continue
            val crossingCaption=predictions.any {d->d.text && d.confidence>.70f && d.bounds.top>=p.bottom-.015f && d.bounds.top<p.bottom+.035f &&
                max(0f,min(p.right,d.bounds.right)-max(p.left,d.bounds.left))/d.bounds.width>.80f}
            if(crossingCaption) {
                val continued=contours.continueBelowCaption(p)
                if(continued!=null && base.indices.none {j->j!=i && overlap(core(base[j]),continued)/area(core(base[j]))>.10f}) {
                    base[i]=continued;continue
                }
            }
            val bottom=neighbours.map {it.bottom}.sorted()[neighbours.size/2]
            if(neighbours.count {abs(it.bottom-bottom)<.015f}<2 || bottom-p.bottom !in .02f.. .08f || p.height>(bottom-p.top)*.85f)continue
            val caption=predictions.any {d ->d.text && d.confidence>.70f && d.bounds.top>=p.bottom-.006f && d.bounds.top<p.bottom+.035f &&
                d.bounds.bottom<=bottom+.01f && max(0f,min(p.right,d.bounds.right)-max(p.left,d.bounds.left))/d.bounds.width>.80f}
            if(caption)base[i]=p.copy(bottom=bottom,readingOrderBounds=null)
        }
        // A weak wide-row observation can locate pale artwork omitted by gutter
        trace?.invoke("physical frames",base.toList())
        // trimming. Keep the separate geometric columns, supported by actual ink.
        for(row in proposals.filter {it.confidence in .30f.. .70f && it.bounds.width>.45f}) {
            val p=row.bounds
            val children=base.indices.filter {i ->val c=core(base[i]);area(c)<area(p)*.65f &&
                overlap(c,p)/area(c)>.80f && !framed(c)}.sortedBy {core(base[it]).left}
            if(children.size !in 2..4 || children.zipWithNext().any {(i,j) ->
                val a=core(base[i]);val b=core(base[j]);b.left-a.right<.025f || abs(a.bottom-b.bottom)>.03f
            })continue
            for((column,i) in children.withIndex()) {
                val c=core(base[i])
                val l=(max(p.left,if(column==0)p.left else (core(base[children[column-1]]).right+c.left)*.5f)*w).toInt().coerceIn(0,w-1)
                val r=(min(p.right,if(column==children.lastIndex)p.right else (c.right+core(base[children[column+1]]).left)*.5f)*w).toInt().coerceIn(l+1,w)
                val t=(p.top*h).toInt().coerceIn(0,h-1);val b=(min(p.bottom,c.bottom+.015f)*h).toInt().coerceIn(t+1,h)
                var top=b;var left=r;var right=l;var bottom=t
                for(y in t until b) {
                    val xs=(l until r).filter {x ->val v=pixels[y*w+x];val red=(v shr 16)and 255;val green=(v shr 8)and 255;val blue=v and 255
                        minOf(red,green,blue)<215 || maxOf(red,green,blue)-minOf(red,green,blue)>18}
                    if(xs.size<maxOf(3,((r-l)*.01f).toInt()))continue
                    top=min(top,y);bottom=max(bottom,y+1);left=min(left,xs.first());right=max(right,xs.last()+1)
                }
                if(top<b && bottom-top>h*.08f)inkEnvelopes[c]=union(c,Panel(max(0f,left.toFloat()/w-.012f),max(p.top,top.toFloat()/h-.012f),min(1f,right.toFloat()/w+.012f),min(p.bottom,bottom.toFloat()/h+.012f)))
            }
        }
        // Artwork touching the top and left edges can be a full-bleed scene.
        // Thin strokes at the far edge must not be lost by downscaled gutter trimming.
        for(p in base.map(::core).filter {it.top<.015f && it.left<.03f && it.width>.65f && !framed(it)}) {
            var right=(p.right*w).toInt()
            val t=(p.top*h).toInt().coerceIn(0,h-1);val b=(p.bottom*h).toInt().coerceIn(t+1,h)
            for(y in t until b)for(x in right until w) {
                val v=pixels[y*w+x];val red=(v shr 16)and 255;val green=(v shr 8)and 255;val blue=v and 255
                if(minOf(red,green,blue)<215 || maxOf(red,green,blue)-minOf(red,green,blue)>18)right=max(right,x+1)
            }
            if(right>p.right*w)inkEnvelopes[p]=union(inkEnvelopes[p] ?: p,Panel(p.left,p.top,min(1f,right.toFloat()/w+.008f),p.bottom))
        }
        }
        trace?.invoke("global rectangular=${rectangular.isNotEmpty()}",base.toList())
        // Freeze physical inserts before any speech expands the reading envelopes.
        val outlined=OrthogonalNotches.attach(base.toList(),pixels,w,h,rawSpeech)
        val notched=InsetPanels.recover(outlined,pixels,w,h,predictions.filter {it.text && it.confidence>.70f}.map {it.bounds})
        val confirmedInserts=notched.map(::core).filter {q ->outlined.none {core(it)==q}}
        base.clear();base.addAll(notched)
        trace?.invoke("physical inserts",base.toList())
        // Text is assigned using the original panel and the matching neural panel, before expanding.
        stop()
        val cores=base.map(::core)
        val result=base.mapIndexed {i,p ->union(if(p.focusExclusions.isEmpty() && p.focusIncludes.isEmpty())cores[i] else p,inkEnvelopes[cores[i]] ?: cores[i]).copy(readingOrderBounds=cores[i],focusExclusions=p.focusExclusions,focusOutline=p.focusOutline,focusIncludes=p.focusIncludes)}.toMutableList()
        // Preserve a confidently associated panel envelope, including a caption across its upper edge.
        for(i in cores.indices) {
            val p=cores[i]
            val d=proposals.filter {it.confidence>.70f && overlap(p,it.bounds)/min(area(p),area(it.bounds))>.90f &&
                max(area(p),area(it.bounds))/min(area(p),area(it.bounds))<2.3f &&
                abs(p.bottom-it.bounds.bottom)<.015f && abs(p.left-it.bounds.left)<.015f &&
                cores.indices.none {j ->j!=i && overlap(cores[j],it.bounds)/area(cores[j])>.50f}}
                .maxByOrNull {it.confidence}
            if(d!=null && d.bounds.top<p.top && p.top-d.bounds.top<.04f)result[i]=union(result[i],d.bounds).copy(readingOrderBounds=p)
        }
        val white=BooleanArray(w*h) {i ->
            val c=pixels[i];minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>202
        }
        val seen=BooleanArray(w*h);val queue=IntArray(w*h)
        val balloons=mutableListOf<Panel>()
        for(seed in white.indices)if(white[seed] && !seen[seed]) {
            var head=0;var tail=1;queue[0]=seed;seen[seed]=true
            var l=w;var t=h;var r=0;var b=0
            while(head<tail) {
                if(head and 4095==0)stop()
                val id=queue[head++];val x=id%w;val y=id/w
                l=min(l,x);r=max(r,x+1);t=min(t,y);b=max(b,y+1)
                fun push(k:Int) {if(white[k] && !seen[k]) {seen[k]=true;queue[tail++]=k}}
                if(x>0)push(id-1);if(x<w-1)push(id+1);if(y>0)push(id-w);if(y<h-1)push(id+w)
            }
            val body=Panel(l.toFloat()/w,t.toFloat()/h,r.toFloat()/w,b.toFloat()/h)
            if(r-l>w*.025 && b-t>h*.01 && (r-l)*(b-t)<w*h*.10 &&
                (tail>(r-l)*(b-t)*.35 || SpeechTail.thinLobe(body,pixels,w,h)))balloons.add(body)
        }
        // Outside an established frame, a weak text proposal (e.g. an isolated footer)
        // is not enough evidence to extend a speech balloon into the page margin.
        val texts=predictions.filter {d ->d.text && (d.confidence>.70f ||
            (d.confidence>.45f && cores.count {c ->overlap(c,d.bounds)/area(d.bounds)>.20f}>=2 && cores.sumOf {c ->overlap(c,d.bounds).toDouble()}>area(d.bounds)*.80f) ||
            cores.any {c ->overlap(c,d.bounds)/area(d.bounds)>.55f} ||
            balloons.any {c ->overlap(c,d.bounds)/area(d.bounds)>.60f})}.map {it.bounds}.toMutableList()
        val typedOwners=mutableMapOf<Panel,Int>()
        val typedBalloons=balloons.flatMap {body ->
            val parts=SpeechTypography.split(body,texts,cores,pixels,w,h)
            if(parts==null)listOf(body) else {
                texts.removeAll {overlap(body,it)/area(it)>.60f}
                parts.forEach {texts.add(it.text);typedOwners[it.body]=it.owner}
                parts.map {it.body}
            }
        }
        // A white margin can connect several separately outlined balloons.
        // Do not assign the resulting page-paper component as one speech body.
        val separateBalloons=typedBalloons.flatMap {b ->
            val words=texts.filter {overlap(b,it)/area(it)>.60f}
            val independent=words.filter {t ->words.none {q ->q!==t && area(q)>area(t) && overlap(q,t)/area(t)>.80f}}
            if(independent.size>=3 && independent.sumOf {area(it).toDouble()}<area(b)*.60f &&
                independent.maxOf {it.right}-independent.minOf {it.left}>.35f &&
                cores.count {c ->independent.any {overlap(c,it)/area(it)>.30f}}>=1)
                independent.map {t ->t.copy(left=max(b.left,t.left-.025f),right=min(b.right,t.right+.025f),
                    top=max(b.top,t.top-.015f),bottom=min(b.bottom,t.bottom+.025f))}
            else listOf(b)
        }
        val speech=separateBalloons.filter {b ->texts.any {overlap(b,it)/area(it)>.60f}}+
            texts.filter {t ->separateBalloons.none {overlap(it,t)/area(t)>.60f}}
        val jointPairs=mutableSetOf<Pair<Int,Int>>()
        val sharedBalloons=mutableMapOf<Int,MutableList<Panel>>()
        for(originalBalloon in speech) {
            val text=texts.filter {overlap(originalBalloon,it)/area(it)>.60f}.maxByOrNull(::area) ?: originalBalloon
            val captionOwner=SpeechOwnership.captionOwner(text,originalBalloon,cores,pixels,w,h,originalBalloon in texts)
            val longTail=if(captionOwner!=null || originalBalloon in typedOwners)null else
                if(originalBalloon in texts)SpeechOwnership.longSideTail(originalBalloon,cores,pixels,w,h)
                else SpeechOwnership.verticalContext(text,originalBalloon,cores,pixels,w,h) ?: SpeechOwnership.longLowerTail(originalBalloon,cores,pixels,w,h) ?: SpeechOwnership.adjacentShaftContext(text,originalBalloon,cores,pixels,w,h)
            val insetOwners=cores.indices.filter {i ->val c=cores[i]
                overlap(c,originalBalloon)/area(originalBalloon)>.10f && overlap(c,text)/area(text)>.10f && (text.top+text.bottom)*.5f in c.top..c.bottom &&
                    base.any {scene ->scene.focusExclusions.any {hole ->overlap(hole,c)/max(area(hole),area(c))>.90f}}}
            // A balloon beside an established inset border belongs to that inset
            // when its only other support is the surrounding scene.
            val insetOwner=insetOwners.singleOrNull()?.takeIf {i ->cores.indices.none {j ->j!=i &&
                overlap(cores[j],text)/area(text)>.15f && base[j].focusExclusions.none {hole ->overlap(hole,cores[i])/max(area(hole),area(cores[i]))>.90f}}}
            val directedOwner=typedOwners[originalBalloon] ?: captionOwner ?: longTail?.owner ?: (if(originalBalloon in texts)null else SpeechOwnership.tailOwner(text,originalBalloon,cores,pixels,w,h)) ?: insetOwner
            val uncertainPair=if(directedOwner!=null)null else SpeechOwnership.uncertainPair(text,originalBalloon,cores,originalBalloon in texts)
            uncertainPair?.let {jointPairs.add(it)}
            val ambiguity=if(directedOwner!=null)null else SpeechOwnership.adjacent(text,originalBalloon,cores,pixels,w,h,originalBalloon in texts)
            val balloon=longTail?.body ?: ambiguity?.body ?: originalBalloon
            if(captionOwner==null && directedOwner!=null)SpeechOwnership.crossingPair(originalBalloon,directedOwner,cores)?.let {jointPairs.add(it)}
            val neuralOwner=proposals.filter {
                val p=it.bounds;val share=overlap(p,text)/area(text)
                share>.55f || (share>.25f && text.top<p.bottom && text.bottom>p.bottom &&
                    (text.left+text.right)/2 in p.left..p.right)
            }.minByOrNull {area(it.bounds)}?.bounds
            val lowerTail=balloon.bottom-text.bottom>max(.008f,(text.top-balloon.top)*1.5f)
            val owner=directedOwner ?: ambiguity?.owner ?: cores.indices.minByOrNull {i ->
                val c=cores[i];val covered=overlap(c,text)/area(text)
                val association=if(neuralOwner!=null)overlap(c,neuralOwner)/max(area(c),area(neuralOwner)) else 0f
                val dx=maxOf(c.left-text.right,text.left-c.right,0f)
                val dy=maxOf(c.top-text.bottom,text.top-c.bottom,0f)
                val tailSupport=if(lowerTail && ((balloon.left+balloon.right)/2 in (c.left-.025f)..(c.right+.025f) && balloon.bottom-.002f in c.top..c.bottom)) .20f else 0f
                dx*dx+dy*dy-covered*.03f-association*.12f-tailSupport
            } ?: continue
            val open=originalBalloon in texts
            val rectangularCaption= !open && listOf(.08f to .08f,.92f to .08f,.08f to .92f,.92f to .92f).all {(dx,dy)->
                val x=((balloon.left+balloon.width*dx)*w).toInt().coerceIn(0,w-1)
                val y=((balloon.top+balloon.height*dy)*h).toInt().coerceIn(0,h-1)
                white[y*w+x]
            }
            val completed=if(open)balloon else SpeechEnvelope.complete(balloon,text,pixels,w,h)
            val readable=if(open || rectangularCaption || ambiguity!=null || directedOwner==null && uncertainPair!=null)completed else SpeechEnvelope.readingMargin(completed,text,owner,cores,w,h)
            val paddedEnvelope=if(open)balloon.copy(left=max(0f,balloon.left-.012f),top=max(0f,balloon.top-.012f),right=min(1f,balloon.right+.020f),bottom=min(1f,balloon.bottom+.012f)) else
                // Flood filling finds the white interior; include its black outline
                // and the short tips lost to antialiasing in a downscaled image.
                readable.copy(left=max(0f,readable.left-.008f),top=max(0f,readable.top-.006f),right=min(1f,readable.right+.008f),bottom=min(1f,readable.bottom+if(rectangularCaption).012f else .006f))
            val ownerCore=cores[owner]
            val closedInset=ownerCore in confirmedInserts && !open &&
                overlap(ownerCore,balloon)/area(balloon)>.995f
            // Breathing room inside a closed insert must not borrow its background.
            // A body or tail actually crossing the frame still keeps its full envelope.
            val envelope=if(closedInset)paddedEnvelope.copy(left=max(ownerCore.left,paddedEnvelope.left),top=max(ownerCore.top,paddedEnvelope.top),right=min(ownerCore.right,paddedEnvelope.right),bottom=min(ownerCore.bottom,paddedEnvelope.bottom)) else paddedEnvelope
            val previousEnvelope=result[owner]
            val expanded=union(previousEnvelope,envelope)
            val crossesNeighbour=cores.indices.any {j ->j!=owner && overlap(cores[j],envelope)>0f &&
                base[j].focusExclusions.none {hole ->overlap(hole,cores[owner])/area(cores[owner])>.80f}}
            val extensionHoles=if(longTail==null && (directedOwner==null || !crossesNeighbour))emptyList() else buildList {
                val core=if(longTail!=null)cores[owner] else previousEnvelope
                if(expanded.top<core.top)add(Panel(expanded.left,expanded.top,expanded.right,core.top))
                if(expanded.bottom>core.bottom)add(Panel(expanded.left,core.bottom,expanded.right,expanded.bottom))
                if(expanded.left<core.left)add(Panel(expanded.left,expanded.top,core.left,expanded.bottom))
                if(expanded.right>core.right)add(Panel(core.right,expanded.top,expanded.right,expanded.bottom))
            }
            result[owner]=expanded.copy(readingOrderBounds=cores[owner],focusExclusions=result[owner].focusExclusions+extensionHoles,focusOutline=result[owner].focusOutline,focusIncludes=result[owner].focusIncludes+envelope)
            (if(directedOwner!=null)emptyList() else SpeechOwnership.uncertainCases(text,originalBalloon,cores,open)).forEach {i ->
                // Display metadata only: physical frames and their order remain unchanged.
                sharedBalloons.getOrPut(i) {mutableListOf()}.add(envelope)
            }
            if(ambiguity!=null || directedOwner!=null)for(j in cores.indices)if(j!=owner && overlap(cores[j],balloon)>0f)
                result[j]=result[j].copy(focusExclusions=result[j].focusExclusions+envelope)
        }
        // Give small balloons beside an outer border enough reading space even
        // when their white body joins the page paper and cannot be flood-filled.
        for(i in cores.indices) {
            val c=cores[i]
            // A closed insert has its own border inside another scene. Nearby
            // text alone does not prove a balloon crosses that border.
            val inset=c in confirmedInserts
            if(!inset && texts.any {overlap(c,it)/area(it)>.80f && c.right-it.right in -.005f.. .025f})
                result[i]=result[i].copy(right=max(result[i].right,min(1f,c.right+.02f)))
        }
        // Keep narrow lettering strokes crossing a frame. Assign by largest overlap,
        // without changing reading-order geometry.
        seen.fill(false)
        fun ink(i:Int):Boolean {
            val c=pixels[i];return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<62
        }
        for(seed in pixels.indices)if(!seen[seed] && ink(seed)) {
            var head=0;var tail=1;queue[0]=seed;seen[seed]=true
            var l=w;var t=h;var r=0;var b=0
            while(head<tail) {
                if(head and 4095==0)stop()
                val id=queue[head++];val x=id%w;val y=id/w
                l=min(l,x);r=max(r,x+1);t=min(t,y);b=max(b,y+1)
                fun push(k:Int) {if(!seen[k] && ink(k)) {seen[k]=true;queue[tail++]=k}}
                if(x>0)push(id-1);if(x<w-1)push(id+1);if(y>0)push(id-w);if(y<h-1)push(id+w)
            }
            if(tail<w*h*.0001 || r-l>w*.10 || b-t>h*.15 || tail<(r-l)*(b-t)*.20)continue
            val stroke=Panel(l.toFloat()/w,t.toFloat()/h,r.toFloat()/w,b.toFloat()/h)
            val owner=cores.indices.maxByOrNull {overlap(cores[it],stroke)/area(stroke)} ?: continue
            val covered=overlap(cores[owner],stroke)/area(stroke)
            if(covered in .30f.. .98f && stroke.top<cores[owner].top && stroke.bottom>cores[owner].top)
                result[owner]=union(result[owner],stroke.copy(bottom=min(stroke.bottom,cores[owner].bottom)))
        }
        // A figure may continue below its frame into an otherwise white page margin.
        // Grow only connected pixels outside the frame, never an isolated page number.
        for(i in cores.indices) {
            val p=cores[i]
            val l=(p.left*w).toInt().coerceIn(0,w-1);val r=(p.right*w).toInt().coerceIn(l+1,w)
            val edge=(p.bottom*h).toInt().coerceIn(1,h-1)
            if(cores.any {it!==p && it.top>=p.bottom-.01f && it.top<p.bottom+.10f && overlap(Panel(p.left,p.bottom,p.right,min(1f,p.bottom+.10f)),it)>0f})continue
            val bandEnd=min(h,edge+(h*.15).toInt())
            if(bandEnd-edge<3)continue
            val whiteBelow=(edge until min(bandEnd,edge+8)).sumOf {y ->(l until r).count {white[y*w+it]}}
            if(whiteBelow.toFloat()/((min(bandEnd,edge+8)-edge)*(r-l))<.80f)continue
            val outerLeft=max(0,l-(w*.05f).toInt());val outerRight=min(w,r+(w*.05f).toInt())
            val bandStart=max(0,edge-(h*.035f).toInt())
            val marked=BooleanArray((bandEnd-bandStart+1)*(outerRight-outerLeft))
            var head=0;var tail=0;var lowest=edge;var furthestLeft=l;var furthestRight=r
            fun push(x:Int,y:Int) {
                if(x !in outerLeft until outerRight || y !in bandStart until bandEnd || white[y*w+x])return
                val key=(y-bandStart)*(outerRight-outerLeft)+x-outerLeft
                if(!marked[key]) {marked[key]=true;queue[tail++]=y*w+x}
            }
            for(x in l until r)push(x,edge-1)
            for(y in bandStart until edge) {push(l,y);push(r-1,y)}
            while(head<tail) {
                if(head and 4095==0)stop()
                val id=queue[head++];val x=id%w;val y=id/w;lowest=max(lowest,y+1);furthestLeft=min(furthestLeft,x);furthestRight=max(furthestRight,x+1)
                push(x-1,y);push(x+1,y);push(x,y+1);push(x,y-1)
            }
            if(lowest>edge)result[i]=result[i].copy(left=min(result[i].left,furthestLeft.toFloat()/w),right=max(result[i].right,furthestRight.toFloat()/w),bottom=max(result[i].bottom,lowest.toFloat()/h))
        }
        trace?.invoke("speech envelopes",result.toList())
        val finalFrames=EdgeSpeech.expand(result.mapIndexed {i,p -> p.copy(
            left=max(0f,p.left-.005f),top=max(0f,p.top-.005f),right=min(1f,p.right+.005f),bottom=min(1f,p.bottom+.005f),
            readingOrderBounds=cores[i])},texts,balloons,pixels,w,h,confirmedInserts)
        trace?.invoke("edge speech",finalFrames)
        return SpeechOwnership.linkViews(FrameForeground.expand(ArtworkOverhangs.expand(ObliqueFrames.attach(OuterMarks.expand(finalFrames,pixels,w,h),pixels,w,h,stop,rectanglePrior),pixels,w,h),pixels,w,h),cores,jointPairs,sharedBalloons)
    }
}
