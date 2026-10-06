package fr.bubblebd

import kotlin.math.*

/** Fit four physical paper edges; a diagonal in the drawing is not enough. */
object ObliqueFrames {
    private data class Line(val slope:Float,val intercept:Float,val paperSupport:Int=40) {fun at(v:Float)=slope*v+intercept}
    fun attach(input:List<Panel>,pixels:IntArray,w:Int,h:Int,stop:()->Unit={},rectanglePrior:Boolean=false):List<Panel> {
        stop()
        fun core(p:Panel)=p.readingOrderBounds ?: p
        fun area(p:Panel)=p.width*p.height
        fun shared(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        fun physicalHoles(p:Panel)=p.focusExclusions.any {hole->input.any {q->q!==p &&
            shared(hole,core(q))/max(area(hole),area(core(q)))>.65f}}
        val contours=FrameContours(pixels,w,h,input.flatMap {it.focusIncludes})
        fun closedRectangle(p:Panel):Boolean {
            val c=core(p);val fitted=contours.fit(c) ?: return false
            return maxOf(abs(c.left-fitted.left),abs(c.top-fitted.top),abs(c.right-fitted.right),abs(c.bottom-fitted.bottom))<.012f
        }
        val candidates=input.filter {p->p.focusOutline.isEmpty() && !physicalHoles(p) && !p.isWholePage && !closedRectangle(p) && input.any {q->
            p!==q && !physicalHoles(q) && shared(core(p),core(q))>0f && shared(core(p),core(q))/min(area(core(p)),area(core(q)))<.25f
        }}.toSet()
        if(candidates.isEmpty())return input
        val paper=BooleanArray(pixels.size);val art=BooleanArray(pixels.size)
        for(i in pixels.indices) {val c=pixels[i];val v=minOf((c shr 16)and 255,(c shr 8)and 255,c and 255);paper[i]=v>235;art[i]=v<218}
        fun fit(horizontal:Boolean,base:Float,from:Float,to:Float,direction:Int):Line? {
            val axis=if(horizontal)h else w;val span=to-from
            if(span<(if(horizontal)w else h)*.10f)return null
            val low=max(2,(base-axis*.08f).toInt());val high=min(axis-3,(base+axis*.08f).toInt())
            var best=-1f;var result:Line?=null
            val insideDistance=max(6,(axis*.009f).toInt())
            val along=IntArray(40) {i->(from+span*(.05f+.90f*i/39)).toInt()}
            for(a in low..high step 2) {
                stop()
                for(b in low..high step 2) {
                if(abs(b-a)>axis*.16f)continue
                var bright=0;var support=0;var valid=true
                val slope=(b-a)/span
                for(u in along) {
                    val v=(a+slope*(u-from)).roundToInt();val inside=v+direction*insideDistance
                    if(v !in 0 until axis || inside !in 0 until axis) {valid=false;break}
                    val index=if(horizontal)v*w+u else u*w+v
                    val insideIndex=if(horizontal)inside*w+u else u*w+inside
                    val before=if(horizontal)index-w else index-1
                    val after=if(horizontal)index+w else index+1
                    if(paper[index] || (v>0 && paper[before]) || (v+1<axis && paper[after]))bright++
                    if(art[insideIndex])support++
                }
                if(!valid || bright<26 || support<22)continue
                val score=bright/40f+support/40f*.15f-abs((a+b)*.5f-base)/axis*.05f-
                    if(rectanglePrior && bright<33)min(.08f,abs(b-a).toFloat()/axis*.8f) else 0f
                if(score>best) {best=score;result=Line(slope,a-slope*from,bright)}
            }
            }
            return result
        }
        fun corner(horizontal:Line,vertical:Line):PanelPoint? {
            val divisor=1-horizontal.slope*vertical.slope
            if(abs(divisor)<.8f)return null
            val y=(horizontal.slope*vertical.intercept+horizontal.intercept)/divisor
            val x=vertical.at(y)
            if(x !in 0f..w.toFloat() || y !in 0f..h.toFloat())return null
            return PanelPoint(x/w,y/h)
        }
        return input.map {p->
            if(p !in candidates)return@map p
            val c=core(p);val l=c.left*w;val r=c.right*w;val t=c.top*h;val b=c.bottom*h
            val top=fit(true,t,l,r,1) ?: if(c.top<.02f)Line(0f,t) else return@map p
            val bottom=fit(true,b,l,r,-1) ?: return@map p
            val left=fit(false,l,t,b,1) ?: return@map p
            val right=fit(false,r,t,b,-1) ?: return@map p
            // One edge may be hidden by foreground, but the other three must
            // independently establish the physical paper boundary.
            if(listOf(top,bottom,left,right).count {it.paperSupport>=33}<3)return@map p
            if(max(abs(top.at(r)-top.at(l))/h,abs(bottom.at(r)-bottom.at(l))/h)<.012f && max(abs(left.at(b)-left.at(t))/w,abs(right.at(b)-right.at(t))/w)<.012f)return@map p
            val poly=listOf(corner(top,left),corner(top,right),corner(bottom,right),corner(bottom,left))
            if(poly.any {it==null})return@map p
            val points=poly.filterNotNull()
            if(points[0].x>=points[1].x || points[3].x>=points[2].x || points[0].y>=points[3].y || points[1].y>=points[2].y)return@map p
            p.copy(left=min(p.left,points.minOf {it.x}),top=min(p.top,points.minOf {it.y}),right=max(p.right,points.maxOf {it.x}),bottom=max(p.bottom,points.maxOf {it.y}),focusOutline=points)
        }
    }
}
