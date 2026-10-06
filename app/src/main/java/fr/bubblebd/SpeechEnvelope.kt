package fr.bubblebd

import kotlin.math.*

/** A thin tail's white component and text model provide complementary limits. */
internal object SpeechEnvelope {
    /** Give short speech a line of breathing room, without borrowing another frame. */
    fun readingMargin(body:Panel,text:Panel,owner:Int,cores:List<Panel>,w:Int,h:Int):Panel {
        if(body.width>.18f || body.height>.12f || body.width/body.height !in .5f..2f)return body
        val margin=min(.025f,min(text.height,body.height*.4f))
        if(margin<=.006f)return body
        val horizontal=min(.015f,margin*.5f*h/w)
        val expanded=body.copy(left=max(0f,body.left-horizontal),right=min(1f,body.right+horizontal),top=max(0f,body.top-margin),bottom=min(1f,body.bottom+margin))
        fun overlap(a:Panel,b:Panel)=max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        if(cores.indices.any {it!=owner && overlap(expanded,cores[it])>overlap(body,cores[it])+.00001f})return body
        return expanded
    }
    fun complete(body:Panel,text:Panel,pixels:IntArray,w:Int,h:Int):Panel {
        if(!SpeechTail.thinLobe(body,pixels,w,h))return body
        val shared=max(0f,min(body.right,text.right)-max(body.left,text.left))*max(0f,min(body.bottom,text.bottom)-max(body.top,text.top))
        val merged=Panel(min(body.left,text.left),min(body.top,text.top),max(body.right,text.right),max(body.bottom,text.bottom))
        if(shared<text.width*text.height*.60f || merged.width*merged.height>body.width*body.height*1.5f)return body
        // Thin tips have a much larger bounding box than the compact body;
        // leave a modest reading margin instead of cutting at its white interior.
        val margin=min(.03f,body.width*.16f)
        return merged.copy(left=max(0f,merged.left-margin),right=min(1f,merged.right+margin))
    }
}
