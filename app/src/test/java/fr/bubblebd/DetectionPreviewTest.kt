package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.DataInputStream

class DetectionPreviewTest {
    @Test fun exportUserExamplesWhenAvailable() {
        val out=File("../docs/detection/private").apply {mkdirs()}
        for(i in 1..10) {
            val source=File("src/test/resources/private/example-$i.rgb")
            if(!source.exists())continue // Private user samples are never shipped or required by CI.
            val input=DataInputStream(source.inputStream())
            val w=input.readInt();val h=input.readInt()
            val pixels=IntArray(w*h) {(255 shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()}
            input.close()
            val panels=BookRules.orderPanels(PanelGeometry.detect(pixels,w,h),false)
            File(out,"example-$i.txt").writeText(panels.mapIndexed {j,p -> "${j+1}: ${p.left*w}, ${p.top*h}, ${p.right*w}, ${p.bottom*h}"}.joinToString("\n"))
            if(i<=6)assertEquals("Private example $i panel count",listOf(4,5,10,7,4,7)[i-1],panels.size)
            if(i==4) {
                val geometry=panels.map {it.readingOrderBounds ?: it}
                assertTrue("Case 4 is the upper central panel",geometry[3].left in .35f.. .40f && geometry[3].top<.50f)
                assertTrue("Case 5 is below case 4",geometry[4].left in .35f.. .40f && geometry[4].top>geometry[3].bottom)
                assertTrue("Tall right panel must be case 6",geometry[5].left>.60f && geometry[5].height>geometry[3].height)
            }
            if(i==1)assertTrue("Cross-frame lettering must remain inside the first reading frame",panels[0].bottom*h>=180)
            if(i==3)assertTrue("Outlying bottom-left balloon belongs to case 6",panels[5].left*w<15)
        }
    }
    @Test fun protrudingBalloonsDoNotMoveRightPanelBeforeCentralColumn() {
        val left=Panel(.05f,.10f,.30f,.90f)
        val top=Panel(.31f,.10f,.60f,.48f)
        val bottom=Panel(.31f,.52f,.60f,.90f)
        val right=Panel(.62f,.10f,.95f,.90f)
        // Reading rectangles overlap gutters because the speech balloons extend outside.
        val centralTop=Panel(.27f,.03f,.64f,.49f,top)
        val centralBottom=Panel(.26f,.48f,.64f,.92f,bottom)
        val tallRight=Panel(.58f,.04f,.99f,.94f,right)
        val input=listOf(tallRight,centralBottom,left,centralTop)
        assertEquals(listOf(left,centralTop,centralBottom,tallRight),BookRules.orderPanels(input,false))
        assertEquals(listOf(tallRight,centralTop,centralBottom,left),BookRules.orderPanels(input,true))
    }
    @Test fun groupingKeepsHighestIndividualZoom() {
        val panels=listOf(Panel(0f,0f,.3f,1f),Panel(.4f,0f,.7f,.45f))
        assertEquals(0..0,GuidedFrames.frame(panels,0,1000,1000,2000,1000).indices)
    }
    @Test fun lightAndBlackGuttersBothSplitPanels() {
        for(bg in listOf(0xff000000.toInt(),0xffffffff.toInt())) {
            val w=300;val h=400;val px=IntArray(w*h){bg}
            for(y in 20..179)for(x in 20..279)px[y*w+x]=0xffa05050.toInt()
            for(y in 220..379)for(x in 20..279)px[y*w+x]=0xffa05050.toInt()
            assertEquals(2,PanelGeometry.detect(px,w,h).size)
        }
    }
}
