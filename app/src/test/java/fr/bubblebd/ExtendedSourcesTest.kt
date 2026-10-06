package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*

class ExtendedSourcesTest {
    @Test fun completeAlbumWithMissingRatingNeedsOnlyTheRatingPass() {
        val full=book.copy(artist="Alice Martin",publisher="Éditeur",date="2024",isbn="9782808510899",genre="Aventure",synopsis="Résumé conservé")
        assertTrue(BibliographicSources.complete(full))
        assertTrue(ExtendedSources.needsLookup(full))
        val result=BibliographicSources.Result(BdTheque.Details(title="Titre remplacé",synopsis="Autre résumé",rating=4.2,ratingScale=5.0,reviewCount=12),listOf("https://books.google.com/books?id=test"))
        val rated=ExtendedSources.mergeRating(full,result)
        assertEquals(full,rated.copy(rating=null,ratingScale=null,reviewCount=null,ratingSource=""))
        assertEquals(4.2,rated.rating!!,0.0)
        assertEquals(12,rated.reviewCount)
        assertFalse(ExtendedSources.needsLookup(rated))
        assertEquals(rated,ExtendedSources.mergeRating(rated,result.copy(details=result.details.copy(rating=1.0))))
        assertEquals(full,ExtendedSources.mergeRating(full,result.copy(details=result.details.copy(reviewCount=null))))
    }
    private val book=Book("1","","Saga T02 Les îles.cbz","Les îles",series="Saga",number="2",writer="Alice Martin",pages=48)
    private fun candidate(n:String="9782808510899",pages:Int=48)=ExtendedSources.Candidate("Les îles","Alice Martin",n,pages,BdTheque.Details(),"https://books.google.com/books?id=$n")
    @Test fun conflictingIsbnCannotBeReplacedByTitleAndAuthor() {
        assertNull(ExtendedSources.select(listOf(candidate()),book.copy(isbn="9782491467029"),listOf("Les îles")))
    }
    @Test fun editionsOfSameTitleAndAuthorsNoLongerPreventAResult() {
        val first=candidate().copy(details=BdTheque.Details(date="2024",synopsis="Résumé fictif."))
        val second=candidate("9782491467029").copy(details=BdTheque.Details(date="2025"))
        assertEquals(first,ExtendedSources.select(listOf(second,first),book,listOf("Les îles")))
        assertEquals(first,ExtendedSources.select(listOf(first,second),book,listOf("Les îles")))
        assertNotNull(ExtendedSources.select(listOf(candidate()),book,listOf("Les îles")))
    }
    @Test fun homonymsWithDifferentAuthorsRemainAmbiguous() {
        val other=candidate("9782491467029").copy(authors="Alice Martin, Bob Durand")
        assertNull(ExtendedSources.select(listOf(candidate(),other),book,listOf("Les îles")))
    }
    @Test fun pageCountAloneNeverIdentifiesAnAlbum() {
        assertNull(ExtendedSources.select(listOf(candidate().copy(authors="Unknown")),book,emptyList()))
        assertNull(ExtendedSources.select(listOf(candidate().copy(title="Autre tome")),book,listOf("Autre tome")))
    }
    @Test fun coverTitleAuthorAndPagesIdentifyGenericFilenameButRejectConflictingTome() {
        val generic=book.copy(title="Saga",filename="Saga T02.cbz")
        assertNotNull(ExtendedSources.select(listOf(candidate()),generic,listOf("Les îles","Alice Martin")))
        assertNull(ExtendedSources.select(listOf(candidate().copy(title="Saga tome 3")),generic,listOf("Saga tome 3","Alice Martin")))
    }
    @Test fun markedReadHandlesUnknownPageCount() {
        assertEquals(100,book.copy(pages=0,readingState="read").progress)
        assertEquals("Lu",book.copy(readingState="read").status)
        assertEquals("À lire",book.copy(readingState="to_read",started=false).status)
    }
}
