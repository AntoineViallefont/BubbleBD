package fr.bubblebd

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Repository.demoBooks():List<Book> = withContext(Dispatchers.IO) {
        val covers=File(context.filesDir,"covers").apply {mkdirs()}
        val dir=File(context.filesDir,"demo").apply { mkdirs() }
        val titles=listOf("Les Rivages bleus","La Ville suspendue","Dernier signal","Les Jours sauvages")
        listOf("rivages","ville","signal","jours").mapIndexed { i,name ->
            val cover=File(covers,"demo-$name.png"); androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("demo/$name.png").use { input -> cover.outputStream().use { input.copyTo(it) } }
            val cbz=File(dir,"$name.cbz")
            if(!cbz.exists()) java.util.zip.ZipOutputStream(cbz.outputStream()).use { zip ->
                listOf("$name.png","page.png","page.png","page.png","page.png").forEachIndexed { p,asset -> zip.putNextEntry(java.util.zip.ZipEntry("${p+1}.png")); androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("demo/$asset").use { it.copyTo(zip) }; zip.closeEntry() }
            }
            Book("demo-$name",cbz.toURI().toString(),cbz.name,titles[i],titles[i],"1",genre="Démonstration",pages=5,page=if(i==0) 1 else 0,started=i==0,lastRead=if(i==0) System.currentTimeMillis() else 0,added=System.currentTimeMillis()-i*1000,cover=cover.path,demo=true)
        }
    }
