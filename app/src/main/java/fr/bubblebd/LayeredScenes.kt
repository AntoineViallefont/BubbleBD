package fr.bubblebd

import kotlin.math.*

/** Corroborate two edge scenes around a central framed inset, before canvas repair. */
internal object LayeredScenes {
    private fun area(p:Panel)=p.width*p.height
    private fun shared(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    fun repair(input:List<Panel>,predictions:List<PanelCandidate>,pixels:IntArray,w:Int,h:Int,contours:FrameContours):List<Panel>? {
        fun ink(x:Int,y:Int):Boolean {
            val c=pixels[y.coerceIn(0,h-1)*w+x.coerceIn(0,w-1)]
            return minOf(c shr 16 and 255,c shr 8 and 255,c and 255)<230
        }
        fun edgeArtwork(p:Panel,left:Boolean):Boolean {
            val x=if(left)2 else w-3
            val t=(p.top*h).toInt()+4;val b=(p.bottom*h).toInt()-4
            return b-t>h*.2f && (t until b).count {ink(x,it)}>(b-t)*.85f
        }
        val candidates=predictions.filter {!it.text && it.confidence>.30f && it.bounds.height in .28f.. .65f && it.bounds.width in .22f.. .60f}
        for(a in candidates.filter {it.bounds.left<.015f})for(b in candidates.filter {it.bounds.right>.985f}) {
            val left=a.bounds;val right=b.bounds
            if(max(a.confidence,b.confidence)<.85f || abs(left.top-right.top)>.04f || abs(left.bottom-right.bottom)>.025f || right.left-left.right !in .02f.. .35f)continue
            if(!edgeArtwork(left,true) || !edgeArtwork(right,false))continue
            val inset=predictions.filter {!it.text && it.confidence>.65f && it.bounds.width<.38f &&
                it.bounds.left<left.right+.035f && it.bounds.right>right.left-.035f &&
                it.bounds.top>=max(left.top,right.top) && it.bounds.top<max(left.top,right.top)+.12f &&
                it.bounds.bottom<min(left.bottom,right.bottom)+.025f}
                .mapNotNull {d ->(contours.fit(d.bounds) ?: contours.fitColour(d.bounds))?.let {d.bounds to it}}
                .minByOrNull {area(it.second)} ?: continue
            val center=(inset.second.left+inset.second.right)*.5f
            val sides=listOf(left.copy(left=0f,top=if(left.top<.05f && ink(2,2))0f else left.top),right.copy(right=1f))
            val region=Panel(0f,min(left.top,right.top),1f,max(left.bottom,right.bottom))
            val result=input.filterNot {p ->
                val c=p.readingOrderBounds ?: p
                sides.any {shared(c,it)/max(area(c),area(it))>.55f} ||
                    (p.width>.85f && p.height>.65f && c.height<.005f && shared(p,region)/area(region)>.85f)
            }.toMutableList()
            // Preserve visible physical bounds; the camera also includes the
            // scene hidden behind its inset, rather than truncating its artwork.
            result.add(sides[0].copy(right=max(sides[0].right,center),readingOrderBounds=sides[0],focusExclusions=listOf(inset.second)))
            result.add(sides[1].copy(left=min(sides[1].left,center),readingOrderBounds=sides[1],focusExclusions=listOf(inset.second)))
            result.removeAll {p ->val c=p.readingOrderBounds ?: p;shared(c,inset.second)/max(area(c),area(inset.second))>.70f}
            result.add(inset.first.copy(readingOrderBounds=inset.second))
            // A further full-bleed scene can remain at the lower page corner.
            for(d in predictions.filter {!it.text && it.confidence>.55f && it.bounds.bottom>.985f &&
                it.bounds.right>.985f && it.bounds.top>=region.bottom-.04f && it.bounds.width>.35f && it.bounds.height>.20f}) {
                val p=d.bounds
                if(!ink(w-3,h-3) || result.any {q ->shared(q,p)/max(area(q),area(p))>.70f})continue
                result.add(p.copy(right=1f,bottom=1f,readingOrderBounds=p.copy(right=1f,bottom=1f)))
            }
            for(d in predictions.filter {!it.text && it.confidence>.75f && it.bounds.bottom>.88f &&
                it.bounds.left>.04f && it.bounds.right<.97f && it.bounds.width in .20f.. .45f &&
                it.bounds.height>.25f && it.bounds.top>=region.bottom+.04f}) {
                val p=d.bounds;val x=((p.left+p.right)*.5f*w).toInt()
                val framed=contours.fit(p) ?: contours.fitColour(p)
                if(framed==null && (max(0,h-12) until h).count {ink(x,it)}<10)continue
                val existing=result.filter {q ->val c=q.readingOrderBounds ?: q;shared(c,p)/max(area(c),area(p))>.60f}
                result.removeAll(existing.toSet());result.add(p.copy(readingOrderBounds=framed ?: p))
            }
            // A central scene may continue below the two framed neighbours.
            for(i in result.indices) {
                val p=result[i];val c=p.readingOrderBounds ?: p
                if(c.bottom<.88f || c.bottom>.99f || c.left<.2f || c.right>.8f || c.height<.25f)continue
                val x=((c.left+c.right)*.5f*w).toInt();val start=(c.bottom*h).toInt()
                if((start until h).count {ink(x,it)}<(h-start)*.95f)continue
                val footer=(0 until w).filter {xx ->(h-5 until h).count {ink(xx,it)}>=4}
                val runs=footer.filter {it.toFloat()/w in c.left-.06f..c.right+.06f}
                if(runs.isEmpty() || runs.last()-runs.first()<c.width*w*.85f)continue
                val extended=c.copy(left=min(c.left,runs.first().toFloat()/w),right=max(c.right,(runs.last()+1f)/w),bottom=1f)
                result[i]=p.copy(left=min(p.left,extended.left),right=max(p.right,extended.right),bottom=1f,readingOrderBounds=extended)
            }
            return result
        }
        return null
    }
}
