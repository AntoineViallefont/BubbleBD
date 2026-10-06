package fr.bubblebd

import kotlin.math.*

/** Deterministic local geometry. No network, stored page coordinates or manual cutting. */
object PanelGeometry {
    private data class R(val l:Int,val t:Int,val r:Int,val b:Int) {
        val w get()=r-l;val h get()=b-t;val area get()=w*h
        fun union(q:R)=R(min(l,q.l),min(t,q.t),max(r,q.r),max(b,q.b))
        fun intersection(q:R)=max(0,min(r,q.r)-max(l,q.l))*max(0,min(b,q.b)-max(t,q.t))
    }
    fun detect(pixels:IntArray,width:Int,height:Int,framesOnly:Boolean=false):List<Panel> {
        require(width>0 && height>0 && pixels.size==width*height)
        val light=BooleanArray(pixels.size);val dark=BooleanArray(pixels.size)
        for(i in pixels.indices) {
            val c=pixels[i];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255
            light[i]=minOf(r,g,b)>202 && maxOf(r,g,b)-minOf(r,g,b)<45
            dark[i]=maxOf(r,g,b)<62
        }
        var pageTop=0;var pageBottom=height
        fun blackRow(y:Int)=(0 until width).count {x ->
            val c=pixels[y*width+x];maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<18
        }>width*.98
        while(pageTop<pageBottom-1 && blackRow(pageTop))pageTop++
        while(pageBottom>pageTop+1 && blackRow(pageBottom-1))pageBottom--
        // The outer paper color selects gutter polarity. Dark artwork alone is not a gutter.
        val border=buildList {
            for(y in pageTop until pageBottom) {add(y*width);add(y*width+width-1)}
            for(x in 0 until width) {add(pageTop*width+x);add((pageBottom-1)*width+x)}
        }
        // White scanner margins can surround an otherwise black-gutter page.
        val innerTop=pageTop
        val darkTop=(innerTop until minOf(innerTop+10,pageBottom)).all {y ->
            (width/12 until width-width/12).count {x ->
                val c=pixels[y*width+x];maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<30
            }>width*.70
        }
        // A thin closed frame at the image boundary is ink, not black paper.
        val depth=minOf(4,(width-1)/2,(pageBottom-pageTop-1)/2)
        val innerBorder=buildList {
            for(y in pageTop+depth until pageBottom-depth) {add(y*width+depth);add(y*width+width-1-depth)}
            for(x in depth until width-depth) {add((pageTop+depth)*width+x);add((pageBottom-1-depth)*width+x)}
        }
        val blackPaper=(border.count {dark[it]}>border.size*.65 && innerBorder.count {dark[it]}>innerBorder.size*.65) || darkTop
        val background=if(blackPaper)dark.copyOf() else light
        val balloonMask=BooleanArray(pixels.size)
        fun trim(a:R):R {
            var l=a.l;var r=a.r;var t=a.t;var b=a.b
            fun row(y:Int)=(l until r).count {!background[y*width+it] && !(blackPaper && balloonMask[y*width+it])}>max(2.0,(r-l)*.025)
            fun col(x:Int)=(t until b).count {!background[it*width+x] && !(blackPaper && balloonMask[it*width+x])}>max(2.0,(b-t)*.025)
            while(t<b-1 && !row(t))t++
            while(b>t+1 && !row(b-1))b--
            while(l<r-1 && !col(l))l++
            while(r>l+1 && !col(r-1))r--
            return R(l,t,r,b)
        }
        // Closed bright components are balloon/caption candidates. Text holes remain inside bounds.
        val seen=BooleanArray(pixels.size);val queue=IntArray(pixels.size);val balloons=mutableListOf<R>()
        for(seed in pixels.indices) if(light[seed] && !seen[seed]) {
            var head=0;var tail=1;queue[0]=seed;seen[seed]=true
            var l=width;var t=height;var r=0;var b=0
            while(head<tail) {
                val n=queue[head++];val x=n%width;val y=n/width
                l=min(l,x);r=max(r,x+1);t=min(t,y);b=max(b,y+1)
                fun push(j:Int) {if(!seen[j] && light[j]) {seen[j]=true;queue[tail++]=j}}
                if(x>0)push(n-1);if(x+1<width)push(n+1);if(y>0)push(n-width);if(y+1<height)push(n+width)
            }
            val box=R(l,t,r,b)
            if(blackPaper && (l==0 || r==width || t==pageTop || b==pageBottom))
                for(k in 0 until tail)background[queue[k]]=true
            if(l>0 && t>0 && r<width && b<height && box.w>width*.035 && box.h>height*.015 &&
                box.area<width*height*.065 && tail>box.area*.40)balloons.add(box)
        }
        if(blackPaper)for(box in balloons)for(y in box.t until box.b)for(x in box.l until box.r)balloonMask[y*width+x]=true
        if(!framesOnly) {
            val grid=GridPanels.detect(pixels,width,height)
            if(grid.isNotEmpty())return grid
            val single=SingleScene.detect(pixels,width,height)
            if(single.isNotEmpty())return single
            val captions=CaptionRows.detect(pixels,width,height)
            if(captions.isNotEmpty())return captions
        }
        val content=trim(R(0,pageTop,width,pageBottom));val regions=mutableListOf<R>()
        fun divide(input:R,depth:Int) {
            val a=trim(input)
            if(depth>=10 || a.w<width*.10 || a.h<height*.05) {regions.add(a);return}
            var best=-1f;var axisBest=0;var cutA=0;var cutB=0
            for(axis in 0..1) {
                val from=if(axis==0)a.t else a.l;val to=if(axis==0)a.b else a.r
                val cf=if(axis==0)a.l else a.t;val ct=if(axis==0)a.r else a.b
                val margin=max(10,((to-from)*.12).toInt());var start=-1
                for(v in from+margin until to-margin) {
                    val actualDark=(cf until ct).count {u ->dark[if(axis==0)v*width+u else u*width+v]}.toFloat()/(ct-cf)
                    val blank=(cf until ct).count {u ->
                        val i=if(axis==0)v*width+u else u*width+v
                        background[i] || (blackPaper && balloonMask[i])
                    }.toFloat()/(ct-cf)>=(if(blackPaper).94f else .84f) && (!blackPaper || axis==0 || actualDark>.45f)
                    if(blank && start<0)start=v
                    if(start>=0 && (!blank || v==to-margin-1)) {
                        val end=if(blank)v+1 else v
                        val score=(end-start).toFloat()/(to-from)+(if(axis==0) {if(blackPaper)1f else .002f} else 0f)
                        if(end-start>=(if(blackPaper && axis==1)maxOf(3,(width*.009).toInt()) else 2) && score>best) {best=score;axisBest=axis;cutA=start;cutB=end}
                        start=-1
                    }
                }
            }
            if(best<0)regions.add(a)
            else if(axisBest==0) {divide(R(a.l,a.t,a.r,cutA),depth+1);divide(R(a.l,cutB,a.r,a.b),depth+1)}
            else {divide(R(a.l,a.t,cutA,a.b),depth+1);divide(R(cutB,a.t,a.r,a.b),depth+1)}
        }
        divide(content,0)
        val useful=regions.filter {it.w>width*.10 && it.h>height*.065}.toMutableList()
        // Four-edge candidates find inset frames even when their border is neither pure black nor white.
        val luminance=IntArray(pixels.size) {i ->
            val c=pixels[i];(((c shr 16)and 255)*3+((c shr 8)and 255)*6+(c and 255))/10
        }
        // Repeated frame checks reuse the same exact contrast evidence.
        val verticalEdges=BooleanArray(pixels.size)
        val horizontalEdges=BooleanArray(pixels.size)
        for(y in 0 until height)for(x in 0 until width) {
            val i=y*width+x
            if(x in 2 until width-2)verticalEdges[i]=abs(luminance[i-2]-luminance[i+2])>15
            if(y in 2 until height-2)horizontalEdges[i]=abs(luminance[i-width*2]-luminance[i+width*2])>10
        }
        fun edge(x:Int,y:Int,vertical:Boolean)=if(vertical)verticalEdges[y*width+x] else horizontalEdges[y*width+x]
        fun divider(x:Int,y:Int):Boolean {
            val c=pixels[y*width+x];val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255
            return light[y*width+x] || (maxOf(r,g,b)<150 && maxOf(r,g,b)-minOf(r,g,b)<60)
        }
        fun frames():List<R> {
            data class H(val y:Int,val l:Int,val r:Int)
            val rawLines=mutableListOf<H>()
            for(y in content.t until content.b) {
                var start=-1;var last=-1
                for(x in content.l until content.r) {
                    if(edge(x,y,false)) {if(start<0)start=x;last=x}
                    if(start>=0 && (x-last>5 || x==content.r-1)) {
                        if(last-start>width*.15)rawLines.add(H(y,start,last+1))
                        start=-1
                    }
                }
            }
            // One antialiased border produces several adjacent observations of the same edge.
            // Keep its two extremes instead of comparing every duplicate against every other edge.
            val lineGroups=mutableListOf<MutableList<H>>()
            for(line in rawLines) {
                val group=lineGroups.lastOrNull {g ->val previous=g.last();line.y-previous.y<=4 && abs(line.l-previous.l)<=2 && abs(line.r-previous.r)<=2}
                if(group==null)lineGroups.add(mutableListOf(line)) else group.add(line)
            }
            val lines=lineGroups.flatMap {g ->if(g.size==1)listOf(g.first()) else listOf(g.first(),g.last())}.sortedWith(compareBy<H>{it.y}.thenBy {it.l})
            // Bound work for heavily textured scans.
            if(lines.size>1600)return emptyList()
            fun uniform(line:Int,from:Int,to:Int,vertical:Boolean):Float {
                var best=0f
                val bins=IntArray(17)
                for(d in -3..3) {
                    if(line+d !in 0 until (if(vertical)width else height))continue
                    bins.fill(0)
                    for(v in from until to)bins[luminance[if(vertical)v*width+line+d else (line+d)*width+v]/16]++
                    best=maxOf(best,(0..15).maxOf {bins[it]+bins[it+1]}.toFloat()/(to-from))
                    if(best==1f)return best
                }
                return best
            }
            val balloonSupport=BooleanArray(pixels.size)
            for(balloon in balloons)for(y in max(0,balloon.t-4)..min(height-1,balloon.b+4))for(x in balloon.l until balloon.r)balloonSupport[y*width+x]=true
            val found=mutableListOf<Pair<R,Float>>()
            for(i in lines.indices)for(j in i+1 until lines.size) {
                val a=lines[i];val b=lines[j]
                if(b.y-a.y<height*.10 || abs(a.l-b.l)>width*.025 || abs(a.r-b.r)>width*.025)continue
                val l=(a.l+b.l)/2;val r=(a.r+b.r)/2
                val box=R(l,a.y,r,b.y)
                if(box.area>width*height*.60 || balloons.any {it.intersection(box)>box.area*.65})continue
                fun join(x:Int)=(a.y..b.y).count {y ->(-4..4).any {d->x+d in 0 until width && edge(x+d,y,true)}}.toFloat()/(b.y-a.y+1)
                val left=join(l);val right=join(r)
                if(minOf(left,right)>.80f && maxOf(left,right)>.95f) {
                    val u=listOf(uniform(l,a.y,b.y,true),uniform(r,a.y,b.y,true),uniform(a.y,l,r,false),uniform(b.y,l,r,false))
                    fun whiteSide(x:Int)=x>content.l+width*.015 && x<content.r-width*.015 && (-6..6).any {d ->
                        x+d in 0 until width && (a.y until b.y).count {y->divider(x+d,y)}>box.h*.90 &&
                        (a.y until b.y).count {y->light[y*width+x+d]}>box.h*.45
                    }
                    val paperBelow=(1..6).any {d ->b.y+d<height && (l until r).count {x->light[(b.y+d)*width+x]}>box.w*.85}
                    if(u.min()>.65f || (paperBelow && (whiteSide(l) || whiteSide(r))))
                        found.add(box to u.average().toFloat())
                }
            }
            // Complement horizontal-edge pairs with vertical pairs: captions often interrupt
            // the top/bottom edge of a frame while its two sides remain continuous.
            data class V(val x:Int,val t:Int,val b:Int)
            val rawVerticals=mutableListOf<V>()
            for(x in content.l until content.r) {
                var start=-1;var last=-1
                for(y in content.t until content.b) {
                    if(edge(x,y,true)) {if(start<0)start=y;last=y}
                    if(start>=0 && (y-last>5 || y==content.b-1)) {
                        if(last-start>height*.10)rawVerticals.add(V(x,start,last+1))
                        start=-1
                    }
                }
            }
            val verticalGroups=mutableListOf<MutableList<V>>()
            for(line in rawVerticals) {
                val group=verticalGroups.lastOrNull {g ->val previous=g.last();line.x-previous.x<=4 && abs(line.t-previous.t)<=2 && abs(line.b-previous.b)<=2}
                if(group==null)verticalGroups.add(mutableListOf(line)) else group.add(line)
            }
            val verticals=verticalGroups.flatMap {g ->if(g.size==1)listOf(g.first()) else listOf(g.first(),g.last())}.sortedWith(compareBy<V>{it.x}.thenBy {it.t})
            if(verticals.size<=1600)for(i in verticals.indices)for(j in i+1 until verticals.size) {
                val a=verticals[i];val b=verticals[j]
                if(b.x-a.x<width*.15 || abs(a.t-b.t)>height*.025 || abs(a.b-b.b)>height*.025)continue
                val t=(a.t+b.t)/2;val bottom=(a.b+b.b)/2
                val box=R(a.x,t,b.x,bottom)
                if(box.area>width*height*.60 || balloons.any {it.intersection(box)>box.area*.65})continue
                if(minOf(uniform(a.x,t,bottom,true),uniform(b.x,t,bottom,true))<.80f)continue
                fun hSupport(y:Int):Float {
                    if(y !in 0 until height)return 0f
                    return (a.x until b.x).count {x ->
                        balloonSupport[y*width+x] ||
                        (-3..3).any {d ->y+d in 0 until height && edge(x,y+d,false)}
                    }.toFloat()/box.w
                }
                val top=hSupport(t);val bot=hSupport(bottom)
                if(minOf(top,bot)>.78f && maxOf(top,bot)>.94f)found.add(box to .85f)
            }
            // Adjacent borderless insets can share a white gutter and the same top/bottom edges.
            val neighbors=mutableListOf<R>()
            for((box,_) in found.toList()) {
                val gutter=(box.l-7..box.l+2).filter {it in 0 until width}.maxByOrNull {x ->(box.t until box.b).count {y->divider(x,y)}} ?: continue
                if((box.t until box.b).count {y->divider(gutter,y)}<box.h*.90 || (box.t until box.b).count {y->light[y*width+gutter]}<box.h*.45)continue
                val right=gutter-1
                fun hSupport(y:Int,l:Int,r:Int)=(l until r).count {x ->(-2..2).any {d->y+d in 0 until height && edge(x,y+d,false)}}.toFloat()/(r-l)
                var best=2.80f;var neighbor:R?=null
                for(l in maxOf(content.l,(right-width*.75).toInt()) until right-(width*.15).toInt()) {
                    val vertical=(box.t until box.b).count {y ->(-2..2).any {d->l+d in 0 until width && edge(l+d,y,true)}}.toFloat()/box.h
                    val top=hSupport(box.t,l,right);val bottom=hSupport(box.b,l,right)
                    val score=vertical+top+bottom
                    if(vertical>.85f && top>.80f && bottom>.90f && score>best) {best=score;neighbor=R(l,box.t,right,box.b)}
                }
                if(neighbor!=null)neighbors.add(neighbor)
            }
            found.addAll(neighbors.map {it to .8f})
            return found.sortedByDescending {it.first.area}.map {it.first}.fold(mutableListOf()) {out,box ->
                if(out.none {it.intersection(box).toFloat()/min(it.area,box.area)>.88f})out.add(box)
                out
            }
        }
        if(framesOnly)return frames().map {Panel(it.l.toFloat()/width,it.t.toFloat()/height,it.r.toFloat()/width,it.b.toFloat()/height)}
        // An inset strip may share its lower edge with a full-bleed scene. Require
        // a near-continuous straight contrast line aligned with the established row above.
        if(blackPaper) {
            for(a in useful.toList()) {
                if(a.w<width*.80 || a.h<height*.40)continue
                val above=useful.filter {it.b<=a.t && a.t-it.b<height*.04}
                if(above.isEmpty())continue
                val l=above.minOf {it.l};val r=above.maxOf {it.r}
                val yRange=(a.t+(height*.08).toInt() until minOf(a.b,a.t+(height*.35).toInt()))
                val seam=yRange.maxByOrNull {y ->(l until r).count {x->edge(x,y,false)}} ?: continue
                if((l until r).count {x->edge(x,seam,false)}<(r-l)*.88)continue
                fun side(x:Int)=(a.t until seam).count {y->(-3..3).any {d->x+d in 0 until width && edge(x+d,y,true)}}.toFloat()/(seam-a.t)
                if(minOf(side(l),side(r))<.80f)continue
                useful.remove(a);useful.add(R(l,a.t,r,seam));useful.add(R(a.l,seam,a.r,a.b))
            }
        }
        // Several white gutters descending from the upper margin to the same junction
        // identify a row of insets, even when balloons interrupt their horizontal edges.
        fun topInsets():List<R> {
            if(blackPaper)return emptyList()
            val paperTop=(pageTop until minOf(pageBottom,pageTop+(height*.03).toInt())).firstOrNull {y ->
                (0 until width).count {light[y*width+it]}>width*.85
            } ?: return emptyList()
            val ends=IntArray(width) {x ->
                var y=paperTop
                while(y<pageBottom && light[y*width+x])y++
                y
            }
            val columns=(content.l until content.r).filter {ends[it]-pageTop in (height*.07).toInt()..(height*.30).toInt()}
            val groups=mutableListOf<MutableList<Int>>()
            for(x in columns) {
                if(groups.lastOrNull()?.last()==x-1)groups.last().add(x) else groups.add(mutableListOf(x))
            }
            val gutters=groups.filter {it.size>=3 && it.size<width*.04}.map {g->Triple(g.first(),g.last()+1,g.map {ends[it]}.sorted()[g.size/2])}
            if(gutters.size<2)return emptyList()
            val aligned=gutters.maxByOrNull {g->gutters.count {abs(it.third-g.third)<height*.015}} ?: return emptyList()
            val row=gutters.filter {abs(it.third-aligned.third)<height*.015}.sortedBy {it.first}
            if(row.size<2 || row.zipWithNext().any {it.second.first-it.first.second<width*.10})return emptyList()
            val junction=row.map {it.third}.sorted()[row.size/2]
            var bottom=junction
            val top=(paperTop until bottom).firstOrNull {y ->
                (content.l until content.r).count {x ->!light[y*width+x] && !dark[y*width+x]}>width*.35
            } ?: return emptyList()
            if(bottom-top<height*.035)return emptyList()
            val occupied=(content.l until content.r).filter {x ->
                (top until minOf(top+8,bottom)).count {y->!light[y*width+x] && !dark[y*width+x]}>=4
            }
            if(occupied.isEmpty())return emptyList()
            if(junction-top<height*.12) {
                val candidates=(junction+(height*.025).toInt() until minOf(pageBottom,junction+(height*.25).toInt()))
                val selected=candidates.maxByOrNull {y ->(occupied.first()..occupied.last()).count {x ->edge(x,y,false)}} ?: return emptyList()
                if((occupied.first()..occupied.last()).count {x ->edge(x,selected,false)}<(occupied.last()-occupied.first())*.65)return emptyList()
                bottom=selected
            }
            val cuts=listOf(occupied.first() to row.first().first)+row.zipWithNext().map {it.first.second to it.second.first}+listOf(row.last().second to occupied.last()+1)
            if(cuts.any {it.second-it.first<width*.10})return emptyList()
            return cuts.map {R(it.first,top,it.second,bottom)}
        }
        val insetRegions=mutableSetOf<R>()
        var insetEvidence=false
        if(useful.size<=2) {
            val topRow=topInsets()
            val candidates=frames().filter {box ->topRow.none {it.intersection(box)>box.area*.30}}+topRow
            val insets=candidates.sortedByDescending {it.area}.fold(mutableListOf<R>()) {out,box ->
                if(out.none {it.intersection(box).toFloat()/min(it.area,box.area)>.88f})out.add(box)
                out
            }
            // A lower closed frame does not invalidate the separate painted scene above.
            // Replacing two geometric scenes with one duplicate inset would lose a case.
            val duplicateOnly=useful.size==2 && insets.size==1 && useful.any {p ->
                val box=insets.single();p.intersection(box).toFloat()/(p.area+box.area-p.intersection(box))>.75f
            }
            if(insets.isNotEmpty() && !duplicateOnly) {
                insetEvidence=true;insetRegions.addAll(insets)
                useful.clear();useful.addAll(insets)
                // Keep the unframed scene as its own focus region between inset rows.
                val sorted=insets.sortedBy {it.t}
                val bottomOfTop=sorted.filter {it.t<content.t+content.h*.25}.maxOfOrNull {it.b}
                val topOfBottom=sorted.filter {it.t>content.t+content.h*.50}.minOfOrNull {it.t} ?: content.b
                if(bottomOfTop!=null && topOfBottom-bottomOfTop>content.h*.18) {
                    val scene=trim(R(content.l,bottomOfTop,content.r,topOfBottom))
                    if(scene.h>height*.065 && scene.w>width*.10)useful.add(R(content.l,bottomOfTop,content.r,topOfBottom))
                }
            }
        }
        if(useful.size==1 && regions.size<=1 && !insetEvidence)return listOf(Panel(0f,0f,1f,1f))
        // Associate each bright balloon with exactly one geometric panel, expanding its reading bounds.
        val bounds=useful.toMutableList()
        for(b in balloons) {
            val owner=useful.indices.minByOrNull {i ->
                val p=useful[i];val overlap=p.intersection(b).toFloat()/b.area
                if(overlap>.65f) -1000f+ p.area.toFloat()/(width*height)
                else {
                    val dx=maxOf(p.l-b.r,b.l-p.r,0).toFloat()/width
                    val dy=maxOf(p.t-b.b,b.t-p.b,0).toFloat()/height
                    dx*dx+dy*dy-overlap*.05f
                }
            } ?: continue
            val p=useful[owner]
            val dx=maxOf(p.l-b.r,b.l-p.r,0).toFloat()/width;val dy=maxOf(p.t-b.b,b.t-p.b,0).toFloat()/height
            if(dx*dx+dy*dy<.018f)bounds[owner]=bounds[owner].union(b)
        }
        // Lettering can cross an inset frame (onomatopoeia). Keep compact connected ink strokes whole.
        if(insetEvidence) {
            seen.fill(false)
            for(seed in pixels.indices)if(dark[seed] && !seen[seed]) {
                var head=0;var tail=1;queue[0]=seed;seen[seed]=true
                var l=width;var t=height;var r=0;var b=0
                while(head<tail) {
                    val n=queue[head++];val x=n%width;val y=n/width
                    l=min(l,x);r=max(r,x+1);t=min(t,y);b=max(b,y+1)
                    fun push(j:Int) {if(!seen[j] && dark[j]) {seen[j]=true;queue[tail++]=j}}
                    if(x>0)push(n-1);if(x+1<width)push(n+1);if(y>0)push(n-width);if(y+1<height)push(n+width)
                }
                val ink=R(l,t,r,b)
                if(tail<width*height*.0001 || tail<ink.area*.20 || ink.w>width*.20 || ink.h>height*.18)continue
                val owner=useful.indices.filter {i ->
                    if(useful[i] !in insetRegions)return@filter false
                    val overlap=useful[i].intersection(ink).toFloat()/ink.area
                    overlap in .20f.. .98f
                }.minByOrNull {useful[it].area} ?: continue
                bounds[owner]=bounds[owner].union(R(maxOf(0,ink.l-3),maxOf(0,ink.t-3),minOf(width,ink.r+3),minOf(height,ink.b+5)))
            }
        }
        return bounds.mapIndexed {index,it ->Panel(max(0f,it.l.toFloat()/width-.008f),max(0f,it.t.toFloat()/height-.008f),min(1f,it.r.toFloat()/width+.008f),min(1f,it.b.toFloat()/height+.008f), useful[index].let {g ->Panel(g.l.toFloat()/width,g.t.toFloat()/height,g.r.toFloat()/width,g.b.toFloat()/height)})}
            .ifEmpty {listOf(Panel(0f,0f,1f,1f))}
    }
}
