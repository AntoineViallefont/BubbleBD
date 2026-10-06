package fr.bubblebd

import kotlin.math.*

/** Reconcile observations before layout decisions; containment is not duplication. */
object PanelCandidates {
    private fun area(p:Panel)=p.width*p.height
    private fun intersection(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
    private fun valid(p:Panel)=listOf(p.left,p.top,p.right,p.bottom).all {it.isFinite() && it in 0f..1f} && p.width>0f && p.height>0f
    fun consolidate(input:List<PanelCandidate>):List<PanelCandidate> {
        val ranked=input.filter {!it.text && it.confidence.isFinite() && it.confidence in .30f..1f && valid(it.bounds)}
            .sortedWith(compareByDescending<PanelCandidate> {it.confidence}
                .thenBy {area(it.bounds)}.thenBy {it.bounds.top}.thenBy {it.bounds.left}
                .thenBy {it.bounds.bottom}.thenBy {it.bounds.right})
        val unique=mutableListOf<PanelCandidate>()
        for(candidate in ranked) {
            if(unique.none {other ->
                val shared=intersection(candidate.bounds,other.bounds)
                val a=candidate.bounds;val b=other.bounds
                // A lower-confidence crop sharing three edges is another observation
                // of the same frame, unlike an independently framed inset.
                val aligned=listOf(abs(a.left-b.left)/b.width,abs(a.top-b.top)/b.height,
                    abs(a.right-b.right)/b.width,abs(a.bottom-b.bottom)/b.height).count {it<.05f}
                shared/(area(a)+area(b)-shared)>.68f ||
                    (aligned>=3 && shared/area(a)>.90f && area(a)<area(b) && candidate.confidence<other.confidence)
            })unique.add(candidate)
        }
        return unique.filterNot {parent ->
            val children=unique.filter {it!==parent && it.confidence>parent.confidence+.20f && it.confidence>.60f &&
                area(it.bounds)<area(parent.bounds)*.75f && intersection(it.bounds,parent.bounds)/area(it.bounds)>.88f}
            // Sum only independent regions: repeated detections must not fabricate coverage.
            val separate=mutableListOf<PanelCandidate>()
            for(child in children)if(separate.none {intersection(it.bounds,child.bounds)/min(area(it.bounds),area(child.bounds))>.15f})separate.add(child)
            parent.confidence<.50f && separate.size>=2 &&
                separate.sumOf {intersection(it.bounds,parent.bounds).toDouble()}>area(parent.bounds)*.75f
        }.sortedWith(compareByDescending<PanelCandidate> {area(it.bounds)}.thenBy {it.bounds.top}.thenBy {it.bounds.left})
    }
}
