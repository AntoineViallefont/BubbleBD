package fr.bubblebd

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

/** Explicit opt-in integration check: public catalogue only, no account or personal library. */
class MetadataLiveTest {
    @Test fun firstPageTitleAndAuthorsDisambiguateTheComicFromTheNovel()=runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("metadataLive")=="true")
        val b=Book("qa","","Sharko et Henebelle - T03.pdf","Sharko et Henebelle",series="Sharko et Henebelle",number="3")
        val result=BibliographicSources.fromFirstPage(b,listOf("ATOMKA","SYLVAIN RUNBERG","LUC BRAHY"),mutableMapOf())!!
        assertEquals("978-2-491467-02-9",result.details.isbn)
        assertEquals("Sylvain Runberg",result.details.writer)
        assertEquals("Luc Brahy",result.details.artist)
        assertEquals("Philéas",result.details.publisher)
    }
    @Test fun publicCataloguesFindExactTome()=runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("metadataLive")=="true")
        val b=Book("qa","","","L'île de minuit",series="L'île de minuit",number="02")
        val result=BibliographicSources.bnf(b,mutableMapOf())!!
        assertEquals("978-2-8085-1089-9",result.details.isbn)
        assertEquals("Lylian",result.details.writer)
        val publisher=BibliographicSources.dupuis(BibliographicSources.merge(b,result),mutableMapOf())!!
        assertEquals("2026-01-16",publisher.details.date)
        assertEquals("Action / aventure",publisher.details.genre)
    }
}
