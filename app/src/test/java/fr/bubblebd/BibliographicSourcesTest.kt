package fr.bubblebd

import org.junit.Test
import org.junit.Assert.*

class BibliographicSourcesTest {
    private val b=Book("test","","L'île de minuit T02.cbz","L'île de minuit",series="L'île de minuit",number="02")
    private fun entries()=BibliographicSources.parseBnf(javaClass.getResource("/metadata/bnf-ile-minuit.xml")!!.readText())
    @Test fun bnfUsesSeriesAndVolumeAndExplicitAuthorRoles() {
        val entries=entries();assertEquals(3,entries.size)
        val result=BibliographicSources.selectBnf(entries,b)!!
        assertEquals("978-2-8085-1089-9",result.details.isbn)
        assertEquals("Lylian",result.details.writer)
        assertEquals("Nicolas Grebil",result.details.artist)
        assertEquals("2026",result.details.date)
        assertEquals("La femme aux singes",result.details.title)
        val absent=BibliographicSources.selectBnf(entries,b.copy(number="3"))!!
        assertEquals("",absent.details.date);assertEquals("",absent.details.isbn)
    }
    @Test fun sameAlbumEditionsUseStableReferenceRegardlessOfResultOrder() {
        val entries=entries();val album=entries.last()
        val second=album.copy(url=album.url+"x",details=album.details.copy(isbn="9782808510806",date="2027"))
        val result=BibliographicSources.selectBnf(entries+second,b)!!
        assertEquals(album.details.isbn,result.details.isbn)
        assertEquals(album.details.isbn,BibliographicSources.selectBnf((entries+second).reversed(),b)!!.details.isbn)
        assertEquals("2026",result.details.date)
        assertEquals(album.details.isbn,BibliographicSources.selectBnf(entries+second,b.copy(isbn=album.details.isbn))!!.details.isbn)
        assertEquals(second.details.isbn,BibliographicSources.selectBnf(entries+second,b.copy(isbn=second.details.isbn))!!.details.isbn)
    }
    @Test fun conflictingAuthorsDoNotUseAnArbitraryEdition() {
        val entries=entries();val album=entries.last()
        val other=album.copy(url=album.url+"x",details=album.details.copy(isbn="9782808510806",writer="Autre auteur"))
        val result=BibliographicSources.selectBnf(entries+other,b)!!
        assertEquals("",result.details.isbn)
        val oneShot=b.copy(title=album.details.title,series="",number="")
        assertNull(BibliographicSources.selectBnf(listOf(album,other),oneShot))
        assertNull(BibliographicSources.selectBnf(listOf(album.copy(details=album.details.copy(artist="",writer="")),other.copy(details=other.details.copy(artist="",writer=""))),oneShot))
    }
    @Test fun publicationIsNotConfusedWithPrinterAndDescriptionIsPreserved() {
        val xml="""<records><record><controlfield tag="003">http://catalogue.bnf.fr/ark:/12148/fictional</controlfield>
            <datafield tag="200"><subfield code="a">Album fictif</subfield></datafield>
            <datafield tag="214" ind2="3"><subfield code="c">Imprimeur fictif</subfield><subfield code="d">2025</subfield></datafield>
            <datafield tag="214" ind2="0"><subfield code="c">Éditeur fictif</subfield><subfield code="d">DL 2024</subfield></datafield>
            <datafield tag="608"><subfield code="a">Bandes dessinées</subfield></datafield>
            <datafield tag="330"><subfield code="a">Résumé fictif disponible dans la notice.</subfield></datafield></record></records>"""
        val result=BibliographicSources.parseBnf(xml).single().details
        assertEquals("Éditeur fictif",result.publisher);assertEquals("2024",result.date)
        assertEquals("Bandes dessinées",result.genre);assertEquals("Résumé fictif disponible dans la notice.",result.synopsis)
    }
    @Test fun verifiedAlbumRefreshesFieldsAndPreservesExistingReviews() {
        val edited=b.copy(writer="Mon auteur",date="2025",rating=4.0,ratingScale=5.0,reviewCount=7,ratingSource="existing")
        val merged=BibliographicSources.merge(edited,BibliographicSources.selectBnf(entries(),b)!!)
        assertEquals("Lylian",merged.writer);assertEquals("2026",merged.date)
        assertEquals(edited.rating,merged.rating);assertEquals(7,merged.reviewCount)
        assertEquals("BnF",BibliographicSources.sourceLabel(merged.metadataSource))
    }
    @Test fun extractSpecificTitleAndValidateIsbnChecksums() {
        val input=Book("x","","U-Boot - FR01 - Docteur Mengel.pdf","",series="U-Boot",number="1")
        assertEquals("Docteur Mengel",BibliographicSources.titleFromFilename(input))
        assertTrue(BibliographicSources.validIsbn("978-2-491467-02-9"))
        assertFalse(BibliographicSources.validIsbn("9782491467028"))
    }
    @Test fun publisherRequiresExactIsbnAndValidCalendarDate() {
        fun html(date:String)="<ul><li>ISBN : 9782808510899</li><li>Date de parution : $date</li><li>Genre : Action / aventure</li></ul>"
        assertEquals("2026-01-16",BibliographicSources.parseDupuis(html("16/01/2026"),"978-2-8085-1089-9")!!.date)
        assertNull(BibliographicSources.parseDupuis(html("16/01/2026"),"9782808503266"))
        assertEquals("",BibliographicSources.parseDupuis(html("31/02/2026"),"9782808510899")!!.date)
    }
    private fun integralEntries()=BibliographicSources.parseBnf(javaClass.getResource("/metadata/bnf-soleil-froid.xml")!!.readText())
    @Test fun integralAbbreviationFindsPublicRecordAndSummaryWithoutAi() {
        val entries=integralEntries()
        val input=Book("integral","","Soleil froid INT.pdf","Soleil froid INT")
        for(book in listOf(input,input.copy(title="Soleil froid Int."),input.copy(title="Soleil froid - Intégrale"),input.copy(title="Soleil froid INT (Nouvelle édition)"),input.copy(writer="Pécau"),
            input.copy(title="Soleil froid",series="Soleil froid",number="INT"))) {
            val result=BibliographicSources.selectBnf(entries,book)!!
            assertEquals("978-2-413-03869-6",result.details.isbn)
            assertEquals("Jean-Pierre Pécau",result.details.writer);assertEquals("Damien",result.details.artist)
            assertEquals("Delcourt",result.details.publisher);assertEquals("2023",result.details.date)
            assertTrue(result.details.synopsis.contains("robot de portage"))
            assertEquals(result,BibliographicSources.selectBnf(entries.reversed(),book))
        }
        val exact=BibliographicSources.selectBnf(entries,input.copy(isbn="9782413091455"))!!
        assertEquals("2025",exact.details.date)
        assertEquals("978-2-413-09145-5",exact.details.isbn)
    }
    @Test fun integralNeverMixesTomesRangesOrHomonymousBooks() {
        val entries=integralEntries();val input=Book("integral","","Soleil froid INT.pdf","Soleil froid INT")
        val integrals=entries.filter {it.subtitle=="intégrale"}
        assertEquals(2,integrals.size)
        assertNull(BibliographicSources.selectBnf(integrals.map {it.copy(contents=emptyList())},input))
        assertNull(BibliographicSources.selectBnf(integrals.mapIndexed {i,e ->if(i==0)e.copy(contents=listOf("Autre tome")) else e},input))
        assertNull(BibliographicSources.selectBnf(entries,input.copy(writer="Autre auteur")))
        assertNull(BibliographicSources.selectBnf(entries,input.copy(title="Soleil froid INT 02",filename="Soleil froid INT 02.pdf")))
        val regular=BibliographicSources.selectBnf(entries,input.copy(title="Soleil froid",series="Soleil froid",number="1"))!!
        assertEquals("H5N4",regular.details.title)
        assertNotEquals("978-2-413-03869-6",regular.details.isbn)
        assertNull(BibliographicSources.integralTitle(input.copy(title="Int le héros",filename="Int le héros.pdf")))
    }

    @Test fun embeddedTomeFindsTheCorrectAlbumInBnf() {
        val embedded=b.copy(title="L'île de minuit - Tome 02 - La femme aux singes",series="",number="")
        assertEquals("978-2-8085-1089-9",BibliographicSources.selectBnf(entries(),embedded)!!.details.isbn)
        val conflict=embedded.copy(series="L'île de minuit",number="1")
        assertNotEquals("978-2-8085-1089-9",BibliographicSources.selectBnf(entries(),conflict)!!.details.isbn)
    }

}
