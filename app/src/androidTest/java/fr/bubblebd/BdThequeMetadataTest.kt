package fr.bubblebd

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BdThequeMetadataTest {
    private val url="https://www.bdtheque.com/series/123/titre-test"
    private val fields="""<h1>Titre test</h1><table><tr><td>Scénario</td><td>Auteur test</td></tr><tr><td>Dessin</td><td>Artiste test</td></tr><tr><td>Editeur</td><td>Editeur test</td></tr><tr><td>Genre / Public / Type</td><td><a href="/recherche/series/genre=roman-graphique">Roman Graphique</a> / Adultes / BD</td></tr><tr><td>Date de parution</td><td>07 Mai 2026</td></tr><tr><td>Statut histoire</td><td>One shot 1 tome paru</td></tr></table><div><img alt="Note: 4/5"><small>(4/5 pour <a>1 avis</a>)</small></div><p class="lead">Résumé public.</p><div id="series_comments"><img alt="Note: 5/5"><p>COMMENTAIRE A EXCLURE</p></div>"""
    private fun album(number:Int,isbn:String)="""<div class="card"><h5 class="card-title">$number - Titre test</h5><small>Date de parution : 07 Mai 2026</small><small>| ISBN : $isbn</small><p class="card-text">Résumé album.</p></div>"""
    @Test fun readsOrdinaryTablesGlobalRatingAndOneShotAlbum() {
        val d=BdTheque.parse(fields+"<div id='albums'>"+album(1,"9782808218757")+"</div>",url)
        assertEquals("Auteur test",d.writer);assertEquals("Artiste test",d.artist);assertEquals("Editeur test",d.publisher);assertEquals("Roman Graphique",d.genre)
        assertEquals(4.0,d.rating!!,0.0);assertEquals(1,d.reviewCount);assertEquals("2026-05-07",d.date);assertEquals("9782808218757",d.isbn)
        assertEquals("",BdTheque.parse(fields+"<div id='albums'>"+album(1,"9782808218757")+"</div>",url,"2").isbn)
        val old=Book("qa","","test.pdf","Titre test",artist="Ma correction",page=5,started=true)
        val merged=BdTheque.merge(old,d,url)
        assertEquals("Ma correction",merged.artist);assertEquals(5,merged.page);assertEquals(url,merged.ratingSource)
    }
    @Test fun seriesDatesAndOtherVolumesAreNeverAssignedToAnAlbum() {
        val html=fields.replace("One shot 1 tome paru","En cours 2 tomes parus")+"<div id='albums'>"+album(1,"111")+album(2,"222")+"</div>"
        assertEquals("",BdTheque.parse(html,url).date);assertEquals("",BdTheque.parse(html,url).isbn)
        assertEquals("222",BdTheque.parse(html,url,"2").isbn)
        assertEquals("",BdTheque.parse(html,url,"3").isbn)
        assertEquals("",BdTheque.parse(html.replace("1 - Titre test","Sans numéro"),url,"HS").isbn)
    }
    @Test fun individualReviewAndUncountedScoreAreNotGlobalRatings() {
        assertNull(BdTheque.parse("<h1>Titre test</h1><div id='series_comments'><img alt='Note: 5/5'><small>pour 1 avis</small></div>",url).rating)
        assertNull(BdTheque.parse("<h1>Titre test</h1><img alt='Note: 4/5'>",url).rating)
    }
    @Test fun browserCapturesDelayedAlbumFieldsWithoutReviews() {
        val instrument=InstrumentationRegistry.getInstrumentation();val ctx=instrument.targetContext
        val script=ctx.assets.open("web/bdtheque-metadata.js").bufferedReader().use {it.readText()}
        val latch=CountDownLatch(1);var actual="";lateinit var web:WebView
        instrument.runOnMainSync {
            web=WebView(ctx);web.layout(0,0,720,1080);web.settings.javaScriptEnabled=true
            web.webViewClient=object:WebViewClient() {
                override fun onPageFinished(view:WebView,pageUrl:String) {
                    view.evaluateJavascript(script,null)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({view.evaluateJavascript("window.capturedHtml||''") {value->actual=value;latch.countDown()}},1200)
                }
            }
            val delayed=org.json.JSONObject.quote(album(1,"9782808218757"))
            web.loadDataWithBaseURL(url,"<html><body>$fields<div id='albums'></div><script>window.BubbleBdHost={metadataFound:(url,html)=>window.capturedHtml=html};setTimeout(()=>document.getElementById('albums').innerHTML=$delayed,200);</script></body></html>","text/html","UTF-8",null)
        }
        assertTrue(latch.await(30,TimeUnit.SECONDS));instrument.runOnMainSync {web.destroy()}
        val html=JSONTokener(actual).nextValue() as String
        assertFalse(html.contains("COMMENTAIRE A EXCLURE"))
        val d=BdTheque.parse(html,url)
        assertEquals("9782808218757",d.isbn);assertEquals(4.0,d.rating!!,0.0);assertEquals("2026-05-07",d.date)
    }
}
