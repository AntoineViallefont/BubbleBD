package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class PublisherSourcesTest {
    private fun html()=javaClass.getResource("/metadata/delcourt-integral.html")!!.readText()
    @Test fun publicPublisherPageProvidesSummaryRolesAndPreciseDate() {
        val result=PublisherSources.parseDelcourt(html(),"978-2-413-03869-6")!!
        assertEquals("Jean-Pierre Pécau",result.details.writer);assertEquals("Damien",result.details.artist)
        assertEquals("2023-03-22",result.details.date)
        assertEquals("Soleil Froid",result.details.series)
        assertTrue(result.details.synopsis.contains("robot de portage"))
        assertEquals("Bandes dessinées tout public",result.details.genre)
        assertEquals("Delcourt",BibliographicSources.sourceLabel(result.urls.single()))
        assertNotNull(PublisherSources.parseDelcourt(html(),"2413038698"))
    }
    @Test fun otherEditionAndUnsafePageCannotReplaceKnownIsbn() {
        assertNull(PublisherSources.parseDelcourt(html(),"9782413091455"))
        assertNull(PublisherSources.parseDelcourt(html().replace("https://www.editions-delcourt.fr/bd/","https://attacker.example/bd/"),"9782413038696"))
        assertNull(PublisherSources.parseDelcourt(html(),""))
    }
    @Test fun discoveryUsesAlbumLinksInTheSeriesAndIgnoresHomepagePromotions() {
        val input=Book("i","","Soleil froid INT.pdf","Soleil froid INT")
        val links="""<a href="/bd/series/serie-soleil-froid/album-soleil-froid-integrale  ">Intégrale</a>
            <a href="/bd/series/serie-une-autre/album-une-autre">Promotion</a>
            <a href="https://attacker.example/bd/series/serie-soleil-froid/album-test">Copie</a>"""
        assertEquals(listOf("https://www.editions-delcourt.fr/bd/series/serie-soleil-froid/album-soleil-froid-integrale"),PublisherSources.delcourtLinks(links,input))
    }
}
