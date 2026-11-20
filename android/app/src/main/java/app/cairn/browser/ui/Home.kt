package app.cairn.browser.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.agent.Agent
import app.cairn.browser.agent.Attachment
import app.cairn.browser.agent.Chats
import app.cairn.browser.browser.Browser
import app.cairn.browser.data.Api
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.Prefs
import app.cairn.browser.data.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Live weather for the greeting pill (approximate location from the network, no location permission). Cached 30 min. */
object Weather {
    var now by mutableStateOf<String?>(null); private set
    private var at = 0L
    suspend fun refresh() {
        if (System.currentTimeMillis() - at < 30 * 60_000L && now != null) return
        at = System.currentTimeMillis()
        val r = withContext(Dispatchers.IO) {
            runCatching {
                Api.http.newCall(Request.Builder().url("https://wttr.in/?format=%t|%C").header("User-Agent", "curl/8").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull()
        }?.trim()
        if (r != null && r.contains('|') && r.length < 60 && !r.contains("<")) {
            val (t, c) = r.split('|', limit = 2)
            now = "${t.trim().removePrefix("+")} · ${c.trim().split(',').first().take(18)}"
        }
    }
}

/** Agent mode on the home Omni-bar: what you type goes straight to the agent instead of a web search. */
object HomeMode { var agent by mutableStateOf(Prefs.homeAgent) }

@Composable
fun HomeHeader() {
    val now = rememberNow()
    LaunchedEffect(Unit) { Weather.refresh() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(greetingFor(now), label = "greet", transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }) { g ->
                Text(g, color = C.text, fontSize = 26.sp, lineHeight = 31.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(Ic.Schedule, java.text.SimpleDateFormat("EEE d MMM · h:mm a", java.util.Locale.getDefault()).format(now.time))
                Weather.now?.let { Pill(Ic.Sun, it) }
                val synced = Prefs.isSignedIn
                Row(Modifier.clip(CircleShape).background(C.surface).border(1.dp, C.line, CircleShape).clickable { Ui.go("settings/account") }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (synced) C.ok else C.faint)); Spacer(Modifier.width(6.dp))
                    Text(if (synced) "Synced" else "On this phone", color = C.muted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        ProfileAvatar(40.dp) { Ui.profile = true }
    }
}

@Composable
private fun Pill(icon: ImageVector, text: String) = Row(Modifier.clip(CircleShape).background(C.surface).border(1.dp, C.line, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(12.dp), tint = C.muted); Spacer(Modifier.width(5.dp))
    Text(text, color = C.muted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/** Glass Omni-bar: search, open a site or hand a task to the agent; voice, camera OCR and an agent-mode toggle. */
@Composable
fun OmniBar(motion: Boolean, quick: List<Triple<ImageVector, String, String>>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val glow = rememberGlowPhase(motion)
    var camUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = camUri
        if (ok && u != null) scope.launch {
            val a = withContext(Dispatchers.IO) { Attachment.fromCameraFile(ctx, u) } ?: return@launch act.toast("Couldn't read that photo")
            a.extract(ctx)
            Ui.assistant = true
            Agent.run("Read all the text in this photo (OCR), then explain what it says and anything useful I should do with it.", attachments = listOf(a))
        }
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) runCatching { Attachment.newCameraUri(ctx).also { camUri = it }.let { camera.launch(it) } }.onFailure { act.toast("Couldn't open the camera") }
        else act.toast("Camera permission is needed to scan text")
    }
    val agent = HomeMode.agent
    val border by animateFloatAsState(if (agent) 1f else .55f, tween(300), label = "b")
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().glowBorder(motion && agent, glow, 24.dp, 1.3.dp).clip(shape)
        .background(Brush.verticalGradient(listOf(C.surface.copy(alpha = .96f), C.surface2.copy(alpha = .82f))))
        .border(1.dp, Brush.verticalGradient(listOf(C.line2.copy(alpha = border), C.line)), shape)) {
        Row(Modifier.fillMaxWidth().height(60.dp).clickable { Ui.omniboxText = null; Ui.omniAgent = agent; Ui.omnibox = true }.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (agent) Ic.Sparkle else Ic.Search, null, Modifier.size(18.dp), tint = if (agent) C.agent else C.muted); Spacer(Modifier.width(12.dp))
            Text(if (agent) "Tell Cairn what to do…" else "Search or type a URL", color = C.faint, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconBtn(Ic.Mic, "Voice", tint = C.muted, size = 42.dp) { act.listen { if (agent || Ui.looksLikeTask(it)) ask(it) else Browser.open(it, Browser.current ?: Browser.newTab()) } }
            IconBtn(Ic.ScanText, "Scan text with camera", tint = C.muted, size = 42.dp) { Haptics.tick(); camPerm.launch(Manifest.permission.CAMERA) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Agent mode toggle
            Row(Modifier.clip(CircleShape).background(if (agent) C.agent else C.surface3).clickable { Haptics.tick(); HomeMode.agent = !agent; Prefs.homeAgent = !agent }
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Bot, null, Modifier.size(14.dp), tint = if (agent) C.accentInk else C.muted); Spacer(Modifier.width(6.dp))
                Text(if (agent) "Agent on" else "Agent off", color = if (agent) C.accentInk else C.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            quick.forEach { (ic, label, prompt) ->
                Row(Modifier.clip(CircleShape).background(C.surface3.copy(alpha = .7f)).clickable { Haptics.tick(); ask(prompt) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, Modifier.size(14.dp), tint = C.agent); Spacer(Modifier.width(6.dp))
                    Text(label, color = C.text, fontSize = 12.5.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Pinned shortcuts: top sites (one per site, never blank or error pages) topped up with defaults. */
@Composable
fun ShortcutGrid(sites: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(22.dp)).padding(vertical = 8.dp, horizontal = 4.dp)) {
        sites.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (name, url) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { Haptics.tick(); Browser.current?.let { Browser.load(it, url) } ?: Browser.newTab(url) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.bg).border(1.dp, C.line, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Favicon(url, size = 28.dp, corner = 7.dp) }
                        Spacer(Modifier.height(7.dp))
                        Text(name, color = C.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private sealed class Journey { data class T(val tab: app.cairn.browser.browser.Tab) : Journey(); data class Ch(val c: app.cairn.browser.agent.ChatMeta) : Journey(); data class H(val url: String, val title: String) : Journey() }

/** "Continue your journey": open tabs and pinned / recent AI research threads, then recent pages. */
@Composable
fun JourneyRow(v: Int) {
    val tabs = Browser.tabs.filter { !it.isNtp && !it.incognito && !BrowserDb.isJunk(it.url, it.title) }.takeLast(5).reversed()
    val chats = Chats.list.sortedWith(compareByDescending<app.cairn.browser.agent.ChatMeta> { it.pinned }.thenByDescending { it.updated }).take(4)
    val hist = remember(v) { runCatching { BrowserDb.history(limit = 24).filter { !BrowserDb.isJunk(it.url, it.title) }.distinctBy { BrowserDb.siteKey(it.url) }.take(4) }.getOrDefault(emptyList()) }
    val cards = buildList<Journey> {
        tabs.forEach { add(Journey.T(it)) }
        chats.forEach { add(Journey.Ch(it)) }
        if (size < 4) hist.filter { h -> tabs.none { it.url == h.url } }.forEach { add(Journey.H(it.url, it.title)) }
    }
    if (cards.isEmpty()) return
    Spacer(Modifier.height(26.dp))
    SectionTitle("Continue your journey")
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(cards.take(10)) { j ->
            val shape = RoundedCornerShape(18.dp)
            val (icon, kind, title, sub, url) = when (j) {
                is Journey.T -> listOf(Ic.Tab, "Open tab", j.tab.title.ifBlank { j.tab.host }, hostOf(j.tab.url).removePrefix("www."), j.tab.url)
                is Journey.Ch -> listOf(if (j.c.pinned) Ic.Star else Ic.AutoAwesome, if (j.c.pinned) "Pinned research" else "AI thread", j.c.title, j.c.preview, "")
                is Journey.H -> listOf(Ic.History, "Recent", j.title.ifBlank { hostOf(j.url) }, hostOf(j.url).removePrefix("www."), j.url)
            }
            Column(Modifier.width(196.dp).height(124.dp).clip(shape).background(if (j is Journey.Ch) C.agentSoft else C.surface).border(1.dp, C.line, shape)
                .clickable {
                    Haptics.tick()
                    when (j) {
                        is Journey.T -> Browser.select(j.tab)
                        is Journey.Ch -> if (Agent.openChat(j.c.id)) Ui.assistant = true
                        is Journey.H -> Browser.current?.let { Browser.load(it, j.url) }
                    }
                }.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((url as String).isNotEmpty()) Favicon(url, size = 18.dp, corner = 5.dp) else Icon(icon as ImageVector, null, Modifier.size(16.dp), tint = C.agent)
                    Spacer(Modifier.width(8.dp))
                    Text(kind as String, color = if (j is Journey.Ch) C.agent else C.faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .3.sp, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                Text(title as String, color = C.text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(sub as String, color = C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun SectionTitle(t: String) {
    Text(t, color = C.text, fontSize = 16.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    Spacer(Modifier.height(10.dp))
}

/** Bento grid of agent abilities: one tall hero tile and smaller tiles, each starts a real task. */
@Composable
fun BentoGrid() {
    val shield = if (Prefs.shield && Prefs.shieldTotal > 0) "%,d blocked".format(Prefs.shieldTotal) else "Ads & trackers"
    val watching = app.cairn.browser.service.PriceWatch.tracks.size
    Spacer(Modifier.height(26.dp))
    SectionTitle("Let Cairn handle it")
    Row(Modifier.fillMaxWidth().height(250.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Hero: autonomous shopping
        Column(Modifier.weight(1.1f).fillMaxHeight().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(C.agentSoft, C.surface))).border(1.dp, C.agent.copy(alpha = .25f), RoundedCornerShape(22.dp))
            .clickable { Haptics.tick(); Ui.omniboxText = "Find CMF Phone 2 Pro (Black, 256 GB) from a trusted store, add it to cart and take it to payment"; Ui.omniAgent = true; Ui.omnibox = true }.padding(16.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(C.agent), contentAlignment = Alignment.Center) { Icon(Ic.ShoppingBag, null, Modifier.size(19.dp), tint = C.accentInk) }
            Spacer(Modifier.weight(1f))
            Text("Find → Cart → Pay", color = C.text, fontSize = 18.sp, lineHeight = 23.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.height(4.dp))
            Text("Exact model only, trusted stores, your address and UPI within your limit.", color = C.muted, fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 3)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Ic.Globe, "Research", "Cited answers", Modifier.weight(1f)) { ask("Research the best 5G phones under ₹25,000 right now and compare them in a table with sources") }
            Tile(Ic.BellRing, "Price watch", if (watching > 0) "$watching watching" else "Alert on drops", Modifier.weight(1f)) { if (watching > 0) Ui.go("settings/prices") else ask("Track the price of Sony WH-1000XM5 and alert me when it drops") }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(Ic.Flight, "Plan a trip", "Flights, stays, cabs", Modifier.weight(1f)) { ask("Plan a weekend in Jaipur: best flight, hotel and cab with prices") }
        Tile(Ic.Shield, "Shield", shield, Modifier.weight(1f)) { Ui.go("settings/privacy") }
        Tile(Ic.Headphones, "Listen", "Articles aloud", Modifier.weight(1f)) { ask("Read the Wikipedia article on the Taj Mahal aloud") }
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(C.surface).border(1.dp, C.line, shape).clickable { Haptics.tick(); onClick() }.padding(14.dp)) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(C.agentSoft), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(16.dp), tint = C.agent) }
        Spacer(Modifier.weight(1f))
        Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = C.faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
