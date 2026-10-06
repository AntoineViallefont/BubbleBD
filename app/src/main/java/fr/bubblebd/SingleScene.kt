package fr.bubblebd

/** An intact thin page frame with no internal framed cells is one sparse scene. */
object SingleScene {
    fun detect(pixels:IntArray,w:Int,h:Int):List<Panel> {
        if(w<100 || h<80)return emptyList()
        fun dark(x:Int,y:Int):Boolean {val c=pixels[y*w+x];return maxOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<190}
        fun white(x:Int,y:Int):Boolean {val c=pixels[y*w+x];return minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)>220}
        if((0 until w).count {dark(it,0)}<w*.95f || (0 until w).count {dark(it,h-1)}<w*.95f ||
            (0 until h).count {dark(0,it)}<h*.95f || (0 until h).count {dark(w-1,it)}<h*.95f)return emptyList()
        val lightSides=listOf((3 until w-3).count {white(it,3)}.toFloat()/(w-6),
            (3 until w-3).count {white(it,h-4)}.toFloat()/(w-6),
            (3 until h-3).count {white(3,it)}.toFloat()/(h-6),
            (3 until h-3).count {white(w-4,it)}.toFloat()/(h-6))
        if(lightSides.count {it>.50f}<2 || pixels.indices.count {i->white(i%w,i/w)}<pixels.size*.50f)return emptyList()
        if(GridPanels.detect(pixels,w,h).isNotEmpty())return emptyList()
        fun closed(p:Panel):Boolean {
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            fun vertical(x:Int)=(-3..3).maxOf {d->if(x+d !in 0 until w)0f else (t until b).count {dark(x+d,it)}.toFloat()/(b-t)}
            fun horizontal(y:Int)=(-3..3).maxOf {d->if(y+d !in 0 until h)0f else (l until r).count {dark(it,y+d)}.toFloat()/(r-l)}
            return minOf(vertical(l),vertical(r),horizontal(t),horizontal(b))>.88f
        }
        if(PanelGeometry.detect(pixels,w,h,framesOnly=true).any(::closed))return emptyList()
        return listOf(Panel(0f,0f,1f,1f))
    }
}
