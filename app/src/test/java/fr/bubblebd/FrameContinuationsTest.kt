package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class FrameContinuationsTest {
    private val w=300
    private val h=400
    private fun paint(end:Int,extra:Boolean=false):IntArray {
        val a=IntArray(w*h){-1}
        fun rect(top:Int,bottom:Int) {
            for(y in top until bottom)for(x in 60..120)a[y*w+x]=if(x==60 || x==120 || y==top || y==bottom-1)0xff000000.toInt() else 0xffa0c0d0.toInt()
        }
        rect(180,end)
        for(y in 249 until 265)for(x in 68..111)a[y*w+x]=-1
        if(extra)rect(281,310)
        return a
    }
    @Test fun continuingSidesRecoverTheFrameBelowItsCaption() {
        val p=Panel(.2f,.45f,121f/w,250f/h)
        val q=FrameContours(paint(310),w,h,emptyList()).continueBelowCaption(p)!!
        assertEquals(310f/h,q.bottom,.002f)
        assertEquals(p.left,q.left,0f)
    }
    @Test fun paperGapPreventsJoiningTwoStackedFrames() {
        val p=Panel(.2f,.45f,121f/w,270f/h)
        assertNull(FrameContours(paint(270,true),w,h,emptyList()).continueBelowCaption(p))
    }
    @Test fun AWhiteCaptionWithoutPhysicalSidesCannotExtendTheCase() {
        val pixels=paint(310)
        for(y in 250 until 310) {pixels[y*w+60]=-1;pixels[y*w+120]=-1}
        assertNull(FrameContours(pixels,w,h,emptyList()).continueBelowCaption(Panel(.2f,.45f,121f/w,250f/h)))
    }
}
