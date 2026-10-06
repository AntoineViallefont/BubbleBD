package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class SpeechOwnershipTest {
    @Test fun lateralTailIdentifiesSpeakerInAnotherCellAndSharesOnlyAlignedNeighbours() {
        val w=240;val h=160
        val left=Panel(.05f,.1f,.49f,.9f);val right=Panel(.51f,.1f,.95f,.9f)
        val body=Panel(.65f,.3f,.85f,.55f)
        val pixels=IntArray(w*h){0xff888888.toInt()}
        for(x in 91..160) {
            pixels[59*w+x]=0xff333333.toInt();pixels[60*w+x]=0xffeeeeee.toInt();pixels[61*w+x]=0xff333333.toInt()
        }
        val found=SpeechOwnership.longSideTail(body,listOf(left,right),pixels,w,h)!!
        assertEquals(0,found.owner)
        assertEquals(0 to 1,SpeechOwnership.crossingPair(body,found.owner,listOf(left,right)))
        assertNull(SpeechOwnership.longSideTail(body,listOf(left,right),IntArray(w*h){-1},w,h))
        assertNull(SpeechOwnership.crossingPair(Panel(.4f,.3f,.6f,.55f),0,listOf(left,right)))
        assertNull(SpeechOwnership.crossingPair(body,0,listOf(left,right.copy(top=.6f))))
    }

    @Test fun privateLongTailKeepsBubbleWithThePortrait() {
        val file=java.io.File("src/test/resources/private/october-replay-55.rgb")
        if(!file.exists())return
        val input=java.io.DataInputStream(file.inputStream());val w=input.readInt();val h=input.readInt()
        val pixels=IntArray(w*h){(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
        fun box(l:Float,t:Float,r:Float,b:Float)=Panel(l/w,t/h,r/w,b/h)
        val frames=listOf(box(40f,23f,558f,213f),box(39f,205f,199f,467f),box(194f,205f,558f,467f))
        val found=SpeechOwnership.longLowerTail(box(58f,147f,119f,192f),frames,pixels,w,h)!!
        assertEquals(1,found.owner)
        assertTrue(found.body.bottom*h>=260)
    }
    @Test fun antialiasedLongTailCrossesIntoAnotherRowWithoutFollowingPlainPaper() {
        val w=200;val h=300;val body=Panel(.25f,.1f,.65f,.25f)
        val frames=listOf(Panel(.1f,.05f,.9f,.3f),Panel(.1f,.32f,.9f,.9f))
        val pixels=IntArray(w*h){0xff888888.toInt()}
        for(y in 71..120) {
            pixels[y*w+89]=0xff444444.toInt();pixels[y*w+90]=0xffaaaaaa.toInt();pixels[y*w+91]=0xff444444.toInt()
            pixels[y*w+88]=0xff444444.toInt();pixels[y*w+92]=0xff444444.toInt()
        }
        val found=SpeechOwnership.longLowerTail(body,frames,pixels,w,h)!!
        assertEquals(1,found.owner)
        assertTrue(found.body.bottom>.40f)
        assertNull(SpeechOwnership.longLowerTail(body,frames,IntArray(w*h){-1},w,h))
    }
    @Test fun uncertaintyLinksHorizontalAndVerticalNeighboursButNotDistantScenes() {
        val left=Panel(.1f,.1f,.49f,.5f);val right=Panel(.51f,.1f,.9f,.5f)
        val crossing=Panel(.43f,.2f,.59f,.3f)
        assertEquals(0 to 1,SpeechOwnership.uncertainPair(crossing,crossing,listOf(left,right),true))
        val ordinary=Panel(.2f,.2f,.3f,.3f)
        assertNull(SpeechOwnership.uncertainPair(ordinary,ordinary,listOf(left,right),true))
        val upper=Panel(.1f,.1f,.9f,.49f);val lower=Panel(.1f,.51f,.9f,.9f)
        val between=Panel(.3f,.46f,.6f,.54f)
        assertEquals(0 to 1,SpeechOwnership.uncertainPair(between,between,listOf(upper,lower),true))
        assertNull(SpeechOwnership.uncertainPair(between,Panel(.3f,.45f,.6f,.66f),listOf(upper,lower),false))
    }
    @Test fun onlyConsecutivePartnersShareAViewAndBothFit() {
        val first=Panel(.05f,.1f,.45f,.4f);val middle=Panel(.5f,.1f,.95f,.4f);val last=Panel(.05f,.5f,.45f,.9f)
        val linked=SpeechOwnership.linkViews(listOf(first,middle,last),listOf(first,middle,last),setOf(0 to 2))
        val frame=GuidedFrames.frame(linked,0,1000,1000,400,600)
        assertEquals(listOf(0),frame.visible)
        assertEquals(0..0,frame.indices)
        assertEquals("Case 1/3",GuidedFrames.label(frame.visible,3))
        val adjacent=SpeechOwnership.linkViews(listOf(first,middle),listOf(first,middle),setOf(0 to 1))
        val together=GuidedFrames.frame(adjacent,0,1000,1000,400,600)
        assertEquals(listOf(0,1),together.visible)
        assertTrue(together.bounds.width*1000*together.scale<=400.01f)
        assertEquals("Cases 1 et 2/2",GuidedFrames.label(together.visible,2))
    }
    @Test fun ambiguousBodyUsesAreaOnEachSideOfGutter() {
        val w=400;val h=500;val pixels=IntArray(w*h){0xff333333.toInt()}
        for(y in 150 until 240)for(x in 125 until 240)pixels[y*w+x]=0xffffffff.toInt()
        val frames=listOf(Panel(.1f,.2f,.49f,.6f),Panel(.51f,.2f,.9f,.6f))
        val body=Panel(.30f,.28f,.62f,.50f);val text=Panel(.35f,.33f,.57f,.43f)
        assertEquals(0,SpeechOwnership.adjacent(text,body,frames,pixels,w,h,false)!!.owner)
        for(y in 150 until 240)for(x in 125 until 170)pixels[y*w+x]=0xff333333.toInt()
        assertEquals(1,SpeechOwnership.adjacent(text,body,frames,pixels,w,h,false)!!.owner)
    }
    @Test fun AreaFallbackDoesNotOverrideDirectionAcrossDifferentRows() {
        val frames=listOf(Panel(.1f,.1f,.9f,.45f),Panel(.1f,.47f,.9f,.9f))
        val text=Panel(.3f,.40f,.6f,.52f)
        assertNull(SpeechOwnership.adjacent(text,text,frames,IntArray(200*300){-1},200,300,true))
    }
}
