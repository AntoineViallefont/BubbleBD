package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class BibliographicIdentityTest {
    private val b=Book("lookup","content://local","Saga - T02 - Album.cbz","Saga - T02 - Album",page=12,started=true)
    @Test fun titleAndFilenameExposeVolumeWithoutChangingLibraryData() {
        for(title in listOf("Saga - T02 - Album","Saga Tome 2 : Album","Saga Vol. 02", "Saga n°2")) {
            val found=BibliographicIdentity.forLookup(b.copy(title=title))
            assertEquals("2",found.number);assertEquals("Saga",found.series)
            assertEquals(title,found.title);assertEquals(12,found.page);assertTrue(found.started)
        }
        assertEquals("2",BibliographicIdentity.forLookup(b.copy(title="Album")).number)
    }
    @Test fun structuredVolumeWinsAndDatesOrRangesAreNotInventedTomes() {
        val conflict=b.copy(series="Saga",number="3")
        assertEquals(conflict,BibliographicIdentity.forLookup(conflict))
        for(title in listOf("1984","XHG-C3","Saga T01 à T03","Saga 2023")) {
            val input=b.copy(title=title,filename="$title.pdf")
            assertEquals(input,BibliographicIdentity.forLookup(input))
        }
        assertEquals("",BibliographicIdentity.forLookup(b.copy(title="Saga INT",filename="Saga INT.pdf")).number)
    }
    @Test fun accentArticleAndEditionVariantsMatchWithoutRemovingRealWords() {
        assertTrue(BibliographicIdentity.titleMatches("L’île de minuit", "Ile de minuit"))
        assertTrue(BibliographicIdentity.titleMatches("Soleil froid INT", "Soleil Froid - Intégrale (Nouvelle Édition)"))
        assertFalse(BibliographicIdentity.titleMatches("La Guerre", "La Guerre des amants"))
        assertFalse(BibliographicIdentity.titleMatches("Saga Tome 1", "Saga Tome 2"))
    }
}
