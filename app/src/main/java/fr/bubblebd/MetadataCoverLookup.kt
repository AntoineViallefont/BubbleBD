package fr.bubblebd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Only a bounded cover thumbnail leaves the device, after a completed negative search. */
object MetadataCoverLookup {
    suspend fun fetch(context:Context,book:Book,pause:suspend ()->Unit={delay(11_000)},lookup:suspend (Book,Map<String,String>)->String={b,clues->MetadataInformation.fetch(b,clues=clues)}):String {
        val first=lookup(book,emptyMap())
        if(JSONObject(first).optBoolean("matched"))return first
        val text=CoverRecognition.read(context,book.cover).joinToString(" · ").replace(Regex("\\s+")," ").take(1800)
        val image=thumbnail(book.cover)
        if(text.isBlank() && image==null)return first
        val clues=buildMap {
            if(text.isNotBlank())put("coverText",text)
            if(image!=null)put("coverImage",image)
        }
        // The first negative may have used the common service's 10-second search slot.
        pause()
        return lookup(book,clues)
    }
    suspend fun thumbnail(path:String):String?=withContext(Dispatchers.IO) {
        if(!File(path).isFile)return@withContext null
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeFile(path,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0)return@withContext null
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
        val original=BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample}) ?: return@withContext null
        val scale=minOf(1f,768f/maxOf(original.width,original.height))
        val bitmap=if(scale<1f)Bitmap.createScaledBitmap(original,(original.width*scale).toInt().coerceAtLeast(1),(original.height*scale).toInt().coerceAtLeast(1),true) else original
        try {
            for(quality in listOf(80,60,40)) {
                val bytes=ByteArrayOutputStream().use {out ->bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out);out.toByteArray()}
                if(bytes.size<=160_000)return@withContext "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)
            }
            null
        } finally {if(bitmap!==original)bitmap.recycle();original.recycle()}
    }
}
