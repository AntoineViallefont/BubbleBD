package fr.bubblebd

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext
    private val language get() = InstrumentationRegistry.getArguments().getString("localizationLanguage") ?: "fr"
    private fun label(french: String) = UiText.translate(french, language)

    @Before fun prepare() {
        assertEquals("Android must apply the requested app language", language, Locale.getDefault().language)
        val repo = Repository(ctx)
        repo.saveBooks(runBlocking { repo.demoBooks() })
        repo.savePrefs(Preferences(theme = "dark"))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15000) { compose.onAllNodesWithText(label("À reprendre")).fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun finish() { if (::scenario.isInitialized) scenario.close() }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(ctx.getExternalFilesDir(null), "localization").apply { mkdirs() }
        val file = File(directory, "$language-$name.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val command = "cp ${file.absolutePath} /sdcard/Download/bubble-$language-$name.png"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun reader(view: View): ComicView? {
        if (view is ComicView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) reader(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun screensFiltersBookDataAndReadingProgressWorkInSelectedLanguage() {
        compose.onNodeWithText("Les Rivages bleus").assertIsDisplayed()
        compose.onNodeWithText(label("Derniers ajouts")).assertIsDisplayed()
        capture("home-dark")
        compose.onNodeWithContentDescription(label("Passer au thème jour")).performClick()
        capture("home-light")
        compose.onNodeWithText(label("Bibliothèque")).performClick()
        capture("library-light")
        compose.onNodeWithContentDescription(label("Trier et régler la taille")).performClick()
        compose.onNodeWithText(label("Titre"), substring = false).performClick()
        assertEquals("Stored sort key must not be translated", "Titre", Repository(ctx).loadPrefs().sort)
        compose.onNodeWithText(label("Terminé"), substring = false).performClick()
        compose.onNodeWithText(label("Titre, série, auteur…")).performTextInput("signal")
        compose.onNodeWithText("Dernier signal").assertIsDisplayed()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription(label("Affichage liste")).performClick()
        compose.onNodeWithContentDescription(label("Fiche de Dernier signal")).performClick()
        compose.onNodeWithText(label("Modifier la fiche")).performClick()
        compose.onNodeWithText(label("Exclure cette fiche des recherches.")).assertIsDisplayed()
        compose.onNodeWithText(label("Rechercher pour ce titre")).assertIsDisplayed()
        compose.onNodeWithContentDescription(label("Figer les informations")).performClick()
        compose.onNodeWithText(label("Rechercher pour ce titre")).assertIsNotEnabled()
        capture("edit-light")
        compose.onNodeWithText(label("Annuler")).performClick()
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
        assertEquals("Original book title must be preserved", "Dernier signal", Repository(ctx).loadBooks().first { it.id == "demo-signal" }.title)
        compose.onNodeWithText(label("Réglages")).performClick()
        compose.onNodeWithText(label("Mes dossiers")).assertIsDisplayed()
        capture("settings-light")
        compose.onNodeWithText(label("À propos"), substring = false).performScrollTo()
        compose.onNodeWithText(label("À propos"), substring = false).performClick()
        compose.onNodeWithText(label("Lire à votre rythme")).assertIsDisplayed()
        capture("about-light")
        compose.onNodeWithContentDescription(label("Retour aux réglages")).performClick()
        compose.onNodeWithText(label("Accueil")).performClick()
        compose.onNodeWithText("Les Rivages bleus").performClick()
        compose.onNodeWithText(label("Reprendre"), substring = false).performClick()
        compose.waitUntil(120000) {
            var ready = false
            scenario.onActivity { ready = reader(it.window.decorView)?.detectionReady == true }
            ready
        }
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        device.findObject(androidx.test.uiautomator.By.text("Got it"))?.click()
        scenario.onActivity { reader(it.window.decorView)!!.onTap() }
        compose.onNodeWithText("2 / 5").performClick()
        compose.onNodeWithText(label("Aller à la page")).assertIsDisplayed()
        capture("reader-dialog")
        compose.onNode(hasSetTextAction()).performTextReplacement("4")
        compose.onNodeWithText(label("Aller"), substring = false).performClick()
        compose.waitUntil(20000) { Repository(ctx).loadBooks().first { it.id == "demo-rivages" }.page == 3 }
        compose.onNodeWithContentDescription(label("Fermer le lecteur")).performClick()
        compose.onNodeWithText(label("Page 4 sur 5")).assertIsDisplayed()
    }
}
