package fr.bubblebd
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class PublicComicSourcesNetworkTest {
    @Test fun kanaIdentifiesSmokeFromFilenameAndUsesBookPublicationDate()=runBlocking {
        val b=Book("network-smoke","","Smoke T02.pdf","Smoke T02")
        val result=PublicComicSources.lookup(b,mutableMapOf())!!
        assertEquals("Smoke",result.details.title)
        assertEquals("2026-01-30",result.details.date)
        assertEquals("9782505075295",result.details.isbn.replace("-",""))
        assertEquals("Oh! Great",result.details.artist)
        assertTrue(result.details.synopsis.isNotBlank())
    }
    @Test fun glenatIdentifiesExactIsbn()=runBlocking {
        val b=Book("network-glenat","","Une nuit avec toi.pdf","Une nuit avec toi",publisher="Glénat",isbn="9782344050743")
        val result=PublicComicSources.lookup(b,mutableMapOf())!!
        assertEquals("Une nuit avec toi",result.details.title)
        assertEquals("2023-09-20",result.details.date)
        assertEquals("Glénat",result.details.publisher)
        assertTrue(result.details.synopsis.contains("Brune"))
    }
    @Test fun lombardFindsTheEditionUsingItsIsbn()=runBlocking {
        val b=Book("network-lombard","","No limits.pdf","No limits",publisher="Le Lombard",isbn="9782803624669")
        val result=MorePublisherSources.lookup(b,mutableMapOf())!!
        assertEquals("No limits",result.details.title);assertEquals("Derib",result.details.writer)
        assertEquals("Derib",result.details.artist);assertEquals("2008-10-17",result.details.date)
        assertTrue(result.details.synopsis.isNotBlank())
    }
    @Test fun dargaudReadsTheKnownSourceAfterCheckingTheIsbn()=runBlocking {
        val b=Book("network-dargaud","","Umami.pdf","Umami",publisher="Dargaud",isbn="9782205213164",
            metadataSource="https://www.dargaud.com/bd/umami-bda5593400")
        val result=MorePublisherSources.lookup(b,mutableMapOf())!!
        assertEquals("Umami",result.details.title);assertEquals("OHM",result.details.writer)
        assertEquals("2026-05-22",result.details.date);assertTrue(result.details.synopsis.isNotBlank())
    }

    @Test fun dargaudDiscoversSousTerreAndItsCompleteSummaryWithoutUrlOrIsbn()=runBlocking {
        val b=Book("network-sous-terre","","Sous Terre.pdf","Sous Terre")
        val result=MorePublisherSources.lookup(b,mutableMapOf())!!
        assertEquals("Sous Terre",result.details.title)
        assertEquals("9782205088250",result.details.isbn)
        assertEquals("Mathieu Burniat",result.details.writer)
        assertEquals("Mathieu Burniat",result.details.artist)
        assertEquals("2021-03-19",result.details.date)
        assertTrue(result.details.synopsis.contains("Suzanne et Tom"))
        assertTrue(result.details.synopsis.contains("monde invisible"))
        assertEquals(listOf("https://www.dargaud.com/bd/sous-terre-bda5375020"),result.urls)
        assertNull(MorePublisherSources.lookup(b.copy(writer="Autre auteur"),mutableMapOf()))
        assertNull(MorePublisherSources.lookup(b.copy(isbn="9782505075295"),mutableMapOf()))
    }
    @Test fun discoveryRequiresUniqueCompleteResultsAndSafeAlbumLinks() {
        val one="""{"album":{"total":1,"items":[{"url":"/bd/test-bda123"}]}}"""
        assertEquals(listOf("https://www.dargaud.com/bd/test-bda123"),MorePublisherSources.dargaudLinks(one))
        assertTrue(MorePublisherSources.dargaudLinks(one.replace("\"total\":1","\"total\":4")).isEmpty())
        assertTrue(MorePublisherSources.dargaudLinks(one.replace("/bd/test-bda123","https://evil.example/bd/test-bda123")).isEmpty())
    }
    @Test fun ambiguousEditionsAreNotChosenEvenWhenBothTitlesMatch()=kotlinx.coroutines.runBlocking {
        val b=Book("ambiguous","","Titre vérifié.pdf","Titre vérifié")
        val html="""<h1>Titre vérifié</h1><dl class="album-caracs"><dt>ISBN/EAN :</dt><dd>9782205213164</dd></dl>"""
        val first="https://www.dargaud.com/bd/test-bda123"
        val second="https://www.dargaud.com/bd/test-bda456"
        val search="https://www.dargaud.com/autocomplete/search?q="+java.net.URLEncoder.encode(b.title,"UTF-8")
        val cache=mutableMapOf(search to """{"album":{"total":2,"items":[{"url":"/bd/test-bda123"},{"url":"/bd/test-bda456"}]}}""",
            first to html,second to html.replace("9782205213164","9782205088250"))
        assertNull(MorePublisherSources.lookup(b,cache))
        val exact=MorePublisherSources.lookup(b.copy(isbn="9782205088250"),cache)!!
        assertEquals(listOf(second),exact.urls)
    }
}
