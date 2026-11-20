package app.cairn.browser.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.applyCanvas
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.Prefs
import app.cairn.browser.data.Search
import app.cairn.browser.ui.BrowserActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

const val NTP = "cairn://newtab"
fun isBlankUrl(u: String?) = u.isNullOrBlank() || u == "about:blank" || u.startsWith("about:blank#") || u.startsWith("about:blank?")

data class PageError(val url: String, val code: Int, val description: String)
class SslProblem(val url: String, val error: SslError, val handler: SslErrorHandler)

/** One browser tab. All fields are Compose state so the UI follows the page live. */
class Tab(val id: Long, val incognito: Boolean, initialUrl: String = "") {
    var url by mutableStateOf(initialUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    var icon by mutableStateOf<Bitmap?>(null)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var thumb by mutableStateOf<Bitmap?>(null)
    var blocked by mutableIntStateOf(0)
    var desktop by mutableStateOf(Prefs.desktopDefault)
    var error by mutableStateOf<PageError?>(null)
    var ssl by mutableStateOf<SslProblem?>(null)
    var unsafe by mutableStateOf<UnsafeSite?>(null)
    var findMatches by mutableStateOf(0 to 0)       // (active index, total)
    var sensitive by mutableStateOf(false)          // login / OTP / card / checkout page → Guard badge
    var translated by mutableStateOf<String?>(null)
    var lastActive = System.currentTimeMillis()
    var opener: Tab? = null
    /**
     * True while the content under the finger can still scroll up. Many sites (YouTube, Instagram, web apps) scroll an
     * inner element instead of the page, so WebView.scrollY stays 0 there — the page script reports the real state.
     */
    @Volatile var jsCanScrollUp = false
    @Volatile var jsScrollY = 0
    /** Root view for this tab: SwipeRefreshLayout wrapping the WebView. */
    var view: SwipeRefreshLayout? = null
    var web: WebView? = null
    val isNtp get() = url.isEmpty() || url == NTP
    val secure get() = url.startsWith("https://")
    val host get() = app.cairn.browser.data.hostOf(url)
}

/**
 * Tab model and WebView factory. There is a single [BrowserActivity] (it handles configuration changes itself),
 * so WebViews are created with that activity as their context and survive rotation.
 */
object Browser {
    private val ids = AtomicLong(System.currentTimeMillis())
    val tabs = mutableStateListOf<Tab>()
    var current by mutableStateOf<Tab?>(null)
    var shieldSession by mutableIntStateOf(0)
    lateinit var activity: BrowserActivity

    private val profilesSupported get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private const val PRIVATE_PROFILE = "cairn-private"

    fun normalTabs() = tabs.filter { !it.incognito }
    fun privateTabs() = tabs.filter { it.incognito }

    // ---------------- Tab lifecycle ----------------
    fun newTab(url: String = NTP, incognito: Boolean = false, foreground: Boolean = true, opener: Tab? = null): Tab {
        val t = Tab(ids.incrementAndGet(), incognito, if (url == NTP) "" else url)
        t.opener = opener
        val idx = opener?.let { tabs.indexOf(it) + 1 + tabs.count { o -> o.opener == it } }?.coerceAtMost(tabs.size) ?: tabs.size
        tabs.add(idx, t)
        if (url != NTP && url.isNotEmpty()) { ensureView(t); load(t, url) }
        if (foreground) select(t)
        persist()
        return t
    }

    fun select(t: Tab) {
        current?.let { if (it != t) { capture(it); it.web?.onPause() } }
        t.web?.onResume()
        t.lastActive = System.currentTimeMillis()
        current = t
        if (::activity.isInitialized) activity.refreshContainer()
    }

    fun close(t: Tab) {
        val i = tabs.indexOf(t); if (i < 0) return
        tabs.removeAt(i)
        destroy(t)
        if (current == t) {
            val sameKind = tabs.filter { it.incognito == t.incognito }
            val next = t.opener?.takeIf { it in tabs } ?: sameKind.getOrNull((i - 1).coerceAtLeast(0).coerceAtMost(sameKind.size - 1)) ?: tabs.lastOrNull()
            if (next != null) select(next) else current = null
        }
        if (t.incognito && privateTabs().isEmpty()) wipePrivate()
        if (tabs.isEmpty()) newTab()
        persist()
    }

    fun closeAll(incognito: Boolean) {
        tabs.filter { it.incognito == incognito }.forEach { tabs.remove(it); destroy(it) }
        if (incognito) wipePrivate()
        if (tabs.isEmpty()) newTab() else if (current?.incognito == incognito || current !in tabs) select(tabs.maxBy { it.lastActive })
        persist()
    }

    private fun destroy(t: Tab) {
        t.web?.let { w -> (t.view?.parent as? ViewGroup)?.removeView(t.view); w.stopLoading(); w.loadUrl("about:blank"); w.destroy() }
        t.web = null; t.view = null
    }

    private fun wipePrivate() {
        // With profile support, private tabs live in their own cookie/storage jar which is deleted here.
        if (profilesSupported) runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
    }

    /** Saves open (non-private) tabs so they come back after the app restarts. */
    fun persist() {
        val a = JSONArray()
        normalTabs().filter { !it.isNtp }.take(60).forEach { a.put(JSONObject().put("u", it.url).put("t", it.title)) }
        Prefs.tabsJson = JSONObject().put("tabs", a).put("cur", normalTabs().indexOf(current).coerceAtLeast(0)).toString()
    }

    fun restore() {
        if (tabs.isNotEmpty()) return
        if (Prefs.restoreTabs) runCatching {
            val j = JSONObject(Prefs.tabsJson); val a = j.getJSONArray("tabs")
            for (i in 0 until a.length()) tabs.add(Tab(ids.incrementAndGet(), false, a.getJSONObject(i).getString("u")).apply { title = a.getJSONObject(i).optString("t") })
            tabs.getOrNull(j.optInt("cur"))?.let { current = it }
            tabs.forEach { loadThumb(it) }
        }
        if (tabs.isEmpty()) newTab() else if (current == null) current = tabs.last()
    }

    /** Restored tabs only get a WebView when first shown. */
    fun ensureLoaded(t: Tab) {
        if (t.web == null && !t.isNtp) { ensureView(t); t.web?.loadUrl(t.url, headers()) }
    }

    // ---------------- Navigation ----------------
    fun headers(): Map<String, String> = if (Prefs.doNotTrack) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()

    /** Opens what the user typed: URL, or a web search. */
    fun open(input: String, t: Tab? = current) {
        val tab = t ?: newTab()
        val url = Search.toUrl(input) ?: Search.searchUrl(input)
        load(tab, url)
    }

    fun load(t: Tab, url: String) {
        if (url == NTP) { t.web?.loadUrl("about:blank"); t.url = ""; t.title = ""; return }
        ensureView(t)
        if (t == current) activity.refreshContainer()
        t.error = null; t.ssl = null; t.unsafe = null
        t.url = url
        t.web!!.loadUrl(url, headers())
    }

    fun back(tab: Tab? = current): Boolean {
        val t = tab ?: return false
        val w = t.web
        when {
            t.error != null && w?.canGoBack() == true -> { t.error = null; w.goBack() }
            w?.canGoBack() == true -> {
                // Skip runs of about:blank (NTP) entries so one Back press lands on a real page or the NTP once.
                val list = w.copyBackForwardList(); var steps = -1
                while (list.currentIndex + steps > 0 && isBlankUrl(list.getItemAtIndex(list.currentIndex + steps)?.url) &&
                    isBlankUrl(list.getItemAtIndex(list.currentIndex + steps - 1)?.url)) steps--
                if (t.isNtp && isBlankUrl(list.getItemAtIndex(list.currentIndex + steps)?.url) && list.currentIndex + steps > 0) steps--
                w.goBackOrForward(steps)
            }
            !t.isNtp && t.opener != null -> close(t)
            !t.isNtp -> { load(t, NTP); t.canBack = false }
            else -> return false
        }
        return true
    }
    fun forward(t: Tab? = current) { t?.web?.goForward() }
    fun reload(tab: Tab? = current) { val t = tab ?: return; t.error = null; if (t.web == null) ensureLoaded(t) else t.web?.reload() }
    fun stop(t: Tab? = current) { t?.web?.stopLoading() }

    fun setDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        t.web?.let { w -> applyUa(w, on); w.settings.loadWithOverviewMode = on; w.reload() }
    }

    /** Live thumbnail for the tab switcher. Visible tabs are copied from the screen (PixelCopy, GPU-exact);
     *  hidden ones are drawn in software. Thumbnails are cached on disk so restored tabs have them too. */
    fun capture(t: Tab) {
        val w = t.web ?: return
        if (w.width <= 0 || w.height <= 0 || t.isNtp || t.error != null) return
        val scale = 0.42f
        val bw = (w.width * scale).toInt().coerceAtLeast(1); val bh = (w.width * scale * 1.3f).toInt().coerceAtMost((w.height * scale).toInt()).coerceAtLeast(1)
        val shown = w.isShown && t == current && ::activity.isInitialized && !activity.overlayCoversPage()
        if (shown) {
            val loc = IntArray(2); w.getLocationInWindow(loc)
            val full = Bitmap.createBitmap(w.width, (bh / scale).toInt().coerceAtMost(w.height), Bitmap.Config.ARGB_8888)
            runCatching {
                android.view.PixelCopy.request(activity.window, android.graphics.Rect(loc[0], loc[1], loc[0] + full.width, loc[1] + full.height), full, { r ->
                    if (r == android.view.PixelCopy.SUCCESS) { val sm = Bitmap.createScaledBitmap(full, bw, bh, true); full.recycle(); setThumb(t, sm) } else drawThumb(t, w, scale, bw, bh)
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }.onFailure { drawThumb(t, w, scale, bw, bh) }
        } else drawThumb(t, w, scale, bw, bh)
    }
    private fun drawThumb(t: Tab, w: WebView, scale: Float, bw: Int, bh: Int) = runCatching {
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.applyCanvas { scale(scale, scale); translate(-w.scrollX.toFloat(), -w.scrollY.toFloat()); w.draw(this) }
        // A blank result (some GPUs return nothing for software draws) must not replace a good thumbnail
        if (!isBlank(bmp)) setThumb(t, bmp)
    }
    private fun isBlank(b: Bitmap): Boolean {
        val first = b.getPixel(b.width / 2, b.height / 2); var same = 0
        for (i in 1..24) { if (b.getPixel((b.width * (i % 6 + 1)) / 7, (b.height * (i / 6 + 1)) / 5) == first) same++ }
        return same >= 24
    }
    private fun setThumb(t: Tab, b: Bitmap) {
        t.thumb = b
        if (t.incognito) return
        val f = thumbFile(t.url) ?: return
        Thread { runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) } } }.start()
    }
    private fun thumbFile(url: String): java.io.File? = if (!::activity.isInitialized || url.isBlank()) null
        else java.io.File(activity.cacheDir, "thumbs").apply { mkdirs() }.let { java.io.File(it, Integer.toHexString(url.hashCode()) + ".jpg") }
    fun loadThumb(t: Tab) { if (t.thumb == null) thumbFile(t.url)?.takeIf { it.exists() }?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.let { t.thumb = it } } }

    /** Persist cookies/logins immediately so Amazon, Flipkart etc. stay signed in across restarts. */
    fun flushCookies() { runCatching { CookieManager.getInstance().flush() } }

    // ---------------- WebView factory ----------------
    private val chromeVersion by lazy {
        Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(activity))?.groupValues?.get(1) ?: "131.0.0.0"
    }
    fun mobileUa(): String = WebSettings.getDefaultUserAgent(activity).replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
    fun desktopUa(): String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeVersion.substringBefore('.')}.0.0.0 Safari/537.36"
    private fun applyUa(w: WebView, desktop: Boolean) { w.settings.userAgentString = if (desktop) desktopUa() else mobileUa() }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(w: WebView, incognito: Boolean, desktop: Boolean = false) {
        if (incognito && profilesSupported) runCatching {
            ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            WebViewCompat.setProfile(w, PRIVATE_PROFILE)
        }
        w.settings.apply {
            javaScriptEnabled = Prefs.javascript
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = desktop
            textZoom = Prefs.textZoom
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = Prefs.popups
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false; allowContentAccess = true
            safeBrowsingEnabled = false // Cairn uses no Google services; Shield + Guard cover tracking and phishing heuristics
            cacheMode = if (incognito && !profilesSupported) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
        }
        applyUa(w, desktop)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, Prefs.darkPages)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
            runCatching { WebSettingsCompat.setWebAuthenticationSupport(w.settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER) }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, !Prefs.blockThirdPartyCookies)
    }

    /** Re-applies settings that changed in the Settings screen to every open tab. */
    fun applySettings() {
        tabs.forEach { t -> t.web?.let { w ->
            w.settings.javaScriptEnabled = Prefs.javascript; w.settings.textZoom = Prefs.textZoom
            w.settings.javaScriptCanOpenWindowsAutomatically = Prefs.popups
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, !Prefs.blockThirdPartyCookies)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, Prefs.darkPages)
        } }
    }

    fun ensureView(t: Tab) {
        if (t.web != null) return
        val ctx = activity
        val w = WebView(ctx)
        configure(w, t.incognito, t.desktop)
        w.setBackgroundColor(if (app.cairn.browser.ui.C.dark) 0xFF1C1C1F.toInt() else 0xFFFFFFFF.toInt())
        w.isScrollbarFadingEnabled = true
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.addJavascriptInterface(Downloads.Bridge(ctx, t), "CairnDownloads")
        app.cairn.browser.vault.WebBridge.attach(w, t)
        val swipe = SwipeRefreshLayout(ctx).apply {
            addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { reload(t); postDelayed({ isRefreshing = false }, 900) }
            setColorSchemeColors(0xFFB89A6A.toInt())
            setProgressBackgroundColorSchemeColor(if (app.cairn.browser.ui.C.dark) 0xFF2B2B2F.toInt() else 0xFFFFFFFF.toInt())
            // Pull-to-refresh only when the page is genuinely at the top — for the document *and* any inner scroller.
            setOnChildScrollUpCallback { _, _ -> !Prefs.pullToRefresh || w.scrollY > 0 || t.jsCanScrollUp || w.canScrollVertically(-1) }
            setDistanceToTriggerSync((resources.displayMetrics.density * 110).toInt())
        }
        w.addJavascriptInterface(ScrollBridge(t), "CairnScroll")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            runCatching { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(w, SCROLL_JS, setOf("*")) }
        w.setOnScrollChangeListener { _, _, y, _, oldY -> activity.onPageScrolled(t, y - oldY, y) }
        w.webViewClient = Client(t)
        w.webChromeClient = Chrome(t)
        w.setDownloadListener { url, ua, cd, mime, len -> Downloads.start(ctx, t, url, ua, cd, mime, len) }
        w.setOnLongClickListener { activity.onLongPress(t, w) }
        w.setFindListener { active, total, done -> if (done) t.findMatches = (if (total == 0) 0 else active + 1) to total }
        t.web = w; t.view = swipe
    }

    /** Reports touch-time and live scroll state of the element actually scrolling (document or inner container). */
    private const val SCROLL_JS = """(function(){if(window.__cairnScroll||!window.CairnScroll)return;window.__cairnScroll=1;
function up(t){if((window.scrollY||document.documentElement.scrollTop||document.body&&document.body.scrollTop||0)>0)return true;
for(var e=t;e&&e.nodeType===1&&e!==document.documentElement;e=e.parentElement||(e.getRootNode&&e.getRootNode().host)){
if(e.scrollTop>0&&e.scrollHeight>e.clientHeight+4){var o=getComputedStyle(e).overflowY;if(o=='auto'||o=='scroll'||o=='overlay')return true}}return false}
addEventListener('touchstart',function(ev){try{CairnScroll.touch(up(ev.target))}catch(x){}},{capture:true,passive:true});
var lt=0;addEventListener('scroll',function(ev){var t=ev.target,d=(t===document||t===document.documentElement||t===document.body);
if(!d&&!(t.clientHeight>innerHeight*0.5))return;var y=d?(window.scrollY||0):(t.scrollTop||0);var n=Date.now();
if(n-lt<60&&y>0)return;lt=n;try{CairnScroll.scroll(Math.round(y*(window.devicePixelRatio||1)),y>0)}catch(x){}},{capture:true,passive:true});})();"""

    private class ScrollBridge(val t: Tab) {
        @android.webkit.JavascriptInterface fun touch(canUp: Boolean) { t.jsCanScrollUp = canUp }
        @android.webkit.JavascriptInterface fun scroll(y: Int, canUp: Boolean) {
            val dy = y - t.jsScrollY; t.jsScrollY = y; t.jsCanScrollUp = canUp
            // Inner-scroller sites never move WebView.scrollY, so drive the dock from the page's own scroll too.
            t.web?.let { w -> if (w.scrollY == 0) w.post { activity.onPageScrolled(t, dy, y) } }
        }
    }

    private class Client(val t: Tab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url; val s = u.scheme?.lowercase() ?: return false
            if ((s == "http" || s == "https") && app.cairn.browser.ui.OneTap.intercept(t, u, req.isForMainFrame)) return true
            if (s == "http" || s == "https" || s == "about" || s == "data" || s == "blob" || s == "javascript") return false
            if (s == "cairn" && u.host == "ask") {
                // "Ask a follow-up" from a Cairn Search page continues in the assistant, with that page as context.
                val q = u.getQueryParameter("q").orEmpty()
                app.cairn.browser.ui.Ui.assistant = true
                if (q.isNotBlank()) app.cairn.browser.agent.Agent.run(q) else app.cairn.browser.ui.Ui.assistantDraft = ""
                return true
            }
            return activity.openExternal(u, req.isForMainFrame && req.hasGesture())
        }
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
            // about:blank is how a tab shows the New Tab page. Going back onto it must show the NTP, not an empty
            // white WebView (that looked like a crash).
            if (isBlankUrl(url)) { t.url = ""; t.title = ""; t.loading = false; t.error = null; t.progress = 100; return }
            app.cairn.browser.vault.WebBridge.fillOffer.value?.takeIf { it.tab == t }?.let { app.cairn.browser.vault.WebBridge.fillOffer.value = null }
            t.loading = true; t.url = url; t.icon = favicon ?: BrowserDb.icon(url); t.blocked = 0; t.error = null; t.sensitive = false; t.translated = null
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }
        override fun onPageFinished(view: WebView, url: String) {
            t.loading = false; t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
            if (isBlankUrl(url)) { t.url = ""; t.title = ""; return }
            t.url = url; t.title = view.title?.takeIf { it != url } ?: t.title
            if (!t.incognito && url.startsWith("http")) BrowserDb.addVisit(url, view.title ?: "")
            if (!t.incognito) persist()
            view.postDelayed({ if (current == t) capture(t) }, 450)
            view.postDelayed({ if (current == t && !t.loading) capture(t) }, 2200)
            if (url.startsWith("http")) {
                flushCookies()
                t.jsCanScrollUp = false; t.jsScrollY = 0
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(SCROLL_JS, null)
                view.evaluateJavascript("(function(){${app.cairn.browser.agent.PageJs.script}\nreturn JSON.stringify(window.${app.cairn.browser.agent.PageJs.ns}.guard())})()") { r ->
                    t.sensitive = runCatching { JSONObject(org.json.JSONTokener(r).nextValue() as String).optBoolean("sensitive") }.getOrDefault(false)
                }
                if (!t.incognito && Prefs.memory) view.postDelayed({
                    // Never index login, OTP, card or checkout pages into on-device memory
                    if (t.web != view || view.url != url || t.sensitive || app.cairn.browser.data.Recall.isPrivateUrl(url)) return@postDelayed
                    view.evaluateJavascript("(function(){${app.cairn.browser.agent.PageJs.script}\nvar a=window.${app.cairn.browser.agent.PageJs.ns}.article();return JSON.stringify({t:a.title,x:a.text})})()") { r ->
                        runCatching { val j = JSONObject(org.json.JSONTokener(r).nextValue() as String); val title = j.optString("t"); val text = j.optString("x")
                            Thread { app.cairn.browser.data.Recall.index(url, title, text) }.start() }
                    }
                }, 2500)
            }
        }
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            t.url = if (isBlankUrl(url)) "" else url
            t.canBack = view.canGoBack(); t.canForward = view.canGoForward()
        }
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (req.url.host == app.cairn.browser.data.CairnSearch.HOST) return app.cairn.browser.data.CairnSearch.intercept(req, app.cairn.browser.ui.C.dark)
            if (req.isForMainFrame && (req.url.scheme == "http" || req.url.scheme == "https")) {
                // Runs on WebView's network thread, so the page never starts loading if it's dangerous.
                SafeBrowsing.check(req.url.host)?.let { kind ->
                    val u = req.url.toString()
                    view.post { t.unsafe = UnsafeSite(u, kind); t.loading = false; t.progress = 100 }
                    return WebResourceResponse("text/html", "utf-8", java.io.ByteArrayInputStream("<!doctype html><title>Dangerous site</title>".toByteArray()))
                }
            }
            if (Prefs.shield && !req.isForMainFrame && Shield.isBlocked(req.url.host)) {
                view.post { t.blocked++; shieldSession++; Prefs.shieldTotal = Prefs.shieldTotal + 1 }
                return Shield.empty()
            }
            return null
        }
        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            if (err.errorCode == ERROR_UNSUPPORTED_SCHEME) return
            t.error = PageError(req.url.toString(), err.errorCode, err.description?.toString() ?: "")
            t.loading = false
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never proceed silently. The UI shows a full-page warning and only proceeds if the user explicitly insists.
            t.ssl = SslProblem(error.url, error, handler); t.loading = false
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Recover instead of crashing the whole browser
            view.post {
                val url = t.url; (t.view?.parent as? ViewGroup)?.removeView(t.view); runCatching { view.destroy() }; t.web = null; t.view = null
                if (t.isNtp) return@post
                // The visible tab reloads straight away; background tabs reload when they're next shown.
                if (t == current) { t.error = null; activity.refreshContainer() } else t.error = PageError(url, -1000, "This page was closed to save memory")
            }
            return true
        }
    }

    private class Chrome(val t: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) { t.progress = p; if (p == 100) t.loading = false }
        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank() && title != view.url) { t.title = title; if (!t.incognito) BrowserDb.updateTitle(view.url ?: t.url, title) }
        }
        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            if (icon == null) return
            t.icon = icon; if (!t.incognito) view.url?.let { BrowserDb.saveIcon(it, icon); Favicons.offer(it, icon) }
        }
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val site = runCatching { Uri.parse(t.url).let { "${it.scheme}://${it.authority}" } }.getOrDefault("")
            val allowed = BrowserDb.perm(site, "popups") ?: Prefs.popups
            if (!isUserGesture && !allowed) { activity.toast("Pop-up blocked · allow it in Site settings"); return false }
            val nt = newTab(NTP, t.incognito, foreground = !Prefs.openLinksInBackground || !isUserGesture, opener = t)
            ensureView(nt)
            (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
            resultMsg.sendToTarget()
            activity.refreshContainer()
            return true
        }
        override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.web == window }?.let { close(it) } }
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean = activity.chooseFile(cb, params)
        override fun onPermissionRequest(request: PermissionRequest) { activity.onSitePermission(t, request) }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { activity.onGeoPermission(t, origin, callback) }
        override fun onShowCustomView(view: View, callback: CustomViewCallback) { activity.enterFullscreen(view, callback) }
        override fun onHideCustomView() { activity.exitFullscreen() }
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }
}

fun BrowserActivity.openIntentSafely(i: Intent): Boolean = try { startActivity(i); true } catch (_: ActivityNotFoundException) { false }
