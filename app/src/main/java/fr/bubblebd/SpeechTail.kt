package fr.bubblebd

import kotlin.math.*

/** A compact white lobe followed by a thin tip survives a low fill ratio. */
object SpeechTail {
    fun thinLobe(p:Panel,pixels:IntArray,w:Int,h:Int):Boolean {
        val l=(p.left*w).roundToInt().coerceIn(0,w-1);val r=(p.right*w).roundToInt().coerceIn(l+1,w)
        val t=(p.top*h).roundToInt().coerceIn(0,h-1);val b=(p.bottom*h).roundToInt().coerceIn(t+1,h)
        val size=(r-l)*(b-t);if(size<64)return false
        var total=0;var upper=0;var left=0
        for(y in t until b)for(x in l until r) {
            val c=pixels[y*w+x]
            if(minOf((c shr 16)and 255,(c shr 8)and 255,c and 255)<=202)continue
            total++;if(y<(t+b)/2)upper++;if(x<(l+r)/2)left++
        }
        if(total.toFloat()/size !in .18f.. .40f)return false
        return listOf(upper,left).any {a->max(a,total-a)>size*.275f && min(a,total-a)<size*.06f}
    }
}
