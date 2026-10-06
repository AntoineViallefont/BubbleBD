package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NewFeaturesTest {
    private fun book(id:String,name:String,parent:String="folder")=Book(id,"",name,name,parentFolder=parent,parentName="Sillage")
    @Test fun infersVolumesWithinSameFolder() {
        val result=SeriesInference.infer(listOf(book("a","Sillage 001.cbz"),book("b","Sillage 002.cbz"),book("c","Sillage 003.cbz","other")))
        assertEquals(listOf("1","2",""),result.map {it.number})
        assertEquals("Sillage",result.first().series)
    }
    @Test fun numbersOnlyUseParentAndMetadataIsPreserved() {
        val result=SeriesInference.infer(listOf(book("a","01.cbz"),book("b","02.cbz"),book("c","03.cbz").copy(metadataEdited=true,series="Corrected")))
        assertEquals("Sillage",result[0].series);assertEquals("Corrected",result[2].series)
        assertTrue(SeriesInference.infer(listOf(book("d","Saga 2020.cbz"),book("e","Saga 2021.cbz"))).all {it.series.isBlank()})
    }
    private val panels=listOf(Panel(0f,0f,.3f,1f),Panel(.35f,0f,.65f,1f),Panel(.7f,0f,1f,1f))
    @Test fun landscapeGroupsWithoutReducingZoomAndHonorsAlreadySeen() {
        val frame=GuidedFrames.frame(panels,0,1000,1000,2000,1000)
        assertEquals(0..2,frame.indices);assertEquals(1f,frame.scale,0f)
        assertEquals("Cases 7, 8 et 9/9",GuidedFrames.label(6..8,9))
        assertEquals(1..2,GuidedFrames.frame(panels,1,1000,1000,2000,1000,minimum=1).indices)
        assertEquals(0..0,GuidedFrames.frame(panels,0,1000,1000,500,1000).indices)
    }
    @Test fun cloudOfflineCopyIsLocalAndRecentMeansAscending() {
        val remote=book("a","a.cbz").copy(cloud=true,lastRead=20)
        assertFalse(remote.local);assertTrue(remote.copy(pinned=true).local)
        assertEquals("a",BookRules.sort(listOf(remote,book("b","b.cbz").copy(lastRead=10)),"Dernière ouverture",false).first().id)
    }
    @Test fun zipFetchesNaturalFirstPageWithoutDownloadingWholeArchive()=runBlocking {
        val output=ByteArrayOutputStream()
        val first="first page".toByteArray()
        ZipOutputStream(output).use {zip ->
            listOf("10.jpg" to ByteArray(300000).also {java.util.Random(42).nextBytes(it)},"2.jpg" to first).forEach {(name,data)->
                zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()
            }
        }
        val archive=output.toByteArray();var fetched=0
        val cover=RemoteZipCover.read(archive.size.toLong()) {start,length->
            fetched+=length;archive.copyOfRange(start.toInt(),start.toInt()+length)
        }
        assertArrayEquals(first,cover);assertTrue(fetched<archive.size/2)
    }
}
