package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class SpeechTypographyTest {
    @Test fun distinctFontSizesSplitAConnectedWhiteBodyWithoutJoiningItsFrames() {
        val w=300;val h=300;val pixels=IntArray(w*h){-1}
        fun glyph(x:Int,y:Int,width:Int,height:Int) {
            for(yy in y until y+height)for(xx in x until x+width)pixels[yy*w+xx]=0xff111111.toInt()
        }
        for(i in 0..7)glyph(85+i*7,110,3,6)
        glyph(169,106,10,22);glyph(185,106,5,22)
        val body=Panel(.25f,.30f,.70f,.55f)
        val cores=listOf(Panel(.10f,.2f,.50f,.70f),Panel(.50f,.2f,.9f,.70f))
        val text=Panel(.27f,.33f,.65f,.45f)
        val parts=SpeechTypography.split(body,listOf(text),cores,pixels,w,h)!!
        assertEquals(setOf(0,1),parts.map {it.owner}.toSet())
        assertTrue(parts.first {it.owner==0}.body.right<parts.first {it.owner==1}.text.left)
        // Equal-sized conversation text must keep the established ownership rules.
        for(y in 106 until 128)for(x in 169 until 190)pixels[y*w+x]=-1
        for(i in 0..7)glyph(165+i*5,110,2,6)
        assertNull(SpeechTypography.split(body,listOf(text),cores,pixels,w,h))
    }

    @Test fun approvedReactionAndSpeechAreDistinctOnThePreservedRaster() {
        val source=File("src/test/resources/private/october-replay-56.rgb")
        if(!source.exists())return
        val input=DataInputStream(source.inputStream());val w=input.readInt();val h=input.readInt()
        val pixels=IntArray(w*h){(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()};input.close()
        fun box(l:Float,t:Float,r:Float,b:Float)=Panel(l/w,t/h,r/w,b/h)
        val frames=listOf(box(182f,150f,280f,416f),box(280f,150f,361f,416f))
        val parts=SpeechTypography.split(box(214f,146f,363f,231f),listOf(box(232f,166f,336f,204f)),frames,pixels,w,h)!!
        assertEquals(0,parts.first {it.text.left*w<280}.owner)
        assertEquals(1,parts.first {it.text.left*w>280}.owner)
    }
}
