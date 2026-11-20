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

/** Home top bar: wordmark on the left; weather and your profile on the right. */
@Composable
fun HomeHeader() {
    val now = rememberNow()
    LaunchedEffect(Unit) { Weather.refresh() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Logo(22.dp); Spacer(Modifier.width(8.dp))
        Text("cairn", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
        Spacer(Modifier.weight(1f))
        Weather.now?.let { Pill(Ic.Sun, it) }
        Spacer(Modifier.width(4.dp))
        ProfileAvatar(34.dp) { Ui.profile = true }
    }
    Spacer(Modifier.height(30.dp))
    AnimatedContent(greetingFor(now), label = "greet", transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }) { g ->
        Text(g, color = C.text, fontSize = 30.sp, lineHeight = 36.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(4.dp))
    Text(java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(now.time) + " · What can Cairn do for you?", color = C.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Pill(icon: ImageVector, text: String) = Row(Modifier.clip(CircleShape).background(C.surface).border(1.dp, C.line, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(12.dp), tint = C.muted); Spacer(Modifier.width(5.dp))
    Text(text, color = C.muted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/** The composer: one big box to search, open a site or hand a task to the agent, with a Search / Agent switch. */
@Composable
fun OmniBar(motion: Boolean, quick: List<Triple<ImageVector, String, String>>) {
    val glow = rememberGlowPhase(motion)
    val agent = HomeMode.agent
    val border by animateFloatAsState(if (agent) 1f else .55f, tween(300), label = "b")
    val shape = RoundedCornerShape(26.dp)
    fun open() { Ui.omniboxText = null; Ui.omniAgent = agent; Ui.omnibox = true }
    Column(Modifier.fillMaxWidth().glowBorder(motion && agent, glow, 26.dp, 1.3.dp).clip(shape)
        .background(Brush.verticalGradient(listOf(C.surface, C.surface2.copy(alpha = .9f))))
        .border(1.dp, Brush.verticalGradient(listOf(C.line2.copy(alpha = border), C.line)), shape)
        .clickable { open() }.padding(start = 18.dp, end = 10.dp, top = 18.dp, bottom = 10.dp)) {
        Text(if (agent) "Tell Cairn what to do…" else "Search or type a URL", color = C.faint, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Search / Agent switch
            Row(Modifier.clip(CircleShape).background(C.surface3.copy(alpha = .8f)).padding(3.dp)) {
                listOf(false to "Search", true to "Agent").forEach { (isAgent, label) ->
                    val sel = agent == isAgent
                    Row(Modifier.clip(CircleShape).background(if (sel) (if (isAgent) C.agent else C.text) else C.surface3.copy(alpha = 0f))
                        .clickable { Haptics.tick(); HomeMode.agent = isAgent; Prefs.homeAgent = isAgent }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isAgent) Ic.Sparkle else Ic.Search, null, Modifier.size(13.dp), tint = if (sel) (if (isAgent) C.accentInk else C.bg) else C.muted)
                        Spacer(Modifier.width(5.dp))
                        Text(label, color = if (sel) (if (isAgent) C.accentInk else C.bg) else C.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconBtn(Ic.Mic, "Voice", tint = C.muted, size = 40.dp) { act.listen { if (agent || Ui.looksLikeTask(it)) ask(it) else Browser.open(it, Browser.current ?: Browser.newTab()) } }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (agent) C.agent else C.text).clickable { Haptics.tick(); open() }, contentAlignment = Alignment.Center) {
                Icon(Ic.Send, if (agent) "Ask Cairn" else "Search", Modifier.size(17.dp), tint = if (agent) C.accentInk else C.bg)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    DeliveryChip()
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        quick.forEach { (ic, label, prompt) ->
            Row(Modifier.clip(CircleShape).background(C.surface).border(1.dp, C.line, CircleShape).clickable { Haptics.tick(); ask(prompt) }.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, Modifier.size(14.dp), tint = C.agent); Spacer(Modifier.width(7.dp))
                Text(label, color = C.text, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** "Delivering to Kota 324005" — or a nudge to add an address so the agent can check out for you. */
@Composable
private fun DeliveryChip() {
    val prof = remember(Prefs.profileJson) { runCatching { org.json.JSONObject(Prefs.profileJson.ifBlank { "{}" }) }.getOrDefault(org.json.JSONObject()) }
    val city = prof.optString("city"); val pin = prof.optString("pin")
    val has = pin.isNotBlank() || city.isNotBlank()
    Row(Modifier.clip(CircleShape).clickable { Haptics.tick(); Ui.go("settings/profile") }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.MapPin, null, Modifier.size(14.dp), tint = if (has) C.agent else C.warn); Spacer(Modifier.width(6.dp))
        Text(if (has) "Delivering to ${listOf(city, pin).filter { it.isNotBlank() }.joinToString(" ")}" else "Add your delivery address so Cairn can check out for you",
            color = C.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Text(if (has) "Change" else "Add", color = C.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Pinned shortcuts: top sites (one per site, never blank or error pages) topped up with defaults. */
@Composable
fun ShortcutGrid(sites: List<Pair<String, String>>) {
    Spacer(Modifier.height(26.dp))
    SectionTitle("Shortcuts")
    Column(Modifier.fillMaxWidth()) {
        sites.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (name, url) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { Haptics.tick(); Browser.current?.let { Browser.load(it, url) } ?: Browser.newTab(url) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Favicon(url, size = 28.dp, corner = 7.dp) }
                        Spacer(Modifier.height(7.dp))
                        Text(name, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
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
    SectionTitle("Continue")
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

/** "Let Cairn handle it": four real tasks in an even 2 × 2 grid. */
@Composable
fun BentoGrid() {
    val watching = app.cairn.browser.service.PriceWatch.tracks.size
    Spacer(Modifier.height(26.dp))
    SectionTitle("Let Cairn handle it")
    Row(Modifier.fillMaxWidth().height(118.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(Ic.ShoppingBag, "Find → Cart → Pay", "Trusted stores, your address", Modifier.weight(1f), hero = true) {
            Ui.omniboxText = "Find CMF Phone 2 Pro (Black, 256 GB) from a trusted store, add it to cart and take it to payment"; Ui.omniAgent = true; Ui.omnibox = true }
        Tile(Ic.Globe, "Research", "Cited answers in a table", Modifier.weight(1f)) { ask("Research the best 5G phones under ₹25,000 right now and compare them in a table with sources") }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().height(118.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(Ic.BellRing, "Price watch", if (watching > 0) "$watching watching" else "Alert me when it drops", Modifier.weight(1f)) { if (watching > 0) Ui.go("settings/prices") else ask("Track the price of Sony WH-1000XM5 and alert me when it drops") }
        Tile(Ic.Flight, "Plan a trip", "Flights, stays and cabs", Modifier.weight(1f)) { ask("Plan a weekend in Jaipur: best flight, hotel and cab with prices") }
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, sub: String, modifier: Modifier, hero: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxHeight().clip(shape).background(if (hero) Brush.linearGradient(listOf(C.agentSoft, C.surface)) else Brush.linearGradient(listOf(C.surface, C.surface)))
        .border(1.dp, if (hero) C.agent.copy(alpha = .25f) else C.line, shape).clickable { Haptics.tick(); onClick() }.padding(14.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (hero) C.agent else C.agentSoft), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(16.dp), tint = if (hero) C.accentInk else C.agent) }
        Spacer(Modifier.weight(1f))
        Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, color = C.faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
