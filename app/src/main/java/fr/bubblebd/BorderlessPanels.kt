package fr.bubblebd

import kotlin.math.*

/** Sparse scenes on plain paper: shared captions/artwork stay together, without stored layouts. */
object BorderlessPanels {
    private data class Box(val l:Int,val t:Int,val r:Int,val b:Int)
    fun detect(pixels:IntArray,width:Int,height:Int):List<Panel> {
        val w=min(240,width);val h=max(1,(height.toDouble()*w/width).roundToInt())
        if(w<80 || h<100)return emptyList()
        val rgb=IntArray(w*h)
        for(y in 0 until h)for(x in 0 until w) {
            var red=0;var green=0;var blue=0;var count=0
            val l=x*width/w;val r=max(l+1,(x+1)*width/w)
            val t=y*height/h;val b=max(t+1,(y+1)*height/h)
            for(yy in t until min(height,b))for(xx in l until min(width,r)) {
                val c=pixels[yy*width+xx];red+=(c shr 16)and 255;green+=(c shr 8)and 255;blue+=c and 255;count++
            }
            rgb[y*w+x]=((red/count) shl 16) or ((green/count) shl 8) or (blue/count)
        }
        fun bin(c:Int)=(((c shr 20)and 15) shl 8) or (((c shr 12)and 15) shl 4) or ((c shr 4)and 15)
        val frequencies=IntArray(4096);for(i in rgb.indices step 5)frequencies[bin(rgb[i])]++
        val mode=frequencies.indices.maxBy {frequencies[it]}
        if(frequencies[mode]<rgb.size/5*.30)return PaperScenes.detect(rgb,w,h,pixels,width,height) // Textured/full artwork is not plain paper.
        val sample=(rgb.indices step 5).map {rgb[it]}.filter {bin(it)==mode}
        val bg=IntArray(3) {channel ->sample.map {(it shr (16-channel*8))and 255}.sorted()[sample.size/2]}
        val darkPaper=bg.max()<120
        if(bg.min()<120 && !darkPaper)return emptyList() // Avoid confusing dark artwork with colored paper.
        val mask=BooleanArray(rgb.size) {i ->
            val c=rgb[i];val channels=intArrayOf((c shr 16)and 255,(c shr 8)and 255,c and 255)
            channels.indices.maxOf {abs(channels[it]-bg[it])}>32 && (darkPaper || channels.min()<=230)
        }
        val inkFraction=mask.count {it}.toFloat()/mask.size
        val densePaper=inkFraction>.34f
        // Illustrated vignettes can cover more paper than sparse line drawings.
        // They still need a white majority and independently separated scenes.
        if(densePaper && (bg.min()<235 || inkFraction>.50f))return emptyList()
        // Ignore scan/crop edge noise. It must never connect otherwise independent scenes.
        for(x in 0 until w) {mask[x]=false;mask[(h-1)*w+x]=false}
        val seen=BooleanArray(mask.size);val queue=IntArray(mask.size);val captionInk=BooleanArray(mask.size);var closedFrames=0
        for(seed in mask.indices)if(mask[seed] && !seen[seed]) {
            var head=0;var tail=1;queue[0]=seed;seen[seed]=true
            while(head<tail) {
                val k=queue[head++];val x=k%w;val y=k/w
                fun push(n:Int) {if(mask[n] && !seen[n]) {seen[n]=true;queue[tail++]=n}}
                if(x>0)push(k-1);if(x<w-1)push(k+1);if(y>0)push(k-w);if(y<h-1)push(k+w)
            }
            if(tail<4)for(i in 0 until tail)mask[queue[i]]=false
            else {
                var l=w;var r=0;var t=h;var b=0
                for(i in 0 until tail) {val x=queue[i]%w;val y=queue[i]/w;l=min(l,x);r=max(r,x+1);t=min(t,y);b=max(b,y+1)}
                if(b-t<=h*.025 && r-l<=w*.22)for(i in 0 until tail)captionInk[queue[i]]=true
                if(r-l>w*.12 && b-t>h*.10) {
                    fun vertical(x:Int)=(t until b).count {y ->(max(0,x-1)..min(w-1,x+1)).any {mask[y*w+it]}}.toFloat()/(b-t)
                    fun horizontal(y:Int)=(l until r).count {x ->(max(0,y-1)..min(h-1,y+1)).any {mask[it*w+x]}}.toFloat()/(r-l)
                    if(minOf(vertical(l),vertical(r-1),horizontal(t),horizontal(b-1))>.75f)closedFrames++
                }
            }
        }
        if(closedFrames>=2)return emptyList() // Visible contours keep priority over scene spacing.
        fun runs(values:IntArray,limit:Double,above:Boolean=false):List<IntRange> {
            val out=mutableListOf<IntRange>();var start=-1
            for(i in 0..values.size) {
                val yes=i<values.size && if(above)values[i]>limit else values[i]<=limit
                if(yes && start<0)start=i
                if(!yes && start>=0) {out.add(start until i);start=-1}
            }
            return out
        }
        val rows=IntArray(h) {y ->(0 until w).count {mask[y*w+it]}}
        val occupied=runs(rows,3.0,true);if(occupied.size<2)return emptyList()
        var top=occupied.first().first;var bottom=occupied.last().last+1
        val last=occupied.last()
        if(last.first>h*.90 && last.count()<h*.03) {
            val xs=(0 until w).filter {x ->last.any {y ->mask[y*w+x]}}
            if(xs.isNotEmpty() && xs.last()-xs.first()<w*.06)bottom=occupied[occupied.lastIndex-1].last+1
        }
        // A broad, short heading above a genuine horizontal gap belongs to the page.
        for(gap in runs(rows.copyOfRange(top,bottom),w*.018)) {
            if(gap.count()>=h*.015 && gap.first<h*.14 && gap.first>h*.03) {
                val xs=(0 until w).filter {x ->(top until top+gap.first).any {y ->mask[y*w+x]}}
                if(xs.isNotEmpty() && xs.last()-xs.first()>w*.25) {top+=gap.last+1;break}
            }
        }
        fun bands(t:Int,b:Int):List<IntRange> {
            val gap=runs(rows.copyOfRange(t,b),w*.018).filter {
                it.count()>=h*.007 && it.first>=h*.13 && b-t-it.last-1>=h*.13
            }.maxByOrNull {it.count()} ?: return listOf(t until b)
            return bands(t,t+gap.first)+bands(t+gap.last+1,b)
        }
        val rowBands=bands(top,bottom)
        if(rowBands.size !in 2..5)return emptyList() // Do not turn isolated illustrations into automatic cases.
        val boxes=mutableListOf<Box>()
        val colored=bg.max()-bg.min()>30
        fun columns(l:Int,t:Int,r:Int,b:Int,depth:Int=0) {
            if(depth>5)return
            val hist=IntArray(r-l) {x ->(t until b).count {mask[it*w+x+l]}}
            // Separate caption groups can disambiguate figures whose pale halos overlap.
            val cap=IntArray(r-l) {x ->(t until min(b,t+max(1,((b-t)*(if(darkPaper).20 else .08)).toInt()))).count {if(darkPaper)captionInk[it*w+x+l] else mask[it*w+x+l]}}
            val captions=mutableListOf<IntRange>()
            for(chunk in runs(cap,0.0,true)) {
                if(captions.isNotEmpty() && chunk.first-captions.last().last-1<=3)captions[captions.lastIndex]=captions.last().first..chunk.last
                else captions.add(chunk)
            }
            val gap=runs(hist,if(darkPaper)0.0 else max(1.0,(b-t)*.03)).filter {
                val middle=(it.first+it.last+1)/2
                it.count()>=w*(if(colored).017 else .004) && it.first>=w*.15 && r-l-it.last-1>=w*.15 &&
                    captions.none {caption->caption.count()>=w*.10 && middle in caption} &&
                    (!darkPaper || captions.any {it.count()>=w*.08 && it.last<middle} && captions.any {it.count()>=w*.08 && it.first>middle})
            }.maxByOrNull {it.count()}
            if(gap!=null) {
                val middle=l+(gap.first+gap.last+1)/2
                columns(l,t,middle,b,depth+1);columns(middle,t,r,b,depth+1);return
            }
            val cut=if(darkPaper)null else captions.zipWithNext().mapNotNull {(a,z) ->
                if(a.count()<w*.10 || z.count()<w*.10 || z.first-a.last-1<w*.04)return@mapNotNull null
                val mid=(a.last+z.first)/2.0
                val x=(a.last+1 until z.first).minByOrNull {hist[it]+abs(it-mid)*.12} ?: return@mapNotNull null
                if(x<w*.15 || r-l-x<w*.15 || hist[x]>=(b-t)*.30)null else x+l
            }.firstOrNull()
            if(cut!=null) {columns(l,t,cut,b,depth+1);columns(cut,t,r,b,depth+1);return}
            var left=r;var right=l;var upper=b;var lower=t
            for(y in t until b)for(x in l until r)if(mask[y*w+x]) {left=min(left,x);right=max(right,x+1);upper=min(upper,y);lower=max(lower,y+1)}
            if(right-left>w*.12 && lower-upper>h*.09)boxes.add(Box(left,upper,right,lower))
        }
        for(band in rowBands) {
            val before=boxes.size
            columns(0,band.first,w,band.last+1)
            if(densePaper && boxes.size-before<2)return emptyList()
        }
        if(boxes.size<3)return emptyList()
        // Pale snow, shadows and thin balloon outlines require breathing room beyond the ink mask.
        return boxes.map {b ->val core=Panel(b.l.toFloat()/w,b.t.toFloat()/h,b.r.toFloat()/w,b.b.toFloat()/h)
            var left=max(0f,core.left-.035f);var upper=max(top.toFloat()/h-.015f,core.top-.03f)
            var right=min(1f,core.right+.035f);var lower=min(1f,core.bottom+.035f)
            if(densePaper) {
                // Breathing room ends inside a real paper gap, before the next caption.
                for(other in boxes)if(other!==b) {
                    val horizontal=min(b.r,other.r)-max(b.l,other.l)
                    val vertical=min(b.b,other.b)-max(b.t,other.t)
                    if(horizontal>min(b.r-b.l,other.r-other.l)*.5f) {
                        if(other.b<=b.t)upper=max(upper,(other.b+b.t)/2f/h)
                        if(other.t>=b.b)lower=min(lower,(b.b+other.t)/2f/h)
                    }
                    if(vertical>min(b.b-b.t,other.b-other.t)*.5f) {
                        if(other.r<=b.l)left=max(left,(other.r+b.l)/2f/w)
                        if(other.l>=b.r)right=min(right,(b.r+other.l)/2f/w)
                    }
                }
                lower=min(lower,bottom.toFloat()/h+.005f)
            }
            Panel(left,upper,right,lower,core)
        }
    }
}
