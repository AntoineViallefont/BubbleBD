package fr.bubblebd

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class DeletedAlbumsTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo:Repository
    private lateinit var originals:List<Book>
    private lateinit var vm:LibraryViewModel
    private var scenario:ActivityScenario<MainActivity>?=null
    @Before fun seed() {
        ctx.getSharedPreferences("bubblebd",0).edit().remove("deletedBooks").remove("excludedUris").apply()
        repo=Repository(ctx);originals=runBlocking {repo.demoBooks()};repo.saveBooks(originals)
        repo.saveFolders(listOf(SourceFolder("content://test/folder","Dossier test",4)))
    }
    @After fun close() {scenario?.close()}
    private fun settings() {
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {activity ->
            vm=ViewModelProvider(activity)[LibraryViewModel::class.java]
            activity.setContent {BubbleTheme("dark") {SettingsScreen(vm,{}, {})}}
        }
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file=File(ctx.getExternalFilesDir(null),"qa/$name.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun digest(book:Book)=MessageDigest.getInstance("SHA-256").digest(File(java.net.URI(book.uri)).readBytes()).toList()
    @Test fun removalRetainsIdentityAndProgressButPurgesPrivateCopies() {
        val book=originals.first().copy(synopsis="Résumé test",readingState="read",pinned=true)
        val hash=digest(book)
        val cached=File(ctx.cacheDir,"comics/${book.id}.cbz").apply {parentFile!!.mkdirs();writeText("test")}
        val offline=File(ctx.filesDir,"offline/${book.id}.cbz").apply {parentFile!!.mkdirs();writeText("test")}
        repo.removeAlbum(book)
        val saved=Repository(ctx).deletedBooks().single()
        assertEquals(book.title,saved.title);assertEquals(book.page,saved.page);assertEquals(book.synopsis,saved.synopsis)
        assertEquals("read",saved.readingState);assertFalse(saved.pinned);assertTrue(File(saved.cover).isFile)
        assertFalse(cached.exists());assertFalse(offline.exists());assertFalse(File(book.cover).exists())
        assertEquals(hash,digest(book));assertTrue(book.uri in repo.excludedUris())
        val original=File(java.net.URI(book.uri));val moved=File(original.parentFile,original.name+".unavailable");assertTrue(original.renameTo(moved))
        repo.clearCache()
        val image=try {runBlocking {Repository(ctx).deletedPreview(saved)}} finally {assertTrue(moved.renameTo(original))};assertTrue(image.width>0);image.recycle()
        assertEquals(hash,digest(book))
    }
    @Test fun selectedRestoreAndAllRestorePreserveOtherExclusions() {
        originals.take(3).forEach(repo::removeAlbum)
        settings()
        compose.onNodeWithText("Ajouter des fichiers").assertDoesNotExist()
        capture("reglages-valides-v0.3.23")
        compose.onNodeWithText("Albums supprimés (3)").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Première page de",substring=true).fetchSemanticsNodes().size==3}
        capture("albums-supprimes-v0.3.23")
        compose.onNodeWithText("Réimporter la sélection").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Sélectionner ${originals[0].title}").performClick()
        compose.onNodeWithContentDescription("Sélectionner ${originals[1].title}").performClick()
        compose.onNodeWithText("2 sélectionnés").assertIsDisplayed()
        compose.onNodeWithText("Réimporter la sélection").performClick()
        compose.waitUntil(15000) {repo.excludedUris().size==1}
        assertTrue(repo.loadBooks().any {it.id==originals[0].id && it.page==originals[0].page && it.started})
        assertTrue(originals[2].uri in repo.excludedUris())
        compose.onNodeWithText("Tout réimporter").performClick()
        compose.waitUntil(15000) {repo.excludedUris().isEmpty()}
        compose.onNodeWithText("Aucun album supprimé").assertIsDisplayed()
        assertEquals(4,repo.loadBooks().size)
        assertTrue(originals.all {File(java.net.URI(it.uri)).exists()})
    }
    @Test fun inaccessibleSourceRemainsExcludedAndVisible() {
        val book=Book("missing","content://test/not-found","Manquant.cbz","Album manquant")
        repo.removeAlbum(book);settings()
        compose.onNodeWithText("Albums supprimés (1)").performClick()
        compose.onNodeWithText("Réimport",substring=false).performClick()
        compose.waitUntil(10000) {vm.message?.startsWith("0/1 réimporté(s)")==true && !vm.busy}
        assertTrue(book.uri in repo.excludedUris());assertTrue(repo.loadBooks().none {it.id==book.id})
        compose.onNodeWithText("Album manquant").assertIsDisplayed()
    }
    @Test fun folderRemovalRequiresConfirmationAndPreservesLibrary() {
        settings()
        compose.onNodeWithContentDescription("Retirer ce dossier du scan").performClick()
        compose.onNodeWithText("Retirer ce dossier ?").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(1,repo.loadFolders().size)
        compose.onNodeWithContentDescription("Retirer ce dossier du scan").performClick()
        compose.onNodeWithText("Retirer",substring=false).performClick()
        compose.waitUntil(5000) {repo.loadFolders().isEmpty()}
        assertEquals(originals.map {it.id},repo.loadBooks().map {it.id})
    }
    @Test fun individualRestoreDoesNotRestoreNeighbours() {
        originals.take(2).forEach(repo::removeAlbum);settings()
        compose.onNodeWithText("Albums supprimés (2)").performClick()
        compose.onAllNodesWithText("Réimport",substring=false)[0].performClick()
        compose.waitUntil(15000) {repo.excludedUris().size==1}
        assertEquals(1,repo.deletedBooks().size)
        assertEquals(3,repo.loadBooks().size)
    }
    @Test fun oneDriveCrossRequiresConfirmationAndCancelDoesNothing() {
        var calls=0
        scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {activity ->activity.setContent {BubbleTheme("light") {OneDriveSettingsRow(true,false,{}, {calls++})}}}
        compose.onNodeWithText("Déconnecter OneDrive").assertDoesNotExist()
        compose.onNodeWithContentDescription("Déconnecter OneDrive").performClick()
        compose.onNodeWithText("Déconnecter OneDrive ?").assertIsDisplayed()
        assertEquals(0,calls)
        compose.onNodeWithText("Annuler").performClick();assertEquals(0,calls)
        compose.onNodeWithContentDescription("Déconnecter OneDrive").performClick()
        compose.onNodeWithText("Déconnecter",substring=false).performClick()
        compose.runOnIdle {assertEquals(1,calls)}
    }
    @Test fun legacyExclusionsResolveFilenameAndRestoreOnlyRequestedSource() {
        val book=originals.first()
        ctx.getSharedPreferences("bubblebd",0).edit().putStringSet("excludedUris",setOf(book.uri,"content://test/missing.cbz")).apply()
        runBlocking {repo.resolveDeletedNames()}
        assertEquals(book.filename,repo.deletedBooks().first {it.uri==book.uri}.filename)
        repo.allowImport(book.uri)
        assertEquals(setOf("content://test/missing.cbz"),repo.excludedUris())
        assertEquals(1,repo.deletedBooks().size)
    }

}
