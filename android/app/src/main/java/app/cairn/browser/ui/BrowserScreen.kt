package app.cairn.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.cairn.browser.agent.Agent
import app.cairn.browser.agent.Chats
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.Downloads
import app.cairn.browser.browser.NTP
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.Prefs
import app.cairn.browser.data.Search
import app.cairn.browser.data.hostOf
import app.cairn.browser.service.ReadAloud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val act get() = Browser.activity

fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    if (android.os.Build.VERSION.SDK_INT < 33) act.toast("Copied")
}
fun shareText(ctx: Context, text: String, subject: String? = null) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }, null))
}
fun ask(text: String) { Ui.assistant = true; Agent.run(text) }

/** Vision on a page image (replaces reverse image search): downloads the image and hands it to Cairn like a lasso selection. */
fun askAboutImage(url: String) {
    Ui.assistant = true
    uiScope.launch { runCatching {
        val bmp = Images.load(url) ?: run { act.toast("Couldn't load that image"); return@runCatching }
        Agent.lasso(bmp, "", listOf(url), "What is in this image? If it's a product, identify it and where to buy it.")
    } }
}
private val uiScope = kotlinx.coroutines.MainScope()

/** "Track price": reads the price from the current page, saves it and lets the background worker watch it. */
fun trackPrice(tab: Tab) {
    val pw = app.cairn.browser.service.PriceWatch
    if (pw.isTracked(tab.url)) { pw.remove(tab.url); act.toast("Stopped tracking this price"); return }
    val w = tab.web ?: return
    uiScope.launch { runCatching {
        val p = app.cairn.browser.agent.PageJs.obj(w, "product()")
        val price = p?.optDouble("price")?.takeIf { !it.isNaN() && it > 0 }
        pw.add(app.cairn.browser.service.Track(tab.url, p?.optString("title")?.takeIf { it.isNotBlank() } ?: tab.title, price, p?.optString("currency").orEmpty().ifBlank { "INR" },
            p?.optBoolean("inStock", true) ?: true, p?.optString("image").orEmpty(), System.currentTimeMillis(), System.currentTimeMillis(), price))
        Haptics.confirm()
        act.toast(if (price != null) "Tracking · Cairn will tell you when it drops below ${pw.money(price, p.optString("currency"))}" else "Tracking this page for price changes")
    } }
}

/** Root of the whole UI. */
@Composable
fun App(webHost: FrameLayout, bgHost: FrameLayout) {
    Box(Modifier.fillMaxSize().background(C.chrome)) {
        BrowserScreen(webHost, bgHost)
        LassoOverlay()
        AnimatedVisibility(Ui.screen != null, enter = slideInHorizontally(cairnSpring(520f, .92f)) { it / 5 } + fadeIn(tween(160)), exit = slideOutHorizontally(tween(180)) { it / 8 } + fadeOut(tween(140))) {
            Box(Modifier.fillMaxSize().background(C.bg)) { Ui.screen?.let { Screens(it) } }
        }
        AnimatedVisibility(Ui.tabSwitcher, enter = fadeIn(tween(160)) + scaleIn(cairnSpring(), initialScale = .96f), exit = fadeOut(tween(140)) + scaleOut(targetScale = .97f)) { TabSwitcher() }
        AnimatedVisibility(Ui.omnibox, enter = fadeIn(tween(140)) + slideInVertically(cairnSpring()) { it / 14 }, exit = fadeOut(tween(110))) { Omnibox() }
        AssistantSheet()
        if (Ui.profile) ProfileSheet { Ui.profile = false }
        // Live preview of background agent pages floats above everything except dialogs
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { LivePreview(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp)) }
        if (Ui.menu) MainMenu()
        if (Ui.siteInfo) SiteInfoSheet()
        Ui.linkMenu?.let { LinkMenuSheet(it) }
        Ui.prompt?.let { SitePromptDialog(it) }
        OneTapSheet()
        VoiceOverlay()
        Ui.fullscreen?.let { v ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView({ FrameLayout(it).apply { (v.parent as? ViewGroup)?.removeView(v); addView(v, FrameLayout.LayoutParams(-1, -1)) } }, Modifier.fillMaxSize())
            }
            BackHandler { act.exitFullscreen() }
        }
        if (!Prefs.setupDone) SetupWizard()
    }
}

@Composable
fun BrowserScreen(webHost: FrameLayout, bgHost: FrameLayout) {
    val tab = Browser.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = Ui.screen == null && !Ui.omnibox && !Ui.tabSwitcher && !Ui.assistant && !Ui.lasso) {
        when {
            Ui.find -> { Ui.find = false; tab?.web?.clearMatches() }
            Ui.collapsed -> Ui.collapsed = false
            tab != null && Browser.back(tab) -> {}
            else -> act.moveTaskToBack(true)
        }
    }
    // Not tied to tab.loading: flipping the dock when a load finished resized the page and made it jump.
    val hidden = Ui.collapsed && tab != null && !tab.isNtp && !Ui.find && !ReadAloud.active && Agent.controlling != tab
    Column(Modifier.fillMaxSize().background(C.chrome).statusBarsPadding().imePadding()) {
        // The page gets its own area. The dock sits *below* it in the layout (never over it), so nothing on the page is
        // hidden or unclickable behind the bar. While you scroll down the dock folds into a slim strip and the page grows.
        Box(Modifier.weight(1f).fillMaxWidth().background(C.bg)) {
            // Background agent pages live under the visible page so they lay out at real size but are never seen.
            AndroidView({ bgHost }, Modifier.fillMaxSize())
            AndroidView({ webHost }, Modifier.fillMaxSize())
            if (tab == null || tab.isNtp) NewTabPage(tab?.incognito == true)
            tab?.error?.let { ErrorPage(tab, it.code, it.description) }
            tab?.ssl?.let { SslPage(tab) }
            tab?.unsafe?.let { UnsafePage(tab) }
            AgentEdge(Agent.controlling != null && Agent.controlling == tab)
            // Small transient pills float just above the dock edge; they're narrow and centred so the page stays usable.
            // Guard pill: only while a password/PIN/OTP/card field is actually focused on a real page, at the top of the
            // page area (far from the dock and the keyboard), and only for a few seconds. Never on the New Tab page.
            val focusGuard = tab != null && !tab.isNtp && tab.secureFocus && tab.error == null
            var guardShown by remember { mutableStateOf(false) }
            LaunchedEffect(focusGuard, tab?.id) { guardShown = focusGuard; if (focusGuard) { delay(3500); guardShown = false } }
            GuardBadge(guardShown && Ui.screen == null && !Ui.assistant, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AgentStatus(Modifier)
            }
        }
        // No animateContentSize here: animating this height resized the WebView on every frame, which made pages
        // re-layout continuously (sticky headers jumping, videos stuttering). The bar swaps in one step instead.
        Column(Modifier.fillMaxWidth().background(C.chrome).then(if (imeVisible) Modifier else Modifier.navigationBarsPadding()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            VaultBar(tab)
            AutofillBar(tab)
            AnimatedVisibility(Ui.find && tab != null, enter = fadeIn() + expandVertically(cairnSpring()), exit = fadeOut() + shrinkVertically()) { tab?.let { Floating { FindBar(it) } } }
            AnimatedVisibility(ReadAloud.active && !Ui.find, enter = fadeIn() + expandVertically(cairnSpring()), exit = fadeOut() + shrinkVertically()) { Floating { ReadAloudBar() } }
            if (!imeVisible && !Ui.find) {
                if (hidden) tab?.let { MiniBar(it) }
                else Box(Modifier.animateEnterFade()) { Dock(tab, Modifier.padding(top = 6.dp, bottom = 6.dp)) }
            }
        }
    }
}

/** Fades/slides content in once when it first appears (no layout animation, so the page above never resizes twice). */
@Composable
private fun Modifier.animateEnterFade(): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, spring(dampingRatio = .9f, stiffness = 700f)) }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18f }
}

/** Shown in place of the dock while reading (Safari-style): just the site, tap to bring the dock back. */
@Composable
private fun MiniBar(tab: Tab) {
    // A full-width strip (not a floating pill): tap anywhere on it, or scroll up, to bring the dock back.
    Row(Modifier.fillMaxWidth().height(28.dp).clickable { Haptics.tick(); Ui.collapsed = false }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(if (tab.secure) Ic.Lock else Ic.Info, null, Modifier.size(10.dp), tint = if (tab.secure) C.faint else C.warn); Spacer(Modifier.width(6.dp))
        Text(tab.host.removePrefix("www."), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Floating(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(20.dp))) { content() }
}

// ---------------------------------------------------------------- Agent visuals
@Composable
fun AgentEdge(on: Boolean) {
    if (!on) return
    val t = rememberInfiniteTransition(label = "edge")
    val a by t.animateFloat(.35f, .9f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "a")
    val col = C.agent
    Box(Modifier.fillMaxSize().drawWithContent {
        drawContent()
        val w = 2.dp.toPx(); val g = 22.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(col.copy(alpha = .22f * a), Color.Transparent), 0f, g))
        drawRect(Brush.horizontalGradient(listOf(col.copy(alpha = .16f * a), Color.Transparent), 0f, g), size = size.copy(width = g))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = .16f * a)), size.width - g, size.width), Offset(size.width - g, 0f))
        drawRect(col.copy(alpha = a), size = size.copy(height = w))
    })
}

// ---------------------------------------------------------------- New tab page
private val defaults = listOf("Wikipedia" to "https://www.wikipedia.org", "Amazon" to "https://www.amazon.in", "Flipkart" to "https://www.flipkart.com", "Reddit" to "https://www.reddit.com",
    "GitHub" to "https://github.com", "X" to "https://x.com", "LinkedIn" to "https://www.linkedin.com", "MakeMyTrip" to "https://www.makemytrip.com")

private data class Idea(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val prompt: String)
private val quick = listOf(
    Triple(Ic.ShoppingBag, "Sabse sasta phone", "Sabse sasta CMF Phone 2 Pro trusted store se dhoondo aur cart mein daal ke checkout tak le jao"),
    Triple(Ic.Summarize, "Summarize this page", "Summarize the page I was reading"),
    Triple(Ic.Flight, "Cheapest flight", "Find the cheapest flight from Delhi to Mumbai this Friday"),
    Triple(Ic.BellRing, "Track a price", "Track the price of Sony WH-1000XM5 and alert me when it drops"),
    Triple(Ic.Newspaper, "Today's news", "Give me today's top India news with sources"),
)
private val ideas = listOf(
    Idea(Ic.ShoppingBag, "Compare prices", "Find the cheapest iPhone 16 from a trusted store"),
    Idea(Ic.Swarm, "Plan a trip", "Plan a weekend in Jaipur: best flight, hotel and cab"),
    Idea(Ic.Headphones, "Listen to an article", "Read the Wikipedia article on the Taj Mahal aloud"),
    Idea(Ic.BellRing, "Watch a price", "Track the price of Sony WH-1000XM5 and alert me when it drops"),
    Idea(Ic.AccountTree, "Map a topic", "Make a mind map of how GPUs work"),
)


@Composable
fun NewTabPage(private: Boolean) {
    val v = BrowserDb.version.intValue
    val sites = remember(v) {
        val top = runCatching { BrowserDb.topSites(8) }.getOrDefault(emptyList()).map { (it.title.ifBlank { hostOf(it.url) }) to it.url }
        (top + defaults.filter { d -> top.none { BrowserDb.siteKey(it.second) == BrowserDb.siteKey(d.second) } }).distinctBy { BrowserDb.siteKey(it.second) }.take(8)
    }
    val motion = rememberMotion()
    val scroll = rememberScrollState()
    val tilt by rememberTilt(motion && !private)
    Box(Modifier.fillMaxSize().background(C.bg)) {
    if (!private) AuroraBackdrop(motion, Modifier.fillMaxWidth().height(380.dp).graphicsLayer { translationY = -scroll.value * .4f; alpha = (1f - scroll.value / 900f).coerceIn(0f, 1f) }, tilt)
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(if (private) 72.dp else 18.dp))
        if (private) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.surface2), contentAlignment = Alignment.Center) { Icon(Ic.VisibilityOff, null, Modifier.size(24.dp), tint = C.text) }
            Spacer(Modifier.height(20.dp))
            Text("Private tab", color = C.text, fontSize = 28.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
            Spacer(Modifier.height(12.dp))
            Text("Cairn won't save your browsing history, cookies, site data or form entries from private tabs. Downloads and bookmarks you create are kept.",
                color = C.muted, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(18.dp))
            listOf(Ic.History to "History and searches are not saved", Ic.Cookie to "Cookies are deleted when you close all private tabs", Ic.Brain to "Nothing is added to Recall memory").forEach { (ic, t) ->
                Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(ic, null, Modifier.size(17.dp), tint = C.muted); Spacer(Modifier.width(14.dp)); Text(t, color = C.text, fontSize = 14.sp) }
            }
            Spacer(Modifier.height(14.dp))
            Text("Websites, your employer or school, and your internet provider may still see your activity.", color = C.faint, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(120.dp))
            return@Column
        }
        HomeHeader(motion, tilt)
        Spacer(Modifier.height(22.dp))
        OmniBar(motion, quick)
        Box(Modifier.enter(5, motion)) { Column { BentoGrid(motion) } }
        JourneyRow(v)
        ShortcutGrid(sites)
        val watching = app.cairn.browser.service.PriceWatch.tracks
        LaunchedEffect(Unit) { app.cairn.browser.service.PriceWatch.load() }
        if (watching.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).clickable { Ui.go("settings/prices") }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.BellRing, null, Modifier.size(18.dp), tint = C.agent); Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Watching ${watching.size} price${if (watching.size == 1) "" else "s"}", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(watching.first().title, color = C.faint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Ic.ChevronRight, null, Modifier.size(16.dp), tint = C.faint)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Shield, null, Modifier.size(13.dp), tint = C.faint); Spacer(Modifier.width(6.dp))
            Text(if (Prefs.shield && Prefs.shieldTotal > 0) "Shield has blocked ${"%,d".format(Prefs.shieldTotal)} trackers and ads" else "Shield blocks trackers and ads on every site", color = C.faint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(120.dp))
    }
    }
}

/** Tiny static render of the new tab page, used in the tab grid. */
@Composable
fun MiniNtp(private: Boolean) {
    Column(Modifier.fillMaxSize().background(C.bg).padding(12.dp)) {
        if (private) { Spacer(Modifier.weight(1f)); Icon(Ic.VisibilityOff, null, Modifier.size(22.dp).align(Alignment.CenterHorizontally), tint = C.muted); Spacer(Modifier.weight(1f)); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) { Logo(12.dp); Spacer(Modifier.width(5.dp)); Text("Cairn", color = C.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp)).background(C.surface).border(.5.dp, C.line2, RoundedCornerShape(6.dp)))
        Spacer(Modifier.height(10.dp))
        defaults.take(8).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (_, u) -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(C.surface), contentAlignment = Alignment.Center) { Favicon(u, size = 12.dp, corner = 3.dp) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(2) { Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(7.dp)).background(C.surface)) } }
    }
}

// ---------------------------------------------------------------- Error pages
@Composable
fun ErrorPage(tab: Tab, code: Int, desc: String) {
    val (title, body) = when (code) {
        android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "This site can't be reached" to "${tab.host.ifBlank { "The server" }}'s address couldn't be found. Check your connection or the spelling of the address."
        android.webkit.WebViewClient.ERROR_CONNECT, android.webkit.WebViewClient.ERROR_TIMEOUT -> "This site can't be reached" to "${tab.host} took too long to respond."
        -1000 -> "Something went wrong" to "This page stopped working. Reloading usually fixes it."
        else -> "This page isn't working" to desc.removePrefix("net::").ifBlank { "Cairn couldn't load this page." }
    }
    Column(Modifier.fillMaxSize().background(C.bg).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Icon(Ic.CloudOff, null, Modifier.size(36.dp), tint = C.faint)
        Spacer(Modifier.height(20.dp))
        Text(title, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(body, color = C.muted, fontSize = 14.5.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(6.dp))
        Text(desc.ifBlank { "ERR_$code" }.uppercase(), color = C.faint, fontSize = 11.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(28.dp))
        Row { PrimaryButton("Reload", icon = Ic.Refresh) { Browser.reload(tab) } }
    }
}

@Composable
fun SslPage(tab: Tab) {
    var advanced by remember { mutableStateOf(false) }
    val p = tab.ssl ?: return
    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(40.dp))
        Icon(Ic.GppBad, null, Modifier.size(38.dp), tint = C.err)
        Spacer(Modifier.height(20.dp))
        Text("Your connection isn't private", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("Attackers might be trying to steal your information from ${hostOf(p.url)} (for example, passwords, messages or credit cards).", color = C.muted, fontSize = 14.5.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Back to safety") { tab.ssl = null; p.handler.cancel(); if (!Browser.back(tab)) Browser.load(tab, NTP) }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide details" else "Advanced", color = C.muted) }
        }
        if (advanced) {
            Spacer(Modifier.height(16.dp))
            val why = when (p.error.primaryError) {
                android.net.http.SslError.SSL_EXPIRED -> "The certificate has expired."
                android.net.http.SslError.SSL_IDMISMATCH -> "The certificate is for a different site."
                android.net.http.SslError.SSL_UNTRUSTED -> "The certificate isn't issued by a trusted authority."
                android.net.http.SslError.SSL_NOTYETVALID -> "The certificate isn't valid yet. Check your device's date and time."
                else -> "The certificate is invalid."
            }
            Text("$why Cairn can't confirm this is the real ${hostOf(p.url)}.", color = C.muted, fontSize = 13.5.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("Proceed to ${hostOf(p.url)} (unsafe)", color = C.err, fontSize = 13.5.sp, modifier = Modifier.clickable { tab.ssl = null; p.handler.proceed() }.padding(vertical = 8.dp))
        }
    }
}

/** Full-screen red interstitial for phishing and malware sites (Chrome-style). */
@Composable
fun UnsafePage(tab: Tab) {
    var details by remember { mutableStateOf(false) }
    val p = tab.unsafe ?: return
    val red = Color(0xFFC5221F); val host = hostOf(p.url)
    Column(Modifier.fillMaxSize().background(red).verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(48.dp))
        Icon(Ic.GppBad, null, Modifier.size(48.dp), tint = Color.White)
        Spacer(Modifier.height(22.dp))
        Text(if (p.kind == "malware") "The site ahead contains malware" else "Dangerous site", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
        Spacer(Modifier.height(12.dp))
        Text(if (p.kind == "malware") "Attackers on $host might attempt to install dangerous programs on your phone that steal or delete your information (for example, photos, passwords, messages and credit cards)."
            else "Attackers on $host may trick you into doing something dangerous like installing software or revealing your personal information (for example, passwords, phone numbers or credit cards).",
            color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(30.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).clickable {
                tab.unsafe = null; if (!Browser.back(tab)) Browser.load(tab, NTP)
            }.padding(horizontal = 22.dp, vertical = 12.dp)) { Text("Back to safety", color = red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details", color = Color.White) }
        }
        if (details) {
            Spacer(Modifier.height(16.dp))
            Text("Cairn Safe Browsing recently found ${if (p.kind == "malware") "malware" else "phishing"} on $host. Websites that are normally safe are sometimes infected. Cairn checked this privately: only a short piece of a hash left your phone.",
                color = Color.White.copy(alpha = 0.9f), fontSize = 13.5.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("Proceed anyway (unsafe)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { app.cairn.browser.browser.SafeBrowsing.allow(host); tab.unsafe = null; Browser.load(tab, p.url) }.padding(vertical = 8.dp))
        }
    }
}

// ---------------------------------------------------------------- Find in page
@Composable
fun FindBar(tab: Tab) {
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    Row(Modifier.fillMaxWidth().background(C.chrome).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(C.surface2).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(q, { q = it; tab.web?.findAllAsync(it) }, Modifier.weight(1f).focusRequester(fr), singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text, fontSize = 15.sp), cursorBrush = SolidColor(C.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions { tab.web?.findNext(true) },
                decorationBox = { inner -> if (q.isEmpty()) Text("Find in page", color = C.faint, fontSize = 15.sp); inner() })
            if (q.isNotEmpty()) Text("${tab.findMatches.first}/${tab.findMatches.second}", color = C.faint, fontSize = 12.5.sp)
        }
        IconBtn(Ic.KeyboardArrowUp, "Previous") { tab.web?.findNext(false) }
        IconBtn(Ic.KeyboardArrowDown, "Next") { tab.web?.findNext(true) }
        IconBtn(Ic.Close, "Close find") { Ui.find = false; tab.web?.clearMatches() }
    }
}

// ---------------------------------------------------------------- Read-aloud player
@Composable
fun ReadAloudBar() {
    var speeds by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(C.chrome)) {
        HorizontalDivider(color = C.line)
        Box(Modifier.fillMaxWidth().height(2.dp).background(C.line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (ReadAloud.total > 0) (ReadAloud.index + 1f) / ReadAloud.total else 0f).background(C.agent))
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            VoiceOrb(0f, if (ReadAloud.playing) "speak" else "think", 40.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(ReadAloud.title.ifBlank { "Reading aloud" }, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(if (ReadAloud.playing) "Playing · ${ReadAloud.index + 1} of ${ReadAloud.total}" else "Paused", color = C.faint, fontSize = 12.sp)
            }
            Box {
                TextButton(onClick = { speeds = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("${"%.2g".format(Prefs.ttsRate / 100f).trimEnd('0').trimEnd('.')}×", color = C.muted, fontSize = 13.sp) }
                DropdownMenu(speeds, { speeds = false }, containerColor = C.surface2) {
                    listOf(75, 100, 125, 150, 175, 200).forEach { r -> DropdownMenuItem({ Text("${r / 100f}×", color = C.text) }, { ReadAloud.setRate(r); speeds = false }) }
                }
            }
            IconBtn(Ic.Replay10, "Back") { ReadAloud.skip(-1) }
            IconBtn(if (ReadAloud.playing) Ic.Pause else Ic.PlayArrow, if (ReadAloud.playing) "Pause" else "Play") { ReadAloud.toggle() }
            IconBtn(Ic.Close, "Stop reading") { ReadAloud.stop() }
        }
    }
}

// ---------------------------------------------------------------- Omnibox
private data class Sugg(val kind: String, val title: String, val sub: String?, val url: String?)

@Composable
fun Omnibox() {
    val ctx = LocalContext.current
    val tab = Browser.current
    val start = Ui.omniboxText ?: tab?.takeIf { !it.isNtp }?.let { Search.queryOf(it.url) ?: it.url } ?: ""
    var tf by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val fr = remember { FocusRequester() }
    var remote by remember { mutableStateOf(emptyList<String>()) }
    val q = tf.text.trim()
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    LaunchedEffect(q) {
        remote = emptyList()
        if (q.length >= 2 && Prefs.searchSuggestions && tab?.incognito != true && q != start) { delay(140); remote = runCatching { Search.suggestions(q) }.getOrDefault(emptyList()) }
    }
    // History/bookmark matches are read off the UI thread so typing never stutters.
    val local by produceState(emptyList<Sugg>(), q) {
        value = if (q.isEmpty() || q == start) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val b = runCatching { BrowserDb.bookmarks(q).take(3) }.getOrDefault(emptyList()).map { Sugg("bookmark", it.title, it.url, it.url) }
            val h = runCatching { BrowserDb.history(q, 6) }.getOrDefault(emptyList()).filter { x -> b.none { it.url == x.url } }.take(4).map { Sugg("history", it.title.ifBlank { it.url }, it.url, it.url) }
            b + h
        }
    }
    // Chrome-style speed-ups while typing: connect to the search engine as soon as the omnibox opens, to a typed
    // address as soon as it looks like one, and fetch the page you are clearly typing from history in advance.
    LaunchedEffect(Unit) { if (tab?.incognito != true) app.cairn.browser.browser.Speed.preconnect(Search.searchUrl("cairn")) }
    LaunchedEffect(q, local) {
        if (tab?.incognito == true || q.isEmpty() || q == start) return@LaunchedEffect
        delay(150)
        Search.toUrl(q)?.let { app.cairn.browser.browser.Speed.preconnect(it) }
        val top = local.firstOrNull()?.url ?: return@LaunchedEffect
        val bare = top.substringAfter("://").removePrefix("www.")
        if (q.length >= 3 && bare.startsWith(q.removePrefix("www."), ignoreCase = true)) app.cairn.browser.browser.Speed.prefetch(top)
        else app.cairn.browser.browser.Speed.preconnect(top)
    }
    val clip = remember {
        runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() }.getOrNull()
            ?.takeIf { it.length < 2000 && Regex("^https?://\\S+$").matches(it) }
    }
    fun go(input: String) {
        if (input.isBlank()) return
        Ui.omnibox = false
        // Chrome-style internal pages: cairn://downloads (also chrome://downloads), history, bookmarks, settings
        Regex("^(?:cairn|chrome)://(downloads|history|bookmarks|settings)/?$", RegexOption.IGNORE_CASE).find(input.trim())?.let { Ui.go(it.groupValues[1].lowercase()); return }
        // Agent mode (home Omni-bar) or an obvious task: hand it to the agent instead of searching.
        if (Search.toUrl(input.trim()) == null && !input.trim().startsWith("http") && Ui.omniAgent && input.trim().contains(' ') && tab?.incognito != true) { Ui.omniAgent = false; ask(input.trim()); return }
        Ui.omniAgent = false
        val t = Browser.current ?: Browser.newTab()
        Browser.open(input, t)
    }
    BackHandler { Ui.omnibox = false; Ui.omniAgent = false }
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().imePadding().navigationBarsPadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
            // reverse layout: first item sits right above the input, like a bottom-anchored address bar
            if (q.isNotEmpty() && q != start) {
                item { SuggRow(Ic.AutoAwesome, "Ask Cairn", q, tint = C.agent) { Ui.omnibox = false; ask(q) } }
                val asUrl = Search.toUrl(q)
                if (asUrl != null) item { SuggRow(Ic.Language, q, "Open website") { go(asUrl) } }
                else item { SuggRow(Ic.Search, q, "${Search.current.name} search") { go(q) } }
                items(remote.filter { !it.equals(q, true) }.take(6)) { s -> SuggRow(Ic.Search, s, null, fill = { tf = TextFieldValue("$s ", TextRange(s.length + 1)) }) { go(s) } }
                items(local) { s -> SuggRow(if (s.kind == "bookmark") Ic.StarOutline else Ic.History, s.title, s.sub) { go(s.url!!) } }
            } else {
                if (clip != null && clip != tab?.url) item { SuggRow(Ic.ContentPaste, "Link you copied", clip) { go(clip) } }
                if (tab != null && !tab.isNtp) item {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface)) {
                        Row(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Favicon(tab.url, tab.icon, 20.dp); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tab.title.ifBlank { tab.host }, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tab.url, color = C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Row(Modifier.padding(8.dp)) {
                            QuickAct(Ic.ContentCopy, "Copy link") { copyText(ctx, "Link", tab.url); Ui.omnibox = false }
                            QuickAct(Ic.Share, "Share") { shareText(ctx, tab.url, tab.title); Ui.omnibox = false }
                            QuickAct(Ic.Edit, "Edit") { tf = TextFieldValue(tab.url, TextRange(tab.url.length)) }
                            QuickAct(Ic.QrCode2, "QR code") { Ui.omnibox = false; Browser.newTab("https://api.qrserver.com/v1/create-qr-code/?size=480x480&data=" + Uri.encode(tab.url), tab.incognito) }
                        }
                    }
                }
                val recent = runCatching { BrowserDb.history(limit = 6) }.getOrDefault(emptyList())
                if (tab?.incognito != true) items(recent) { h -> SuggRow(Ic.History, h.title.ifBlank { h.url }, hostOf(h.url)) { go(h.url) } }
            }
        }
        // Input row
        Row(Modifier.fillMaxWidth().background(C.chrome).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(C.surface2).border(1.dp, C.line2, RoundedCornerShape(23.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(tf, { tf = it }, Modifier.weight(1f).focusRequester(fr), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text, fontSize = 15.5.sp), cursorBrush = SolidColor(C.text),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { go(tf.text) }),
                    decorationBox = { inner -> if (tf.text.isEmpty()) Text(if (tab?.incognito == true) "Search privately" else if (Ui.omniAgent) "Tell Cairn what to do…" else "Search, ask, or type a URL", color = C.faint, fontSize = 15.5.sp); inner() })
                if (tf.text.isNotEmpty()) IconBtn(Ic.Cancel, "Clear", tint = C.faint, size = 38.dp) { tf = TextFieldValue("") }
                else IconBtn(Ic.Mic, "Voice", tint = C.muted, size = 38.dp) { act.listen { act.voiceCommand(it) } }
            }
            TextButton(onClick = { Ui.omnibox = false }) { Text("Cancel", color = C.muted, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun SuggRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, tint: Color = C.muted, fill: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 22.dp, end = 8.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint); Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = C.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = C.faint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        fill?.let { IconBtn(Ic.NorthWest, "Use suggestion", tint = C.faint, size = 40.dp, onClick = it) }
    }
}

@Composable
private fun RowScope.QuickAct(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(19.dp), tint = C.text); Spacer(Modifier.height(5.dp))
        Text(label, color = C.muted, fontSize = 11.5.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Tab switcher
@Composable
fun TabSwitcher() {
    var private by remember { mutableStateOf(Browser.current?.incognito == true) }
    var menu by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    val list = Browser.tabs.filter { it.incognito == private }
    BackHandler { Ui.tabSwitcher = false }
    Column(Modifier.fillMaxSize().background(C.chrome).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconBtn(Ic.MoreVert, "More") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = C.surface2) {
                    DropdownMenuItem({ Text(if (private) "Close all private tabs" else "Close all tabs", color = C.text) }, { menu = false; Browser.closeAll(private); if (!private) Ui.tabSwitcher = false })
                    DropdownMenuItem({ Text("Settings", color = C.text) }, { menu = false; Ui.tabSwitcher = false; Ui.go("settings") })
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(C.surface2).padding(3.dp)) {
                Seg("${Browser.normalTabs().size}  Tabs", !private && !devices) { private = false; devices = false }
                Seg("Private", private && !devices) { private = true; devices = false }
                Seg("Devices", devices) { devices = true; Haptics.tick(); app.cairn.browser.vault.Sync.soon(0) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { Ui.tabSwitcher = false }) { Text("Done", color = C.text, fontWeight = FontWeight.Medium) }
        }
        if (devices) RemoteTabsList(Modifier.weight(1f).fillMaxWidth())
        else if (list.isEmpty()) EmptyState(if (private) Ic.VisibilityOff else Ic.Tab, if (private) "No private tabs" else "No tabs",
            if (private) "Private tabs don't save history or cookies." else "Open a new tab to start browsing.", Modifier.weight(1f).fillMaxWidth())
        else LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { t -> TabCard(t) }
        }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(C.accent).clickable { Haptics.confirm(); Browser.newTab(NTP, private); Ui.tabSwitcher = false; Ui.omnibox = true }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Add, null, Modifier.size(18.dp), tint = C.accentInk); Spacer(Modifier.width(8.dp))
                Text(if (private) "New private tab" else "New tab", color = C.accentInk, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RemoteTabsList(mod: Modifier) {
    val remote = app.cairn.browser.vault.Sync.remoteTabs
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(2500); waited = true }
    if (Prefs.isSignedIn && remote.isEmpty() && !waited) { SkeletonRows(5, mod); return }
    if (!Prefs.isSignedIn || remote.isEmpty()) {
        EmptyState(Ic.Devices, if (Prefs.isSignedIn) "No tabs from other devices yet" else "Sign in to see your other devices",
            if (Prefs.isSignedIn) "Tabs open on your computer or other phones show up here." else "Your open tabs follow your Cairn account, end-to-end encrypted.", mod)
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(mod, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        remote.forEach { d ->
            item(key = "h" + d.device) {
                Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Devices, null, Modifier.size(16.dp), tint = C.muted); Spacer(Modifier.width(8.dp))
                    Text(d.device, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (d.at > 0) Text(android.text.format.DateUtils.getRelativeTimeSpanString(d.at).toString(), color = C.faint, fontSize = 12.sp)
                }
            }
            items(d.tabs.filter { it.first.startsWith("http") }, key = { d.device + it.first }) { (url, title) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).clickable { Haptics.tick(); Browser.newTab(url, false); Ui.tabSwitcher = false }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Favicon(url, null, 18.dp, 4.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title.ifBlank { url }, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(android.net.Uri.parse(url).host.orEmpty().removePrefix("www."), color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Seg(t: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) C.surface3 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(t, color = if (on) C.text else C.muted, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun TabCard(t: Tab) {
    var dx by remember { mutableFloatStateOf(0f) }
    val cur = t == Browser.current
    LaunchedEffect(t) { if (t.thumb == null) Browser.loadThumb(t) }
    Column(Modifier.graphicsOffset(dx).clip(RoundedCornerShape(18.dp)).background(C.surface)
        .border(if (cur) 2.dp else 1.dp, if (Agent.controlling == t) C.agent else if (cur) C.text.copy(alpha = .85f) else C.line, RoundedCornerShape(18.dp))
        .pointerInput(t) { detectHorizontalDragGestures(onDragEnd = { if (kotlin.math.abs(dx) > size.width * .35f) Browser.close(t); dx = 0f }) { _, d -> dx += d } }
        .clickable { Haptics.tick(); Browser.select(t); Ui.tabSwitcher = false }) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.isNtp) Logo(14.dp) else Favicon(t.url, t.icon, 15.dp, 3.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (t.isNtp) "New tab" else t.title.ifBlank { t.host }, color = C.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconBtn(Ic.Close, "Close tab", tint = C.muted, size = 34.dp) { Haptics.tick(); Browser.close(t) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(.78f).background(C.bg), contentAlignment = Alignment.Center) {
            val th = t.thumb
            if (th != null && !t.isNtp) Image(th.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            else if (t.isNtp) MiniNtp(t.incognito) else Favicon(t.url, t.icon, 32.dp, 8.dp)
            if (Agent.controlling == t) Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(C.surface).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentDots(); Spacer(Modifier.width(6.dp)); Text("Cairn is working", color = C.text, fontSize = 10.5.sp)
            }
        }
    }
}

private fun Modifier.graphicsOffset(dx: Float) = this.offset { androidx.compose.ui.unit.IntOffset(dx.toInt(), 0) }.graphicsLayer { alpha = 1f - (kotlin.math.abs(dx) / 700f).coerceIn(0f, .7f) }

// ---------------------------------------------------------------- Main menu
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenu() {
    val ctx = LocalContext.current
    val tab = Browser.current
    val page = tab != null && !tab.isNtp
    var starred by remember { mutableStateOf(page && runCatching { BrowserDb.isBookmarked(tab!!.url) }.getOrDefault(false)) }
    fun close() { Ui.menu = false }
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = C.surface, contentColor = C.text, scrimColor = C.scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(C.line2)) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            if (page) Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                QuickAct(Ic.ArrowBack, "Back") { close(); Browser.back(tab!!) }
                QuickAct(Ic.ArrowForward, "Forward") { close(); Browser.forward(tab) }
                QuickAct(if (starred) Ic.Star else Ic.StarOutline, if (starred) "Saved" else "Bookmark") { starred = BrowserDb.toggleBookmark(tab!!.url, tab.title); act.toast(if (starred) "Bookmarked" else "Bookmark removed") }
                QuickAct(Ic.Share, "Share") { close(); shareText(ctx, tab!!.url, tab.title) }
                QuickAct(Ic.Search, "Find") { close(); Ui.find = true }
            }
            if (page) {
                SectionLabel("Cairn on this page", Modifier.padding(start = 20.dp, top = 14.dp))
                Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface2).padding(vertical = 4.dp)) {
                    QuickAct(Ic.Summarize, "Summary") { close(); Ui.assistant = true; Agent.quick("summarize") }
                    QuickAct(Ic.Headphones, "Listen") { close(); Agent.quick("read") }
                    QuickAct(Ic.AccountTree, "Mind map") { close(); Ui.assistant = true; Agent.quick("mindmap") }
                    QuickAct(Ic.Translate, if (tab!!.translated != null) "Original" else "Translate") {
                        close()
                        if (tab.translated != null) { tab.translated = null; Browser.reload(tab) }
                        else { Ui.assistant = true; Agent.translate(java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface2).padding(vertical = 4.dp)) {
                    QuickAct(Ic.Lasso, "Circle") { close(); startLasso() }
                    QuickAct(Ic.PictureAsPdf, "PDF") { close(); Agent.quick("pdf"); act.toast("Saving as PDF…") }
                    QuickAct(Ic.Screenshot, "Long shot") { close(); Agent.quick("longshot"); act.toast("Capturing the full page…") }
                    val tracked = app.cairn.browser.service.PriceWatch.isTracked(tab.url)
                    QuickAct(if (tracked) Ic.Check else Ic.BellRing, if (tracked) "Tracking" else "Track price") { close(); trackPrice(tab) }
                }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 6.dp))
            MenuRow(Ic.Add, "New tab") { close(); Browser.newTab(); Ui.omnibox = true }
            MenuRow(Ic.VisibilityOff, "New private tab") { close(); Browser.newTab(NTP, incognito = true) }
            MenuRow(Ic.QuestionAnswer, "Chats with Cairn") { close(); Ui.chats = true; Ui.assistant = true }
            MenuRow(Ic.History, "History") { Ui.go("history") }
            MenuRow(Ic.Download, "Downloads") { Ui.go("downloads") }
            MenuRow(Ic.CloudOff, "Offline pages") { Ui.go("offline") }
            MenuRow(Ic.BookmarkBorder, "Bookmarks") { Ui.go("bookmarks") }
            if (page) {
                MenuRow(Ic.ReadMore, "Reader view") { Ui.go("reader") }
                Row(Modifier.fillMaxWidth().clickable { Browser.setDesktop(tab!!, !tab.desktop); close() }.padding(horizontal = 20.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.DesktopWindows, null, Modifier.size(20.dp), tint = C.muted); Spacer(Modifier.width(20.dp))
                    Text("Desktop site", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    CairnSwitch(tab!!.desktop) { Browser.setDesktop(tab, it); close() }
                }
                if (Prefs.shield) {
                    val site = remember(tab.url) { runCatching { android.net.Uri.parse(tab.url).let { "${it.scheme}://${it.authority}" } }.getOrDefault("") }
                    var shieldOn by remember(site) { mutableStateOf(!Browser.isShieldOff(site)) }
                    Row(Modifier.fillMaxWidth().clickable { shieldOn = !shieldOn; Browser.setShieldOff(site, !shieldOn); close(); Browser.reload(tab) }.padding(horizontal = 20.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.Shield, null, Modifier.size(20.dp), tint = C.muted); Spacer(Modifier.width(20.dp))
                        Text("Shield on this site", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        CairnSwitch(shieldOn) { shieldOn = it; Browser.setShieldOff(site, !it); close(); Browser.reload(tab) }
                    }
                }
                MenuRow(Ic.AddToHomeScreen, "Add to Home screen") { close(); addToHome(ctx, tab) }
                MenuRow(Ic.FileDownload, "Download page") { close(); Agent.quick("pdf") }
                MenuRow(Ic.CloudOff, "Save for offline") {
                    close(); val w = tab.web
                    if (w != null) app.cairn.browser.browser.Offline.save(ctx, w, tab.title, tab.url) { ok -> act.toast(if (ok) "Saved for offline reading" else "Couldn't save this page") }
                }
            }
            MenuRow(Ic.Settings, "Settings") { Ui.go("settings") }
            if (page && Prefs.shield) Text(if (tab!!.blocked > 0) "Shield blocked ${tab.blocked} trackers on this page" else "Shield is on. No trackers found on this page.",
                color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
        }
    }
}

@Composable
fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: Color = C.muted, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint); Spacer(Modifier.width(20.dp))
        Text(title, color = C.text, fontSize = 15.sp)
    }
}

fun addToHome(ctx: Context, t: Tab) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) { act.toast("Your launcher doesn't support shortcuts"); return }
    val icon = t.icon?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(ctx, app.cairn.browser.R.mipmap.ic_launcher)
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(t.url), ctx, BrowserActivity::class.java)
    ShortcutManagerCompat.requestPinShortcut(ctx, ShortcutInfoCompat.Builder(ctx, "site-" + t.url.hashCode()).setShortLabel(t.title.ifBlank { t.host }.take(24)).setIcon(icon).setIntent(i).build(), null)
}

// ---------------------------------------------------------------- Site info
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteInfoSheet() {
    val tab = Browser.current ?: return
    val origin = remember(tab.url) { Uri.parse(tab.url).let { "${it.scheme}://${it.host}" + if (it.port > 0) ":${it.port}" else "" } }
    var perms by remember { mutableStateOf(BrowserDb.perms().filter { it.first == origin }) }
    ModalBottomSheet(onDismissRequest = { Ui.siteInfo = false }, containerColor = C.surface, scrimColor = C.scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(C.line2)) }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Favicon(tab.url, tab.icon, 24.dp); Spacer(Modifier.width(14.dp))
                Column { Text(tab.host, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium); Text(tab.title, color = C.faint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 6.dp))
            Row2(if (tab.secure) Ic.Lock else Ic.LockOpen, if (tab.secure) "Connection is secure" else "Connection is not secure",
                if (tab.secure) "Information you send to this site is encrypted." else "Don't enter passwords or payment details on this site.", tint = if (tab.secure) C.text else C.warn)
            Row2(Ic.Shield, if (Prefs.shield) "Shield is on" else "Shield is off", if (Prefs.shield) "${tab.blocked} trackers and ads blocked on this page" else "Turn it on in Settings → Privacy")
            perms.forEach { (_, p, allow) ->
                Row2(when { p.contains("camera") -> Ic.Videocam; p.contains("micro") -> Ic.Mic; else -> Ic.LocationOn },
                    p.replaceFirstChar { it.uppercase() }.replace("+", " and "), if (allow) "Allowed" else "Blocked")
            }
            if (perms.isNotEmpty()) TextButton(onClick = { BrowserDb.clearPerms(origin); perms = emptyList() }, Modifier.padding(start = 12.dp)) { Text("Reset permissions", color = C.text) }
            TextButton(onClick = {
                android.webkit.CookieManager.getInstance().let { cm -> cm.getCookie(tab.url)?.split(";")?.forEach { c -> cm.setCookie(tab.url, c.substringBefore("=").trim() + "=; Max-Age=0") }; cm.flush() }
                act.toast("Cookies for ${tab.host} cleared"); Ui.siteInfo = false; Browser.reload(tab)
            }, Modifier.padding(start = 12.dp)) { Text("Clear cookies for this site", color = C.text) }
        }
    }
}

// ---------------------------------------------------------------- Long-press menu
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkMenuSheet(m: LinkMenu) {
    val ctx = LocalContext.current
    fun close() { Ui.linkMenu = null }
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = C.surface, scrimColor = C.scrim,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(C.line2)) }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(m.link ?: m.image ?: "", color = C.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 4.dp))
            m.link?.let { l ->
                MenuRow(Ic.OpenInNew, "Open in new tab") { close(); Browser.newTab(l, m.tab.incognito, foreground = !Prefs.openLinksInBackground, opener = m.tab); if (Prefs.openLinksInBackground) act.toast("Opened in background") }
                MenuRow(Ic.VisibilityOff, "Open in private tab") { close(); Browser.newTab(l, incognito = true) }
                MenuRow(Ic.ContentCopy, "Copy link") { close(); copyText(ctx, "Link", l) }
                MenuRow(Ic.Share, "Share link") { close(); shareText(ctx, l) }
                MenuRow(Ic.Download, "Download link") { close(); Downloads.enqueue(ctx, l, null, null, null, m.tab.url, m.tab.incognito) }
                MenuRow(Ic.AutoAwesome, "Summarize link with Cairn", tint = C.agent) { close(); ask("Summarize this page: $l") }
            }
            m.image?.let { img ->
                if (m.link == null || img != m.link) {
                    MenuRow(Ic.Image, "Open image in new tab") { close(); Browser.newTab(img, m.tab.incognito, opener = m.tab) }
                    MenuRow(Ic.FileDownload, "Download image") { close(); act.downloadImage(m.tab, img) }
                    if (!img.startsWith("data:")) MenuRow(Ic.Sparkle, "Ask Cairn about this image", tint = C.agent) { close(); askAboutImage(img) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Site permission prompt
@Composable
fun SitePromptDialog(p: SitePrompt) {
    var remember by remember { mutableStateOf(true) }
    fun done(allow: Boolean) { Ui.prompt = null; p.decide(allow, remember) }
    AlertDialog(onDismissRequest = { done(false) }, containerColor = C.surface2, shape = RoundedCornerShape(22.dp),
        icon = { Icon(when (p.icon) { "camera" -> Ic.Videocam; "mic" -> Ic.Mic; "notifications" -> Ic.Notifications; else -> Ic.LocationOn }, null, tint = C.text) },
        title = { Text("${hostOf(p.origin)} wants to ${p.what}", color = C.text, fontSize = 17.sp, textAlign = TextAlign.Center) },
        text = {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { remember = !remember }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remember, { remember = it }, colors = CheckboxDefaults.colors(checkedColor = C.text, checkmarkColor = C.bg, uncheckedColor = C.muted))
                Text("Remember my decision for this site", color = C.muted, fontSize = 14.sp)
            }
        },
        confirmButton = { TextButton(onClick = { done(true) }) { Text("Allow", color = C.text, fontWeight = FontWeight.Medium) } },
        dismissButton = { TextButton(onClick = { done(false) }) { Text("Block", color = C.muted) } })
}

// ---------------------------------------------------------------- First run
@Composable
fun Onboarding() {
    var page by remember { mutableIntStateOf(0) }
    val pages = listOf(
        Triple(Ic.Language, "A fast, private browser", "Tabs, private browsing, bookmarks, downloads and a built-in Shield that blocks trackers and ads."),
        Triple(Ic.AutoAwesome, "An assistant that does the work", "Ask in any language. Cairn compares prices across stores, reads articles aloud, summarizes pages and fills carts for you."),
        Triple(Ic.Fingerprint, "You stay in control", "Cairn stops before anything important. Payments always need your fingerprint, face or PIN."),
    )
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Logo(24.dp); Spacer(Modifier.width(8.dp)); Text("cairn", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp) }
        Spacer(Modifier.weight(1f))
        val (ic, t, s) = pages[page]
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(C.surface2), contentAlignment = Alignment.Center) { Icon(ic, null, Modifier.size(28.dp), tint = if (page == 1) C.agent else C.text) }
        Spacer(Modifier.height(28.dp))
        Text(t, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp, letterSpacing = (-0.6).sp)
        Spacer(Modifier.height(14.dp))
        Text(s, color = C.muted, fontSize = 16.sp, lineHeight = 24.sp)
        Spacer(Modifier.weight(1.2f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            pages.indices.forEach { i -> Box(Modifier.padding(end = 6.dp).height(4.dp).width(if (i == page) 22.dp else 8.dp).clip(CircleShape).background(if (i == page) C.text else C.line2)) }
            Spacer(Modifier.weight(1f))
            if (page < pages.size - 1) TextButton(onClick = { Prefs.onboarded = true }) { Text("Skip", color = C.muted) }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(if (page < pages.size - 1) "Next" else "Start browsing") { if (page < pages.size - 1) page++ else Prefs.onboarded = true }
        }
        if (page == pages.size - 1 && !runCatching { act.isDefaultBrowser }.getOrDefault(true)) {
            Spacer(Modifier.height(14.dp))
            Text("Make Cairn your default browser", color = C.muted, fontSize = 14.sp, modifier = Modifier.clickable { act.requestDefaultBrowser { } }.padding(vertical = 6.dp))
        }
    }
}
