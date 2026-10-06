package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class WebBibliographicSourcesTest {
    private val book=Book("1","","Album-test.cbz","Album-test")
    private val url="https://fr.wikipedia.org/wiki/Album-test"
    private fun page(albums:String="<li><i>Album-test</i> (1995) ISBN 9-0564-6-002-1</li>")="""
        <h1 id="firstHeading">Album-test</h1><div class="mw-parser-output">
        <section><p><b>Album-test</b> est une bande dessinée belge, dessinée et scénarisée par Alice Martin.
        C'est un recueil de <a>science-fiction</a>.</p></section>
        <section><h2>Album</h2><ul>$albums</ul></section>
        <section><h2>Anecdotes</h2><ul><li>L'éditeur est <i>Éditeur test</i>, installé en France.</li>
        <li>Prépublication en 1983.</li></ul></section></div>
    """.trimIndent()
    @Test fun oneShotGetsAlbumFieldsWithoutConfusingPrepublicationAndAlbumYear() {
        val d=WebBibliographicSources.parseWikipedia(page(),url,book)!!.details
        assertEquals("Alice Martin",d.writer);assertEquals(d.writer,d.artist)
        assertEquals("1995",d.date);assertEquals("Éditeur test",d.publisher)
        assertEquals("9056460021",d.isbn);assertEquals("science-fiction",d.genre)
        assertTrue(d.synopsis.startsWith("Album-test"))
    }
    @Test fun equivalentIsbn10And13AreTheSameEdition() {
        assertNotNull(WebBibliographicSources.parseWikipedia(page(),url,book.copy(isbn="9789056460020")))
    }
    @Test fun seriesKeepsGeneralRolesButNeverTransfersAnotherAlbumDateIsbnOrSynopsis() {
        val html=page("<li>Premier album (1995) ISBN 9-0564-6-002-1</li><li>Deuxième album (1997)</li>")
        val d=WebBibliographicSources.parseWikipedia(html,url,book.copy(series="Album-test",number="2"))!!.details
        assertEquals("Alice Martin",d.writer)
        assertEquals("",d.date);assertEquals("",d.isbn);assertEquals("",d.publisher);assertEquals("",d.synopsis)
    }
    @Test fun knownIdentityAndUnrelatedOrDisambiguationPagesRemainProtected() {
        assertNull(WebBibliographicSources.parseWikipedia(page(),url,book.copy(writer="Bob Dupont")))
        assertNull(WebBibliographicSources.parseWikipedia(page(),url,book.copy(isbn="9782491467029")))
        assertNull(WebBibliographicSources.parseWikipedia(page(),url,book.copy(title="Autre BD",filename="Autre BD.pdf")))
        assertNull(WebBibliographicSources.parseWikipedia(page()+"<div class=homonymie></div>",url,book))
        assertNull(WebBibliographicSources.parseWikipedia(page(),"https://user@fr.wikipedia.org/wiki/Album-test",book))
    }
    @Test fun encyclopediaEditionDoesNotOverrideAnExistingFieldOrReadingProgress() {
        val before=book.copy(date="2000",publisher="Correction",page=8,started=true)
        val result=WebBibliographicSources.parseWikipedia(page(),url,before)!!
        val after=BibliographicSources.merge(before,result)
        assertEquals("2000",after.date);assertEquals("Correction",after.publisher);assertEquals(8,after.page)
        assertEquals(url,after.metadataSource)
    }
}
