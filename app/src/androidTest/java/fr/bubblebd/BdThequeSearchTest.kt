package fr.bubblebd

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local DOM fixtures: no network access or Cloudflare bypass. */
class BdThequeSearchTest {
    @Test fun googleFindsExactSeriesAndIgnoresBedetheque() {
        checkWork("""<a href="https://www.bedetheque.com/serie-321-BD-Test.html"><h3>Attends-moi - BD, informations, cotes</h3></a><a href="https://www.bdtheque.com/series/123/attends-moi"><h3>Attends-moi - BD, avis, informations, images, albums</h3></a>""","https://www.bdtheque.com/series/123/attends-moi",google=true)
    }
    @Test fun googleUnwrapsResultRedirect() {
        checkWork("""<a href="/url?q=https%3A%2F%2Fwww.bdtheque.com%2Falbums%2F123%2Fattends-moi"><h3>Attends-moi - BD, avis</h3></a>""","https://www.bdtheque.com/albums/123/attends-moi",google=true)
    }
    @Test fun googleDoesNotChooseAmongHomonyms() {
        checkWork("""<a href="https://www.bdtheque.com/series/123/attends-moi"><h3>Attends-moi - BD, avis</h3></a><a href="https://www.bdtheque.com/series/456/attends-moi"><h3>Attends-moi - BD, avis</h3></a>""",null,google=true)
    }
    @Test fun googleDoesNotChooseADifferentTitle() {
        checkWork("""<a href="https://www.bdtheque.com/series/123/autre"><h3>Un autre titre - BD, avis</h3></a>""",null,google=true)
    }
    @Test fun followsAlbumDetailFromReviewsAfterDelayedLoading() {
        checkWork("""<h1>Attends-moi</h1><div id="detail"></div><script>setTimeout(()=>document.getElementById('detail').innerHTML='<a href="/albums/123/attends-moi">Voir la fiche de cet album</a>',180);</script>""","https://www.bdtheque.com/albums/123/attends-moi")
    }
    @Test fun followsAssociatedWorkWithoutRepeatingSearch() {
        checkWork("""<h1>Avis des lecteurs</h1><a href="/albums/123/attends-moi">Voir la fiche de cet album</a>""","https://www.bdtheque.com/albums/123/attends-moi",true)
    }
    @Test fun opensAlbumSheetThroughTheSitesOwnButton() {
        checkWork("""<h1>Attends-moi</h1><button onclick="document.body.dataset.target='album-sheet';document.body.dataset.count=Number(document.body.dataset.count||0)+1">Voir la fiche de cet album</button>""","album-sheet")
    }
    @Test fun opensAlbumModalWithoutReloadingItsFragmentLink() {
        checkWork("""<h1>Attends-moi</h1><a href="#album-sheet" role="button" onclick="event.preventDefault();document.body.dataset.target='album-sheet';document.body.dataset.count=Number(document.body.dataset.count||0)+1">Voir la fiche de cet album</a>""","album-sheet")
    }
    @Test fun ambiguousWorksAreNotAutomaticallyChosen() {
        checkWork("""<a href="/albums/123/attends-moi">Attends-moi</a><a href="/albums/456/attends-moi">Attends-moi</a>""",null)
    }
    @Test fun externalDetailLinkIsNeverFollowed() {
        checkWork("""<h1>Attends-moi</h1><a href="https://example.com/albums/123/attends-moi">Voir la fiche de cet album</a>""",null)
    }
    private fun checkWork(body:String,expected:String?,associated:Boolean=false,google:Boolean=false) {
        val instrument=InstrumentationRegistry.getInstrumentation();val ctx=instrument.targetContext
        val script=ctx.assets.open(if(google)"web/bdtheque-google.js" else "web/bdtheque-work.js").bufferedReader().use {it.readText()}
        val latch=CountDownLatch(1);var actual="";lateinit var web:WebView
        instrument.runOnMainSync {
            web=WebView(ctx);web.layout(0,0,720,1080);web.settings.javaScriptEnabled=true
            web.webViewClient=object:WebViewClient() {
                override fun onPageFinished(view:WebView,url:String) {
                    view.evaluateJavascript(script+if(google)";findBubbleBdGoogleWork('Attends-moi');" else ";openBubbleBdWork('Attends-moi',$associated);",null)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({view.evaluateJavascript("JSON.stringify({target:document.body.dataset.target||'',count:document.body.dataset.count||'0'})") {value->actual=value;latch.countDown()}},1000)
                }
            }
            web.loadDataWithBaseURL(if(google)"https://www.google.com/search?q=bdtheque" else "https://www.bdtheque.com/reviews/fixture","<html><body><script>window.BubbleBdHost={workFound:function(u){document.body.dataset.target=u;document.body.dataset.count=Number(document.body.dataset.count||0)+1;}};</script>$body</body></html>","text/html","UTF-8",null)
        }
        assertTrue("Local work link responds",latch.await(30,TimeUnit.SECONDS))
        instrument.runOnMainSync {web.destroy()}
        val result=JSONObject(org.json.JSONTokener(actual).nextValue() as String)
        assertEquals(expected.orEmpty(),result.getString("target"));assertEquals(if(expected==null)"0" else "1",result.getString("count"))
    }
    @Test fun fillsDelayedModalOutsideFormAndStartsSearchOnce() {
        checkFixture("""<div role="dialog" id="modal"></div><script>
            setTimeout(function(){document.getElementById('modal').innerHTML='<input placeholder="Série ou auteur"><button type="button">Rechercher</button>';document.querySelector('button').onclick=function(){document.body.dataset.count=Number(document.body.dataset.count||0)+1;document.body.dataset.query=document.querySelector('input').value;};},180);
            </script>""")
    }
    @Test fun submitsClassicSearchFormWithItsFields() {
        checkFixture("""<form action="/recherche" onsubmit="event.preventDefault();document.body.dataset.count=Number(document.body.dataset.count||0)+1;document.body.dataset.query=this.q.value;"><input name="q" type="search"><input type="hidden" name="category" value="series"><button type="submit">Rechercher</button></form>""")
    }
    private fun checkFixture(body:String) {
        val instrument=InstrumentationRegistry.getInstrumentation();val ctx=instrument.targetContext
        val query="Le chant des runes"
        val script=ctx.assets.open("web/bdtheque-search.js").bufferedReader().use {it.readText()}
        val latch=CountDownLatch(1);var actual="";lateinit var web:WebView
        instrument.runOnMainSync {
            web=WebView(ctx);web.layout(0,0,720,1080);web.settings.javaScriptEnabled=true
            web.webViewClient=object:WebViewClient() {
                override fun onPageFinished(view:WebView,url:String) {
                    view.evaluateJavascript(script+";startBubbleBdSearch("+JSONObject.quote(query)+");",null)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({view.evaluateJavascript("JSON.stringify({query:document.body.dataset.query,count:document.body.dataset.count})") {value->actual=value;latch.countDown()}},900)
                }
            }
            web.loadDataWithBaseURL("https://www.bdtheque.com/","<html><body>$body</body></html>","text/html","UTF-8",null)
        }
        assertTrue("Local WebView responds",latch.await(30,TimeUnit.SECONDS))
        instrument.runOnMainSync {web.destroy()}
        val result=JSONObject(org.json.JSONTokener(actual).nextValue() as String)
        assertEquals(query,result.getString("query"));assertEquals("1",result.getString("count"))
    }
}
