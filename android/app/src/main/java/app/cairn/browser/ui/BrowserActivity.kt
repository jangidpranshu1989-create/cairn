package app.cairn.browser.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.cairn.browser.agent.Agent
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.Downloads
import app.cairn.browser.browser.NTP
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Api
import app.cairn.browser.data.AppState
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.Prefs
import app.cairn.browser.service.SyncService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class LinkMenu(val tab: Tab, val link: String?, val image: String?, val text: String?)
data class SitePrompt(val origin: String, val what: String, val icon: String, val decide: (Boolean, Boolean) -> Unit)

/** Everything the Compose UI shows on top of the web content. */
object Ui {
    var screen by mutableStateOf<String?>(null)          // null = browser; "history", "bookmarks", "downloads", "settings…", "reader"
    var omnibox by mutableStateOf(false)
    var omniboxText by mutableStateOf<String?>(null)    // prefill
    var omniAgent by mutableStateOf(false)               // opened from the home Omni-bar in agent mode: plain text goes to the agent
    private val TASK = Regex("(?i)^(find|search for|compare|buy|order|book|plan|track|summari[sz]e|research|read|translate|explain|make|create|write|show|dhoondo|dhundo|kharid|mujhe|sabse|cart)\\b|\\b(karo|kar do|kr do|dikhao|batao|chahiye)\\b")
    fun looksLikeTask(s: String) = TASK.containsMatchIn(s.trim()) && s.trim().contains(' ')
    var tabSwitcher by mutableStateOf(false)
    var menu by mutableStateOf(false)
    var assistant by mutableStateOf(false)
    var find by mutableStateOf(false)
    var siteInfo by mutableStateOf(false)
    var linkMenu by mutableStateOf<LinkMenu?>(null)
    var prompt by mutableStateOf<SitePrompt?>(null)
    var fullscreen by mutableStateOf<View?>(null)
    var collapsed by mutableStateOf(false)               // address bar minimised while scrolling
    var pairLink by mutableStateOf<Uri?>(null)
    var assistantDraft by mutableStateOf("")
    var lasso by mutableStateOf(false)                   // circle-to-act overlay
    var lassoFrame by mutableStateOf<android.graphics.Bitmap?>(null)  // frozen page frame the lasso selects from
    var pip by mutableStateOf(true)                      // live mini-preview of background agent work
    var mentions = androidx.compose.runtime.mutableStateListOf<Tab>()  // @tab context for the next message
    val attachments = androidx.compose.runtime.mutableStateListOf<app.cairn.browser.agent.Attachment>()
    var chats by mutableStateOf(false)                    // chat history list inside the assistant
    var profile by mutableStateOf(false)                  // Chrome-style account sheet
    var attachTray by mutableStateOf(false)               // widget "Photo": open the attach tray in the assistant
    val stack = ArrayDeque<String>()

    fun go(s: String) { screen?.let { stack.addLast(it) }; screen = s; menu = false; tabSwitcher = false }
    fun back() { screen = stack.removeLastOrNull() }
}

class BrowserActivity : FragmentActivity() {
    lateinit var webHost: FrameLayout; private set
    private lateinit var bgHost: FrameLayout
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var permCont: ((Map<String, Boolean>) -> Unit)? = null
    private var roleCont: (() -> Unit)? = null

    private var cameraUri: Uri? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(FileChooser.result(this, r.resultCode, r.data, cameraUri)); fileCallback = null; cameraUri = null
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> permCont?.invoke(r); permCont = null }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { roleCont?.invoke(); roleCont = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Browser.activity = this
        Haptics.view = window.decorView
        webHost = FrameLayout(this)
        bgHost = FrameLayout(this).apply { clipChildren = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        Browser.restore()
        app.cairn.browser.agent.Chats.init(this)
        AppState.paired.value = Prefs.isPaired
        AppState.refreshFiles()
        handleIntent(intent, cold = true)
        setContent { CairnTheme { App(webHost, bgHost) } }
        // Chrome-style pre-warm: once the first frame is idle, spin up a spare WebView so the next tab opens instantly.
        android.os.Looper.myQueue().addIdleHandler { runCatching { Browser.prewarm() }; false }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && Prefs.onboarded)
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent, cold = false) }

    override fun onStop() { super.onStop(); Browser.flushCookies() }

    /** Under memory pressure free background tabs (they reload when shown) instead of letting Android kill the app. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> { Browser.trimLiveTabs(all = true); Browser.dropSpare() }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> { Browser.trimLiveTabs(all = true); Browser.dropSpare() }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> Browser.trimLiveTabs()
        }
    }

    override fun onDestroy() {
        runCatching { Browser.dropSpare() }
        // Closing Cairn (swipe from Recents / Close all, or leaving the last screen) ends Incognito, like Chrome.
        if (isFinishing && !isChangingConfigurations) runCatching { Browser.endPrivateSession() }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        app.cairn.browser.service.AgentService.foreground = true
        runCatching { app.cairn.browser.data.Telemetry.start() }
        Browser.current?.web?.onResume()
        if (Prefs.isPaired) SyncService.start(this, SyncService.ACTION_SYNC)
        runCatching { app.cairn.browser.data.ServerConfig.migrate() }
        Thread { runCatching { app.cairn.browser.data.ServerConfig.refresh() } }.start()
        app.cairn.browser.agent.Llm.warm() // wake Cairn Cloud (free tier sleeps when idle) before the first AI request
        if (app.cairn.browser.Crash.consumeRecovered()) { app.cairn.browser.data.Telemetry.event("crash"); toast("Cairn recovered from a problem — your tabs are back") }
        app.cairn.browser.vault.Sync.soon(800)
    }

    override fun onPause() {
        super.onPause()
        app.cairn.browser.service.AgentService.foreground = false
        runCatching { app.cairn.browser.data.Telemetry.stop() }
        Browser.flushCookies()
        Browser.current?.let { Browser.capture(it) }
        Browser.persist()
    }

    private fun handleIntent(i: Intent?, cold: Boolean) {
        i ?: return
        when (i.action) {
            Intent.ACTION_VIEW -> {
                val u = i.data ?: return
                if (u.scheme == "cairn" && u.host == "pair") { Ui.pairLink = u; Ui.go("settings/pair"); return }
                if (u.scheme == "http" || u.scheme == "https") { Browser.newTab(u.toString()); Ui.screen = null }
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> i.getStringExtra("query")?.let { Browser.open(it, Browser.newTab()); Ui.screen = null }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
                val url = Regex("https?://\\S+").find(t)?.value
                if (url != null) Browser.newTab(url) else { Ui.assistant = true; Ui.assistantDraft = t }
            }
            ACTION_NEW_TAB -> { Browser.newTab(); Ui.screen = null; if (!cold) Ui.omnibox = true }
            ACTION_PRIVATE_TAB -> { Browser.newTab(NTP, incognito = true); Ui.screen = null }
            ACTION_ASSISTANT -> { Ui.screen = null; Ui.assistant = true }
            ACTION_FOCUS_SEARCH -> { Ui.screen = null; Ui.assistant = false; Browser.current?.takeIf { it.isNtp && !it.incognito } ?: Browser.newTab(); webHost.postDelayed({ Ui.omnibox = true }, if (cold) 420 else 60) }
            ACTION_VOICE -> { Ui.screen = null; webHost.postDelayed({ listen { t -> voiceCommand(t) } }, if (cold) 600 else 150) }
            ACTION_ASK_PHOTO -> { Ui.screen = null; Ui.assistant = true; Ui.attachTray = true }
        }
        refreshContainer()
    }

    // ---------------- Web container ----------------
    /** Puts the current tab's view into the web host (called whenever the current tab changes). */
    fun refreshContainer() {
        val t = Browser.current ?: return
        if (!t.isNtp) Browser.ensureLoaded(t)
        val v = t.view
        for (k in webHost.childCount - 1 downTo 0) { val c = webHost.getChildAt(k); if (c != v) webHost.removeViewAt(k) }
        if (v != null && v.parent != webHost) { (v.parent as? ViewGroup)?.removeView(v); webHost.addView(v, FrameLayout.LayoutParams(-1, -1)) }
    }

    fun backgroundHost(): FrameLayout = bgHost

    private var scrollAcc = 0
    private var lastToggle = 0L
    fun onPageScrolled(t: Tab, dy: Int, y: Int) {
        if (t != Browser.current) return
        Backdrop.tick++
        if (!Prefs.compactBar) return
        val now = android.os.SystemClock.uptimeMillis()
        // Showing/hiding the dock resizes the page, which itself moves the scroll position. Ignore that echo for a
        // moment, otherwise the bar flickers and sites with sticky headers jump.
        if (now - lastToggle < 450) { scrollAcc = 0; return }
        if (dy == 0) return
        scrollAcc = if ((scrollAcc > 0) == (dy > 0)) scrollAcc + dy else dy
        val px = resources.displayMetrics.density
        val hide = scrollAcc > 48 * px && y > 160 * px
        val show = scrollAcc < -36 * px || y < 24 * px
        if (hide && !Ui.collapsed) { Ui.collapsed = true; lastToggle = now; scrollAcc = 0 }
        else if (show && Ui.collapsed) { Ui.collapsed = false; lastToggle = now; scrollAcc = 0 }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------- External apps & links ----------------
    fun openExternal(u: Uri, userGesture: Boolean): Boolean {
        val s = u.scheme?.lowercase() ?: return true
        try {
            if (s == "intent") {
                val intent = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).apply { addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null }
                // Don't ask resolveActivity() first: on Android 11+ package visibility makes it return null for most apps
                // (WhatsApp, Paytm, PhonePe...), so app links always fell back to the web page or the Play Store.
                if (userGesture) {
                    try { startActivity(intent); return true } catch (_: android.content.ActivityNotFoundException) {} catch (_: SecurityException) {}
                }
                intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("http") }?.let { f -> Browser.current?.let { Browser.load(it, f) }; return true }
                intent.`package`?.let { pkg -> if (userGesture) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
                return true
            }
            if (!userGesture && s !in setOf("upi", "tel", "mailto", "sms", "geo")) return true
            startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) { toast("No app can open this link") }
        return true
    }

    // ---------------- File chooser ----------------
    fun chooseFile(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        // A page that opens a second chooser before the first returned must not leave the first callback hanging
        // (WebView would then ignore every later upload on that page).
        fileCallback?.onReceiveValue(null); fileCallback = cb; cameraUri = null
        return try {
            val (intent, cam) = FileChooser.intent(this, params)
            cameraUri = cam
            fileLauncher.launch(intent); true
        } catch (_: Exception) {
            // Last resort: the platform's own intent (no camera, plain types).
            runCatching { fileLauncher.launch(params.createIntent()); true }.getOrElse { fileCallback?.onReceiveValue(null); fileCallback = null; toast("Can't open the file picker"); false }
        }
    }

    private fun runtime(perms: Array<String>, then: (Boolean) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return then(true)
        permCont = { r -> then(r.values.all { it }) }
        permLauncher.launch(missing.toTypedArray())
    }

    // ---------------- Site permissions (camera, microphone, location) ----------------
    fun onSitePermission(t: Tab, req: PermissionRequest) {
        val origin = req.origin.toString().trimEnd('/')
        val res = req.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        if (res.isEmpty()) return req.deny()
        if (res.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }) return req.grant(res.toTypedArray())
        val cam = PermissionRequest.RESOURCE_VIDEO_CAPTURE in res; val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in res
        val keys = listOfNotNull("camera".takeIf { cam }, "microphone".takeIf { mic })
        val grant = {
            val perms = listOfNotNull(Manifest.permission.CAMERA.takeIf { cam }, Manifest.permission.RECORD_AUDIO.takeIf { mic }).toTypedArray()
            runtime(perms) { ok -> if (ok) req.grant(res.toTypedArray()) else req.deny() }
        }
        val legacy = BrowserDb.perm(origin, "camera+microphone")
        val saved = keys.map { BrowserDb.perm(origin, it) ?: legacy }
        when {
            saved.any { it == false } -> req.deny()
            saved.all { it == true } -> grant()
            else -> Ui.prompt = SitePrompt(origin, when { cam && mic -> "use your camera and microphone"; cam -> "use your camera"; else -> "use your microphone" }, if (cam) "camera" else "mic") { allow, remember ->
                if (remember && !t.incognito) keys.forEach { BrowserDb.setPerm(origin, it, allow) }
                if (allow) grant() else req.deny()
            }
        }
    }

    /** Web Notifications (WebView has none built in): asked like Chrome, remembered per site in Site settings. */
    fun onNotifyPermission(t: Tab, origin: String, done: (String) -> Unit) {
        when (BrowserDb.perm(origin, "notifications")) {
            true -> return done("granted"); false -> return done("denied"); null -> {}
        }
        if (t.incognito) return done("denied")
        Ui.prompt = SitePrompt(origin, "show notifications", "notifications") { allow, _ ->
            BrowserDb.setPerm(origin, "notifications", allow)
            if (allow && android.os.Build.VERSION.SDK_INT >= 33) runtime(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { ok -> done(if (ok) "granted" else "denied") }
            else done(if (allow) "granted" else "denied")
        }
    }

    fun onGeoPermission(t: Tab, origin: String, cb: GeolocationPermissions.Callback) {
        val o = origin.trimEnd('/')
        val grant = { runtime(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) { ok -> cb.invoke(origin, ok, false) } }
        when (BrowserDb.perm(o, "location")) {
            true -> grant()
            false -> cb.invoke(origin, false, false)
            null -> Ui.prompt = SitePrompt(o, "know your location", "location") { allow, remember ->
                if (remember && !t.incognito) BrowserDb.setPerm(o, "location", allow)
                if (allow) grant() else cb.invoke(origin, false, false)
            }
        }
    }

    // ---------------- Fullscreen video ----------------
    fun enterFullscreen(v: View, cb: WebChromeClient.CustomViewCallback) {
        if (Ui.fullscreen != null) { cb.onCustomViewHidden(); return }
        fullscreenCallback = cb; Ui.fullscreen = v
        WindowInsetsControllerCompat(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }
    fun exitFullscreen() {
        Ui.fullscreen = null; fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ---------------- Long-press menu ----------------
    fun onLongPress(t: Tab, w: WebView): Boolean {
        val h = w.hitTestResult
        when (h.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> Ui.linkMenu = LinkMenu(t, h.extra, null, null)
            WebView.HitTestResult.IMAGE_TYPE -> Ui.linkMenu = LinkMenu(t, null, h.extra, null)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = android.os.Handler(mainLooper).obtainMessage()
                w.requestFocusNodeHref(msg)
                Ui.linkMenu = LinkMenu(t, msg.data.getString("url")?.takeIf { it.isNotBlank() }, h.extra ?: msg.data.getString("src"), msg.data.getString("title"))
            }
            else -> return false
        }
        return true
    }

    fun downloadImage(t: Tab, url: String) {
        if (url.startsWith("data:")) { Downloads.saveDataUrl(this, url, "image-${System.currentTimeMillis()}")?.let { toast("Saved to ${it.path}") } }
        else Downloads.enqueue(this, url, null, null, null, t.url, t.incognito)
    }

    // ---------------- Voice, biometrics, default browser ----------------
    /** Voice input, recorded and transcribed inside Cairn (see service/Voice.kt). */
    fun listen(onText: (String) -> Unit) {
        Haptics.tick()
        app.cairn.browser.service.Voice.onError = { m -> toast(m) }
        val go = { app.cairn.browser.service.Voice.start(this, onText); app.cairn.browser.service.Voice.error?.let { toast(it) }; Unit }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) go()
        else runtime(arrayOf(Manifest.permission.RECORD_AUDIO)) { ok -> if (ok) go() else toast("Microphone permission is needed for voice input") }
    }

    /**
     * Where spoken words go. Speaking is almost always a command ("mere liye iPhone 17 dhundh ke cart mein daal"), and
     * Whisper may return it in Devanagari, which the typed-text task detector doesn't recognise — so voice goes to the
     * agent unless it is clearly a web address or a one/two-word search.
     */
    fun voiceCommand(t: String) {
        val q = t.trim(); if (q.isBlank()) return
        if (app.cairn.browser.agent.Intents.voiceIsSearch(q)) { Browser.open(q, Browser.current?.takeIf { it.isNtp } ?: Browser.newTab()); return }
        Ui.omnibox = false; Ui.assistant = true
        when {
            app.cairn.browser.agent.Agent.pendingAsk != null -> app.cairn.browser.agent.Agent.answer(q)
            app.cairn.browser.agent.Agent.running -> { Ui.assistantDraft = q; toast("Cairn is still working — your words are in the box, send when ready") }
            else -> app.cairn.browser.agent.Agent.run(q, Ui.mentions.toList()).also { Ui.mentions.clear() }
        }
    }

    /** True when a Cairn panel covers the page, so screen-copied tab thumbnails would show it. */
    fun overlayCoversPage() = Ui.assistant || Ui.tabSwitcher || Ui.menu || Ui.screen != null || Ui.omnibox || Ui.lasso

    suspend fun authenticate(title: String, subtitle: String): Boolean {
        val auth = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("Set up a screen lock (fingerprint, face or PIN) to approve payments"); return false
        }
        return suspendCoroutine { c ->
            var done = false
            BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (!done) { done = true; c.resume(true) } }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { if (!done) { done = true; c.resume(false) } }
            }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(auth).setConfirmationRequired(true).build())
        }
    }

    val isDefaultBrowser: Boolean get() =
        if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_BROWSER)
        else packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName

    fun requestDefaultBrowser(then: () -> Unit) {
        roleCont = then
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)); return }
            }
            roleLauncher.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) { roleCont = null; toast("Open Android Settings → Apps → Default apps to choose Cairn") }
    }

    fun approveLocally(item: app.cairn.browser.agent.ApprovalItem, approve: Boolean) {
        lifecycleScope.launch {
            if (!approve) { item.state = "denied"; item.result.complete(false); return@launch }
            val ok = authenticate(if (item.kind == "payment") "Confirm payment" else "Confirm it's you", listOf(item.amount, item.merchant).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { item.title })
            if (ok) { item.state = "approved"; item.result.complete(true) }
        }
    }

    companion object {
        const val ACTION_NEW_TAB = "app.cairn.browser.NEW_TAB"
        const val ACTION_PRIVATE_TAB = "app.cairn.browser.PRIVATE_TAB"
        const val ACTION_ASSISTANT = "app.cairn.browser.ASSISTANT"
        const val ACTION_FOCUS_SEARCH = "app.cairn.browser.SEARCH"
        const val ACTION_VOICE = "app.cairn.browser.VOICE"
        const val ACTION_ASK_PHOTO = "app.cairn.browser.ASK_PHOTO"
    }
}
