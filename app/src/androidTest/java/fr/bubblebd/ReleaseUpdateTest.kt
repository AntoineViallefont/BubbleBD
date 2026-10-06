package fr.bubblebd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only Android APIs so the same signed test APK can verify the previous release. */
@RunWith(AndroidJUnit4::class)
class ReleaseUpdateTest {
    @Test fun syntheticLibraryAndPreferencesSurviveSignedUpdate() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("bubblebd", android.content.Context.MODE_PRIVATE)
        if (InstrumentationRegistry.getArguments().getString("updatePhase") == "seed") {
            val book = JSONObject().put("id", "release-update-fixture")
                .put("uri", "file:///sdcard/Download/fictitious.cbz").put("filename", "Fictitious.cbz")
                .put("title", "Update test — title kept").put("series", "Test series").put("number", "2")
                .put("pages", 12).put("page", 6).put("started", true).put("demo", true)
                .put("metadataLocked", true).put("synopsis", "Fictional update-test record")
            assertTrue(prefs.edit().putString("books", JSONArray().put(book).toString())
                .putString("theme", "dark").putString("sort", "Titre").putInt("sortVersion", 3)
                .putInt("demoRemovalVersion", 1).putInt("outsideDim", 75).commit())
        }
        assertEquals("dark", prefs.getString("theme", ""))
        assertEquals("Titre", prefs.getString("sort", ""))
        assertEquals(75, prefs.getInt("outsideDim", -1))
        val books = JSONArray(prefs.getString("books", "[]"))
        assertEquals(1, books.length())
        val book = books.getJSONObject(0)
        assertEquals("release-update-fixture", book.getString("id"))
        assertEquals("Update test — title kept", book.getString("title"))
        assertEquals("Test series", book.getString("series"))
        assertEquals("2", book.getString("number"))
        assertEquals("Fictional update-test record", book.getString("synopsis"))
        assertEquals(12, book.getInt("pages"))
        assertEquals(6, book.getInt("page"))
        assertTrue(book.getBoolean("started"))
        assertTrue(book.getBoolean("metadataLocked"))
    }
}
