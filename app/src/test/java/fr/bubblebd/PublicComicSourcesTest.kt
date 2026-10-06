package fr.bubblebd
import org.junit.Test
import org.junit.Assert.*

class PublicComicSourcesTest {
    private val book=Book("x","","Smoke T02.pdf","Smoke T02")
    private val html="""<h1 class="single-product-header__title">Smoke</h1><div class="tech"><table>
      <tr><td>ISBN / EAN</td><td>9782505075295</td></tr><tr><td>Parution</td><td>30 janvier 2026</td></tr>
      <tr><td>Catégorie</td><td>Science-fiction</td></tr></table></div>
      <div class="auteurs__content"><ul><li>Dessin, Scénario<div><h3><a>Oh! Great</a></h3></div></li>
      <li>Traducteur<div><h3><a>Traducteur</a></h3></div></li></ul></div>
      <div class="hero-area__description" itemprop="description">Résumé de test.</div>"""
    @Test fun titleAndKnownFieldsCanBeCorrectedWithoutChangingProgress() {
        val result=PublicComicSources.parseKana(html,"https://www.kana.fr/produit/smoke/",book)!!
        val updated=BibliographicSources.merge(book.copy(page=12,genre="Ancien genre"),result)
        assertEquals("Smoke",updated.title);assertEquals("Oh! Great",updated.writer)
        assertEquals("2026-01-30",updated.date);assertEquals("Science-fiction",updated.genre);assertEquals(12,updated.page)
    }
    @Test fun rejectsDifferentWorkCreatorIsbnAndForeignHost() {
        val url="https://www.kana.fr/produit/smoke/"
        assertNull(PublicComicSources.parseKana(html,url,book.copy(title="Smoke City")))
        assertNull(PublicComicSources.parseKana(html,url,book.copy(writer="Mariolle")))
        assertNull(PublicComicSources.parseKana(html,url,book.copy(isbn="9782344050743")))
        assertNull(PublicComicSources.parseKana(html,"https://evil.example/",book))
    }
    @Test fun titlePresentationAvoidsRepeatingSeries() {
        assertEquals("Saga - Le départ",book.copy(series="Saga",title="Le départ").displayTitle)
        assertEquals("Smoke",book.copy(series="Smoke",title="Smoke").displayTitle)
        assertEquals("Saga - Le départ",book.copy(series="Saga",title="Saga - Le départ").displayTitle)
    }
}
