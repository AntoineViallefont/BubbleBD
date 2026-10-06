package fr.bubblebd
import org.junit.Assert.*
import org.junit.Test
class MorePublisherSourcesTest {
    private val book=Book("x","","test.pdf","Ancien titre",isbn="9782205213164")
    private val html="""<h1>Titre vérifié</h1><dl class="album-caracs"><dt>ISBN/EAN :</dt><dd>9782205213164</dd>
        <dt>Date de parution :</dt><dd>22.05.2026</dd></dl><ul class="album-details__authors"><li><a>Auteur</a> (Scénario, Dessin)</li></ul>"""
    @Test fun exactEditionCanCorrectTitleAndRoles() {
        val d=MorePublisherSources.parse(html,"https://www.dargaud.com/bd/test",book)!!.details
        assertEquals("Titre vérifié",d.title);assertEquals("Auteur",d.artist);assertEquals("Auteur",d.writer)
    }
    @Test fun rejectsWrongIsbnAndForeignDomain() {
        assertNull(MorePublisherSources.parse(html,"https://www.dargaud.com/bd/test",book.copy(isbn="9782505075295")))
        assertNull(MorePublisherSources.parse(html,"https://dargaud.com.evil.example/bd/test",book))
    }

    @Test fun titleDiscoveryChecksKnownAuthorsVolumesAndLock() {
        val url="https://www.dargaud.com/bd/test-bda123"
        val unknown=book.copy(title="Titre vérifié",isbn="")
        assertNotNull(MorePublisherSources.parse(html,url,unknown,true))
        assertNull(MorePublisherSources.parse(html,url,unknown))
        assertNull(MorePublisherSources.parse(html,url,unknown.copy(writer="Autre auteur"),true))
        assertNull(MorePublisherSources.parse(html,url,unknown.copy(number="2"),true))
        assertNull(MorePublisherSources.parse(html,url,unknown.copy(title="Un homonyme"),true))
        assertNull(MorePublisherSources.parse(html,url,book.copy(metadataLocked=true),true))
    }

}
