package fr.bubblebd

/** Small per-album history; revisiting a page never counts it twice. */
class PanelStyleHistory(initial:Map<Int,Pair<Int,Int>> = emptyMap()) {
    private val pages=java.util.TreeMap<Int,Pair<Int,Int>>().apply {putAll(initial.filter {it.key>=0 && it.value.first>=0 && it.value.second>=it.value.first})}
    @Synchronized fun record(page:Int,panels:List<Panel>) {
        val cases=panels.filterNot {it.isWholePage || it.focusExclusions.isNotEmpty()}
        if(cases.size<2)return
        pages[page]=cases.count {it.focusOutline.isEmpty()} to cases.size
        // Keep the beginning and the latest observed pages, with a strict memory bound.
        while(pages.size>64)pages.remove(pages.keys.elementAt(16))
    }
    @Synchronized fun prefersRectangles(page:Int):Boolean {
        val earlier=pages.filterKeys {it<page}.values
        val total=earlier.sumOf {it.second}
        return earlier.size>=2 && total>=6 && earlier.sumOf {it.first}>=total*.90
    }
    @Synchronized fun snapshot():Map<Int,Pair<Int,Int>> = pages.toMap()
}
