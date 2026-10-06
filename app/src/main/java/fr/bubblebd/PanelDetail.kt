package fr.bubblebd

/** Revisit broad pale regions where a whole-page pass can miss small scenes. */
object PanelDetail {
    fun needed(geometry:List<Panel>,pixels:IntArray,w:Int,h:Int):Boolean {
        if(geometry.size in 2..3)return true
        if(geometry.size !in 4..16)return false
        return geometry.any {p->
            if(p.focusExclusions.isNotEmpty() || p.width<.8f || p.height<.3f || p.width*p.height<.25f)return@any false
            var paper=0;var seen=0
            for(y in (p.top*h).toInt().coerceIn(0,h-1) until (p.bottom*h).toInt().coerceIn(1,h) step 3)
                for(x in (p.left*w).toInt().coerceIn(0,w-1) until (p.right*w).toInt().coerceIn(1,w) step 3) {
                    val c=pixels[y*w+x];seen++
                    if(minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>202)paper++
                }
            seen>0 && paper>seen*.55f
        }
    }
}
