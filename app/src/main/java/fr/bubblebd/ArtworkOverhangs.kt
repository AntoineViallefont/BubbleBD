package fr.bubblebd

import kotlin.math.*

/** Follow foreground through a broken frame on paper, preserving neighbouring masks. */
object ArtworkOverhangs {
    private val directions=listOf(-1 to 0,1 to 0,0 to -1,0 to 1)
    private fun area(p:Panel)=p.width*p.height
    private fun inside(x:Float,y:Float,p:Panel):Boolean {
        if(p.focusOutline.isEmpty())return p.contains(x,y)
        var hit=false;var j=p.focusOutline.lastIndex
        for(i in p.focusOutline.indices) {
            val a=p.focusOutline[i];val b=p.focusOutline[j]
            if((a.y>y)!=(b.y>y) && x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)hit=!hit
            j=i
        }
        return hit
    }
    internal fun hull(input:List<Pair<Int,Int>>):List<Pair<Int,Int>> {
        val points=input.distinct().sortedWith(compareBy<Pair<Int,Int>>{it.first}.thenBy {it.second})
        fun cross(o:Pair<Int,Int>,a:Pair<Int,Int>,b:Pair<Int,Int>)=(a.first-o.first).toLong()*(b.second-o.second)-(a.second-o.second).toLong()*(b.first-o.first)
        fun chain(values:List<Pair<Int,Int>>):List<Pair<Int,Int>> {
            val out=mutableListOf<Pair<Int,Int>>()
            for(p in values) {while(out.size>=2 && cross(out[out.size-2],out.last(),p)<=0)out.removeAt(out.lastIndex);out.add(p)}
            return out.dropLast(1)
        }
        return if(points.size<3)emptyList() else chain(points)+chain(points.asReversed())
    }
    fun expand(input:List<Panel>,pixels:IntArray,w:Int,h:Int):List<Panel> {
        if(input.size !in 2..30 || w<80 || h<100)return input
        fun white(i:Int):Boolean {val c=pixels[i];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>240 && maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)-minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<15}
        val edge=(0 until h).count {white(it*w) && white(it*w+w-1)}*2+(0 until w).count {white(it) && white((h-1)*w+it)}*2
        if(edge<(w+h)*1f)return input // Dark gutters are not exterior paper.
        val cores=input.map {p->(p.readingOrderBounds ?: p).copy(focusOutline=p.focusOutline)}
        val coreBits=IntArray(w*h);val protected=IntArray(w*h)
        fun mark(p:Panel,bit:Int,target:IntArray) {
            val l=(p.left*w).toInt().coerceIn(0,w-1);val r=(p.right*w).toInt().coerceIn(l+1,w)
            val t=(p.top*h).toInt().coerceIn(0,h-1);val b=(p.bottom*h).toInt().coerceIn(t+1,h)
            for(y in t until b)for(x in l until r)if(inside((x+.5f)/w,(y+.5f)/h,p))target[y*w+x]=target[y*w+x] or bit
        }
        for(i in input.indices) {val bit=1 shl i;mark(cores[i],bit,coreBits);mark(cores[i],bit,protected);input[i].focusIncludes.forEach {mark(it,bit,protected)}}
        val queue=IntArray(w*h);val result=input.toMutableList()
        // Collect boundary evidence before assigning exterior artwork. Reading order
        // must not give the first frame ownership of a neighbour's larger overhang.
        val seeds=IntArray(w*h)
        val limits=mutableMapOf<Int,IntArray>()
        val order=BookRules.orderPanels(input,false).map {input.indexOf(it)}
        for(i in order) {
            val p=input[i];val c=cores[i];val bit=1 shl i
            if(p.isWholePage || p.focusExclusions.any {hole->cores.indices.any {j->j!=i &&
                max(0f,min(hole.right,cores[j].right)-max(hole.left,cores[j].left))*max(0f,min(hole.bottom,cores[j].bottom)-max(hole.top,cores[j].top))/max(area(hole),area(cores[j]))>.65f}})continue
            // An inset sits in painted background, not exterior paper.
            if(input.indices.any {j->j!=i && input[j].focusExclusions.any {hole->
                max(0f,min(hole.right,c.right)-max(hole.left,c.left))*max(0f,min(hole.bottom,c.bottom)-max(hole.top,c.top))>area(c)*.80f
            }})continue
            val freeScene=c.bottom>.98f && c.height>.18f && c.top>.40f
            val l=if(freeScene)1 else max(1,((c.left-.15f)*w).toInt());val r=if(freeScene)w-1 else min(w-1,((c.right+.15f)*w).toInt())
            val t=max(1,((c.top-if(freeScene).05f else .15f)*h).toInt());val b=min(h-1,((c.bottom+.15f)*h).toInt())
            limits[i]=intArrayOf(l,t,r,b)
            fun allowed(x:Int,y:Int)=x in l until r && y in t until b && coreBits[y*w+x] and bit==0 && protected[y*w+x] and bit.inv()==0 && !white(y*w+x)
            
            fun same(a:Int,b:Int)=maxOf(abs(((a shr 16)and 255)-((b shr 16)and 255)),abs(((a shr 8)and 255)-((b shr 8)and 255)),abs((a and 255)-(b and 255)))<=45
            for(y in t until b)for(x in l until r) {
                if(!allowed(x,y))continue
                val colour=pixels[y*w+x]
                val high=maxOf((colour shr 16)and 255,(colour shr 8)and 255,colour and 255)
                val low=minOf((colour shr 16)and 255,(colour shr 8)and 255,colour and 255)
                // Grayscale alone cannot distinguish a broken frame from black ink.
                if(high<90 || high-low<15)continue
                for((dx,dy) in directions) {
                    val nx=x+dx;val ny=y+dy;val qx=x+dx*3;val qy=y+dy*3
                    if(qx !in 0 until w || qy !in 0 until h || nx !in 0 until w || ny !in 0 until h || coreBits[ny*w+nx] and bit==0 || coreBits[qy*w+qx] and bit==0 || !same(colour,pixels[qy*w+qx]))continue
                    // A straight black border does not prove that a foreground crosses it.
                    val bar=(-3..3).any {normal->(-3..3).count {along->
                        val xx=x+normal*dx+along*dy;val yy=y+normal*dy+along*dx
                        if(xx !in 0 until w || yy !in 0 until h)false else pixels[yy*w+xx].let {v->maxOf((v shr 16)and 255,(v shr 8)and 255,v and 255)<170}
                    }>=6}
                    // Similar paint in the next physical scene is not proof of an overhang.
                    val disputed=cores.indices.any {j->j!=i && run {
                        val other=cores[j]
                        val xx=x.coerceIn((other.left*w).toInt()+3,max((other.left*w).toInt()+3,(other.right*w).toInt()-4)).coerceIn(0,w-1)
                        val yy=y.coerceIn((other.top*h).toInt()+3,max((other.top*h).toInt()+3,(other.bottom*h).toInt()-4)).coerceIn(0,h-1)
                        abs(xx-x)<=w*.025f && abs(yy-y)<=h*.025f && inside((xx+.5f)/w,(yy+.5f)/h,other) && same(colour,pixels[yy*w+xx])
                    }}
                    if(!bar && !disputed) {seeds[y*w+x]=seeds[y*w+x] or bit;break}
                }
            }
        }
        val seen=BooleanArray(w*h)
        for(seed in seeds.indices) {
            if(seeds[seed]==0 || seen[seed])continue
            var head=0;var tail=1;queue[0]=seed;seen[seed]=true
            val votes=IntArray(input.size)
            fun push(x:Int,y:Int) {
                if(x !in 1 until w-1 || y !in 1 until h-1)return
                val k=y*w+x
                if(!seen[k] && protected[k]==0 && !white(k)) {seen[k]=true;queue[tail++]=k}
            }
            while(head<tail) {
                val k=queue[head++];val x=k%w;val y=k/w
                for(i in input.indices)if(seeds[k] and (1 shl i)!=0)votes[i]++
                push(x-1,y);push(x+1,y);push(x,y-1);push(x,y+1)
            }
            val bottomContinuation=(0 until tail).any {queue[it]/w>=h*.98f}
            val freeOwner=if(bottomContinuation)votes.indices.filter {votes[it]>0 && cores[it].bottom>.98f && cores[it].height>.18f && cores[it].top>.40f}.maxByOrNull {votes[it]} else null
            val i=freeOwner ?: votes.indices.maxByOrNull {votes[it]} ?: continue
            if(votes[i]==0)continue
            val second=votes.indices.filter {it!=i}.maxOfOrNull {votes[it]} ?: 0
            if(freeOwner==null && second>0 && votes[i]<second*1.5f)continue
            val bounds=limits[i] ?: continue
            var retained=0
            for(k in 0 until tail) {
                val n=queue[k];val x=n%w;val y=n/w
                fun distance(q:Panel):Float {
                    val dx=maxOf(q.left*w-x,x-q.right*w,0f);val dy=maxOf(q.top*h-y,y-q.bottom*h,0f)
                    return sqrt(dx*dx+dy*dy)
                }
                val own=distance(cores[i])
                // A faint outside stroke of another frame can connect to an
                // overhang. Keep short gutter crossings, not remote border strips.
                val remoteNeighbour=freeOwner==null && cores.indices.any {j->j!=i && run {
                    val q=cores[j];val other=distance(q)
                    val rowGap=max(0f,(cores[i].top-q.bottom)*h)
                    val crossing=max(4f,min(h*.015f,rowGap+3))
                    other<own && own>crossing && other<4f
                }}
                if(!remoteNeighbour && x in bounds[0] until bounds[2] && y in bounds[1] until bounds[3])queue[retained++]=n
            }
            tail=retained
            if(tail<w*h*.0002f)continue
            val p=result[i];val c=cores[i]
            val left=IntArray(h){w};val right=IntArray(h){-1};var top=h;var bottom=0
            for(k in 0 until tail) {val n=queue[k];val x=n%w;val y=n/w;left[y]=min(left[y],x);right[y]=max(right[y],x);top=min(top,y);bottom=max(bottom,y+1)}
            val samples=(top until bottom).filter {right[it]>=0}.flatMap {y->listOf(left[y] to y,right[y] to y)}
            val contour=hull(samples.flatMap {(x,y)->listOf((x-2).coerceAtLeast(0) to (y-2).coerceAtLeast(0),(x+3).coerceAtMost(w) to (y-2).coerceAtLeast(0),(x-2).coerceAtLeast(0) to (y+3).coerceAtMost(h),(x+3).coerceAtMost(w) to (y+3).coerceAtMost(h))})
            if(contour.size<3)continue
            val part=Panel(contour.minOf {it.first}.toFloat()/w,contour.minOf {it.second}.toFloat()/h,contour.maxOf {it.first}.toFloat()/w,contour.maxOf {it.second}.toFloat()/h,focusOutline=contour.map {(x,y)->PanelPoint(x.toFloat()/w,y.toFloat()/h)})
            val holes=cores.filterIndexed {j,q->j!=i && q.left<part.right && q.right>part.left && q.top<part.bottom && q.bottom>part.top}
            result[i]=p.copy(left=min(p.left,part.left),top=min(p.top,part.top),right=max(p.right,part.right),bottom=max(p.bottom,part.bottom),readingOrderBounds=p.readingOrderBounds ?: c,focusIncludes=p.focusIncludes+part,focusExclusions=(p.focusExclusions+holes).distinct())
        }
        return result
    }
}
