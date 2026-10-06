package fr.bubblebd

import android.graphics.Bitmap

/** En lecture par cases, publier l'image et son découpage ensemble. */
internal suspend fun loadReaderPage(
    guided:Boolean,
    cached:List<Panel>?,
    load:suspend ()->Bitmap,
    detect:suspend (Bitmap)->List<Panel>,
    display:(Bitmap,List<Panel>)->Unit
):List<Panel> {
    val image=load()
    if(cached!=null) {
        display(image,cached)
        return cached
    }
    // La lecture en page entière reste disponible avant la fin de l'analyse.
    if(!guided) display(image,emptyList())
    val panels=detect(image)
    display(image,panels)
    return panels
}
