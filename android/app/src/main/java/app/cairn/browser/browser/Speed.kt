package app.cairn.browser.browser

import android.net.Uri
import android.os.CancellationSignal
import android.webkit.WebSettings
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executors

/**
 * Chrome-style "instant" page loads on top of Android WebView. Every call is feature-gated: on an older WebView
 * the browser simply behaves as before.
 *
 *  - Back/forward cache: Back and Forward show the previous page instantly from memory instead of reloading it.
 *  - Speculation rules: pages that ask for prerendering (Google Search, many news sites) open links instantly.
 *  - Renderer warm-up: the page process starts while the app opens, so the first page doesn't wait for it.
 *  - Preconnect: DNS + TCP + TLS to a site are done while you are still typing or touching a link.
 *  - Prefetch: the top history suggestion is fetched in the background so pressing Go shows it at once.
 */
@androidx.annotation.OptIn(markerClass = [
    Profile.ExperimentalPreconnect::class, Profile.ExperimentalUrlPrefetch::class, Profile.ExperimentalWarmUpRendererProcess::class,
    WebSettingsCompat.ExperimentalSpeculativeLoading::class, WebSettingsCompat.ExperimentalBackForwardCacheSettings::class,
])
object Speed {
    /** DB writes and other small jobs that used to run on the UI thread during page loads. */
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cairn-io").apply { priority = Thread.NORM_PRIORITY - 1 } }

    private fun profile(): Profile? = runCatching {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) null
        else ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME)
    }.getOrNull()

    fun configure(s: WebSettings, incognito: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.BACK_FORWARD_CACHE))
            runCatching { WebSettingsCompat.setBackForwardCacheEnabled(s, true) }
        if (!incognito && app.cairn.browser.data.Prefs.preloadPages && WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING))
            runCatching { WebSettingsCompat.setSpeculativeLoadingStatus(s, WebSettingsCompat.SPECULATIVE_LOADING_PRERENDER_ENABLED) }
    }

    /** Called once WebView is initialised (main thread). */
    fun warmUp() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WARM_UP_RENDERER_PROCESS)) runCatching { profile()?.warmUpRendererProcess() }
    }

    private val recent = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 32
    }

    /** Opens a connection to [url]'s site ahead of time. Cheap and safe: no request is made, no cookies are sent. */
    fun preconnect(url: String?) {
        if (!app.cairn.browser.data.Prefs.preloadPages) return
        val origin = originOf(url) ?: return
        val now = System.currentTimeMillis()
        synchronized(recent) { if ((recent[origin] ?: 0L) > now - 20_000) return; recent[origin] = now }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT)) runCatching { profile()?.preconnect(origin) }
    }

    private var prefetchCancel: CancellationSignal? = null
    private var prefetched: String? = null

    /** Fetches a page the user is very likely to open (their top history match). https only, normal tabs only. */
    fun prefetch(url: String?) {
        if (!app.cairn.browser.data.Prefs.preloadPages) return
        if (url == null || !url.startsWith("https://") || url == prefetched) return
        // Never fetch links that could change something just by being opened.
        if (Regex("(?i)log-?out|sign-?out|unsubscribe|delete|remove|confirm|verify|token=|otp|pay|checkout|cart").containsMatchIn(url)) { preconnect(url); return }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROFILE_URL_PREFETCH)) { preconnect(url); return }
        val p = profile() ?: return
        prefetchCancel?.cancel()
        val c = CancellationSignal(); prefetchCancel = c; prefetched = url
        runCatching {
            p.prefetchUrlAsync(url, c, io, object : androidx.webkit.WebViewOutcomeReceiver<Void, androidx.webkit.PrefetchException> {
                override fun onResult(result: Void?) {}
                override fun onError(error: androidx.webkit.PrefetchException) { if (prefetched == url) prefetched = null }
            })
        }.onFailure { prefetched = null; preconnect(url) }
    }

    private fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (u.scheme != "https" && u.scheme != "http") return null
        val host = u.host ?: return null
        return "${u.scheme}://$host" + if (u.port > 0) ":${u.port}" else ""
    }
}
