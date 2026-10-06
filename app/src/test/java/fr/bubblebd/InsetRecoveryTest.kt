package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*

class InsetRecoveryTest {
    @Test fun framedInsetsAreRecoveredOnEverySide() {
        val w=600;val h=800
        val pixels=IntArray(w*h) {0xffffffff.toInt()}
        for(y in 100..400)for(x in 80..560)pixels[y*w+x]=0xffaaaaaa.toInt()
        for(y in 165..315)for(x in 55..220)pixels[y*w+x]=if(x==55 || x==220 || y==165 || y==315)0xff222222.toInt() else 0xffdddddd.toInt()
        val scene=Panel(80f/w,100f/h,560f/w,400f/h)
        val inset=Panel(55f/w,165f/h,220f/w,315f/h)
        for(transpose in listOf(false,true))for(mirror in listOf(false,true)) {
            val width=if(transpose)h else w;val height=if(transpose)w else h
            val transformed=IntArray(w*h)
            for(y in 0 until h)for(x in 0 until w) {
                val xx=if(transpose)y else x;val yy=if(transpose)x else y
                transformed[yy*width+if(mirror)width-1-xx else xx]=pixels[y*w+x]
            }
            fun convert(p:Panel):Panel {
                val q=if(transpose)Panel(p.top,p.left,p.bottom,p.right) else p
                return if(mirror)Panel(1f-q.right,q.top,1f-q.left,q.bottom) else q
            }
            val parent=convert(scene);val child=convert(inset)
            val found=InsetPanels.recover(listOf(parent),transformed,width,height)
            assertEquals("Orientation transpose=$transpose mirror=$mirror",2,found.size)
            val detected=found.last()
            assertTrue(detected.contains(child.left,child.top) && detected.contains(child.right,child.bottom))
            assertEquals("Main scene is preserved",parent.copy(focusExclusions=found.first().focusExclusions),found.first())
            assertEquals(listOf(detected.copy(readingOrderBounds=null)),found.first().focusExclusions)
        }
    }
    @Test fun anOpenShapeDoesNotBecomeAnInset() {
        val w=400;val h=600;val pixels=IntArray(w*h) {0xffffffff.toInt()}
        for(y in 100..330)for(x in 60..370)pixels[y*w+x]=0xff999999.toInt()
        // A balloon/figure silhouette has no pair of closed horizontal frame borders.
        for(y in 160..290)for(x in 40..140)if(x==40 || x==140)pixels[y*w+x]=0xff222222.toInt()
        val scene=Panel(.15f,100f/h,.925f,330f/h)
        assertEquals(listOf(scene),InsetPanels.recover(listOf(scene),pixels,w,h))
    }

    @Test fun aTextFilledWhiteCaptionDoesNotAddAPhysicalCase() {
        val w=600;val h=800;val pixels=IntArray(w*h){-1}
        for(y in 100..400)for(x in 80..560)pixels[y*w+x]=0xffaaaaaa.toInt()
        for(y in 165..315)for(x in 55..220)pixels[y*w+x]=
            if(x==55 || x==220 || y==165 || y==315)0xff222222.toInt() else -1
        val scene=Panel(80f/w,100f/h,560f/w,400f/h)
        val caption=Panel(65f/w,185f/h,210f/w,295f/h)
        assertEquals(listOf(scene),InsetPanels.recover(listOf(scene),pixels,w,h,listOf(caption)))
        // A small speech fragment inside the same white scene is insufficient
        // evidence to discard a real frame.
        val small=Panel(90f/w,195f/h,130f/w,215f/h)
        assertEquals(2,InsetPanels.recover(listOf(scene),pixels,w,h,listOf(small)).size)
    }
}
