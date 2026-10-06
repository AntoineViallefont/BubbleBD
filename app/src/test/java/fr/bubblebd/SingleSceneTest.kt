package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*

class SingleSceneTest {
    private fun drawing():Triple<IntArray,Int,Int> {
        val w=300;val h=240;val px=IntArray(w*h){-1}
        for(x in 0 until w){px[x]=0xff000000.toInt();px[(h-1)*w+x]=0xff000000.toInt()}
        for(y in 0 until h){px[y*w]=0xff000000.toInt();px[y*w+w-1]=0xff000000.toInt()}
        // Separate trees, shadows and a figure are decoration within one frame.
        for(y in 25..210)for(x in 230..236)px[y*w+x]=0xff000000.toInt()
        for(y in 10..50)for(x in 180..270)if((x+y)%3==0)px[y*w+x]=0xff000000.toInt()
        for(y in 170..210)for(x in 80..86)px[y*w+x]=0xff000000.toInt()
        for(y in 210 until h)for(x in 0 until w)px[y*w+x]=0xff000000.toInt()
        return Triple(px,w,h)
    }
    @Test fun intactOuterFrameKeepsDisjointArtworkInOneScene() {
        val (px,w,h)=drawing();assertEquals(1,SingleScene.detect(px,w,h).size)
        assertTrue(SingleScene.detect(px,w,h).single().isWholePage)
    }
    @Test fun interiorFramedInsertPreventsSingleSceneShortcut() {
        val (px,w,h)=drawing()
        for(x in 20..130){px[30*w+x]=0xff000000.toInt();px[120*w+x]=0xff000000.toInt()}
        for(y in 30..120){px[y*w+20]=0xff000000.toInt();px[y*w+130]=0xff000000.toInt()}
        assertTrue(SingleScene.detect(px,w,h).isEmpty())
    }
    @Test fun brokenOuterFrameDoesNotInventOnePanel() {
        val (px,w,h)=drawing();for(x in 30..270)px[x]=-1
        assertTrue(SingleScene.detect(px,w,h).isEmpty())
    }
}
