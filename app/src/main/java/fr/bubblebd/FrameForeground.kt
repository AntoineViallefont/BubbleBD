package fr.bubblebd

import kotlin.math.*

/** Colour-continuous foreground can occlude a previous row's physical frame. */
internal object FrameForeground {
    private fun inside(x:Float,y:Float,p:Panel):Boolean {
        if(p.focusOutline.isEmpty())return p.contains(x,y)
        var hit=false;var j=p.focusOutline.lastIndex
        for(i in p.focusOutline.indices) {val a=p.focusOutline[i];val b=p.focusOutline[j]
            if((a.y>y)!=(b.y>y) && x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)hit=!hit
            j=i
        }
        return hit
    }
    fun expand(input:List<Panel>,pixels:IntArray,w:Int,h:Int):List<Panel> {
        if(input.size !in 2..30 || w<80 || h<100)return input
        val result=input.toMutableList()
        val cores=input.map {p->(p.readingOrderBounds ?: p).copy(focusOutline=p.focusOutline)}
        val order=BookRules.orderPanels(input,false).map {input.indexOf(it)}
        for(i in order) {
            val owner=input[i];val c=cores[i]
            if(c.focusOutline.size!=4 || c.width*c.height>.30f)continue
            val previous=cores.indices.filter {j->j!=i && cores[j].top<c.top-.06f && cores[j].bottom>c.top-.08f}
            if(previous.isEmpty())continue
            val l=max(1,((c.left-.03f)*w).toInt());val r=min(w-1,((c.right+.03f)*w).toInt())
            val t=max(1,((c.top-.08f)*h).toInt());val b=min(h-1,((c.top+.04f)*h).toInt())
            if(r<=l || b<=t)continue
            fun belongs(x:Int,y:Int)=inside((x+.5f)/w,(y+.5f)/h,c)
            fun prior(x:Int,y:Int)=previous.any {inside((x+.5f)/w,(y+.5f)/h,cores[it])}
            fun protected(x:Int,y:Int)=previous.any {j->input[j].focusIncludes.any {inside((x+.5f)/w,(y+.5f)/h,it)}}
            fun same(a:Int,b:Int):Boolean {
                val ar=(a shr 16)and 255;val ag=(a shr 8)and 255;val ab=a and 255
                val br=(b shr 16)and 255;val bg=(b shr 8)and 255;val bb=b and 255
                val ac=if(ar>=ag && ar>=ab)0 else if(ag>=ab)1 else 2
                val bc=if(br>=bg && br>=bb)0 else if(bg>=bb)1 else 2
                if(ac!=bc || maxOf(br,bg,bb)-minOf(br,bg,bb)<10)return false
                if(maxOf(abs(ar-br),abs(ag-bg),abs(ab-bb))<=45)return true
                val ah=maxOf(ar,ag,ab).toFloat();val bh=maxOf(br,bg,bb).toFloat()
                if(min(ah,bh)<60 || ah-minOf(ar,ag,ab)<ah*.15f || bh-minOf(br,bg,bb)<bh*.15f)return false
                // Shading changes brightness, but should retain the foreground's colour.
                return maxOf(abs(ar/ah-br/bh),abs(ag/ah-bg/bh),abs(ab/ah-bb/bh))<=.25f
            }
            val seen=BooleanArray((r-l)*(b-t));val queue=IntArray(seen.size)
            for(y in t until b)for(x in l until r) {
                if(belongs(x,y) || !prior(x,y) || protected(x,y) || seen[(y-t)*(r-l)+x-l])continue
                val colour=pixels[y*w+x];val hi=maxOf((colour shr 16)and 255,(colour shr 8)and 255,colour and 255)
                val lo=minOf((colour shr 16)and 255,(colour shr 8)and 255,colour and 255)
                if(hi<130 || hi-lo<20)continue
                // A narrow paper gutter can lie between the tip and its frame.
                val crossing=(1..max(4,(h*.015f).toInt())).firstOrNull {step->y+step+2<h && belongs(x,y+step) && belongs(x,y+step+2)} ?: continue
                if(!(1..crossing+2).all {step->same(colour,pixels[(y+step)*w+x])})continue
                val closed=(-3..3).any {dy->(-3..3).count {dx->
                    val v=pixels[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)]
                    val high=maxOf((v shr 16)and 255,(v shr 8)and 255,v and 255)
                    val low=minOf((v shr 16)and 255,(v shr 8)and 255,v and 255)
                    high<60 || (high<170 && high-low<12)
                }>=6}
                if(closed)continue
                var head=0;var tail=0
                fun push(xx:Int,yy:Int) {
                    if(xx !in l until r || yy !in t until b)return
                    val id=(yy-t)*(r-l)+xx-l
                    if(seen[id] || belongs(xx,yy) || !prior(xx,yy) || protected(xx,yy) || !same(colour,pixels[yy*w+xx]))return
                    seen[id]=true;queue[tail++]=yy*w+xx
                }
                push(x,y)
                while(head<tail) {val k=queue[head++];val xx=k%w;val yy=k/w;push(xx-1,yy);push(xx+1,yy);push(xx,yy-1);push(xx,yy+1)}
                if(tail<max(12,(w*h*.0001f).toInt()) || tail>w*h*c.width*c.height*.08f)continue
                val samples=(0 until tail).map {queue[it]%w to queue[it]/w}
                val top=samples.minOf {it.second};if(y-top<h*.012f)continue
                if(samples.maxOf {it.first}-samples.minOf {it.first}>c.width*w*.75f)continue
                val edge=ArtworkOverhangs.hull(samples.flatMap {(xx,yy)->listOf(xx-2 to yy-2,xx+3 to yy-2,xx-2 to yy+3,xx+3 to yy+3)})
                if(edge.size<3)continue
                val part=Panel(max(0f,edge.minOf {it.first}.toFloat()/w),max(0f,edge.minOf {it.second}.toFloat()/h),min(1f,edge.maxOf {it.first}.toFloat()/w),min(1f,edge.maxOf {it.second}.toFloat()/h),focusOutline=edge.map {(xx,yy)->PanelPoint((xx.toFloat()/w).coerceIn(0f,1f),(yy.toFloat()/h).coerceIn(0f,1f))},foregroundOverhang=true)
                val p=result[i];result[i]=p.copy(left=min(p.left,part.left),top=min(p.top,part.top),right=max(p.right,part.right),bottom=max(p.bottom,part.bottom),readingOrderBounds=p.readingOrderBounds ?: c,focusIncludes=p.focusIncludes+part)
                for(j in previous)if(part.left<cores[j].right && part.right>cores[j].left && part.top<cores[j].bottom && part.bottom>cores[j].top)result[j]=result[j].copy(focusExclusions=result[j].focusExclusions+part)
            }
        }
        return result
    }
}
