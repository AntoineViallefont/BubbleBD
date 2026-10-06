package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*

class CaptionRowsTest {
    private fun page():Triple<IntArray,Int,Int> {
        val w=300;val h=600;val px=IntArray(w*h){-1}
        fun scene(l:Int,t:Int,r:Int,b:Int) {
            for(y in t..b)for(x in l..r)px[y*w+x]=if(x==l || x==r || y==t || y==b)0xff383838.toInt() else 0xffd8c6a0.toInt()
        }
        scene(3,3,295,97)
        for(row in 1..5) {
            val t=row*100;scene(3,t,147,t+87);scene(153,t,295,t+87)
            for(y in t+90 until minOf(h,t+100))for(x in 3..295)if((x+y)%10<3)px[y*w+x]=0xff555555.toInt()
        }
        return Triple(px,w,h)
    }
    @Test fun commentsBelowDenseRowsStayWithTheirFramesAndHeaderIsWhole() {
        val (px,w,h)=page();val panels=CaptionRows.detect(px,w,h)
        assertEquals(11,panels.size)
        assertTrue(panels.first().left==0f && panels.first().right==1f)
        assertTrue(panels[1].bottom>=200f/h && panels[2].bottom>=200f/h)
        assertEquals(1f,panels.last().bottom)
    }
    @Test fun brightCaptionRowsWithoutFourPhysicalEdgesAreNotCases() {
        val (px,w,h)=page()
        for(y in 100 until h)for(x in listOf(3,147,153,295))px[y*w+x]=-1
        assertTrue(CaptionRows.detect(px,w,h).isEmpty())
    }
    @Test fun threeRowsDoNotEstablishTheDenseCaptionPattern() {
        val (px,w,h)=page()
        for(y in 390 until h)for(x in 0 until w)px[y*w+x]=-1
        assertTrue(CaptionRows.detect(px,w,h).isEmpty())
    }
    @Test fun UnevenWhiteArtworkBandsAreNotForcedIntoARegularGrid() {
        val (px,w,h)=page()
        for(y in 290..299)for(x in 0 until w)px[y*w+x]=0xffd8c6a0.toInt()
        assertTrue(CaptionRows.detect(px,w,h).isEmpty())
    }
}
