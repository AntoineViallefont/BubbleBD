package fr.bubblebd

import kotlin.math.*

/** Recover closed rectangular inserts protruding into a paper margin; no extra inference. */
object InsetPanels {
    fun recover(panels:List<Panel>,pixels:IntArray,w:Int,h:Int,texts:List<Panel> = emptyList()):List<Panel> {
        val found=mutableListOf<Panel>()
        fun area(p:Panel)=p.width*p.height
        fun shared(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        fun core(p:Panel)=p.readingOrderBounds ?: p
        for(parent in panels.filterNot {it.width>.9f && it.focusExclusions.size>=2})for(rotated in listOf(false,true))for(reflected in listOf(false,true)) {
            val width=if(rotated)h else w;val height=if(rotated)w else h
            val original=core(parent)
            val unreflected=if(rotated)Panel(original.top,original.left,original.bottom,original.right) else original
            val p=if(reflected)Panel(1f-unreflected.right,unreflected.top,1f-unreflected.left,unreflected.bottom) else unreflected
            if(p.width<.40f || p.height<.18f)continue
            fun color(x:Int,y:Int):Int {
                val xx=if(reflected)width-1-x else x
                return pixels[if(rotated)xx*w+y else y*w+xx]
            }
            fun dark(x:Int,y:Int):Boolean {if(x !in 0 until width || y !in 0 until height)return false;val c=color(x,y);return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<190}
            fun paper(x:Int,y:Int):Boolean {if(x !in 0 until width || y !in 0 until height)return false;val c=color(x,y);return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>220}
            val left=(p.left*width).toInt();val top=(p.top*height).toInt().coerceAtLeast(0);val bottom=(p.bottom*height).toInt().coerceAtMost(height)
            val minHeight=max(16,(height*.08f).toInt())
            // Only the small outside band is scanned. A straight paper/ink boundary
            // must have three other supported borders, unlike a balloon or a figure.
            for(x in max(3,left-(width*.06f).toInt()) until left-max(2,(width*.005f).toInt())) {
                var start=-1;var last=-1;var hits=0
                fun complete() {
                    if(start<0 || last-start<minHeight || hits.toFloat()/(last-start+1)<.88f)return
                    val t=start;val b=last+1
                    if(t-top<height*.015f || bottom-b<height*.015f || b-t>(bottom-top)*.90f)return
                    // The top and bottom must close in the protruding margin itself.
                    // Dark artwork inside the parent is not evidence of a frame.
                    fun marginBorder(y:Int)=(-2..2).maxOf {d ->(x until left-1).count {dark(it,y+d)}.toFloat()/(left-1-x)}
                    if(marginBorder(t)<.80f || marginBorder(b-1)<.80f)return
                    val minRight=x+max(16,(width*.10f).toInt())
                    val maxRight=min((p.right*width).toInt()-3,x+(p.width*width*.55f).toInt())
                    var best=0f;var right=-1
                    for(r in minRight..maxRight) {
                        val side=(-2..2).maxOf {d ->(t until b).count {dark(r+d,it)}.toFloat()/(b-t)}
                        if(side<.90f)continue
                        fun horizontal(y:Int)=(-2..2).maxOf {d ->(x..r).count {dark(it,y+d)}.toFloat()/(r-x+1)}
                        val upper=horizontal(t);val lower=horizontal(b-1)
                        if(upper<.88f || lower<.88f)continue
                        val score=side+upper+lower
                        if(score>best+.03f) {best=score;right=r}
                    }
                    if(right<0)return
                    // Verify that the outside strip is truly page paper for almost
                    // the whole side, then include antialiased borders in the bounds.
                    if((t until b).count {paper(x-3,it) || paper(x-4,it)}.toFloat()/(b-t)<.90f)return
                    var q=Panel(max(0f,(x-2f)/width),max(0f,(t-2f)/height),min(1f,(right+3f)/width),min(1f,(b+2f)/height))
                    if(reflected)q=Panel(1f-q.right,q.top,1f-q.left,q.bottom)
                    if(rotated)q=Panel(q.top,q.left,q.bottom,q.right)
                    if(shared(q,original)/area(q)<.60f || area(q)>area(original)*.45f)return
                    // A closed narration box can also protrude into page paper.
                    // Large contained text and four white interior corners jointly
                    // distinguish that box from a small scene with its own artwork.
                    val textFilled=texts.any {shared(q,it)/area(it)>.95f && area(it)/area(q)>.40f}
                    if(textFilled && listOf(.08f to .08f,.92f to .08f,.08f to .92f,.92f to .92f).all {(dx,dy) ->
                        val xx=((q.left+q.width*dx)*w).toInt().coerceIn(0,w-1)
                        val yy=((q.top+q.height*dy)*h).toInt().coerceIn(0,h-1)
                        val c=pixels[yy*w+xx]
                        minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>220
                    })return
                    val outside=area(q)-shared(q,original)
                    if(panels.any {other ->
                        val c=core(other)
                        val intersection=Panel(max(q.left,original.left),max(q.top,original.top),min(q.right,original.right),min(q.bottom,original.bottom))
                        val occupied=shared(q,c)-shared(intersection,c)
                        other!==parent && shared(c,original)/area(c)<.60f &&
                            (shared(q,c)/area(q)>.07f || occupied>max(outside*.15f,4f/(w*h)))
                    })return
                    if((panels+found).none {other ->shared(q,core(other))/max(area(q),area(core(other)))>.65f})found.add(q)
                }
                for(y in top until bottom) {
                    if((-1..1).any {dark(x+it,y)} && (paper(x-3,y) || paper(x-4,y))) {
                        if(start<0)start=y
                        last=y;hits++
                    }
                    if(start>=0 && (y-last>5 || y==bottom-1)) {
                        complete();start=-1;last=-1;hits=0
                    }
                }
            }
        }
        if(found.isEmpty())return panels
        val all=panels+found.map {it.copy(readingOrderBounds=it)}
        return all.map {parent ->
            val holes=found.filter {q ->q!==parent && area(q)<area(core(parent))*.45f && shared(q,core(parent))/area(q)>.60f}
            parent.copy(focusExclusions=parent.focusExclusions+holes)
        }
    }
}
