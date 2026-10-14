package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class PageBleedTest {
    @Test fun topEdgeArtworkGrowsOnlyUntilItsWhiteBoundary() {
        val w=200;val h=300;val pixels=IntArray(w*h){0xffffffff.toInt()}
        for(y in 0 until 180)for(x in 0 until 82)pixels[y*w+x]=0xffff8040.toInt()
        val scene=Panel(0f,0f,.38f,.6f)
        val grown=PageBleed.expand(listOf(scene),pixels,w,h).single()
        assertEquals(.41f,grown.right,0.00001f)
        assertEquals(scene.top,grown.top,0f);assertEquals(scene.bottom,grown.bottom,0f)
    }
    @Test fun continuousBottomSceneUsesItsRealPageEdgeButIsolatedMarksDoNot() {
        val w=200;val h=300;val pixels=IntArray(w*h){0xffffffff.toInt()}
        for(y in 210 until h)for(x in 65 until w)pixels[y*w+x]=0xffa06040.toInt()
        val scene=Panel(.37f,.7f,1f,1f)
        assertEquals(.325f,PageBleed.expand(listOf(scene),pixels,w,h).single().left,.00001f)
        for(y in 295 until h)for(x in 61..62)pixels[y*w+x]=0xff000000.toInt()
        assertEquals("An isolated mark does not extend the continuous scene",.325f,PageBleed.expand(listOf(scene),pixels,w,h).single().left,.00001f)
    }
}
