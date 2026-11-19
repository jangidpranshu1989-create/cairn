package app.cairn.browser.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.cairn.browser.agent.*
import app.cairn.browser.browser.Browser
import app.cairn.browser.data.Prefs
import app.cairn.browser.data.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// ---------------------------------------------------------------- Image loader (product photos, page images)
object Images {
    private val cache = LruCache<String, Bitmap>(80)
    private val http = OkHttpClient.Builder().followRedirects(true).build()
    /** Loads the first URL that decodes. Sends a Referer (many CDNs refuse hot-links without one) and asks for formats Android can decode. */
    suspend fun load(url: String, referer: String? = null): Bitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            val u = if (url.startsWith("//")) "https:$url" else url
            val rq = Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
                .header("Accept", if (android.os.Build.VERSION.SDK_INT >= 31) "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.5" else "image/webp,image/png,image/jpeg,*/*;q=0.5")
            referer?.let { r -> runCatching { java.net.URI(r).let { "${it.scheme}://${it.host}/" } }.getOrNull()?.let { rq.header("Referer", it) } }
            http.newCall(rq.build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val b = r.body?.bytes() ?: return@use null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
                if (o.outWidth <= 1) return@use null
                var s = 1; while (o.outWidth / (s * 2) >= 360) s *= 2
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })?.takeIf { it.width > 8 && it.height > 8 }
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }
    suspend fun first(urls: List<String>, referer: String?): Bitmap? {
        for (u in urls.filter { it.startsWith("http") || it.startsWith("//") }.distinct().take(5)) load(u, referer)?.let { return it }
        return null
    }
}

@Composable
private fun NetImage(urls: List<String>, modifier: Modifier, referer: String? = null) {
    var tried by remember(urls) { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, urls) { value = Images.first(urls, referer); tried = true }
    val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(220), label = "img")
    // Product photos are shot on white, so loaded images sit on white; the placeholder stays in the theme.
    Box(modifier.background(if (bmp != null) Color.White else C.surface2), contentAlignment = Alignment.Center) {
        if (bmp == null && !tried) Box(Modifier.fillMaxSize().shimmer(0.dp))
        if (bmp == null && tried) Icon(Ic.Package, null, tint = C.faint, modifier = Modifier.size(22.dp))
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp).graphicsLayer { alpha = a }, contentScale = ContentScale.Fit) }
    }
}

// ---------------------------------------------------------------- Sheet
/**
 * The Cairn panel. A real bottom sheet with two resting heights (half: the page stays visible above; full: a focused
 * conversation), driven by one spring so open, close, drag and fling all feel continuous.
 */
@Composable
fun AssistantSheet() {
    LaunchedEffect(Agent.openRequests) { if (Agent.openRequests > 0) Ui.assistant = true }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        val top = WindowInsets.statusBars.getTop(density) + with(density) { 8.dp.toPx() }
        val half = (h * .42f).coerceAtLeast(top)
        val y = remember { Animatable(if (Ui.assistant) (if (Agent.items.isEmpty()) half else top) else h) }
        var visible by remember { mutableStateOf(Ui.assistant) }
        val scope = rememberCoroutineScope()
        val ime = WindowInsets.ime.getBottom(density) > 0
        val spring = spring<Float>(dampingRatio = .84f, stiffness = 420f)
        LaunchedEffect(Ui.assistant) {
            if (Ui.assistant) { visible = true; Haptics.tick(); y.animateTo(if (Agent.items.isEmpty() && !ime) half else top, spring) }
            else { y.animateTo(h, spring(1f, 600f)); visible = false }
        }
        LaunchedEffect(ime, Agent.items.size) { if (Ui.assistant && (ime || Agent.items.size > 2) && y.value > top + 1) y.animateTo(top, spring) }
        if (!visible) return@BoxWithConstraints
        BackHandler { Ui.assistant = false }
        val frac = ((h - y.value) / (h - half)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().background(C.scrim.copy(alpha = C.scrim.alpha * frac * .8f)).pointerInput(Unit) { detectTapGestures { Ui.assistant = false } })
        val drag = rememberDraggableState { d -> scope.launch { y.snapTo((y.value + d).coerceIn(top, h)) } }
        fun settle(v: Float) {
            val target = when {
                v > 1800f || y.value > half + (h - half) * .35f -> h
                v < -1800f || y.value < (top + half) / 2 -> top
                else -> half
            }
            if (target == h) Ui.assistant = false else { Haptics.gesture(); scope.launch { y.animateTo(target, spring) } }
        }
        val full = y.value <= top + 2
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { (h - y.value).coerceAtLeast(0f).toDp() })
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(C.bg)
            .border(1.dp, Brush.verticalGradient(listOf(C.line2, Color.Transparent), 0f, 120f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .imePadding().navigationBarsPadding()) {
            // Handle + header: drag to resize, fling down to close
            Column(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { v -> settle(v) })) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 9.dp).size(36.dp, 4.dp).clip(CircleShape).background(C.line2))
                Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(18.dp, lift = if (Agent.running) 1f else 0f); Spacer(Modifier.width(9.dp))
                    Text("Cairn", color = C.text, fontSize = 16.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold)
                    val t = Browser.current
                    if (t != null && !t.isNtp) {
                        Spacer(Modifier.width(10.dp))
                        Row(Modifier.weight(1f, false).clip(RoundedCornerShape(10.dp)).background(C.surface).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Favicon(t.url, t.icon, 12.dp, 3.dp); Spacer(Modifier.width(6.dp))
                            Text(t.host.removePrefix("www."), color = C.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconBtn(Ic.EditNote, "New chat", tint = C.muted, size = 40.dp, enabled = Agent.items.isNotEmpty() && !Agent.running) { Haptics.tick(); Agent.newChat(); Ui.mentions.clear() }
                    IconBtn(if (full) Ic.Minimize else Ic.Maximize, if (full) "Half height" else "Full height", tint = C.muted, size = 40.dp) { scope.launch { y.animateTo(if (full) half else top, spring) } }
                    IconBtn(Ic.Close, "Close", tint = C.muted, size = 40.dp) { Ui.assistant = false }
                }
            }
            val list = rememberLazyListState()
            LaunchedEffect(Agent.items.size) { if (Agent.items.isNotEmpty()) list.animateScrollToItem(Agent.items.size - 1) }
            if (Agent.items.isEmpty()) EmptyAssistant(Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(Agent.items, key = { it.key }) { Box(Modifier.animateItem(fadeInSpec = tween(220), placementSpec = spring(stiffness = 500f))) { ItemView(it) } }
                if (Agent.running) item(key = "working") { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    AgentDots(); Spacer(Modifier.width(10.dp))
                    Text(Agent.status.ifBlank { "Thinking…" }, color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }
            }
            Composer()
        }
    }
}

@Composable
private fun EmptyAssistant(modifier: Modifier) {
    val t = Browser.current
    val onPage = t != null && !t.isNtp
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.Bottom) {
        Spacer(Modifier.height(8.dp))
        Text(if (onPage) "How can I help with this page?" else "What can I do for you?", color = C.text, fontSize = 24.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text("Ask in any language. Cairn browses, compares, reads aloud and acts for you — and always stops before you pay.", color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(18.dp))
        val s = if (onPage) listOf(Ic.Summarize to "Summarize this page", Ic.Headphones to "Read this page aloud", Ic.AccountTree to "Make a mind map of this page", Ic.BadgePercent to "Find coupons and bank offers here", Ic.Lasso to "Circle something on the page")
        else listOf(Ic.ShoppingBag to "Find the cheapest iPhone 16 from a trusted store", Ic.Swarm to "Plan a weekend in Jaipur: best flight, hotel and cab",
            Ic.Headphones to "Read the Wikipedia article on black holes aloud", Ic.Brain to "What was that article I read about solar panels?")
        s.forEach { (ic, q) ->
            Row(Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).clickable {
                Haptics.tick()
                when (q) { "Summarize this page" -> Agent.quick("summarize"); "Read this page aloud" -> Agent.quick("read"); "Make a mind map of this page" -> Agent.quick("mindmap"); "Circle something on the page" -> startLasso(); else -> Agent.run(q) }
            }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, Modifier.size(17.dp), tint = C.agent); Spacer(Modifier.width(14.dp)); Text(q, color = C.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Ic.NorthEast, null, Modifier.size(13.dp), tint = C.faint)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private val MENTION = Regex("(?:^|\\s)@([\\p{L}\\p{N}._-]*)$")

@Composable
private fun Composer() {
    val ask = Agent.pendingAsk
    var tf by remember { mutableStateOf(TextFieldValue(Ui.assistantDraft)) }
    LaunchedEffect(Ui.assistantDraft) { if (Ui.assistantDraft.isNotEmpty()) { tf = TextFieldValue(Ui.assistantDraft, TextRange(Ui.assistantDraft.length)); Ui.assistantDraft = "" } }
    val text = tf.text
    val m = MENTION.find(text.substring(0, tf.selection.end.coerceIn(0, text.length)))
    val cur = Browser.current
    val candidates = if (m == null) emptyList() else Browser.tabs.filter { !it.isNtp && it.incognito == (cur?.incognito == true) && it !in Ui.mentions }
        .filter { val q = m.groupValues[1]; q.isEmpty() || it.title.contains(q, true) || it.host.contains(q, true) }.take(6)
    fun send() {
        val t = text.trim(); if (t.isEmpty()) return
        Haptics.confirm()
        tf = TextFieldValue("")
        if (ask != null) Agent.answer(t) else { Agent.run(t, Ui.mentions.toList()); Ui.mentions.clear() }
    }
    fun pick(tab: app.cairn.browser.browser.Tab) {
        Haptics.tick()
        val mm = m ?: return
        val start = mm.range.first + (if (mm.value.startsWith("@")) 0 else 1)
        val nt = text.substring(0, start) + text.substring(tf.selection.end.coerceAtMost(text.length))
        tf = TextFieldValue(nt, TextRange(start)); Ui.mentions.add(tab)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp, top = 4.dp)) {
        // @tab picker
        AnimatedVisibility(candidates.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(16.dp)).padding(vertical = 6.dp)) {
                Text("Add a tab as context", color = C.faint, fontSize = 11.5.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                candidates.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { pick(t) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Favicon(t.url, t.icon, 18.dp, 4.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title.ifBlank { t.host }, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.host.removePrefix("www."), color = C.faint, fontSize = 11.5.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (Ui.mentions.isNotEmpty()) Row(Modifier.padding(bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ui.mentions.toList().forEach { t ->
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.agentSoft).border(1.dp, C.agent.copy(alpha = .35f), RoundedCornerShape(10.dp)).padding(start = 8.dp, end = 2.dp).height(30.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Favicon(t.url, t.icon, 13.dp, 3.dp); Spacer(Modifier.width(6.dp))
                    Text(t.title.ifBlank { t.host }.take(28), color = C.text, fontSize = 12.sp, maxLines = 1)
                    Box(Modifier.size(26.dp).clip(CircleShape).clickable { Ui.mentions.remove(t) }, contentAlignment = Alignment.Center) { Icon(Ic.Close, "Remove", Modifier.size(12.dp), tint = C.muted) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(C.surface)
            .border(1.dp, if (ask != null) C.agent.copy(alpha = .6f) else C.line2, RoundedCornerShape(26.dp)).padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.At, "Mention a tab", tint = C.muted, size = 40.dp) {
                val s = tf.text; val add = if (s.isEmpty() || s.endsWith(" ")) "@" else " @"; tf = TextFieldValue(s + add, TextRange(s.length + add.length))
            }
            BasicTextField(tf, { tf = it }, Modifier.weight(1f).padding(vertical = 13.dp), maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text, fontSize = 15.5.sp, lineHeight = 21.sp), cursorBrush = SolidColor(C.agent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner -> if (text.isEmpty()) Text(if (ask != null) "Type your answer…" else if (Agent.running) "Cairn is working…" else "Ask anything · @ to add a tab", color = C.faint, fontSize = 15.5.sp, maxLines = 1); inner() })
            Crossfade(when { Agent.running && ask == null && text.isEmpty() -> 0; text.isNotBlank() -> 1; else -> 2 }, label = "act") { k ->
                when (k) {
                    0 -> RoundAction(Ic.Stop, "Stop", C.surface3, C.text) { Haptics.reject(); Agent.stop() }
                    1 -> RoundAction(Ic.ArrowUpward, "Send", C.accent, C.accentInk) { send() }
                    else -> IconBtn(Ic.Mic, "Speak", tint = C.muted, size = 40.dp) { act.listen { t -> if (Agent.pendingAsk != null) Agent.answer(t) else { Agent.run(t, Ui.mentions.toList()); Ui.mentions.clear() } } }
                }
            }
        }
        Text("Cairn can make mistakes. Payments always need your approval.", color = C.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 7.dp))
    }
}

@Composable
private fun RoundAction(icon: ImageVector, desc: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, desc, Modifier.size(19.dp), tint = fg) }
}

// ---------------------------------------------------------------- Items
@Composable
private fun ItemView(it: Item) {
    when (it) {
        is UserMsg -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            it.page?.let { p -> Text(p, color = C.faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp).widthIn(max = 260.dp)) }
            if (it.mentions.isNotEmpty()) Row(Modifier.padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                it.mentions.take(3).forEach { m -> Text("@" + m.take(22), color = C.agent, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.agentSoft).padding(horizontal = 7.dp, vertical = 3.dp)) }
            }
            Box(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(C.surface2).padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(it.text, color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        is AiMsg -> Column {
            Markdown(it.markdown, color = if (it.error) C.muted else C.text)
            if (it.setup) Row(Modifier.padding(top = 10.dp)) {
                if (!Prefs.isSignedIn) { PrimaryButton("Sign in") { Ui.assistant = false; Ui.go("settings/account") }; Spacer(Modifier.width(8.dp)) }
                SecondaryButton("AI settings") { Ui.assistant = false; Ui.go("settings/ai") }
            }
        }
        is StepsItem -> Steps(it)
        is ProductsItem -> Products(it)
        is SummaryItem -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Summarize, null, Modifier.size(16.dp), tint = C.muted); Spacer(Modifier.width(8.dp))
                Text(it.title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(hostOf(it.url), color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp, bottom = 10.dp))
            Markdown(it.markdown)
            val ctx = LocalContext.current
            Row(Modifier.padding(top = 8.dp)) {
                SmallAct(Ic.Headphones, "Listen") { app.cairn.browser.service.ReadAloud.speak(it.title, it.markdown.replace(Regex("[*#_`>]"), "")) }
                SmallAct(Ic.ContentCopy, "Copy") { copyText(ctx, "Summary", it.markdown) }
                SmallAct(Ic.Share, "Share") { shareText(ctx, "${it.title}\n\n${it.markdown}\n\n${it.url}") }
            }
        }
        is MindmapItem -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.AccountTree, null, Modifier.size(16.dp), tint = C.muted); Spacer(Modifier.width(8.dp))
                Text("Mind map", color = C.muted, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            MindNode(it.tree, 0)
        }
        is FileItem -> FileCard(it)
        is SourcesItem -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            it.sources.forEach { (title, url, _) ->
                Row(Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(C.surface).clickable { Ui.assistant = false; Browser.newTab(url, Browser.current?.incognito == true) }
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Favicon(url, size = 14.dp, corner = 3.dp); Spacer(Modifier.width(8.dp))
                    Column { Text(title, color = C.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(hostOf(url), color = C.faint, fontSize = 11.sp, maxLines = 1) }
                }
            }
        }
        is ApprovalItem -> Approval(it)
        is LassoItem -> LassoView(it)
        is OffersItem -> OffersView(it)
        is NoticeItem -> NoticeView(it)
        is SwarmItem -> SwarmView(it)
        is AskItem -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.agentSoft).padding(14.dp)) {
            Icon(Ic.HelpOutline, null, Modifier.size(18.dp), tint = C.agent); Spacer(Modifier.width(10.dp))
            Column { Text(it.question, color = C.text, fontSize = 14.5.sp, lineHeight = 20.sp); it.answer?.let { a -> Text("You answered: $a", color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp)) } }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(14.dp), content = content)

@Composable
private fun SmallAct(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = C.muted); Spacer(Modifier.width(6.dp)); Text(label, color = C.muted, fontSize = 12.5.sp)
    }
}

@Composable
private fun Steps(s: StepsItem) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.done) Icon(Ic.CheckCircle, null, Modifier.size(15.dp), tint = C.muted) else AgentDots()
            Spacer(Modifier.width(10.dp))
            Text(if (s.done) "Completed ${s.steps.size} step${if (s.steps.size == 1) "" else "s"}" else s.steps.lastOrNull()?.text ?: "Working…", color = C.muted, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (open) Ic.ExpandLess else Ic.ExpandMore, null, Modifier.size(18.dp), tint = C.faint)
        }
        if (open) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            s.steps.forEach { st ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Icon(stepIcon(st.icon), null, Modifier.size(14.dp).padding(top = 2.dp), tint = C.faint); Spacer(Modifier.width(10.dp))
                    Text(st.text, color = C.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

private fun stepIcon(k: String): ImageVector = when (k) {
    "search" -> Ic.Search; "nav" -> Ic.Language; "read" -> Ic.Article; "click" -> Ic.TouchApp
    "type" -> Ic.Keyboard; "scroll" -> Ic.SwapVert; "shop", "store" -> Ic.ShoppingBag; "down" -> Ic.Download
    "pdf" -> Ic.PictureAsPdf; "shot" -> Ic.Screenshot; "speak" -> Ic.Headphones; "lock" -> Ic.Lock
    "map" -> Ic.AccountTree; "sum" -> Ic.Summarize; "phone" -> Ic.PhoneAndroid; "ask" -> Ic.HelpOutline
    "warn" -> Ic.WarningAmber; else -> Ic.ChevronRight
}

@Composable
private fun Products(p: ProductsItem) {
    Column(Modifier.fillMaxWidth()) {
        p.headline?.let { Text(it, color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)) }
        if (p.progress.isNotEmpty() && !p.done) StoreChips(p)
        if (p.stores.isNotEmpty() && p.done) Text("Compared ${p.stores.sumOf { it.count }} listings across ${p.stores.count { it.count > 0 }} of ${p.stores.size} stores", color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))) {
            p.items.forEachIndexed { i, x ->
                if (i > 0) HorizontalDivider(color = C.line)
                ProductRow(x)
            }
            if (!p.done) {
                val pending = p.progress.count { it.state == "queued" || it.state == "loading" }.coerceAtLeast(if (p.items.isEmpty()) 2 else 0).coerceAtMost(3)
                repeat(pending) { i -> if (i > 0 || p.items.isNotEmpty()) HorizontalDivider(color = C.line); ProductSkeleton() }
            }
        }
    }
}

@Composable
private fun ProductRow(x: Product) {
    Column(Modifier.fillMaxWidth().background(if (x.best) C.surface2 else C.surface).clickable { Ui.assistant = false; Browser.newTab(x.url, Browser.current?.incognito == true) }.padding(12.dp)) {
        Row {
            NetImage(listOf(x.image) + x.images, Modifier.size(68.dp).clip(RoundedCornerShape(10.dp)), referer = x.url)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (x.best) Text("BEST PICK", color = C.agent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(bottom = 3.dp))
                Text(x.title, color = C.text, fontSize = 13.5.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Shopping.money(x.price), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    x.mrp?.takeIf { it > x.price }?.let { Spacer(Modifier.width(6.dp)); Text(Shopping.money(it), color = C.faint, fontSize = 12.sp, textDecoration = TextDecoration.LineThrough) }
                    x.discount?.takeIf { it > 0 }?.let { Spacer(Modifier.width(6.dp)); Text("$it% off", color = C.ok, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(x.store, color = C.muted, fontSize = 12.sp)
                    x.rating?.let { Text("  ·  ★ ${"%.1f".format(it)}${x.reviews?.let { r -> " (${"%,d".format(r)})" } ?: ""}", color = C.muted, fontSize = 12.sp) }
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val (label, col) = when { x.trust >= 80 -> "Trusted" to C.ok; x.trust >= 60 -> "Okay" to C.muted; else -> "Be careful" to C.warn }
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = .14f)).padding(horizontal = 8.dp, vertical = 3.dp)) { Text("$label · ${x.trust}", color = col, fontSize = 11.5.sp, fontWeight = FontWeight.Medium) }
            x.flags.firstOrNull()?.let { Spacer(Modifier.width(8.dp)); Text(it, color = C.faint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false)) }
            Spacer(Modifier.weight(1f))
            val tracked = app.cairn.browser.service.PriceWatch.tracks.any { it.url == x.url }
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).clickable {
                Haptics.tick()
                val pw = app.cairn.browser.service.PriceWatch
                if (tracked) pw.remove(x.url) else { pw.add(app.cairn.browser.service.Track(x.url, x.title, x.price, "INR", true, x.image, System.currentTimeMillis(), System.currentTimeMillis(), x.price)); act.toast("Tracking the price at ${x.store}") }
            }, contentAlignment = Alignment.Center) { Icon(if (tracked) Ic.BellRing else Ic.Notifications, if (tracked) "Stop tracking" else "Track price", Modifier.size(16.dp), tint = if (tracked) C.agent else C.muted) }
            Spacer(Modifier.width(6.dp))
            Text("Add to cart", color = C.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, C.line2, RoundedCornerShape(10.dp))
                .clickable { Agent.run("Add \"${x.title.take(90)}\" from ${x.store} to my cart: ${x.url}") }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun MindNode(n: JSONObject, depth: Int) {
    var open by remember { mutableStateOf(depth < 2) }
    val kids = n.optJSONArray("children")
    val has = kids != null && kids.length() > 0
    Column(Modifier.padding(start = if (depth == 0) 0.dp else 14.dp)) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = has) { open = !open }.padding(vertical = 5.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (depth > 0) Box(Modifier.size(if (depth == 1) 7.dp else 5.dp).clip(CircleShape).background(if (depth == 1) C.agent else C.faint))
            if (depth > 0) Spacer(Modifier.width(9.dp))
            Text(n.optString("title"), color = if (depth == 0) C.text else if (depth == 1) C.text else C.muted, fontSize = if (depth == 0) 16.sp else if (depth == 1) 14.sp else 13.sp,
                fontWeight = if (depth < 2) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f, false))
            if (has && depth > 0) Icon(if (open) Ic.ExpandLess else Ic.ExpandMore, null, Modifier.size(16.dp), tint = C.faint)
        }
        if (open && has) Column(Modifier.padding(start = if (depth == 0) 0.dp else 3.dp).border(width = 0.dp, color = Color.Transparent)) {
            for (i in 0 until kids!!.length()) kids.optJSONObject(i)?.let { MindNode(it, depth + 1) }
        }
    }
}

@Composable
private fun FileCard(f: FileItem) {
    val ctx = LocalContext.current
    fun intent(action: String) = Intent(action).apply {
        if (action == Intent.ACTION_SEND) { type = f.saved.mime; putExtra(Intent.EXTRA_STREAM, f.saved.uri) } else setDataAndType(f.saved.uri, f.saved.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp))
        .clickable { runCatching { ctx.startActivity(intent(Intent.ACTION_VIEW)) }.onFailure { act.toast("No app can open this file") } }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(C.surface3), contentAlignment = Alignment.Center) {
            Icon(if (f.kind == "image") Ic.Image else Ic.PictureAsPdf, null, Modifier.size(20.dp), tint = C.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.saved.name, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${f.saved.path} · ${android.text.format.Formatter.formatShortFileSize(ctx, f.saved.size)}", color = C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(Ic.Share, "Share file", tint = C.muted) { runCatching { ctx.startActivity(Intent.createChooser(intent(Intent.ACTION_SEND), null)) } }
    }
}

@Composable
private fun Approval(a: ApprovalItem) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, if (a.state == "pending") C.agent.copy(alpha = .5f) else C.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Lock, null, Modifier.size(16.dp), tint = C.agent); Spacer(Modifier.width(8.dp))
            Text(if (a.kind == "payment") "Approval needed to pay" else "Approval needed", color = C.agent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        if (a.amount.isNotBlank()) Text(a.amount, color = C.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
        Text(a.title.ifBlank { a.itemName }, color = C.text, fontSize = 14.sp, lineHeight = 20.sp)
        Text(listOf(a.merchant, a.site).filter { it.isNotBlank() }.distinct().joinToString(" · "), color = C.faint, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(14.dp))
        when (a.state) {
            "pending" -> Row {
                PrimaryButton("Approve", Modifier.weight(1f), icon = Ic.Fingerprint) { act.approveLocally(a, true) }
                Spacer(Modifier.width(10.dp))
                SecondaryButton("Deny", Modifier.weight(1f)) { act.approveLocally(a, false) }
            }
            "approved" -> Text("Approved with your device lock", color = C.ok, fontSize = 13.sp)
            else -> Text("Denied. Nothing was paid.", color = C.muted, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------- New agent cards
@Composable
private fun LassoView(it: LassoItem) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        val ratio = (it.image.width.toFloat() / it.image.height.coerceAtLeast(1)).coerceIn(.5f, 3f)
        Image(it.image.asImageBitmap(), "Your selection", Modifier.widthIn(max = 240.dp).heightIn(max = 180.dp).aspectRatio(ratio).clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, C.agent.copy(alpha = .7f), RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(C.surface2).padding(horizontal = 15.dp, vertical = 10.dp)) {
            Text(it.text, color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
private fun OffersView(o: OffersItem) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.BadgePercent, null, Modifier.size(16.dp), tint = C.ok); Spacer(Modifier.width(8.dp))
            Text("Offers on this page", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (o.cards.isNotEmpty()) Text("Matched to your cards", color = C.faint, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        o.offers.forEach { t ->
            val mine = o.cards.any { c -> t.contains(c, true) }
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Icon(if (mine) Ic.CreditCard else Ic.Tag, null, Modifier.padding(top = 2.dp).size(14.dp), tint = if (mine) C.ok else C.faint); Spacer(Modifier.width(10.dp))
                Text(t, color = if (mine) C.text else C.muted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (mine) FontWeight.Medium else FontWeight.Normal)
            }
        }
        if (o.codes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                o.codes.forEach { c ->
                    val done = o.applied == c
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, if (done) C.ok else C.line2, RoundedCornerShape(10.dp))
                        .clickable(enabled = !Agent.running && !done) { Haptics.tick(); Agent.run("Apply the coupon code $c on this page") }.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(c, color = C.text, fontSize = 12.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(if (done) "Applied" else "Apply", color = if (done) C.ok else C.agent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeView(n: NoticeItem) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.agentSoft).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(when (n.icon) { "bell" -> Ic.BellRing; "shield", "guard" -> Ic.GppGood; "profile" -> Ic.UserRound; "brain", "recall" -> Ic.Brain; "translate" -> Ic.Translate; "offer" -> Ic.BadgePercent; else -> Ic.Info },
            null, Modifier.padding(top = 2.dp).size(16.dp), tint = C.agent)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) { Markdown(n.text) }
    }
}

@Composable
private fun SwarmView(s: SwarmItem) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Swarm, null, Modifier.size(16.dp), tint = C.agent); Spacer(Modifier.width(8.dp))
            Text(if (s.done) "${s.agents.size} agents finished" else "${s.agents.count { it.state == "running" }} of ${s.agents.size} agents working in parallel",
                color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Text(s.goal, color = C.faint, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp, top = 2.dp, bottom = 8.dp))
        s.agents.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 2.dp))
            SubAgentRow(a)
        }
    }
}

@Composable
private fun SubAgentRow(a: SubAgent) {
    var open by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(a.state, a.web) {
        while (a.state == "running") {
            a.web?.takeIf { it.width > 0 }?.let { w ->
                frame = runCatching {
                    val sc = 120f / w.width
                    Bitmap.createBitmap(120, 84, Bitmap.Config.ARGB_8888).also { b -> android.graphics.Canvas(b).apply { scale(sc, sc); translate(-w.scrollX.toFloat(), -w.scrollY.toFloat()); w.draw(this) } }.asImageBitmap()
                }.getOrNull() ?: frame
            }
            kotlinx.coroutines.delay(700)
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = a.result.isNotBlank()) { open = !open }.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(C.surface2), contentAlignment = Alignment.Center) {
                when {
                    a.state == "running" && frame != null -> Image(frame!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    a.state == "running" -> Box(Modifier.fillMaxSize().shimmer(0.dp))
                    a.state == "done" -> Icon(Ic.Check, null, Modifier.size(16.dp), tint = C.ok)
                    a.state == "failed" -> Icon(Ic.WarningAmber, null, Modifier.size(16.dp), tint = C.warn)
                    else -> Icon(Ic.Schedule, null, Modifier.size(15.dp), tint = C.faint)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.status, color = if (a.state == "running") C.agent else C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (a.result.isNotBlank()) Icon(if (open) Ic.ExpandLess else Ic.ExpandMore, null, Modifier.size(16.dp), tint = C.faint)
        }
        AnimatedVisibility(open) { Box(Modifier.padding(start = 64.dp, top = 6.dp)) { Markdown(a.result) } }
    }
}

@Composable
private fun ProductSkeleton() {
    Row(Modifier.fillMaxWidth().background(C.surface).padding(12.dp)) {
        Box(Modifier.size(68.dp).shimmer(10.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(.9f).height(12.dp).shimmer(4.dp)); Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth(.6f).height(12.dp).shimmer(4.dp)); Spacer(Modifier.height(10.dp))
            Box(Modifier.width(84.dp).height(16.dp).shimmer(4.dp)); Spacer(Modifier.height(7.dp))
            Box(Modifier.width(120.dp).height(10.dp).shimmer(4.dp))
        }
    }
}

@Composable
private fun StoreChips(p: ProductsItem) {
    Row(Modifier.padding(bottom = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        p.progress.forEach { s ->
            val col = when (s.state) { "done" -> if (s.count > 0) C.ok else C.faint; "failed" -> C.warn; "loading" -> C.agent; else -> C.faint }
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.surface).border(1.dp, if (s.state == "loading") C.agent.copy(alpha = .45f) else C.line, RoundedCornerShape(10.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (s.state) {
                    "loading" -> CircularProgressIndicator(Modifier.size(10.dp), color = C.agent, strokeWidth = 1.4.dp)
                    "done" -> Icon(if (s.count > 0) Ic.Check else Ic.Minus, null, Modifier.size(11.dp), tint = col)
                    "failed" -> Icon(Ic.Close, null, Modifier.size(11.dp), tint = col)
                    else -> Box(Modifier.size(6.dp).clip(CircleShape).background(C.faint))
                }
                Spacer(Modifier.width(6.dp))
                Text(s.name + if (s.state == "done" && s.count > 0) " · ${s.count}" else "", color = if (s.state == "queued") C.faint else C.text, fontSize = 11.5.sp)
            }
        }
    }
}
