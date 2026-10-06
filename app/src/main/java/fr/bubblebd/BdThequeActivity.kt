package fr.bubblebd

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Finds an exact public result, then remembers the displayed work. Access checks stay visible. */
class BdThequeActivity:Activity() {
    private lateinit var browser:WebView
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val query=intent.getStringExtra("query").orEmpty().take(180)
        val initial=intent.getStringExtra("initialUrl").orEmpty().trim().takeIf(BdTheque::validUrl)
        val bookId=intent.getStringExtra("bookId").orEmpty()
        var selectedUrl=initial.orEmpty()
        var metadataHtml=""
        val googleHosts=listOf("google.com","www.google.com","google.fr","www.google.fr")
        fun returnSelection() {if(selectedUrl.isNotBlank())setResult(RESULT_OK,Intent().putExtra("bookId",bookId).putExtra("bdtheque",selectedUrl).putExtra("metadataHtml",metadataHtml))}
        var submitted=initial!=null
        var associated=initial!=null
        val visited=mutableSetOf<String>()
        browser=WebView(this)
        browser.settings.javaScriptEnabled=true
        browser.settings.domStorageEnabled=true
        browser.settings.allowFileAccess=false
        browser.settings.allowContentAccess=false
        val searchScript=assets.open("web/bdtheque-search.js").bufferedReader().use {it.readText()}
        val workScript=assets.open("web/bdtheque-work.js").bufferedReader().use {it.readText()}
        val googleScript=assets.open("web/bdtheque-google.js").bufferedReader().use {it.readText()}
        val metadataScript=assets.open("web/bdtheque-metadata.js").bufferedReader().use {it.readText()}
        returnSelection()
        browser.addJavascriptInterface(object {
            @JavascriptInterface fun searchSubmitted() {runOnUiThread {
                submitted=true
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(browser.windowToken,0)
            }}
            @JavascriptInterface fun workFound(url:String) {runOnUiThread {
                if(BdTheque.validUrl(url) && visited.size<6 && visited.add(url)) {
                    selectedUrl=url;metadataHtml="";returnSelection()
                    associated=true;submitted=true
                    if(browser.url!=url)browser.loadUrl(url) else browser.evaluateJavascript(metadataScript,null)
                }
            }}
            @JavascriptInterface fun metadataFound(url:String,html:String) {runOnUiThread {
                if(url==selectedUrl && url==browser.url && BdTheque.validUrl(url) && html.length<=160000) {
                    metadataHtml=html;returnSelection()
                }
            }}
        },"BubbleBdHost")
        browser.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                val uri=request.url
                return uri.scheme!="https" || (uri.host !in listOf("bdtheque.com","www.bdtheque.com") && !(selectedUrl.isBlank() && uri.host in googleHosts))
            }
            override fun onPageFinished(view:WebView,url:String) {
                if(Uri.parse(url).host in googleHosts && selectedUrl.isBlank()) {
                    view.evaluateJavascript(googleScript+"\nfindBubbleBdGoogleWork("+JSONObject.quote(query)+");",null)
                    return
                }
                if(!url.startsWith("https://www.bdtheque.com/") && !url.startsWith("https://bdtheque.com/"))return
                if(selectedUrl.isBlank() && BdTheque.validUrl(url)) {
                    view.evaluateJavascript("(function(){const n=s=>s.toLowerCase().normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').trim();if(n(document.querySelector('h1')?.textContent||'')===n("+JSONObject.quote(query)+"))BubbleBdHost.workFound(location.href);})();",null)
                }
                if(url==selectedUrl)view.evaluateJavascript(metadataScript,null)
                view.evaluateJavascript(workScript+"\nopenBubbleBdWork("+JSONObject.quote(query)+",$associated);",null)
                if(!submitted) {
                    view.evaluateJavascript(searchScript+"\nstartBubbleBdSearch("+JSONObject.quote(query)+");",null)
                }
            }
        }
        setContentView(browser)
        browser.loadUrl(initial ?: if(query.isNotBlank())"https://www.google.com/search?q="+Uri.encode("bdtheque $query") else "https://www.bdtheque.com/")
    }
    override fun onDestroy() {browser.removeJavascriptInterface("BubbleBdHost");browser.destroy();super.onDestroy()}
}
