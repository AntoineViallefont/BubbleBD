package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class ContextShaftTest {
    private fun borders(pixels:IntArray,w:Int,h:Int,frames:List<Panel>) {
        for(p in frames) {
            val l=(p.left*w).toInt();val r=(p.right*w).toInt();val t=(p.top*h).toInt();val b=(p.bottom*h).toInt()
            for(y in t..b)for(x in listOf(l,r))pixels[y*w+x]=0xff111111.toInt()
            for(x in l..r)for(y in listOf(t,b))pixels[y*w+x]=0xff111111.toInt()
        }
    }
    @Test fun bodyAtGutterCanBelongToNextRowOnlyWithContinuousShaft() {
        val w=240;val h=400;val body=Panel(.2f,.4f,.5f,.5f);val text=Panel(.22f,.42f,.45f,.47f)
        val pixels=IntArray(w*h){0xff888888.toInt()}
        for(y in 198..260) {
            pixels[y*w+79]=0xff333333.toInt();pixels[y*w+80]=0xffbbbbbb.toInt();pixels[y*w+81]=0xff333333.toInt()
        }
        val frames=listOf(Panel(.05f,.05f,.95f,.50f),Panel(.05f,.515f,.95f,.95f))
        borders(pixels,w,h,frames)
        val found=SpeechOwnership.adjacentShaftContext(text,body,frames,pixels,w,h)!!
        assertEquals(1,found.owner);assertTrue(found.body.bottom>.65f)
        assertNull(SpeechOwnership.adjacentShaftContext(text,body,frames,IntArray(w*h){-1},w,h))
        assertNull(SpeechOwnership.adjacentShaftContext(text,body,listOf(frames[0],frames[1].copy(top=.63f)),pixels,w,h))
        val scenery=IntArray(w*h){0xff888888.toInt()}
        for(y in 198..260) {
            scenery[y*w+79]=0xff333333.toInt();scenery[y*w+80]=0xffbbbbbb.toInt();scenery[y*w+81]=0xff333333.toInt()
        }
        assertNull(SpeechOwnership.adjacentShaftContext(text,body,frames,scenery,w,h))
    }
    @Test fun collapsedDarkShaftContinuesLightTipButPlainDecorCannotStartIt() {
        val w=240;val h=400;val body=Panel(.2f,.4f,.5f,.5f);val text=Panel(.22f,.42f,.45f,.47f)
        val frames=listOf(Panel(.05f,.05f,.95f,.50f),Panel(.05f,.515f,.95f,.95f))
        val pixels=IntArray(w*h){0xffaaaaaa.toInt()}
        for(y in 198..204) {
            pixels[y*w+79]=0xff333333.toInt();pixels[y*w+80]=0xffbbbbbb.toInt();pixels[y*w+81]=0xff333333.toInt()
        }
        for(y in 205..260) {
            pixels[y*w+79]=0xffcccccc.toInt();pixels[y*w+80]=0xff444444.toInt();pixels[y*w+81]=0xffcccccc.toInt()
        }
        borders(pixels,w,h,frames)
        assertEquals(1,SpeechOwnership.adjacentShaftContext(text,body,frames,pixels,w,h)!!.owner)
        for(y in 198..204)for(x in 79..81)pixels[y*w+x]=0xffaaaaaa.toInt()
        assertNull(SpeechOwnership.adjacentShaftContext(text,body,frames,pixels,w,h))
    }
    @Test fun roundedCapOppositeLongerProtrusionDoesNotFollowScenery() {
        val w=240;val h=400
        val body=Panel(.2f,.50f,.5f,.65f);val text=Panel(.22f,.525f,.45f,.595f)
        val frames=listOf(Panel(.05f,.05f,.95f,.495f),Panel(.05f,.50f,.95f,.95f))
        val pixels=IntArray(w*h){0xff888888.toInt()}
        borders(pixels,w,h,frames)
        for(y in 140..202) {
            pixels[y*w+79]=0xff333333.toInt();pixels[y*w+80]=0xffbbbbbb.toInt();pixels[y*w+81]=0xff333333.toInt()
        }
        assertNull(SpeechOwnership.adjacentShaftContext(text,body,frames,pixels,w,h))
    }
}
