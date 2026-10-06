package fr.bubblebd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** One additional image; its analysis never delays full-page display. */
internal class ReaderPrefetch<I,F>(
    private val scope:CoroutineScope,
    private val load:suspend (Int)->I,
    private val detect:suspend (Int,I)->F
) {
    internal class Prepared<I,F>(private val image:Deferred<I?>,private val frames:Deferred<F?>) {
        suspend fun image():I?=image.await()
        suspend fun frames():F?=frames.await()
        fun cancel() {frames.cancel();image.cancel()}
    }
    private var target:Int?=null
    private var pending:Prepared<I,F>?=null
    fun prepare(page:Int) {
        if(target==page && pending!=null)return
        cancel();target=page
        val image=scope.async {
            try {load(page)} catch(e:CancellationException) {throw e} catch(_:Exception) {null}
        }
        val frames=scope.async {
            try {image.await()?.let {detect(page,it)}} catch(e:CancellationException) {throw e} catch(_:Exception) {null}
        }
        pending=Prepared(image,frames)
    }
    fun cancelUnless(page:Int) {if(target!=page)cancel()}
    fun cancel() {pending?.cancel();pending=null;target=null}
    /** The caller owns the transferred jobs and must cancel them when leaving. */
    fun take(page:Int):Prepared<I,F>? {
        cancelUnless(page)
        val prepared=pending
        pending=null;target=null
        return prepared
    }
    companion object {
        fun nextPage(page:Int,pages:Int,visible:List<Int>?,cases:Int,direction:Int):Int? {
            val near=visible.isNullOrEmpty() || cases<=0 ||
                if(direction>0)visible.max()>=cases-maxOf(3,(cases+1)/2)
                else visible.min()<maxOf(3,(cases+1)/2)
            val next=page+if(direction>0)1 else -1
            return next.takeIf {near && it in 0 until pages}
        }
    }
}
