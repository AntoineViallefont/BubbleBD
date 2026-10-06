package fr.bubblebd

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VersionTwoTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualUserPageHasSixPanelsInNarrativeOrder() {
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val stream=runCatching {assets.open("private/nota-bene-page.jpg")}.getOrNull()
        assumeTrue("Private user fixture is intentionally not distributed",stream!=null)
        val b=stream!!.use {BitmapFactory.decodeStream(it)}
        val p=BookRules.orderPanels(PanelDetector.detect(b,ctx),false)
        println("Nota bene panels: $p")
        assertEquals(6,p.size)
        assertTrue(p[0].left<p[1].left)
        assertTrue(p[2].width>.7f)
        assertTrue(p[3].left<.15f && p[4].left<.15f && p[4].top>p[3].top)
        assertTrue(p[5].left>.45f && p[5].height>p[3].height*1.7f)
        b.recycle()
    }
    @Test fun deletePreservesOriginalAndExcludesNextScans() {
        val repo=Repository(ctx);val old=repo.loadBooks()
        val original=File(ctx.filesDir,"original-test.cbz").apply {writeText("original unchanged")}
        val cover=File(ctx.filesDir,"covers/remove-test.jpg").apply {writeText("private cover")}
        val b=Book("remove-test",original.toURI().toString(),original.name,"Test",cover=cover.path)
        try {
            repo.saveBooks(old+b);repo.removeAlbum(b)
            assertTrue(original.exists());assertFalse(cover.exists())
            assertFalse(Repository(ctx).loadBooks().any {it.id==b.id})
            assertTrue(b.uri in Repository(ctx).excludedUris())
            repo.allowImport(b.uri);assertFalse(b.uri in repo.excludedUris())
        } finally {original.delete();repo.saveBooks(old);repo.allowImport(b.uri)}
    }
    @Test fun bdthequeSeriesDoesNotInventAlbumDateOrOverwriteUserFields() {
        val url="https://www.bdtheque.com/series/18390/nota-bene"
        val html="""<h1>Nota bene</h1><script type="application/ld+json">{"@type":"ComicSeries","name":"Nota bene","genre":"Histoire","author":{"name":"Auteur test"},"datePublished":"2024-01-01","description":"Résumé de test"}</script>"""
        val d=BdTheque.parse(html,url)
        assertEquals("Nota bene",d.series);assertEquals("",d.date)
        val b=BdTheque.merge(Book("1","","","Mon titre",writer="Mon auteur"),d,url)
        assertEquals("Mon titre",b.title);assertEquals("Mon auteur",b.writer);assertEquals("Histoire",b.genre)
        assertEquals(url,b.metadataSource)
        assertFalse(BdTheque.validUrl("https://bdtheque.com.attacker.test/series/1/title"))
        assertFalse(BdTheque.validUrl("https://www.bdtheque.com/"))
        assertFalse(BdTheque.validUrl("http://www.bdtheque.com/series/1/title"))
    }
    @Test fun bdthequeRatingUsesOnlyExplicitAggregateAndPersists() {
        val url="https://www.bdtheque.com/series/18390/nota-bene"
        val html="""<h1>Nota bene</h1><script type="application/ld+json">{"@type":"ComicSeries","name":"Nota bene","aggregateRating":{"ratingValue":4.25,"bestRating":5,"reviewCount":28}}</script>"""
        val d=BdTheque.parse(html,url)
        assertEquals(4.25,d.rating!!,0.0);assertEquals(5.0,d.ratingScale!!,0.0);assertEquals(28,d.reviewCount)
        assertNull(BdTheque.parse(html.replace(",\"bestRating\":5",""),url).rating)
        val repo=Repository(ctx);val old=repo.loadBooks()
        try {
            val book=BdTheque.merge(Book("rating-test","","","Title",writer="Keep author"),d,url)
            repo.saveBooks(old+book)
            val loaded=repo.loadBooks().first {it.id=="rating-test"}
            assertEquals(28,loaded.reviewCount);assertEquals(4.25,loaded.rating!!,0.0);assertEquals(url,loaded.ratingSource);assertEquals("Keep author",loaded.writer)
        } finally {repo.saveBooks(old)}
    }
    @Test fun oneDriveResourcesPreserveRemoteIdentity() {
        val value=OneDrive.uri("A12345","A12345!42")
        assertEquals("/drives/A12345/items/A12345!42",OneDrive.resource(value))
    }
    @Test fun oneDriveLoginHasPkceReadOnlyScopesAndResolvableCallback() {
        val request=OneDrive.get(ctx).authorizationRequest()
        assertEquals("b082ae2f-bde7-494e-850b-9c1539e410ac",request.clientId)
        assertEquals("fr.bubblebd.auth://oauth2redirect",request.redirectUri.toString())
        assertEquals("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",request.configuration.authorizationEndpoint.toString())
        assertEquals(setOf("https://graph.microsoft.com/Files.Read","offline_access"),request.scope!!.split(" ").toSet())
        assertEquals("S256",request.codeVerifierChallengeMethod)
        assertFalse(request.codeVerifier.isNullOrBlank())
        assertFalse(request.state.isNullOrBlank())
        assertNotEquals(request.state,OneDrive.get(ctx).authorizationRequest().state)
        val callback=android.content.Intent(android.content.Intent.ACTION_VIEW,request.redirectUri)
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE).setPackage(ctx.packageName)
        val activity=ctx.packageManager.resolveActivity(callback,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull(activity)
        assertEquals("net.openid.appauth.RedirectUriReceiverActivity",activity!!.activityInfo.name)
        assertNotNull(OneDrive.get(ctx).loginIntent())
    }

}
