package app.cairn.analytics

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/** Hosts the bundled dashboard (server/web/admin) and keeps the home-screen widget in sync with it. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#161618"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            addJavascriptInterface(Host(), "CairnHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == "appassets.androidplatform.net") return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }; return true
                }
            }
        }
        setContentView(web)
        // Seed the page with the key saved by an earlier session (e.g. after the app data was kept but WebView storage cleared).
        val key = Stats.key(this)
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html" + if (key.isNotBlank()) "#key=" + Uri.encode(key) else "")
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (web.canGoBack()) web.goBack() else finish()
        }
        StatsWidget.schedule(this)
    }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }

    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }

    inner class Host {
        @JavascriptInterface fun saveKey(key: String?, api: String?) {
            val k = key.orEmpty(); if (k == Stats.key(this@MainActivity) && api.orEmpty().trimEnd('/') == Stats.api(this@MainActivity)) return
            Stats.saveKey(this@MainActivity, k, api.orEmpty())
            runOnUiThread { StatsWidget.render(this@MainActivity) }
        }
        /** Every dashboard refresh (15 s) pushes the headline numbers so the widget is current while the app is open. */
        @JavascriptInterface fun push(json: String?) {
            val o = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return
            Stats.store(this@MainActivity, Stats.fromOverview(o))
            runOnUiThread { StatsWidget.render(this@MainActivity) }
        }
    }
}
