package fr.bubblebd

import org.junit.Assert.*
import org.junit.Test

class UiTextTest {
    @Test fun languageSelectionAndUnknownTextRemainPredictable() {
        assertEquals("Bibliothèque", UiText.translate("Bibliothèque", "fr"))
        assertEquals("Library", UiText.translate("Bibliothèque", "en"))
        assertEquals("Library", UiText.translate("Bibliothèque", "de"))
        assertEquals("Les Rivages bleus", UiText.translate("Les Rivages bleus", "en"))
        assertEquals("Page 3 of 12", UiText.translate("Page 3 sur 12", "en"))
        assertEquals("Panels 1, 2 and 3/8", UiText.translate("Cases 1, 2 et 3/8", "en"))
    }

    @Test fun capturedTitlesAreNotTreatedAsFormatStringsOrTranslatedAgain() {
        assertEquals("Cover of Titre {0} \$5", UiText.translate("Couverture de Titre {0} \$5", "en"))
        assertEquals("Titre\nPage 3 of 12", UiText.translate("Titre\nPage 3 sur 12", "en"))
        assertEquals("3 books\n1 local · 2 in the cloud", UiText.translate("3 albums\n1 en local · 2 dans le cloud", "en"))
    }

    @Test fun translatedFiltersNeverChangeStoredKeysOrBookData() {
        val prefs = Preferences(sort = "Dernière ouverture")
        val book = Book("id", "", "Titre.cbz", "Titre", series = "Série", number = "2", synopsis = "Résumé original")
        assertEquals("Last opened", UiText.translate(prefs.sort, "en"))
        assertEquals("Dernière ouverture", prefs.sort)
        assertEquals("Titre", book.title)
        assertEquals("Résumé original", book.synopsis)
        assertEquals("Volume 2", UiText.translate(book.volume, "en"))
        assertEquals("Tome 2", book.volume)
    }

    @Test fun cataloguePreservesAllTemplateArguments() {
        val slots = Regex("\\{\\d+\\}")
        UiCatalog.entries.forEach { (french, english) ->
            assertEquals(french, slots.findAll(french).map { it.value }.toSet(), slots.findAll(english).map { it.value }.toSet())
            assertTrue(french, english.isNotBlank())
        }
    }
}
