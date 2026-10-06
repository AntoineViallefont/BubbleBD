package fr.bubblebd
import org.junit.Test
import org.junit.Assert.*

class SheetRulesTest {
    @Test fun frenchDateRoundTripsWithoutInventingMissingDays() {
        assertEquals("27/09/2026",PublicationDate.display("2026-09-27"))
        assertEquals("2026-09-27",PublicationDate.storage("27/09/2026"))
        assertNull(PublicationDate.storage("31/02/2026"))
        assertEquals("2024-02-29",PublicationDate.storage("29/02/2024"))
        assertEquals("2026",PublicationDate.storage("2026"))
        assertEquals("09/2026",PublicationDate.display("2026-09"))
        assertEquals("2026-09",PublicationDate.storage("09/2026"))
    }
    @Test fun bdthequeAlwaysHasAnActualWebsiteDestination() {
        assertEquals("https://www.bdtheque.com/",BdTheque.browserUrl(""))
        val direct="https://www.bdtheque.com/series/18390/nota-bene"
        assertEquals(direct,BdTheque.browserUrl(" $direct "))
        assertEquals("https://www.bdtheque.com/",BdTheque.browserUrl("javascript:alert(1)"))
    }
    @Test fun searchRequiresUniqueExactTitleAndSameSiteForm() {
        val book=Book("1","","","Title",series="Saga")
        val one="""<a href="/series/12/saga">Saga</a>"""
        assertEquals("https://www.bdtheque.com/series/12/saga",BdTheque.uniqueResult(one,book,"https://www.bdtheque.com/"))
        assertNull(BdTheque.uniqueResult(one+"""<a href="/series/13/saga">Saga</a>""",book,"https://www.bdtheque.com/"))
        assertNull(BdTheque.uniqueResult("""<a href="/series/12/saga">Autre saga</a>""",book,"https://www.bdtheque.com/"))
        assertNotNull(BdTheque.discoverSearchForm("""<form action="/recherche" method="get"><input name="q" type="search"></form>"""))
        assertNull(BdTheque.discoverSearchForm("""<form action="https://example.com/"><input name="q" type="search"></form>"""))
    }
}
