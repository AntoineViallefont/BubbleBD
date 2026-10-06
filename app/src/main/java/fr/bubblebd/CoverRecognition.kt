package fr.bubblebd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads the private first-page thumbnail offline. Only bibliographic queries leave the device. */
object CoverRecognition {
    suspend fun read(context:Context,path:String):List<String> = withContext(Dispatchers.IO) {
        val file=File(path)
        if(!file.isFile)return@withContext emptyList()
        val bitmap=BitmapFactory.decodeFile(path) ?: return@withContext emptyList()
        try {readBitmap(context,bitmap)} finally {bitmap.recycle()}
    }
    @Synchronized fun readBitmap(context:Context,bitmap:Bitmap):List<String> {
        val home=File(context.filesDir,"ocr")
        val model=File(home,"tessdata/fra.traineddata")
        if(!model.isFile) {
            model.parentFile!!.mkdirs()
            val staging=File(model.parentFile,"fra.part")
            context.assets.open("ocr/fra.traineddata").use {input ->staging.outputStream().use {input.copyTo(it)}}
            check(staging.renameTo(model))
        }
        val api=TessBaseAPI()
        try {
            check(api.init(home.absolutePath,"fra"))
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            api.setImage(bitmap)
            return api.utF8Text.orEmpty().lines().map {it.trim()}.filter {it.length in 3..160}.distinct().take(24)
        } finally {api.recycle()}
    }
}
