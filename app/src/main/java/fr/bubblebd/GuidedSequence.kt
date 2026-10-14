package fr.bubblebd

/** Stable forward/backward itinerary. Detection and direct selection stay intact. */
object GuidedSequence {
    fun steps(panels:List<Panel>,pageWidth:Int,pageHeight:Int,viewWidth:Int,viewHeight:Int):List<GuidedFrames.Frame> {
        if(panels.isEmpty() || minOf(pageWidth,pageHeight,viewWidth,viewHeight)<=0)return emptyList()
        val result=mutableListOf<GuidedFrames.Frame>()
        val displayed=mutableListOf<Panel>()
        var anchor=0
        while(anchor<panels.size) {
            val bounds=GuidedFrames.focusBounds(panels[anchor])
            // Require the entire focus, including balloons. Separate views are
            // never combined to claim that an unread case has already been shown.
            val covered=displayed.any {p ->p.left<=bounds.left && p.top<=bounds.top && p.right>=bounds.right && p.bottom>=bounds.bottom}
            if(covered) {anchor++;continue}
            val frame=GuidedFrames.frame(panels,anchor,pageWidth,pageHeight,viewWidth,viewHeight,minimum=anchor)
            result.add(frame)
            if(frame.visible.any {!panels[it].isWholePage || panels[it].focusExclusions.isNotEmpty()})displayed.add(frame.bounds)
            anchor=frame.indices.last+1
        }
        return result
    }
}
