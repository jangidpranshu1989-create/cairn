package app.saarthi.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ReadMore
import androidx.compose.material.icons.outlined.*
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
import app.saarthi.browser.agent.Agent
import app.saarthi.browser.browser.Browser
import app.saarthi.browser.browser.Downloads
import app.saarthi.browser.browser.NTP
import app.saarthi.browser.browser.Tab
import app.saarthi.browser.data.BrowserDb
import app.saarthi.browser.data.Prefs
import app.saarthi.browser.data.Search
import app.saarthi.browser.data.hostOf
import app.saarthi.browser.service.ReadAloud
import kotlinx.coroutines.delay

val act get() = Browser.activity

fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    if (android.os.Build.VERSION.SDK_INT < 33) act.toast("Copied")
}
fun shareText(ctx: Context, text: String, subject: String? = null) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }, null))
}
fun ask(text: String) { Ui.assistant = true; Agent.run(text) }

/** Root of the whole UI. */
@Composable
fun App(webHost: FrameLayout, bgHost: FrameLayout) {
    Box(Modifier.fillMaxSize().background(C.chrome)) {
        BrowserScreen(webHost, bgHost)
        AnimatedVisibility(Ui.screen != null, enter = slideInVertically { it / 12 } + fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            Box(Modifier.fillMaxSize().background(C.bg)) { Ui.screen?.let { Screens(it) } }
        }
        AnimatedVisibility(Ui.tabSwitcher, enter = fadeIn(tween(140)) + slideInVertically { it / 16 }, exit = fadeOut(tween(120))) { TabSwitcher() }
        AnimatedVisibility(Ui.omnibox, enter = fadeIn(tween(120)), exit = fadeOut(tween(100))) { Omnibox() }
        AssistantSheet()
        if (Ui.menu) MainMenu()
        if (Ui.siteInfo) SiteInfoSheet()
        Ui.linkMenu?.let { LinkMenuSheet(it) }
        Ui.prompt?.let { SitePromptDialog(it) }
        Ui.fullscreen?.let { v ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView({ FrameLayout(it).apply { (v.parent as? ViewGroup)?.removeView(v); addView(v, FrameLayout.LayoutParams(-1, -1)) } }, Modifier.fillMaxSize())
            }
            BackHandler { act.exitFullscreen() }
        }
        if (!Prefs.onboarded) Onboarding()
    }
}

@Composable
fun BrowserScreen(webHost: FrameLayout, bgHost: FrameLayout) {
    val tab = Browser.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = Ui.screen == null && !Ui.omnibox && !Ui.tabSwitcher && !Ui.assistant) {
        when {
            Ui.find -> { Ui.find = false; tab?.web?.clearMatches() }
            tab != null && Browser.back(tab) -> {}
            else -> act.moveTaskToBack(true)
        }
    }
    Column(Modifier.fillMaxSize().background(C.chrome).statusBarsPadding().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(C.bg)) {
            // Background agent pages live under the visible page so they lay out at real size but are never seen.
            AndroidView({ bgHost }, Modifier.fillMaxSize())
            AndroidView({ webHost }, Modifier.fillMaxSize())
            if (tab == null || tab.isNtp) NewTabPage(tab?.incognito == true)
            tab?.error?.let { ErrorPage(tab, it.code, it.description) }
            tab?.ssl?.let { SslPage(tab) }
            AgentEdge(Agent.controlling != null && Agent.controlling == tab)
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(Agent.running && !Ui.assistant, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) { AgentPill() }
            }
        }
        AnimatedVisibility(Ui.find && tab != null) { tab?.let { FindBar(it) } }
        AnimatedVisibility(ReadAloud.active && !Ui.find) { ReadAloudBar() }
        if (!imeVisible || Ui.find) BottomBar(tab)
    }
}

// ---------------------------------------------------------------- Bottom bar
@Composable
fun BottomBar(tab: Tab?) {
    val collapsed = Ui.collapsed && tab != null && !tab.isNtp && !tab.loading
    Column(Modifier.fillMaxWidth().background(C.chrome).navigationBarsPadding()) {
        // Progress line
        val p by animateFloatAsState(if (tab?.loading == true) tab.progress / 100f else 1f, tween(220), label = "p")
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (tab?.loading == true) Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceAtLeast(0.06f)).background(if (Agent.controlling == tab && tab != null) C.agent else C.text.copy(alpha = .75f)))
        }
        AnimatedVisibility(collapsed, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().height(30.dp).clickable { Ui.collapsed = false }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (tab?.secure == true) Icon(Icons.Outlined.Lock, null, Modifier.size(11.dp), tint = C.faint)
                Spacer(Modifier.width(5.dp))
                Text(tab?.let { Search.queryOf(it.url) ?: it.host } ?: "", color = C.muted, fontSize = 12.5.sp, maxLines = 1)
            }
        }
        AnimatedVisibility(!collapsed, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                AddressPill(tab)
                Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", enabled = tab != null && (tab.canBack || !tab.isNtp)) { Browser.back(tab) }
                    IconBtn(Icons.AutoMirrored.Outlined.ArrowForward, "Forward", enabled = tab?.canForward == true) { Browser.forward() }
                    Spacer(Modifier.weight(1f))
                    AssistantButton()
                    Spacer(Modifier.weight(1f))
                    TabsButton(if (tab?.incognito == true) Browser.privateTabs().size else Browser.normalTabs().size, tab?.incognito == true)
                    IconBtn(Icons.Outlined.Add, "New tab", onLongClick = { Browser.newTab(NTP, incognito = true) }) { Browser.newTab(NTP, tab?.incognito == true); Ui.omnibox = true }
                }
            }
        }
    }
}

@Composable
fun AddressPill(tab: Tab?) {
    val ctx = LocalContext.current
    var drag by remember { mutableFloatStateOf(0f) }
    val swipe = with(LocalDensity.current) { 72.dp.toPx() }
    Row(
        Modifier.padding(horizontal = 12.dp).padding(top = 6.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (tab?.incognito == true) C.surface3 else C.surface2)
            .pointerInput(tab) {
                detectHorizontalDragGestures(onDragEnd = {
                    val list = Browser.tabs.filter { it.incognito == tab?.incognito }
                    val i = list.indexOf(tab)
                    if (drag < -swipe) list.getOrNull(i + 1)?.let { Browser.select(it) }
                    else if (drag > swipe) list.getOrNull(i - 1)?.let { Browser.select(it) }
                    drag = 0f
                }) { _, d -> drag += d }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(Icons.Outlined.MoreVert, "Menu", size = 44.dp) { Ui.menu = true }
        Row(Modifier.weight(1f).fillMaxHeight().combinedClickableCompat(onClick = { Ui.omniboxText = null; Ui.omnibox = true }, onLongClick = { tab?.takeIf { !it.isNtp }?.let { copyText(ctx, "Link", it.url) } }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            when {
                tab == null || tab.isNtp -> {
                    Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = C.faint); Spacer(Modifier.width(8.dp))
                    Text(if (tab?.incognito == true) "Search privately" else "Search or type URL", color = C.faint, fontSize = 15.sp)
                }
                else -> {
                    val q = Search.queryOf(tab.url)
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { Ui.siteInfo = true }, contentAlignment = Alignment.Center) {
                        Icon(when { q != null -> Icons.Outlined.Search; tab.secure -> Icons.Outlined.Tune; else -> Icons.Outlined.Info }, "Site information",
                            Modifier.size(16.dp), tint = if (!tab.secure && q == null) C.warn else C.muted)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(q ?: tab.host.ifBlank { tab.url }, color = C.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (tab.blocked > 0 && Prefs.shield) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Shield, "Trackers blocked", Modifier.size(13.dp), tint = C.faint)
                    }
                    Spacer(Modifier.width(32.dp))
                }
            }
        }
        if (tab != null && !tab.isNtp) IconBtn(if (tab.loading) Icons.Outlined.Close else Icons.Outlined.Refresh, if (tab.loading) "Stop" else "Reload", size = 44.dp) { if (tab.loading) Browser.stop(tab) else Browser.reload(tab) }
        else IconBtn(Icons.Outlined.Mic, "Voice search", size = 44.dp) { act.listen { t -> Browser.open(t) } }
    }
}

@Composable
fun AssistantButton() {
    Row(Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(C.surface2).border(1.dp, if (Agent.running) C.agent.copy(alpha = .55f) else C.line, RoundedCornerShape(19.dp)),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.fillMaxHeight().clickable { Ui.assistant = true }.padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (Agent.running) CircularProgressIndicator(Modifier.size(14.dp), color = C.agent, strokeWidth = 1.6.dp)
            else Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = C.text)
            Spacer(Modifier.width(8.dp))
            Text("Assistant", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.width(1.dp).height(18.dp).background(C.line2))
        Box(Modifier.fillMaxHeight().clickable { act.listen { ask(it) } }.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Mic, "Ask by voice", Modifier.size(17.dp), tint = C.muted)
        }
    }
}

@Composable
fun TabsButton(n: Int, private: Boolean) {
    Box(Modifier.size(44.dp).clip(CircleShape).combinedClickableCompat(onClick = { Ui.tabSwitcher = true }, onLongClick = { Ui.menu = false; Browser.current?.let { Browser.close(it) } }), contentAlignment = Alignment.Center) {
        Box(Modifier.size(21.dp).border(1.6.dp, C.text, RoundedCornerShape(6.dp)).background(if (private) C.surface3 else Color.Transparent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text(if (n > 99) ":D" else n.toString(), color = C.text, fontSize = if (n > 9) 9.5.sp else 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
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
        val w = 3.dp.toPx(); val g = 26.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(col.copy(alpha = .28f * a), Color.Transparent), 0f, g))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = .28f * a)), size.height - g, size.height), Offset(0f, size.height - g))
        drawRect(Brush.horizontalGradient(listOf(col.copy(alpha = .22f * a), Color.Transparent), 0f, g), size = size.copy(width = g))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = .22f * a)), size.width - g, size.width), Offset(size.width - g, 0f))
        drawRect(col.copy(alpha = a), size = size.copy(height = w))
    })
}

@Composable
fun AgentPill() {
    Row(Modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.agent.copy(alpha = .45f), RoundedCornerShape(20.dp))
        .clickable { Ui.assistant = true }.padding(start = 14.dp, end = 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = C.agent, strokeWidth = 1.6.dp)
        Spacer(Modifier.width(10.dp))
        Text(Agent.status.ifBlank { "Working…" }, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp))
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { Agent.stop() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Stop", color = C.muted, fontSize = 13.sp) }
    }
}

// ---------------------------------------------------------------- New tab page
private val defaults = listOf("Wikipedia" to "https://www.wikipedia.org", "YouTube" to "https://m.youtube.com", "Amazon" to "https://www.amazon.in", "Flipkart" to "https://www.flipkart.com",
    "Reddit" to "https://www.reddit.com", "GitHub" to "https://github.com", "News" to "https://news.google.com", "Maps" to "https://maps.google.com")

private val ideas = listOf(
    Icons.Outlined.ShoppingBag to "Find the cheapest iPhone 16 from a trusted store",
    Icons.Outlined.Headphones to "Read the Wikipedia article on the Taj Mahal aloud",
    Icons.Outlined.Summarize to "Summarize today's top tech news",
    Icons.Outlined.AccountTree to "Make a mind map of how GPUs work",
)

@Composable
fun NewTabPage(private: Boolean) {
    val v = BrowserDb.version.intValue
    val sites = remember(v) {
        val top = runCatching { BrowserDb.topSites(8) }.getOrDefault(emptyList()).map { (it.title.ifBlank { hostOf(it.url) }) to it.url }
        (top + defaults.filter { d -> top.none { hostOf(it.second) == hostOf(d.second) } }).take(8)
    }
    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(if (private) 72.dp else 88.dp))
        if (private) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(C.surface2), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.VisibilityOff, null, tint = C.text) }
            Spacer(Modifier.height(18.dp))
            Text("Private tab", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("Saarthi won't save your browsing history, cookies, site data or form entries from private tabs. Downloads and bookmarks you create are kept.",
                color = C.muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text("Your activity may still be visible to websites you visit, your employer or school, and your internet provider.", color = C.faint, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(30.dp); Spacer(Modifier.width(10.dp))
            Text("saarthi", color = C.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(26.dp))
            .clickable { Ui.omniboxText = null; Ui.omnibox = true }.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = C.faint); Spacer(Modifier.width(10.dp))
            Text("Ask anything or search the web", color = C.faint, fontSize = 15.sp, modifier = Modifier.weight(1f))
            IconBtn(Icons.Outlined.Mic, "Voice", tint = C.muted) { act.listen { ask(it) } }
        }
        Spacer(Modifier.height(28.dp))
        // Shortcuts
        Column(Modifier.fillMaxWidth()) {
            sites.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (name, url) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { Browser.current?.let { Browser.load(it, url) } }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(C.surface), contentAlignment = Alignment.Center) { Favicon(url, size = 22.dp) }
                            Spacer(Modifier.height(7.dp))
                            Text(name, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        SectionLabel("Try Saarthi", Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface)) {
            ideas.forEachIndexed { i, (ic, t) ->
                if (i > 0) HorizontalDivider(color = C.line, modifier = Modifier.padding(start = 50.dp))
                Row(Modifier.fillMaxWidth().clickable { ask(t) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, Modifier.size(18.dp), tint = C.muted); Spacer(Modifier.width(16.dp))
                    Text(t, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.NorthEast, null, Modifier.size(14.dp), tint = C.faint)
                }
            }
        }
        val recent = remember(v) { runCatching { BrowserDb.history(limit = 4) }.getOrDefault(emptyList()) }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionLabel("Jump back in", Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface)) {
                recent.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider(color = C.line, modifier = Modifier.padding(start = 50.dp))
                    Row(Modifier.fillMaxWidth().clickable { Browser.current?.let { Browser.load(it, h.url) } }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Favicon(h.url, size = 18.dp); Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title.ifBlank { hostOf(h.url) }, color = C.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hostOf(h.url), color = C.faint, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        if (Prefs.shield && Prefs.shieldTotal > 0) Text("Shield has blocked ${"%,d".format(Prefs.shieldTotal)} trackers and ads", color = C.faint, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Error pages
@Composable
fun ErrorPage(tab: Tab, code: Int, desc: String) {
    val (title, body) = when (code) {
        android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "This site can't be reached" to "${tab.host.ifBlank { "The server" }}'s address couldn't be found. Check your connection or the spelling of the address."
        android.webkit.WebViewClient.ERROR_CONNECT, android.webkit.WebViewClient.ERROR_TIMEOUT -> "This site can't be reached" to "${tab.host} took too long to respond."
        -1000 -> "Something went wrong" to "This page stopped working. Reloading usually fixes it."
        else -> "This page isn't working" to desc.removePrefix("net::").ifBlank { "Saarthi couldn't load this page." }
    }
    Column(Modifier.fillMaxSize().background(C.bg).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(36.dp), tint = C.faint)
        Spacer(Modifier.height(20.dp))
        Text(title, color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(body, color = C.muted, fontSize = 14.5.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(6.dp))
        Text(desc.ifBlank { "ERR_$code" }.uppercase(), color = C.faint, fontSize = 11.sp, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(28.dp))
        Row { PrimaryButton("Reload", icon = Icons.Outlined.Refresh) { Browser.reload(tab) } }
    }
}

@Composable
fun SslPage(tab: Tab) {
    var advanced by remember { mutableStateOf(false) }
    val p = tab.ssl ?: return
    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Outlined.GppBad, null, Modifier.size(38.dp), tint = C.err)
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
            Text("$why Saarthi can't confirm this is the real ${hostOf(p.url)}.", color = C.muted, fontSize = 13.5.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("Proceed to ${hostOf(p.url)} (unsafe)", color = C.err, fontSize = 13.5.sp, modifier = Modifier.clickable { tab.ssl = null; p.handler.proceed() }.padding(vertical = 8.dp))
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
        IconBtn(Icons.Outlined.KeyboardArrowUp, "Previous") { tab.web?.findNext(false) }
        IconBtn(Icons.Outlined.KeyboardArrowDown, "Next") { tab.web?.findNext(true) }
        IconBtn(Icons.Outlined.Close, "Close find") { Ui.find = false; tab.web?.clearMatches() }
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
            Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp), tint = C.agent)
            Spacer(Modifier.width(12.dp))
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
            IconBtn(Icons.Outlined.Replay10, "Back") { ReadAloud.skip(-1) }
            IconBtn(if (ReadAloud.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (ReadAloud.playing) "Pause" else "Play") { ReadAloud.toggle() }
            IconBtn(Icons.Outlined.Close, "Stop reading") { ReadAloud.stop() }
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
    val local = remember(q) {
        if (q.isEmpty() || q == start) emptyList() else {
            val b = runCatching { BrowserDb.bookmarks(q).take(3) }.getOrDefault(emptyList()).map { Sugg("bookmark", it.title, it.url, it.url) }
            val h = runCatching { BrowserDb.history(q, 6) }.getOrDefault(emptyList()).filter { x -> b.none { it.url == x.url } }.take(4).map { Sugg("history", it.title.ifBlank { it.url }, it.url, it.url) }
            b + h
        }
    }
    val clip = remember {
        runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim() }.getOrNull()
            ?.takeIf { it.length < 2000 && Regex("^https?://\\S+$").matches(it) }
    }
    fun go(input: String) {
        if (input.isBlank()) return
        Ui.omnibox = false
        val t = Browser.current ?: Browser.newTab()
        Browser.open(input, t)
    }
    BackHandler { Ui.omnibox = false }
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().imePadding().navigationBarsPadding()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp)) {
            // reverse layout: first item sits right above the input, like a bottom-anchored address bar
            if (q.isNotEmpty() && q != start) {
                item { SuggRow(Icons.Outlined.AutoAwesome, "Ask Saarthi", q, tint = C.agent) { Ui.omnibox = false; ask(q) } }
                val asUrl = Search.toUrl(q)
                if (asUrl != null) item { SuggRow(Icons.Outlined.Language, q, "Open website") { go(asUrl) } }
                else item { SuggRow(Icons.Outlined.Search, q, "${Search.current.name} search") { go(q) } }
                items(remote.filter { !it.equals(q, true) }.take(6)) { s -> SuggRow(Icons.Outlined.Search, s, null, fill = { tf = TextFieldValue("$s ", TextRange(s.length + 1)) }) { go(s) } }
                items(local) { s -> SuggRow(if (s.kind == "bookmark") Icons.Outlined.StarOutline else Icons.Outlined.History, s.title, s.sub) { go(s.url!!) } }
            } else {
                if (clip != null && clip != tab?.url) item { SuggRow(Icons.Outlined.ContentPaste, "Link you copied", clip) { go(clip) } }
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
                            QuickAct(Icons.Outlined.ContentCopy, "Copy link") { copyText(ctx, "Link", tab.url); Ui.omnibox = false }
                            QuickAct(Icons.Outlined.Share, "Share") { shareText(ctx, tab.url, tab.title); Ui.omnibox = false }
                            QuickAct(Icons.Outlined.Edit, "Edit") { tf = TextFieldValue(tab.url, TextRange(tab.url.length)) }
                            QuickAct(Icons.Outlined.QrCode2, "QR code") { Ui.omnibox = false; Browser.newTab("https://api.qrserver.com/v1/create-qr-code/?size=480x480&data=" + Uri.encode(tab.url), tab.incognito) }
                        }
                    }
                }
                val recent = runCatching { BrowserDb.history(limit = 6) }.getOrDefault(emptyList())
                if (tab?.incognito != true) items(recent) { h -> SuggRow(Icons.Outlined.History, h.title.ifBlank { h.url }, hostOf(h.url)) { go(h.url) } }
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
                    decorationBox = { inner -> if (tf.text.isEmpty()) Text(if (tab?.incognito == true) "Search privately" else "Search, ask, or type a URL", color = C.faint, fontSize = 15.5.sp); inner() })
                if (tf.text.isNotEmpty()) IconBtn(Icons.Outlined.Cancel, "Clear", tint = C.faint, size = 38.dp) { tf = TextFieldValue("") }
                else IconBtn(Icons.Outlined.Mic, "Voice", tint = C.muted, size = 38.dp) { act.listen { go(it) } }
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
        fill?.let { IconBtn(Icons.Outlined.NorthWest, "Use suggestion", tint = C.faint, size = 40.dp, onClick = it) }
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
    val list = Browser.tabs.filter { it.incognito == private }
    BackHandler { Ui.tabSwitcher = false }
    Column(Modifier.fillMaxSize().background(C.chrome).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconBtn(Icons.Outlined.MoreVert, "More") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = C.surface2) {
                    DropdownMenuItem({ Text(if (private) "Close all private tabs" else "Close all tabs", color = C.text) }, { menu = false; Browser.closeAll(private); if (!private) Ui.tabSwitcher = false })
                    DropdownMenuItem({ Text("Settings", color = C.text) }, { menu = false; Ui.tabSwitcher = false; Ui.go("settings") })
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(C.surface2).padding(3.dp)) {
                Seg("${Browser.normalTabs().size}  Tabs", !private) { private = false }
                Seg("Private", private) { private = true }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { Ui.tabSwitcher = false }) { Text("Done", color = C.text, fontWeight = FontWeight.Medium) }
        }
        if (list.isEmpty()) EmptyState(if (private) Icons.Outlined.VisibilityOff else Icons.Outlined.Tab, if (private) "No private tabs" else "No tabs",
            if (private) "Private tabs don't save history or cookies." else "Open a new tab to start browsing.", Modifier.weight(1f).fillMaxWidth())
        else LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { t -> TabCard(t) }
        }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.accent).clickable { Browser.newTab(NTP, private); Ui.tabSwitcher = false; Ui.omnibox = true }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = C.accentInk); Spacer(Modifier.width(8.dp))
                Text(if (private) "New private tab" else "New tab", color = C.accentInk, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Seg(t: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) C.surface3 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(t, color = if (on) C.text else C.muted, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun TabCard(t: Tab) {
    var dx by remember { mutableFloatStateOf(0f) }
    val cur = t == Browser.current
    Column(Modifier.graphicsOffset(dx).clip(RoundedCornerShape(16.dp)).background(C.surface)
        .border(if (cur) 2.dp else 1.dp, if (cur) C.text.copy(alpha = .8f) else C.line, RoundedCornerShape(16.dp))
        .pointerInput(t) { detectHorizontalDragGestures(onDragEnd = { if (kotlin.math.abs(dx) > size.width * .35f) Browser.close(t); dx = 0f }) { _, d -> dx += d } }
        .clickable { Browser.select(t); Ui.tabSwitcher = false }) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.isNtp) Logo(14.dp) else Favicon(t.url, t.icon, 15.dp, 3.dp)
            Spacer(Modifier.width(8.dp))
            Text(if (t.isNtp) "New tab" else t.title.ifBlank { t.host }, color = C.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconBtn(Icons.Outlined.Close, "Close tab", tint = C.muted, size = 34.dp) { Browser.close(t) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(.8f).background(C.bg), contentAlignment = Alignment.Center) {
            val th = t.thumb
            if (th != null && !t.isNtp) Image(th.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            else if (t.isNtp) Logo(28.dp, color = C.faint) else Favicon(t.url, t.icon, 28.dp)
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
                QuickAct(if (starred) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (starred) "Saved" else "Bookmark") { starred = BrowserDb.toggleBookmark(tab!!.url, tab.title); act.toast(if (starred) "Bookmarked" else "Bookmark removed") }
                QuickAct(Icons.Outlined.Share, "Share") { close(); shareText(ctx, tab!!.url, tab.title) }
                QuickAct(Icons.Outlined.Search, "Find") { close(); Ui.find = true }
                QuickAct(Icons.Outlined.Translate, "Translate") { close(); Browser.load(tab!!, "https://translate.google.com/translate?sl=auto&tl=${java.util.Locale.getDefault().language}&u=" + Uri.encode(tab.url)) }
            }
            if (page) {
                SectionLabel("Saarthi on this page", Modifier.padding(start = 20.dp, top = 14.dp))
                Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface2).padding(vertical = 4.dp)) {
                    QuickAct(Icons.Outlined.Summarize, "Summary") { close(); Ui.assistant = true; Agent.quick("summarize") }
                    QuickAct(Icons.Outlined.Headphones, "Listen") { close(); Agent.quick("read") }
                    QuickAct(Icons.Outlined.AccountTree, "Mind map") { close(); Ui.assistant = true; Agent.quick("mindmap") }
                    QuickAct(Icons.Outlined.PictureAsPdf, "PDF") { close(); Agent.quick("pdf"); act.toast("Saving as PDF…") }
                    QuickAct(Icons.Outlined.Screenshot, "Long shot") { close(); Agent.quick("longshot"); act.toast("Capturing the full page…") }
                }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 6.dp))
            MenuRow(Icons.Outlined.Add, "New tab") { close(); Browser.newTab(); Ui.omnibox = true }
            MenuRow(Icons.Outlined.VisibilityOff, "New private tab") { close(); Browser.newTab(NTP, incognito = true) }
            MenuRow(Icons.Outlined.History, "History") { Ui.go("history") }
            MenuRow(Icons.Outlined.Download, "Downloads") { Ui.go("downloads") }
            MenuRow(Icons.Outlined.BookmarkBorder, "Bookmarks") { Ui.go("bookmarks") }
            if (page) {
                MenuRow(Icons.AutoMirrored.Outlined.ReadMore, "Reader view") { Ui.go("reader") }
                Row(Modifier.fillMaxWidth().clickable { Browser.setDesktop(tab!!, !tab.desktop); close() }.padding(horizontal = 20.dp).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DesktopWindows, null, Modifier.size(20.dp), tint = C.muted); Spacer(Modifier.width(20.dp))
                    Text("Desktop site", color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    SaarthiSwitch(tab!!.desktop) { Browser.setDesktop(tab, it); close() }
                }
                MenuRow(Icons.Outlined.AddToHomeScreen, "Add to Home screen") { close(); addToHome(ctx, tab) }
                MenuRow(Icons.Outlined.FileDownload, "Download page") { close(); Agent.quick("pdf") }
            }
            MenuRow(Icons.Outlined.Settings, "Settings") { Ui.go("settings") }
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
    val icon = t.icon?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(ctx, app.saarthi.browser.R.mipmap.ic_launcher)
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
            Row2(if (tab.secure) Icons.Outlined.Lock else Icons.Outlined.LockOpen, if (tab.secure) "Connection is secure" else "Connection is not secure",
                if (tab.secure) "Information you send to this site is encrypted." else "Don't enter passwords or payment details on this site.", tint = if (tab.secure) C.text else C.warn)
            Row2(Icons.Outlined.Shield, if (Prefs.shield) "Shield is on" else "Shield is off", if (Prefs.shield) "${tab.blocked} trackers and ads blocked on this page" else "Turn it on in Settings → Privacy")
            perms.forEach { (_, p, allow) ->
                Row2(when { p.contains("camera") -> Icons.Outlined.Videocam; p.contains("micro") -> Icons.Outlined.Mic; else -> Icons.Outlined.LocationOn },
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
                MenuRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open in new tab") { close(); Browser.newTab(l, m.tab.incognito, foreground = !Prefs.openLinksInBackground, opener = m.tab); if (Prefs.openLinksInBackground) act.toast("Opened in background") }
                MenuRow(Icons.Outlined.VisibilityOff, "Open in private tab") { close(); Browser.newTab(l, incognito = true) }
                MenuRow(Icons.Outlined.ContentCopy, "Copy link") { close(); copyText(ctx, "Link", l) }
                MenuRow(Icons.Outlined.Share, "Share link") { close(); shareText(ctx, l) }
                MenuRow(Icons.Outlined.Download, "Download link") { close(); Downloads.enqueue(ctx, l, null, null, null, m.tab.url, m.tab.incognito) }
                MenuRow(Icons.Outlined.AutoAwesome, "Summarize link with Saarthi", tint = C.agent) { close(); ask("Summarize this page: $l") }
            }
            m.image?.let { img ->
                if (m.link == null || img != m.link) {
                    MenuRow(Icons.Outlined.Image, "Open image in new tab") { close(); Browser.newTab(img, m.tab.incognito, opener = m.tab) }
                    MenuRow(Icons.Outlined.FileDownload, "Download image") { close(); act.downloadImage(m.tab, img) }
                    if (!img.startsWith("data:")) MenuRow(Icons.Outlined.ImageSearch, "Search image with Google Lens") { close(); Browser.newTab("https://lens.google.com/uploadbyurl?url=" + Uri.encode(img), m.tab.incognito) }
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
        icon = { Icon(when (p.icon) { "camera" -> Icons.Outlined.Videocam; "mic" -> Icons.Outlined.Mic; else -> Icons.Outlined.LocationOn }, null, tint = C.text) },
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
        Triple(Icons.Outlined.Language, "A fast, private browser", "Tabs, private browsing, bookmarks, downloads and a built-in Shield that blocks trackers and ads."),
        Triple(Icons.Outlined.AutoAwesome, "An assistant that does the work", "Ask in any language. Saarthi compares prices across stores, reads articles aloud, summarizes pages and fills carts for you."),
        Triple(Icons.Outlined.Fingerprint, "You stay in control", "Saarthi stops before anything important. Payments always need your fingerprint, face or PIN."),
    )
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Logo(24.dp); Spacer(Modifier.width(8.dp)); Text("saarthi", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp) }
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
            Text("Make Saarthi your default browser", color = C.muted, fontSize = 14.sp, modifier = Modifier.clickable { act.requestDefaultBrowser { } }.padding(vertical = 6.dp))
        }
    }
}
