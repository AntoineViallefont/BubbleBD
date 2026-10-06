package fr.bubblebd

import kotlin.math.*

/** Confirm a complete orthogonal layout from independent proposals and real gutters. */
object RectangularLayout {
    fun confirm(proposals:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,texts:List<Panel> = emptyList(),geometry:List<Panel> = emptyList()):List<Panel> {
        val contours=FrameContours(pixels,w,h,texts)
        val strong=proposals.filter {it.confidence>.90f}
        if(strong.size<4)return emptyList()
        // A weaker proposal may complete the layout only with all four physical
        // borders confirmed. Scores alone never turn a missing region into a case.
        val panels=(strong.map {it.bounds}+proposals.filter {it.confidence in .45f.. .90f}.mapNotNull {d ->
            contours.fit(d.bounds)?.takeIf {f ->maxOf(abs(f.left-d.bounds.left),abs(f.top-d.bounds.top),
                abs(f.right-d.bounds.right),abs(f.bottom-d.bounds.bottom))<.006f}
        }).sortedWith(compareBy<Panel> {it.top}.thenBy {it.left})
        if(panels.size !in 4..20)return emptyList()
        // A whole-row observation must not erase a physical internal gutter.
        if(panels.any {p ->contours.split(p)?.let {parts ->parts.all {part ->geometry.any {observed ->
            val g=observed.readingOrderBounds ?: observed
            val shared=max(0f,min(g.right,part.right)-max(g.left,part.left))*max(0f,min(g.bottom,part.bottom)-max(g.top,part.top))
            shared/(g.width*g.height+part.width*part.height-shared)>.70f
        }}}==true})return emptyList()
        fun area(p:Panel)=p.width*p.height
        fun overlap(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        if(panels.indices.any {i ->(i+1 until panels.size).any {j ->overlap(panels[i],panels[j])/min(area(panels[i]),area(panels[j]))>.025f}})return emptyList()
        // A complete geometric frame outside the model's layout remains useful.
        // Global tiling may not discard it just because its model score is low.
        if(geometry.any {observed ->
            val g=observed.readingOrderBounds ?: observed
            area(g)>.01f && panels.sumOf {overlap(it,g).toDouble()}<area(g)*.15 &&
                contours.fit(g)!=null
        })return emptyList()
        // Do not silently drop a weaker, independently located case.
        if(proposals.any {d ->d.confidence>.45f && panels.none {overlap(it,d.bounds)/(area(it)+area(d.bounds)-overlap(it,d.bounds))>.85f}})return emptyList()
        val l=panels.minOf {it.left};val r=panels.maxOf {it.right};val t=panels.minOf {it.top};val b=panels.maxOf {it.bottom}
        if(r-l<.65f || b-t<.55f || panels.sumOf {area(it).toDouble()}/((r-l)*(b-t))<.90)return emptyList()
        val masked=BooleanArray(w*h)
        for(p in texts)for(y in max(0,(p.top*h).toInt()) until min(h,(p.bottom*h).toInt()+1))
            for(x in max(0,(p.left*w).toInt()) until min(w,(p.right*w).toInt()+1))masked[y*w+x]=true
        fun paperGap(horizontal:Boolean,end:Float,start:Float,from:Float,to:Float):Boolean {
            if(start-end !in .001f.. .035f)return false
            val axis=if(horizontal)h else w;val span=if(horizontal)w else h
            val low=(from*span).toInt().coerceIn(0,span-1);val high=(to*span).toInt().coerceIn(low+1,span)
            val center=((end+start)*.5f*axis).toInt()
            return (-1..1).any {delta ->
                val v=center+delta
                if(v !in 0 until axis)false else {
                    var seen=0;var white=0
                    for(u in low until high) {
                        val i=if(horizontal)v*w+u else u*w+v
                        if(masked[i])continue
                        seen++;val c=pixels[i]
                        if(minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>225)white++
                    }
                    seen>(high-low)*.50f && white>seen*.94f
                }
            }
        }
        // A tall frame can neighbour two shorter ones. Validate each shared
        // boundary instead of requiring every frame to fit a uniform row.
        val neighbours=List(panels.size) {mutableSetOf<Int>()}
        for(i in panels.indices)for(j in i+1 until panels.size) {
            val a=panels[i];val z=panels[j]
            val overlapX=min(a.right,z.right)-max(a.left,z.left)
            val overlapY=min(a.bottom,z.bottom)-max(a.top,z.top)
            var connected=false
            if(overlapX>.035f) {
                val upper=if(a.top<z.top)a else z;val lower=if(a.top<z.top)z else a
                if(lower.top-upper.bottom in .001f.. .035f) {
                    if(!paperGap(true,upper.bottom,lower.top,max(a.left,z.left),min(a.right,z.right)))return emptyList()
                    connected=true
                }
            }
            if(overlapY>.035f) {
                val left=if(a.left<z.left)a else z;val right=if(a.left<z.left)z else a
                if(right.left-left.right in .001f.. .035f) {
                    if(!paperGap(false,left.right,right.left,max(a.top,z.top),min(a.bottom,z.bottom)))return emptyList()
                    connected=true
                }
            }
            if(connected) {neighbours[i].add(j);neighbours[j].add(i)}
        }
        val visited=mutableSetOf<Int>();val pending=java.util.ArrayDeque<Int>();pending.add(0)
        while(pending.isNotEmpty()) {val i=pending.removeFirst();if(visited.add(i))neighbours[i].forEach {pending.add(it)}}
        if(visited.size!=panels.size)return emptyList()
        for(p in panels) {
            val l0=(p.left*w).toInt();val r0=(p.right*w).toInt();val t0=(p.top*h).toInt();val b0=(p.bottom*h).toInt()
            fun edge(v:Int,a:Int,z:Int,vertical:Boolean)=(-3..3).maxOf {contours.support(v+it,a,z,vertical)}
            val sides=listOf(edge(l0,t0,b0,true),edge(r0,t0,b0,true),edge(t0,l0,r0,false),edge(b0,l0,r0,false))
            if(sides.count {it>.65f}<2 || max(sides[0],sides[1])<.65f || max(sides[2],sides[3])<.65f)return emptyList()
        }
        // Snap to a supported frame when possible; open balloons can interrupt it.
        return panels.map {p ->contours.fit(p)?.takeIf {f ->
            maxOf(abs(f.left-p.left),abs(f.top-p.top),abs(f.right-p.right),abs(f.bottom-p.bottom))<.006f
        } ?: p}
    }
}
