package fr.bubblebd

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    @Test fun settingsSwitchLanguagesAndSystemWithoutChangingLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        fun capture(name: String) {
            device.waitForIdle()
            Thread.sleep(700) // Let the navigation/dialog animation finish before the screenshot.
            val image = instrumentation.uiAutomation.takeScreenshot()
            val file = java.io.File(ctx.getExternalFilesDir(null),"language-$name.png")
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/bubble-language-$name.png")).use {it.readBytes()}
        }
        val repo = Repository(ctx)
        repo.saveBooks(runBlocking { repo.demoBooks() })
        repo.savePrefs(Preferences(theme="dark", outsideDim=75))
        val before = repo.loadBooks()
        AppLanguage.select(ctx,"fr")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun click(text: String) {
                assertTrue("Visible: $text", device.wait(Until.hasObject(By.text(text)),15000))
                device.findObject(By.text(text)).click()
            }
            click("Réglages")
            capture("settings-fr")
            click("Langue : Français")
            capture("choices-fr")
            click("English")
            assertTrue(device.wait(Until.hasObject(By.text("Language : English")),15000))
            assertEquals("en",AppLanguage.selected(ctx))
            capture("settings-en")
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Language : English")),15000))
            click("Language : English")
            click("Français")
            click("Langue : Français")
            click("Langue du téléphone")
            assertTrue(device.wait(Until.hasObject(By.textContains(" : ")),15000))
            assertEquals("",AppLanguage.selected(ctx))
            assertEquals(before,repo.loadBooks())
            assertEquals("dark",repo.loadPrefs().theme)
            assertEquals(75,repo.loadPrefs().outsideDim)
        }
        AppLanguage.select(ctx,"fr")
    }
}
