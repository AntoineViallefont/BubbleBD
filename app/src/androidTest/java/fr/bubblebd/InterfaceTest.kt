package fr.bubblebd

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var scenario:ActivityScenario<MainActivity>
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun prepare() {
        // This seed affects only the development emulator's test installation.
        val repo=Repository(ctx)
        repo.saveBooks(runBlocking {repo.demoBooks()})
        repo.savePrefs(Preferences(theme="dark"))
        scenario=ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5000) {compose.onAllNodesWithText("À reprendre").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun finish() {scenario.close()}
    private fun capture(name:String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        if(name.startsWith("lecteur")) {
            val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Got it")),3000)?.click()
        }
        // Window transitions (dialogs, system icon color) run outside Compose's idling clock.
        Thread.sleep(1000)
        // Coil loads covers asynchronously; commit recompositions after decoding.
        compose.waitForIdle()
        val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(ctx.getExternalFilesDir(null),"qa").mkdirs()
        val result=File(ctx.getExternalFilesDir(null),"qa/$name.png")
        result.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cp ${result.absolutePath} /sdcard/Download/bubble-$name.png")).use {it.readBytes()}
    }
    @Test fun metadataSearchIsCompactAndDistinguishesWaitingFromRunning() {
        val status=androidx.compose.runtime.mutableStateOf(MetadataSearchStatus(MetadataSearchStatus.Phase.SEARCHING,"Les Rivages bleus",3,120))
        scenario.onActivity {activity ->
            val vm=androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
            activity.setContent {BubbleTheme(vm.prefs.theme) {BubbleApp(vm,status.value)}}
        }
        val active="Recherche des infos · BD 3/120 · Les Rivages bleus"
        compose.onNodeWithContentDescription(active).assertDoesNotExist()
        compose.onNodeWithContentDescription("Bibliothèque").performClick()
        compose.onNodeWithContentDescription(active).assertDoesNotExist()
        compose.onNodeWithContentDescription("Réglages").performClick()
        compose.onNodeWithText("Informations des BD").assertIsDisplayed()
        compose.onNodeWithText("Relancer la recherche des infos").assertIsDisplayed()
        compose.onNodeWithContentDescription(active).assertIsDisplayed()
        val bounds=compose.onNodeWithContentDescription(active).fetchSemanticsNode().boundsInRoot
        val theme=compose.onNodeWithContentDescription("Passer au thème jour").fetchSemanticsNode().boundsInRoot
        assertTrue("Progression compacte uniquement dans les réglages",bounds.top>=theme.bottom && bounds.height<36*ctx.resources.displayMetrics.density)
        capture("infos-reglages-nuit-v0.3.23")
        compose.onNodeWithContentDescription("Passer au thème jour").performClick()
        compose.onNodeWithContentDescription(active).assertIsDisplayed()
        capture("infos-reglages-jour-v0.3.23")
        compose.runOnIdle {status.value=MetadataSearchStatus(MetadataSearchStatus.Phase.WAITING,position=3,total=120)}
        compose.onNodeWithContentDescription(active).assertDoesNotExist()
        compose.onNodeWithContentDescription("Infos en attente · BD 3/120").assertDoesNotExist()
        compose.onNodeWithText("Infos en attente").assertDoesNotExist()
        capture("infos-au-repos-jour-v0.3.19")
        compose.onNodeWithContentDescription("Passer au thème nuit").performClick()
        compose.onNodeWithText("Infos en attente").assertDoesNotExist()
        compose.onNodeWithContentDescription(active).assertDoesNotExist()
        capture("infos-au-repos-nuit-v0.3.19")
        compose.runOnIdle {status.value=MetadataSearchStatus()}
        compose.onNodeWithContentDescription("Infos en attente · BD 3/120").assertDoesNotExist()
        compose.onNodeWithText("Recherche des infos").assertDoesNotExist()
    }
    @Test fun albumSheetHidesResearchStatusInBothThemes() {
        val book=Book("ui-research","content://fictional","fictional.pdf","Les Rivages bleus",synopsis="Album fictif de test.",metadataAttribution="Recherche Web · Mistral",rating=4.0,ratingScale=5.0,reviewCount=12,ratingSource="https://books.google.com/books?id=test",pinned=true)
        val status=androidx.compose.runtime.mutableStateOf(AlbumResearch(AlbumResearch.State.NO_MATCH,1791050000000L))
        val theme=androidx.compose.runtime.mutableStateOf("light")
        scenario.onActivity {activity -> activity.setContent {BubbleTheme(theme.value) {
            BookSheet(book,{}, {}, {}, {},research=status.value)
        }}}
        compose.onNodeWithText("Recherche effectuée · aucun résultat fiable").assertDoesNotExist()
        compose.onNodeWithText("Recherche Web · Mistral").assertDoesNotExist()
        compose.onNodeWithText("Note de l’album").assertDoesNotExist()
        compose.onNodeWithText("Libérer la copie hors ligne").assertIsDisplayed()
        compose.onNodeWithText("4,00 / 5",substring=true).assertIsDisplayed()
        compose.onNodeWithText(status.value.date).assertDoesNotExist()
        capture("fiche-recherche-jour-v0.3.19")
        compose.runOnIdle {theme.value="dark";status.value=AlbumResearch(AlbumResearch.State.RETRY,1791050000000L)}
        compose.onNodeWithText("Recherche interrompue · nouvel essai prévu").assertDoesNotExist()
        compose.onNodeWithText(status.value.date).assertDoesNotExist()
        capture("fiche-recherche-nuit-v0.3.19")
        compose.runOnIdle {status.value=AlbumResearch(AlbumResearch.State.QUOTA,1791050000000L)}
        compose.onNodeWithText("Quota gratuit atteint · recherche différée").assertDoesNotExist()
        capture("fiche-quota-nuit-v0.3.19")
    }
    @Test fun editorLockDisablesSearchAndPersists() {
        val original=Repository(ctx).loadBooks().first().copy(demo=false)
        var saved:Book?=null
        scenario.onActivity {activity ->activity.setContent {BubbleTheme("dark") {
            EditBookDialog(original,{},search={error("Fiche figée recherchée")},save={saved=it})
        }}}
        compose.onNodeWithContentDescription("Figer les informations").performClick()
        compose.onNodeWithText("Rechercher pour ce titre").assertIsNotEnabled()
        capture("fiche-verrouillee")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.runOnIdle {assertTrue(saved?.metadataLocked==true)}
    }
    @Test fun editorSearchSendsOnlyCurrentEditedAlbum() {
        val original=Repository(ctx).loadBooks().first().copy(demo=false,metadataLocked=true)
        var searched:Book?=null
        scenario.onActivity {activity ->activity.setContent {BubbleTheme("dark") {
            EditBookDialog(original,{},search={searched=it},save={})
        }}}
        compose.onNodeWithContentDescription("Figer les informations").performClick()
        compose.onNodeWithText("Titre",substring=false).performTextReplacement("Titre corrigé")
        compose.onNodeWithText("Rechercher pour ce titre").performClick()
        compose.runOnIdle {assertTrue(searched?.id==original.id && searched?.title=="Titre corrigé" && searched?.metadataLocked==false)}
        capture("fiche-recherche-unitaire")
    }
    @Test fun editedTitleIsSavedWithoutChangingReadingProgress() {
        val repo=Repository(ctx)
        val original=repo.loadBooks().first().copy(title="Titre initial",demo=false,page=2,started=true)
        repo.saveBooks(listOf(original))
        val current=androidx.compose.runtime.mutableStateOf(original)
        scenario.onActivity {activity -> activity.setContent {BubbleTheme("dark") {
            BookSheet(current.value,{}, {}, {}, {},save={updated ->
                repo.saveBooks(listOf(updated));current.value=updated
            })
        }}}
        compose.onNodeWithText("Modifier la fiche").performClick()
        compose.onNodeWithText("Titre",substring=false).performTextReplacement("Titre corrigé")
        compose.onNodeWithText("Enregistrer").performClick()
        compose.onNodeWithText(original.copy(title="Titre corrigé").displayTitle).assertIsDisplayed()
        val stored=repo.loadBooks().single()
        assertTrue(stored.title=="Titre corrigé" && stored.page==2 && stored.started && stored.uri==original.uri && stored.synopsis==original.synopsis)
        capture("fiche-modifier-titre")
    }
    @Test fun longPressSeriesMarksAllVolumes() {
        val repo=Repository(ctx)
        val originals=repo.loadBooks()
        repo.saveBooks(originals.take(2).mapIndexed {i,b ->b.copy(series="Saga test",number="${i+1}",title="Album fictif ${i+1}",demo=false,metadataSource="https://catalogue.bnf.fr/ark:/12148/qa",metadataEdited=true,started=false,readingState="")})
        scenario.close();scenario=ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("Bibliothèque").performClick()
        compose.onNodeWithText("Saga test").performTouchInput {longClick()}
        compose.onNodeWithText(originals.first().filename,substring=false).assertDoesNotExist()
        compose.onNodeWithText("Sources : BnF",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Télécharger").assertIsDisplayed()
        compose.onNodeWithText("Sources",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Voir sur BDThèque").assertDoesNotExist()
        compose.onNodeWithText("Lire",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Modifier la fiche").assertIsDisplayed()
        val edit=compose.onNodeWithText("Modifier la fiche").fetchSemanticsNode().boundsInRoot
        val download=compose.onNodeWithText("Télécharger").fetchSemanticsNode().boundsInRoot
        assertTrue("Modifier à gauche du téléchargement",edit.right<download.left && kotlin.math.abs(edit.center.y-download.center.y)<10)
        val mark=compose.onNodeWithText("Marquer Lu").fetchSemanticsNode().boundsInRoot
        val remove=compose.onNodeWithText("Supprimer").fetchSemanticsNode().boundsInRoot
        assertTrue("Suppression à côté du marquage lu",mark.right<remove.left && kotlin.math.abs(mark.center.y-remove.center.y)<10)
        compose.onNodeWithText("Supprimer").assertIsDisplayed()
        capture("fiche-compacte-v0.3.17")
        compose.onNodeWithText("2 albums · marquage de la série entière").assertIsDisplayed()
        compose.onNodeWithText("Relire les informations du fichier").assertDoesNotExist()
        compose.onNodeWithText("À lire",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Marquer Lu",substring=false).performClick()
        compose.waitForIdle()
        assertTrue(repo.loadBooks().all {it.readingState=="read"})
    }
    @Test fun landscapeTitlesFiltersAndThemeShortcut() {
        val brand=compose.onNodeWithText("BubbleBD",substring=false).fetchSemanticsNode().boundsInRoot
        val manga=compose.onNodeWithContentDescription("Activer la lecture manga de droite à gauche").fetchSemanticsNode().boundsInRoot
        val theme=compose.onNodeWithContentDescription("Passer au thème jour").fetchSemanticsNode().boundsInRoot
        assertTrue("Le titre ne chevauche pas les commandes",brand.right<=manga.left)
        assertTrue("Manga est placé près du thème",manga.right<=theme.left && kotlin.math.abs(manga.center.y-theme.center.y)<2)
        compose.onNodeWithContentDescription("Activer la lecture manga de droite à gauche").performClick()
        compose.onNodeWithContentDescription("Revenir à la lecture de gauche à droite").assertIsOn()
        compose.waitForIdle()
        assertTrue(Repository(ctx).loadPrefs().rtl)
        capture("entete-manga-actif")
        compose.onNodeWithContentDescription("Passer au thème jour").performClick()
        compose.onNodeWithContentDescription("Passer au thème nuit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Revenir à la lecture de gauche à droite").assertIsOn()
        compose.onNodeWithContentDescription("Passer au thème nuit").performClick()
        scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000) {ctx.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        compose.onNodeWithText("Bibliothèque").performClick()
        compose.onNodeWithContentDescription("Revenir à la lecture de gauche à droite").performClick()
        compose.waitForIdle()
        assertTrue(!Repository(ctx).loadPrefs().rtl)
        compose.onNodeWithContentDescription("État de lecture").performClick()
        compose.onNodeWithText("En cours").performClick()
        compose.onNodeWithContentDescription("Emplacement").performClick()
        compose.onNodeWithText("Local",substring=false).performClick()
        val state=compose.onNodeWithText("En cours").fetchSemanticsNode().boundsInRoot
        val location=compose.onNodeWithText("Local",substring=false).fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(state.top-location.top)<5f)
        compose.onNodeWithText("Les Rivages bleus").performScrollTo().assertIsDisplayed()
        capture("bibliotheque-paysage")
        scenario.onActivity {it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
    }

    @Test fun homeLibrarySettingsAndTheme() {
        compose.onNodeWithText("À reprendre").assertIsDisplayed()
        compose.onNodeWithText("Derniers ajouts").assertIsDisplayed()
        capture("accueil-nuit")
        compose.onNodeWithText("Bibliothèque").performClick()
        capture("bibliotheque-grille")
        compose.onNodeWithText("Titre, série, auteur…").performTextInput("signal")
        compose.onNodeWithText("Dernier signal").assertIsDisplayed()
        compose.onNodeWithText("1 albums",substring=true).assertIsDisplayed()
        capture("bibliotheque-recherche")
        compose.onNodeWithContentDescription("Affichage liste").performClick()
        compose.onNodeWithContentDescription("Fiche de Dernier signal").assertIsDisplayed()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        capture("bibliotheque-liste")
        compose.onNodeWithContentDescription("Trier et régler la taille").performClick()
        capture("bibliotheque-tri")
        compose.onNodeWithText("Terminé",substring=false).performClick()
        compose.onNodeWithContentDescription("Fiche de Dernier signal").performClick()
        capture("fiche-album")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Réglages").performClick()
        compose.onNodeWithText("Mes dossiers").assertIsDisplayed()
        compose.onNodeWithText("Lecture manga · droite vers gauche").assertDoesNotExist()
        compose.onNodeWithContentDescription("Passer au thème jour").performClick()
        capture("reglages-jour")
        compose.onNodeWithText("Stockage local").performScrollTo()
        capture("reglages-stockage")
        compose.onNodeWithContentDescription("Assombrissement hors case").performScrollTo()
        compose.onNodeWithContentDescription("Assombrissement hors case").performTouchInput {swipe(centerLeft+androidx.compose.ui.geometry.Offset(width*.10f,0f),centerRight-androidx.compose.ui.geometry.Offset(width*.03f,0f),250)}
        compose.waitForIdle()
        capture("reglages-lecture")
        assertTrue("La barre reste réglable sans curseur : ${Repository(ctx).loadPrefs().outsideDim}",Repository(ctx).loadPrefs().outsideDim>=95)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("À propos",substring=false))
        compose.onNodeWithText("À propos",substring=false).performClick()
        compose.onNodeWithText("Lire à votre rythme").assertIsDisplayed()
        capture("a-propos-jour")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Données et crédits"))
        compose.onNodeWithText("Données et crédits").assertIsDisplayed()
        capture("a-propos-credits")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Mes dossiers"))
        compose.onNodeWithText("Mes dossiers").assertIsDisplayed()
        compose.onNodeWithContentDescription("Passer au thème nuit").performClick()
        compose.onNodeWithText("Jour",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Nuit",substring=false).assertDoesNotExist()
        compose.onNodeWithText("Système",substring=false).assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("À propos",substring=false))
        compose.onNodeWithText("À propos",substring=false).performClick()
        capture("a-propos-nuit")
        compose.onNodeWithContentDescription("Retour aux réglages").performClick()
        compose.onNodeWithText("Accueil").performClick()
        capture("accueil-jour")
    }
    @Test fun resumeAndManualPageInputPersist() {
        compose.onNodeWithText("Les Rivages bleus").performClick()
        compose.onNodeWithText("Reprendre la lecture ?").assertIsDisplayed()
        compose.onNodeWithText("Reprendre",substring=false).performClick()
        compose.waitUntil(20000) {Repository(ctx).loadBooks().first {it.id=="demo-rivages"}.started}
        // Capture actual full-screen renderer, with no permanently visible controls.
        Thread.sleep(1200)
        val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.findObject(androidx.test.uiautomator.By.text("Got it"))?.click()
        capture("lecteur-page")
        scenario.onActivity {activity ->
            fun find(view:android.view.View):ComicView? {if(view is ComicView) return view; if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let {return it}; return null}
            find(activity.window.decorView)!!.onTap()
        }
        compose.onNodeWithText("2 / 5").performClick()
        compose.onNodeWithText("Aller à la page").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("4")
        compose.onNodeWithText("Aller",substring=false).performClick()
        compose.waitUntil(15000) {Repository(ctx).loadBooks().first {it.id=="demo-rivages"}.page==3}
        capture("lecteur-commandes")
        compose.onNodeWithContentDescription("Fermer le lecteur").performClick()
        compose.onNodeWithText("Page 4 sur 5").assertIsDisplayed()
        compose.onNodeWithText("Les Rivages bleus").performClick()
        compose.onNodeWithText("Première page").performClick()
        compose.waitUntil(15000) {Repository(ctx).loadBooks().first {it.id=="demo-rivages"}.page==0}
    }
    @Test fun guidedIndicatorPreview() {
        compose.onNodeWithText("Les Rivages bleus").performClick()
        compose.onNodeWithText("Reprendre",substring=false).performClick()
        compose.waitUntil(120000) {
            var ready=false
            scenario.onActivity {activity ->
                fun loaded(v:android.view.View):Boolean {if(v is ComicView)return v.isEnabled && v.detectionReady;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)if(loaded(v.getChildAt(i)))return true;return false}
                ready=loaded(activity.window.decorView)
            }
            ready
        }
        val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Got it")),3000)?.click()
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        scenario.onActivity {activity ->
            fun find(v:android.view.View):ComicView? {if(v is ComicView)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let {return it};return null}
            val view=find(activity.window.decorView)!!
            val b=InstrumentationRegistry.getInstrumentation().context.assets.open("demo/page.png").use {android.graphics.BitmapFactory.decodeStream(it)}
            val found=BookRules.orderPanels(PanelDetector.detect(b),false);println("Detected panels: $found")
            val p=found[1]
            val scale=minOf(view.width.toFloat()/b.width,view.height.toFloat()/b.height)
            val x=(view.width-b.width*scale)/2+(p.left+p.right)/2*b.width*scale
            val y=(view.height-b.height*scale)/2+(p.top+p.bottom)/2*b.height*scale
            val t=android.os.SystemClock.uptimeMillis()
            listOf(0L to android.view.MotionEvent.ACTION_DOWN,40L to android.view.MotionEvent.ACTION_UP,100L to android.view.MotionEvent.ACTION_DOWN,140L to android.view.MotionEvent.ACTION_UP).forEach {(offset,action)->
                val e=android.view.MotionEvent.obtain(t,t+offset,action,x,y,0);view.onTouchEvent(e);e.recycle()
            }
            b.recycle()
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onRoot().printToLog("BubbleIndicator")
        Thread.sleep(200)
        val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(ctx.getExternalFilesDir(null),"qa").mkdirs()
        File(ctx.getExternalFilesDir(null),"qa/lecteur-case.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("Case 2/5").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1700)
        compose.onNodeWithText("Case 2/5").assertDoesNotExist()
        compose.mainClock.autoAdvance=true
    }

    @Test fun pageControlsAndManualJumpKeepGuidedReading() {
        fun find(v:android.view.View):ComicView? {
            if(v is ComicView)return v
            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let {return it}
            return null
        }
        fun awaitPage(index:Int,guided:Boolean) {
            compose.waitUntil(120000) {
                var ready=false
                scenario.onActivity {activity ->find(activity.window.decorView)?.let {ready=it.isEnabled && it.detectionReady && it.guidedReading==guided}}
                ready && Repository(ctx).loadBooks().first {it.id=="demo-rivages"}.page==index
            }
        }
        compose.onNodeWithText("Les Rivages bleus").performClick()
        compose.onNodeWithText("Reprendre",substring=false).performClick()
        awaitPage(1,false)
        val book=Repository(ctx).loadBooks().first {it.id=="demo-rivages"}
        val image=ComicDocument(File(java.net.URI(book.uri))).use {it.bitmap(1)}
        val panels=BookRules.orderPanels(PanelDetector.detect(image),false)
        scenario.onActivity {activity ->
            val view=find(activity.window.decorView)!!
            val p=panels[1]
            val scale=minOf(view.width.toFloat()/image.width,view.height.toFloat()/image.height)
            val x=(view.width-image.width*scale)/2+(p.left+p.right)/2*image.width*scale
            val y=(view.height-image.height*scale)/2+(p.top+p.bottom)/2*image.height*scale
            val t=android.os.SystemClock.uptimeMillis()
            listOf(0L to android.view.MotionEvent.ACTION_DOWN,40L to android.view.MotionEvent.ACTION_UP,100L to android.view.MotionEvent.ACTION_DOWN,140L to android.view.MotionEvent.ACTION_UP).forEach {(offset,action)->
                val event=android.view.MotionEvent.obtain(t,t+offset,action,x,y,0);view.onTouchEvent(event);event.recycle()
            }
            org.junit.Assert.assertTrue(view.guidedReading)
            view.onTap()
        }
        image.recycle()
        compose.onNodeWithContentDescription("Page suivante").performClick()
        awaitPage(2,true)
        compose.onNodeWithContentDescription("Page précédente").performClick()
        awaitPage(1,true)
        compose.onNodeWithText("2 / 5").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("4")
        compose.onNodeWithText("Aller",substring=false).performClick()
        awaitPage(3,true)
        compose.onNodeWithContentDescription("Voir toute la page").performClick()
        compose.onNodeWithContentDescription("Page suivante").performClick()
        awaitPage(4,false)
    }

    @Test fun compactHomeListAndDeletionConfirmation() {
        compose.onNodeWithContentDescription("Affichage liste").performClick()
        compose.onNodeWithContentDescription("Fiche de Dernier signal").assertIsDisplayed()
        capture("accueil-liste")
        compose.onNodeWithContentDescription("Fiche de Dernier signal").performClick()
        compose.onNodeWithText("Supprimer").performScrollTo().performClick()
        compose.onNodeWithText("Supprimer cet album ?").assertIsDisplayed()
        capture("suppression-album")
        compose.onNodeWithText("Annuler").performClick()
        assertTrue(Repository(ctx).loadBooks().any {it.id=="demo-signal"})
        compose.onNodeWithText("Supprimer").performScrollTo().performClick()
        compose.onAllNodesWithText("Supprimer",substring=false).onLast().performClick()
        compose.waitUntil(5000) {Repository(ctx).loadBooks().none {it.id=="demo-signal"}}
        compose.onNodeWithText("Dernier signal").assertDoesNotExist()
    }

    @Test fun seriesExpandAndCollapse() {
        // Two volumes are needed to exercise a series; the remaining demo albums stay standalone.
        scenario.close()
        val repo=Repository(ctx)
        val books=repo.loadBooks()
        val first=books.first {it.id=="demo-rivages"}
        repo.saveBooks(books+first.copy(id="demo-rivages-2",title="Les Rivages bleus · Tome 2",number="2",lastRead=0,started=false))
        scenario=ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5000) {compose.onAllNodesWithText("À reprendre").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Bibliothèque").performClick()
        compose.onNodeWithContentDescription("Couverture de Les Rivages bleus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Couverture de Dernier signal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Déployer Dernier signal").assertDoesNotExist()
        compose.onNodeWithText("Série · 2 tomes").assertIsDisplayed()
        capture("bibliotheque-serie-repliee")
        compose.onNodeWithContentDescription("Déployer Les Rivages bleus").performClick()
        compose.onNodeWithText("Les Rivages bleus · Tome 2").assertIsDisplayed()
        capture("bibliotheque-serie-deployee")
        compose.onNodeWithContentDescription("Replier Les Rivages bleus").performClick()
        compose.onNodeWithText("Les Rivages bleus · Tome 2").assertDoesNotExist()
        compose.onNodeWithContentDescription("Affichage liste").performClick()
        compose.onNodeWithText("Série · 2 tomes").assertIsDisplayed()
        capture("bibliotheque-series-liste")
        compose.onNodeWithContentDescription("Fiche de Dernier signal").performClick()
        compose.onNodeWithText("Supprimer").assertExists()
    }

}
