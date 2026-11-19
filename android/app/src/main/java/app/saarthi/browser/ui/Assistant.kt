package app.saarthi.browser.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
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
import app.saarthi.browser.agent.*
import app.saarthi.browser.browser.Browser
import app.saarthi.browser.data.Prefs
import app.saarthi.browser.data.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// ---------------------------------------------------------------- Tiny image loader for product cards
private object Images {
    private val cache = LruCache<String, Bitmap>(60)
    private val http = OkHttpClient()
    suspend fun load(url: String): Bitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36").build()).execute().use { r ->
                val b = r.body?.bytes() ?: return@use null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var s = 1; while (o.outWidth / (s * 2) >= 240) s *= 2
                BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

@Composable
private fun NetImage(url: String, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, url) { value = if (url.startsWith("http")) Images.load(url) else null }
    // Product photos are shot on white, so loaded images sit on white; the placeholder stays in the theme.
    Box(modifier.background(if (bmp != null) Color.White else C.surface3), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) }
            ?: Icon(Icons.Outlined.Image, null, tint = C.faint, modifier = Modifier.size(22.dp))
    }
}

// ---------------------------------------------------------------- Sheet
@Composable
fun AssistantSheet() {
    LaunchedEffect(Agent.openRequests) { if (Agent.openRequests > 0) Ui.assistant = true }
    AnimatedVisibility(Ui.assistant, enter = slideInVertically(tween(260)) { it } + fadeIn(tween(160)), exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160))) {
        BackHandler { Ui.assistant = false }
        var drag by remember { mutableFloatStateOf(0f) }
        Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().imePadding().navigationBarsPadding()) {
            // Header (drag down to close)
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp)
                .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (drag > 120) Ui.assistant = false; drag = 0f }) { _, d -> drag += d } },
                verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Outlined.KeyboardArrowDown, "Close assistant") { Ui.assistant = false }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Assistant", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val t = Browser.current
                    if (t != null && !t.isNtp) Text(t.host, color = C.faint, fontSize = 11.5.sp, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                IconBtn(Icons.Outlined.EditNote, "New chat", enabled = Agent.items.isNotEmpty()) { Agent.newChat() }
            }
            val list = rememberLazyListState()
            LaunchedEffect(Agent.items.size) { if (Agent.items.isNotEmpty()) list.animateScrollToItem(Agent.items.size - 1) }
            if (Agent.items.isEmpty()) EmptyAssistant(Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(Agent.items, key = { it.key }) { ItemView(it) }
                if (Agent.running) item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    CircularProgressIndicator(Modifier.size(13.dp), color = C.agent, strokeWidth = 1.5.dp); Spacer(Modifier.width(10.dp))
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
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Bottom) {
        Logo(30.dp); Spacer(Modifier.height(16.dp))
        Text(if (onPage) "How can I help with this page?" else "What should we do today?", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 30.sp)
        Spacer(Modifier.height(6.dp))
        Text("Ask in any language. I can browse, compare, read aloud and take actions for you.", color = C.muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp))
        val s = if (onPage) listOf(Icons.Outlined.Summarize to "Summarize this page", Icons.Outlined.Headphones to "Read this page aloud", Icons.Outlined.AccountTree to "Make a mind map of this page", Icons.Outlined.QuestionAnswer to "What are the key takeaways?")
        else listOf(Icons.Outlined.ShoppingBag to "Find the cheapest iPhone 16 from a trusted store", Icons.Outlined.Headphones to "Read the Wikipedia article on black holes aloud",
            Icons.Outlined.Flight to "Find the cheapest flight from Delhi to Goa next Friday", Icons.Outlined.Newspaper to "Summarize the top 5 news stories today")
        s.forEach { (ic, q) ->
            Row(Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).clickable {
                when (q) { "Summarize this page" -> Agent.quick("summarize"); "Read this page aloud" -> Agent.quick("read"); "Make a mind map of this page" -> Agent.quick("mindmap"); else -> Agent.run(q) }
            }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, Modifier.size(17.dp), tint = C.muted); Spacer(Modifier.width(14.dp)); Text(q, color = C.text, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Composer() {
    val ask = Agent.pendingAsk
    var text by remember { mutableStateOf(Ui.assistantDraft) }
    LaunchedEffect(Ui.assistantDraft) { if (Ui.assistantDraft.isNotEmpty()) { text = Ui.assistantDraft; Ui.assistantDraft = "" } }
    fun send() {
        val t = text.trim(); if (t.isEmpty()) return
        text = ""
        if (ask != null) Agent.answer(t) else Agent.run(t)
    }
    Column(Modifier.fillMaxWidth().background(C.chrome).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(C.surface2)
            .border(1.dp, if (ask != null) C.agent.copy(alpha = .6f) else C.line2, RoundedCornerShape(25.dp)).padding(start = 18.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(text, { text = it }, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 5,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text, fontSize = 15.5.sp, lineHeight = 21.sp), cursorBrush = SolidColor(C.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner -> if (text.isEmpty()) Text(if (ask != null) "Type your answer…" else if (Agent.running) "Saarthi is working…" else "Ask anything…", color = C.faint, fontSize = 15.5.sp); inner() })
            when {
                Agent.running && ask == null && text.isEmpty() -> RoundAction(Icons.Outlined.Stop, "Stop", C.text, C.bg) { Agent.stop() }
                text.isNotBlank() -> RoundAction(Icons.Outlined.ArrowUpward, "Send", C.accent, C.accentInk) { send() }
                else -> IconBtn(Icons.Outlined.Mic, "Speak", tint = C.muted, size = 40.dp) { act.listen { t -> if (Agent.pendingAsk != null) Agent.answer(t) else Agent.run(t) } }
            }
        }
        Text("Saarthi can make mistakes. Payments always need your approval.", color = C.faint, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 7.dp))
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
            Box(Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(C.surface2).padding(horizontal = 15.dp, vertical = 10.dp)) {
                Text(it.text, color = C.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        is AiMsg -> Column {
            Markdown(it.markdown, color = if (it.error) C.muted else C.text)
            if (it.setup) Row(Modifier.padding(top = 10.dp)) {
                if (!Prefs.isSignedIn) { PrimaryButton("Sign in") { Ui.assistant = false; Ui.go("settings/account") }; Spacer(Modifier.width(8.dp)) }
                SecondaryButton("Add API key") { Ui.assistant = false; Ui.go("settings/ai") }
            }
        }
        is StepsItem -> Steps(it)
        is ProductsItem -> Products(it)
        is SummaryItem -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Summarize, null, Modifier.size(16.dp), tint = C.muted); Spacer(Modifier.width(8.dp))
                Text(it.title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(hostOf(it.url), color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp, bottom = 10.dp))
            Markdown(it.markdown)
            val ctx = LocalContext.current
            Row(Modifier.padding(top = 8.dp)) {
                SmallAct(Icons.Outlined.Headphones, "Listen") { app.saarthi.browser.service.ReadAloud.speak(it.title, it.markdown.replace(Regex("[*#_`>]"), "")) }
                SmallAct(Icons.Outlined.ContentCopy, "Copy") { copyText(ctx, "Summary", it.markdown) }
                SmallAct(Icons.Outlined.Share, "Share") { shareText(ctx, "${it.title}\n\n${it.markdown}\n\n${it.url}") }
            }
        }
        is MindmapItem -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp), tint = C.muted); Spacer(Modifier.width(8.dp))
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
        is AskItem -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.agentSoft).padding(14.dp)) {
            Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp), tint = C.agent); Spacer(Modifier.width(10.dp))
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
            if (s.done) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp), tint = C.muted) else CircularProgressIndicator(Modifier.size(13.dp), color = C.agent, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (s.done) "Completed ${s.steps.size} step${if (s.steps.size == 1) "" else "s"}" else s.steps.lastOrNull()?.text ?: "Working…", color = C.muted, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = C.faint)
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
    "search" -> Icons.Outlined.Search; "nav" -> Icons.Outlined.Language; "read" -> Icons.Outlined.Article; "click" -> Icons.Outlined.TouchApp
    "type" -> Icons.Outlined.Keyboard; "scroll" -> Icons.Outlined.SwapVert; "shop", "store" -> Icons.Outlined.ShoppingBag; "down" -> Icons.Outlined.Download
    "pdf" -> Icons.Outlined.PictureAsPdf; "shot" -> Icons.Outlined.Screenshot; "speak" -> Icons.Outlined.Headphones; "lock" -> Icons.Outlined.Lock
    "map" -> Icons.Outlined.AccountTree; "sum" -> Icons.Outlined.Summarize; "phone" -> Icons.Outlined.PhoneAndroid; "ask" -> Icons.Outlined.HelpOutline
    "warn" -> Icons.Outlined.WarningAmber; else -> Icons.Outlined.ChevronRight
}

@Composable
private fun Products(p: ProductsItem) {
    Column(Modifier.fillMaxWidth()) {
        p.headline?.let { Text(it, color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)) }
        if (p.stores.isNotEmpty()) Text("Compared ${p.stores.sumOf { it.count }} listings across ${p.stores.count { it.count > 0 }} of ${p.stores.size} stores", color = C.faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))) {
            p.items.forEachIndexed { i, x ->
                if (i > 0) HorizontalDivider(color = C.line)
                ProductRow(x)
            }
        }
    }
}

@Composable
private fun ProductRow(x: Product) {
    Column(Modifier.fillMaxWidth().background(if (x.best) C.surface2 else C.surface).clickable { Ui.assistant = false; Browser.newTab(x.url, Browser.current?.incognito == true) }.padding(12.dp)) {
        Row {
            NetImage(x.image, Modifier.size(68.dp).clip(RoundedCornerShape(10.dp)))
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
            if (has && depth > 0) Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = C.faint)
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
            Icon(if (f.kind == "image") Icons.Outlined.Image else Icons.Outlined.PictureAsPdf, null, Modifier.size(20.dp), tint = C.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.saved.name, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${f.saved.path} · ${android.text.format.Formatter.formatShortFileSize(ctx, f.saved.size)}", color = C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(Icons.Outlined.Share, "Share file", tint = C.muted) { runCatching { ctx.startActivity(Intent.createChooser(intent(Intent.ACTION_SEND), null)) } }
    }
}

@Composable
private fun Approval(a: ApprovalItem) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, if (a.state == "pending") C.agent.copy(alpha = .5f) else C.line, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = C.agent); Spacer(Modifier.width(8.dp))
            Text(if (a.kind == "payment") "Approval needed to pay" else "Approval needed", color = C.agent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        if (a.amount.isNotBlank()) Text(a.amount, color = C.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
        Text(a.title.ifBlank { a.itemName }, color = C.text, fontSize = 14.sp, lineHeight = 20.sp)
        Text(listOf(a.merchant, a.site).filter { it.isNotBlank() }.distinct().joinToString(" · "), color = C.faint, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(14.dp))
        when (a.state) {
            "pending" -> Row {
                PrimaryButton("Approve", Modifier.weight(1f), icon = Icons.Outlined.Fingerprint) { act.approveLocally(a, true) }
                Spacer(Modifier.width(10.dp))
                SecondaryButton("Deny", Modifier.weight(1f)) { act.approveLocally(a, false) }
            }
            "approved" -> Text("Approved with your device lock", color = C.ok, fontSize = 13.sp)
            else -> Text("Denied. Nothing was paid.", color = C.muted, fontSize = 13.sp)
        }
    }
}
