package fr.bubblebd

import kotlin.math.min

object GuidedFrames {
    // Use the physical frames, even when a balloon enlarges the focus bounds.
    internal fun sameRow(a:Panel,b:Panel):Boolean {
        val x=a.readingOrderBounds ?: a;val y=b.readingOrderBounds ?: b
        return kotlin.math.abs(x.top-y.top)<.035f && kotlin.math.abs(x.bottom-y.bottom)<.035f &&
            minOf(x.right,y.right)-maxOf(x.left,y.left)<.025f
    }
    internal fun focusBounds(p:Panel)=p.focusIncludes.fold(p) {bounds,part ->bounds.copy(
        left=minOf(bounds.left,part.left),top=minOf(bounds.top,part.top),
        right=maxOf(bounds.right,part.right),bottom=maxOf(bounds.bottom,part.bottom))}

    data class Frame(val indices:IntRange,val bounds:Panel,val scale:Float,val visible:List<Int> = indices.toList())
    fun frame(panels:List<Panel>,anchor:Int,pageWidth:Int,pageHeight:Int,viewWidth:Int,viewHeight:Int,
              minimum:Int=0,maximum:Int=panels.lastIndex,forward:Boolean=true):Frame {
        val p=focusBounds(panels[anchor])
        fun fit(q:Panel)=min(viewWidth/(q.width*pageWidth),viewHeight/(q.height*pageHeight))
        fun core(q:Panel)=q.readingOrderBounds ?: q
        val companions=p.jointFocus.mapNotNull {linked ->panels.indices.filter {it!=anchor}.minByOrNull {i ->
            val c=core(panels[i]);kotlin.math.abs(c.left-linked.left)+kotlin.math.abs(c.top-linked.top)+kotlin.math.abs(c.right-linked.right)+kotlin.math.abs(c.bottom-linked.bottom)
        }?.takeIf {i ->val c=core(panels[i]);kotlin.math.abs(i-anchor)==1 && sameRow(panels[anchor],panels[i]) && maxOf(kotlin.math.abs(c.left-linked.left),kotlin.math.abs(c.top-linked.top),kotlin.math.abs(c.right-linked.right),kotlin.math.abs(c.bottom-linked.bottom))<.015f}}
        val partner=companions.distinct().minByOrNull {i ->val q=panels[i];(maxOf(p.right,q.right)-minOf(p.left,q.left))*(maxOf(p.bottom,q.bottom)-minOf(p.top,q.top))}
        if(partner!=null) {
            // Include the uncertain neighbour even when this requires less zoom.
            // Only consecutive cases on the same row form a joint reading step.
            val visible=listOf(anchor,partner).sorted()
            val lit=visible.map {focusBounds(panels[it])}
            val bounds=Panel(lit.minOf {it.left},lit.minOf {it.top},lit.maxOf {it.right},lit.maxOf {it.bottom})
            val consecutive=visible.last()-visible.first()+1==visible.size
            return Frame(if(consecutive)visible.first()..visible.last() else anchor..anchor,bounds,fit(bounds),visible)
        }
        var scale=fit(p)
        var first=anchor;var last=anchor;var bounds=p
        fun include(i:Int):Boolean {
            if(i !in minimum..maximum || panels[i].isWholePage)return false
            if(!sameRow(panels[anchor],panels[i]))return false
            val q=focusBounds(panels[i])
            val combined=Panel(minOf(bounds.left,q.left),minOf(bounds.top,q.top),maxOf(bounds.right,q.right),maxOf(bounds.bottom,q.bottom))
            val maximumZoom=maxOf(scale,fit(q))
            if(combined.width*pageWidth*maximumZoom>viewWidth+.5f || combined.height*pageHeight*maximumZoom>viewHeight+.5f)return false
            scale=maximumZoom
            bounds=combined;first=minOf(first,i);last=maxOf(last,i);return true
        }
        if(forward) {while(include(last+1)){};while(include(first-1)){}}
        else {while(include(first-1)){};while(include(last+1)){}}
        return Frame(first..last,bounds,scale)
    }
    fun label(indices:IntRange,total:Int):String {
        return label(indices.toList(),total)
    }
    fun label(indices:List<Int>,total:Int):String {
        val numbers=indices.map {(it+1).toString()}
        return if(numbers.size==1)"Case ${numbers[0]}/$total" else "Cases ${numbers.dropLast(1).joinToString(", ")} et ${numbers.last()}/$total"
    }
}
