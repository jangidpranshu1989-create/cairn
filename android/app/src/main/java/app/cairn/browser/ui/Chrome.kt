package app.cairn.browser.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.applyCanvas
import app.cairn.browser.agent.Agent
import app.cairn.browser.agent.PageJs
import app.cairn.browser.agent.PageTools
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.NTP
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.Prefs
import app.cairn.browser.data.Search
import app.cairn.browser.service.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ============================================================ Motion
/** One spring for the whole app, so every surface moves with the same physical feel. */
fun <T> cairnSpring(stiff: Float = 420f, damp: Float = 0.86f) = spring<T>(dampingRatio = damp, stiffness = stiff)

/** Scales down slightly while pressed; used on every tappable surface in the chrome. */
fun Modifier.pressScale(pressed: Boolean): Modifier = composed {
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, cairnSpring(900f, 0.6f), label = "press")
    this.scale(s)
}

/** Shimmer used by skeleton loaders. */
fun Modifier.shimmer(corner: androidx.compose.ui.unit.Dp = 8.dp): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val base = C.surface2; val hi = C.surface3
    this.clip(RoundedCornerShape(corner)).drawBehind {
        drawRect(base)
        val w = size.width
        drawRect(Brush.linearGradient(listOf(Color.Transparent, hi.copy(alpha = .9f), Color.Transparent), start = Offset(w * x - w * .5f, 0f), end = Offset(w * x, size.height)))
    }
}

// ============================================================ Glass (backdrop blur over the live page)
/**
 * WebView pixels can't be blurred by Compose (they are drawn by Chromium's compositor), so the dock samples the strip of
 * page directly behind it at 1/8 scale, blurs it on the CPU (two box-blur passes — a few hundred pixels, sub-millisecond)
 * and draws that under a translucent tint. It refreshes only when the page scrolls or loads.
 */
object Backdrop {
    var image by mutableStateOf<ImageBitmap?>(null); private set
    var tick by mutableIntStateOf(0)
    private var last = 0L

    fun sample(t: Tab?, top: Float, height: Float) {
        val w = t?.web ?: run { image = null; return }
        if (t.isNtp || w.width <= 0 || height <= 0f) { image = null; return }
        val now = android.os.SystemClock.uptimeMillis(); if (now - last < 120) return; last = now
        runCatching {
            val s = 1f / 8f
            val bw = (w.width * s).roundToInt().coerceAtLeast(4); val bh = (height * s).roundToInt().coerceAtLeast(4)
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            b.applyCanvas { scale(s, s); translate(-w.scrollX.toFloat(), -(w.scrollY.toFloat() + top)); w.draw(this) }
            if (!uniform(b)) { blur(b, 3); blur(b, 3); image = b.asImageBitmap() } else image = null
        }
    }
    private fun uniform(b: Bitmap): Boolean { val p = b.getPixel(0, 0); return (0 until 8).all { b.getPixel((b.width - 1) * it / 7, (b.height - 1) * (it % 2)) == p } }
    private fun blur(b: Bitmap, r: Int) {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h); val out = IntArray(w * h)
        fun pass(src: IntArray, dst: IntArray, horiz: Boolean) {
            val n = if (horiz) w else h; val m = if (horiz) h else w
            for (j in 0 until m) for (i in 0 until n) {
                var ra = 0; var ga = 0; var ba = 0; var c = 0
                for (k in -r..r) { val ii = (i + k).coerceIn(0, n - 1); val p = src[if (horiz) j * w + ii else ii * w + j]; ra += (p shr 16) and 255; ga += (p shr 8) and 255; ba += p and 255; c++ }
                dst[if (horiz) j * w + i else i * w + j] = (0xFF shl 24) or ((ra / c) shl 16) or ((ga / c) shl 8) or (ba / c)
            }
        }
        pass(px, out, true); pass(out, px, false); b.setPixels(px, 0, w, 0, 0, w, h)
    }
}

/** Glass surface: blurred page behind + tint + a hairline that is brighter at the top edge (light catching glass). */
@Composable
fun Glass(modifier: Modifier, corner: androidx.compose.ui.unit.Dp, sampled: Boolean, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.shadow2(corner).clip(shape)
        .background(if (sampled && Backdrop.image != null) C.glass else C.surface)
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (C.dark) .13f else .55f), Color.White.copy(alpha = if (C.dark) .03f else .15f))), shape)) {
        if (sampled) Backdrop.image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, alpha = .9f) }
        if (sampled && Backdrop.image != null) Box(Modifier.matchParentSize().background(C.glass))
        content()
    }
}
private fun Modifier.shadow2(corner: androidx.compose.ui.unit.Dp) = this.drawBehind {
    val r = corner.toPx()
    for (i in 1..4) drawRoundRect(Color.Black.copy(alpha = 0.05f), topLeft = Offset(0f, i * 1.5f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + i, r + i))
}

// ============================================================ Dock
/**
 * Comet-style two-row dock. Row 1: a full-width address pill (menu · site · reload). Row 2: back, forward, the
 * Assistant pill (tap = chat, waveform = voice, long-press = Circle to Ask), tabs and new tab. It sits *below* the page
 * in the layout, so it never covers page content.
 */
@Composable
fun Dock(tab: Tab?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        AddressPill(tab)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            val canBack = tab != null && (tab.canBack || !tab.isNtp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconBtn(Ic.ArrowBack, "Back", tint = if (canBack) C.text else C.faint, size = 46.dp, enabled = canBack) { Haptics.tick(); tab?.let { Browser.back(it) } }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val canFwd = tab?.canForward == true
                IconBtn(Ic.ArrowForward, "Forward", tint = if (canFwd) C.text else C.faint, size = 46.dp, enabled = canFwd) { Haptics.tick(); Browser.forward(tab) }
            }
            AssistantPill(Modifier.weight(2.6f))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { TabsTile(tab) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconBtn(Ic.Add, "New tab", tint = C.text, size = 46.dp) { Haptics.tick(); Browser.newTab(NTP, tab?.incognito == true); Ui.omnibox = true }
            }
        }
    }
}

@Composable
private fun AddressPill(tab: Tab?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var drag by remember { mutableFloatStateOf(0f) }
    val swipe = with(LocalDensity.current) { 64.dp.toPx() }
    val dx by animateFloatAsState(drag * .35f, cairnSpring(), label = "dx")
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxWidth().height(50.dp).clip(shape).background(if (tab?.incognito == true) C.surface3 else C.surface)
        .border(1.dp, C.line2, shape)
        .pointerInput(tab) {
            detectHorizontalDragGestures(onDragEnd = {
                val list = Browser.tabs.filter { it.incognito == tab?.incognito }; val i = list.indexOf(tab)
                val next = if (drag < -swipe) list.getOrNull(i + 1) else if (drag > swipe) list.getOrNull(i - 1) else null
                if (next != null) { Haptics.gesture(); Browser.select(next) }
                drag = 0f
            }) { _, d -> drag += d }
        }
        .combinedClickableCompat(onClick = { Ui.omniboxText = null; Ui.omnibox = true }, onLongClick = { tab?.takeIf { !it.isNtp }?.let { Haptics.long(); copyText(ctx, "Link", it.url); act.toast("Link copied") } })) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.MoreVert, "Menu", tint = C.text, size = 42.dp) { Haptics.tick(); Ui.menu = true }
            Row(Modifier.weight(1f).offset { IntOffset(dx.roundToInt(), 0) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (tab == null || tab.isNtp) {
                    Text(if (tab?.incognito == true) "Search privately" else "Search or ask", color = C.faint, fontSize = 16.sp, maxLines = 1)
                } else {
                    val q = Search.queryOf(tab.url)
                    when {
                        tab.sensitive -> { Icon(Ic.GppGood, "Cairn Guard is active", Modifier.size(14.dp).clickable { Ui.siteInfo = true }, tint = C.ok); Spacer(Modifier.width(6.dp)) }
                        !tab.secure && q == null -> { Icon(Ic.Info, "Not secure", Modifier.size(14.dp).clickable { Ui.siteInfo = true }, tint = C.warn); Spacer(Modifier.width(6.dp)) }
                        else -> {}
                    }
                    Text(q ?: tab.host.removePrefix("www.").ifBlank { tab.url }, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, false))
                    tab.translated?.let { Text(it.take(2).uppercase(), color = C.agent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp).border(1.dp, C.agent.copy(alpha = .5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) }
                }
            }
            if (tab == null || tab.isNtp) IconBtn(Ic.Mic, "Voice search", size = 42.dp, tint = C.text) { act.listen { t -> Browser.open(t) } }
            else IconBtn(if (tab.loading) Ic.Close else Ic.Refresh, if (tab.loading) "Stop" else "Reload", size = 42.dp, tint = C.text) { Haptics.tick(); if (tab.loading) Browser.stop(tab) else Browser.reload(tab) }
        }
        val p by animateFloatAsState(if (tab?.loading == true) tab.progress / 100f else 1f, tween(240), label = "p")
        val alpha by animateFloatAsState(if (tab?.loading == true) 1f else 0f, tween(if (tab?.loading == true) 80 else 420), label = "pa")
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp).fillMaxWidth(p.coerceAtLeast(.04f)).height(2.dp).clip(CircleShape)
            .background((if (Agent.controlling == tab && tab != null) C.agent else C.text).copy(alpha = .8f * alpha)))
    }
}

/** Center of the dock: Cairn mark + "Assistant", with a white voice button on the right. */
@Composable
private fun AssistantPill(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "pill")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "rot")
    val col = C.agent
    val shape = RoundedCornerShape(25.dp)
    Row(modifier.padding(horizontal = 4.dp).height(48.dp).clip(shape).background(C.surface).border(1.dp, C.line2, shape)
        .combinedClickableCompat(onClick = { Haptics.tick(); Ui.assistant = true }, onLongClick = { Haptics.long(); startLasso() })
        .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (Agent.running) Canvas(Modifier.size(24.dp).rotate(rot)) { drawArc(col, 0f, 110f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
            Logo(18.dp, lift = if (Agent.running) 1f else 0f)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (Agent.running) "Working…" else "Assistant", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (C.dark) Color(0xFFECECEC) else C.text).clickable {
            Haptics.tick(); act.listen { q -> if (q.isNotBlank()) { Ui.assistant = true; Agent.run(q) } }
        }, contentAlignment = Alignment.Center) {
            Icon(Ic.GraphicEq, "Talk to Cairn", Modifier.size(20.dp), tint = if (C.dark) Color(0xFF1C1C1F) else C.bg)
        }
    }
}

/** The Cairn stone: opens the agent; long-press starts Circle-to-Act. Spins a sandstone arc while the agent works. */
@Composable
fun AgentStone() {
    val t = rememberInfiniteTransition(label = "stone")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "rot")
    val col = C.agent
    Box(Modifier.size(44.dp).clip(CircleShape).combinedClickableCompat(onClick = { Haptics.tick(); Ui.assistant = true }, onLongClick = { Haptics.long(); startLasso() }),
        contentAlignment = Alignment.Center) {
        if (Agent.running) Canvas(Modifier.size(36.dp).rotate(rot)) { drawArc(col, 0f, 110f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
        Logo(22.dp, lift = if (Agent.running) 1f else 0f)
    }
}

/**
 * Circle to ask. Like Android's Circle to Search, the page is frozen into a still frame first; the user then circles or
 * taps anything on it. Selecting from a frozen frame means the crop is exact (no dimming scrim or lasso stroke baked in)
 * and works even while the page scrolls or animates underneath.
 */
fun startLasso() {
    val t = Browser.current
    val w = t?.web
    if (t == null || t.isNtp || w == null || w.width <= 0) { act.toast("Open a page to circle something on it"); return }
    val wasOpen = Ui.assistant || Ui.menu
    Ui.assistant = false; Ui.menu = false
    Haptics.gesture()
    // Let any closing sheet leave the screen before copying pixels.
    w.postDelayed({
        val frame = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2); w.getLocationInWindow(loc)
        fun soft() = runCatching { frame.applyCanvas { translate(-w.scrollX.toFloat(), -w.scrollY.toFloat()); w.draw(this) } }
        fun show() { Ui.lassoFrame = frame; Ui.lasso = true }
        runCatching {
            android.view.PixelCopy.request(act.window, android.graphics.Rect(loc[0], loc[1], loc[0] + w.width, loc[1] + w.height), frame,
                { r -> if (r != android.view.PixelCopy.SUCCESS) soft(); show() }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { soft(); show() }
    }, if (wasOpen) 320L else 16L)
}

@Composable
fun TabsTile(tab: Tab?) {
    val n = if (tab?.incognito == true) Browser.privateTabs().size else Browser.normalTabs().size
    Box(Modifier.size(44.dp).clip(CircleShape).combinedClickableCompat(onClick = { Haptics.tick(); Browser.current?.let { Browser.capture(it) }; Ui.tabSwitcher = true },
        onLongClick = { Haptics.long(); Browser.newTab(NTP, tab?.incognito == true); Ui.omnibox = true }), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).border(1.6.dp, C.text, RoundedCornerShape(7.dp)).background(if (tab?.incognito == true) C.surface3 else Color.Transparent), contentAlignment = Alignment.Center) {
            AnimatedContent(n, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "n") { v ->
                Text(if (v > 99) "∞" else v.toString(), color = C.text, fontSize = if (v > 9) 10.sp else 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ============================================================ Guard badge
@Composable
fun GuardBadge(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(180)) + scaleIn(cairnSpring(), initialScale = .9f), exit = fadeOut(tween(160)) + scaleOut(targetScale = .9f)) {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(C.ok.copy(alpha = if (C.dark) .16f else .12f)).border(1.dp, C.ok.copy(alpha = .35f), RoundedCornerShape(14.dp))
            .clickable { act.toast("Cairn never reads or types passwords, PINs, OTPs or card numbers. They're masked from the AI.") }.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.GppGood, null, Modifier.size(14.dp), tint = C.ok); Spacer(Modifier.width(6.dp))
            Text("Cairn Guard Active — Passwords & PINs Masked", color = C.ok, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

// ============================================================ Agent status (sheet closed)
@Composable
fun AgentStatus(modifier: Modifier = Modifier) {
    AnimatedVisibility(Agent.running && !Ui.assistant, modifier, enter = fadeIn() + slideInVertically(cairnSpring()) { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        Glass(Modifier.padding(horizontal = 24.dp).height(42.dp), 21.dp, sampled = false) {
            Row(Modifier.fillMaxHeight().clickable { Ui.assistant = true }.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentDots(); Spacer(Modifier.width(10.dp))
                AnimatedContent(Agent.status.ifBlank { "Working…" }, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "st") { s ->
                    Text(s, color = C.text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).clickable { Haptics.reject(); Agent.stop() }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Stop", color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Three stones breathing in sequence — Cairn's "working" indicator. */
@Composable
fun AgentDots(color: Color = C.agent) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(.25f, 1f, infiniteRepeatable(keyframes { durationMillis = 900; .25f at 0; 1f at 300 + i * 0; .25f at 900 }, initialStartOffset = StartOffset(i * 150)), label = "d$i")
            Box(Modifier.padding(horizontal = 1.5.dp).size(5.dp).clip(CircleShape).background(color.copy(alpha = a)))
        }
    }
}

// ============================================================ Live mini-preview (PiP) of background agent pages
@Composable
fun LivePreview(modifier: Modifier = Modifier, inline: Boolean = false) {
    val pages = PageTools.live
    // While the assistant sheet is open the preview is shown inline in the chat (never floating over message bubbles).
    val show = pages.isNotEmpty() && Prefs.agentLivePreview && (if (inline) true else Ui.pip && !Ui.assistant)
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val dens = LocalDensity.current
    val maxX = with(dens) { (conf.screenWidthDp.dp - 160.dp).toPx() }; val maxY = with(dens) { (conf.screenHeightDp.dp - 340.dp).toPx() }
    var idx by remember { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var off by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(show, pages.size) {
        while (show && pages.isNotEmpty()) {
            val p = pages.getOrNull(idx % pages.size) ?: break
            val w = p.web
            frame = runCatching {
                if (w.width <= 0) null else {
                    val s = 260f / w.width
                    Bitmap.createBitmap(260, (w.height * s).roundToInt().coerceAtMost(420).coerceAtLeast(1), Bitmap.Config.ARGB_8888).applyCanvas {
                        scale(s, s); translate(-w.scrollX.toFloat(), -w.scrollY.toFloat()); w.draw(this)
                    }.asImageBitmap()
                }
            }.getOrNull() ?: frame
            delay(450)
        }
    }
    AnimatedVisibility(show, modifier.offset { IntOffset(off.x.roundToInt(), off.y.roundToInt()) }, enter = fadeIn() + scaleIn(cairnSpring(), initialScale = .7f, transformOrigin = TransformOrigin(1f, 1f)), exit = fadeOut() + scaleOut(targetScale = .7f, transformOrigin = TransformOrigin(1f, 1f))) {
        Column((if (inline) Modifier.fillMaxWidth() else Modifier.width(132.dp)).clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.agent.copy(alpha = .45f), RoundedCornerShape(16.dp))
            .then(if (inline) Modifier else Modifier.pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); off = Offset((off.x + d.x).coerceIn(-maxX, 0f), (off.y + d.y).coerceIn(0f, maxY.coerceAtLeast(0f))) } })
            .clickable { if (pages.size > 1) idx = (idx + 1) % pages.size else Ui.assistant = true }) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentDots(); Spacer(Modifier.width(6.dp))
                val p = pages.getOrNull(idx % pages.size.coerceAtLeast(1))
                Text(p?.label ?: "", color = C.text, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (pages.size > 1) Text("${idx % pages.size + 1}/${pages.size}", color = C.faint, fontSize = 9.5.sp)
                if (!inline) Box(Modifier.size(22.dp).clip(CircleShape).clickable { Ui.pip = false }, contentAlignment = Alignment.Center) { Icon(Ic.Close, "Hide preview", Modifier.size(12.dp), tint = C.faint) }
            }
            Box(Modifier.fillMaxWidth().height(if (inline) 150.dp else 170.dp).background(Color.White)) {
                frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) } ?: Box(Modifier.fillMaxSize().shimmer(0.dp))
            }
        }
    }
}

// ============================================================ Voice
@Composable
fun VoiceOverlay() {
    val on = Voice.phase != Voice.Phase.Idle
    AnimatedVisibility(on, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
        BackHandler { Voice.cancel() }
        Box(Modifier.fillMaxSize().background(C.scrim).clickable { Voice.finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.padding(16.dp).navigationBarsPadding().fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.surface).padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                    VoiceOrb(Voice.level, if (Voice.phase == Voice.Phase.Transcribing) "think" else "listen", 150.dp)
                    if (Voice.phase != Voice.Phase.Transcribing) Icon(Ic.Mic, null, Modifier.size(24.dp), tint = Color(0xFF1C1C1F).copy(alpha = .75f))
                }
                Spacer(Modifier.height(12.dp))
                Text(if (Voice.phase == Voice.Phase.Transcribing) "Transcribing…" else "Listening", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(if (Voice.phase == Voice.Phase.Transcribing) "One moment" else "Speak in any language · tap to finish", color = C.faint, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Text("Cancel", color = C.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { Voice.cancel() }.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }
    }
}

// ============================================================ Circle to Act
@Composable
fun LassoOverlay() {
    if (!Ui.lasso) return
    val tab = Browser.current ?: run { Ui.lasso = false; return }
    val w = tab.web ?: run { Ui.lasso = false; return }
    val frame = Ui.lassoFrame ?: run { Ui.lasso = false; return }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val path = remember { mutableStateListOf<Offset>() }
    var done by remember { mutableStateOf<Rect?>(null) }
    var crop by remember { mutableStateOf<Bitmap?>(null) }
    var region by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var webAt by remember { mutableStateOf(Offset.Zero) }
    var ask by remember { mutableStateOf("") }
    val img = remember(frame) { frame.asImageBitmap() }
    val inf = rememberInfiniteTransition(label = "lasso")
    val phase by inf.animateFloat(0f, 60f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ph")
    val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sw")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = .8f, stiffness = 300f)) }
    val col = C.agent
    fun close() { Ui.lasso = false; Ui.lassoFrame = null; path.clear(); done = null; crop = null }
    BackHandler { if (done != null) { done = null; path.clear(); crop = null; region = null } else close() }
    fun select(r0: Rect) {
        // Selection in overlay coordinates → frame (WebView) pixels
        val off = webAt - origin
        val r = Rect((r0.left - off.x).coerceIn(0f, frame.width - 1f), (r0.top - off.y).coerceIn(0f, frame.height - 1f),
            (r0.right - off.x).coerceIn(1f, frame.width.toFloat()), (r0.bottom - off.y).coerceIn(1f, frame.height.toFloat()))
        if (r.width < 12 || r.height < 12) { path.clear(); return }
        done = r0; Haptics.confirm()
        crop = Bitmap.createBitmap(frame, r.left.roundToInt(), r.top.roundToInt(), r.width.roundToInt().coerceAtMost(frame.width - r.left.roundToInt()), r.height.roundToInt().coerceAtMost(frame.height - r.top.roundToInt()))
        region = null
        scope.launch {
            val m = PageJs.obj(w, "metrics()"); val sc = (m?.optDouble("w", 0.0)?.takeIf { it > 0 } ?: (w.width / w.resources.displayMetrics.density.toDouble())) / w.width
            region = PageJs.obj(w, "region(${r.left * sc}, ${r.top * sc}, ${r.width * sc}, ${r.height * sc})") ?: org.json.JSONObject()
        }
    }
    fun go(prompt: String?) {
        val b = crop ?: return; val r = region
        val links = r?.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val text = r?.optString("text").orEmpty()
        close(); Ui.assistant = true
        Agent.lasso(b, text, links, prompt)
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow(); webAt = IntArray(2).also { a -> w.getLocationInWindow(a) }.let { a -> Offset(a[0].toFloat(), a[1].toFloat()) } }) {
        val off = webAt - origin
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { p ->
                // A tap selects a generous box around the finger (handy for a product photo or a word).
                val hw = 120.dp.toPx(); val hh = 90.dp.toPx()
                path.clear(); select(Rect(p.x - hw, p.y - hh, p.x + hw, p.y + hh))
                path.addAll(listOf(Offset(p.x - hw, p.y - hh), Offset(p.x + hw, p.y - hh), Offset(p.x + hw, p.y + hh), Offset(p.x - hw, p.y + hh)))
            }
        }.pointerInput(Unit) {
            detectDragGestures(onDragStart = { path.clear(); done = null; crop = null; path.add(it); Haptics.gesture() }, onDragEnd = {
                if (path.size < 6) { path.clear(); return@detectDragGestures }
                select(Rect(path.minOf { it.x }, path.minOf { it.y }, path.maxOf { it.x }, path.maxOf { it.y }))
            }) { c, _ -> c.consume(); path.add(c.position) }
        }) {
            // Frozen page settles from a slight zoom to exactly 1:1 (so what you circle is exactly what gets cropped),
            // under a soft veil; the selection is cut out at full brightness.
            val sc = 1.04f - .04f * appear.value
            drawContext.transform.scale(sc, sc, Offset(size.width / 2, size.height / 2))
            drawImage(img, IntOffset(off.x.roundToInt(), off.y.roundToInt()))
            drawContext.transform.scale(1 / sc, 1 / sc, Offset(size.width / 2, size.height / 2))
            val veil = Color.Black.copy(alpha = .42f * appear.value)
            val sel = done
            if (sel == null) drawRect(veil)
            else {
                val hole = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(sel, androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()))) }
                clipPath(hole, ClipOp.Difference) { drawRect(veil) }
                drawPath(hole, Brush.sweepGradient(listOf(col, Color.White, col.copy(alpha = .5f), col), sel.center), style = Stroke(3.dp.toPx()))
            }
            // Shimmering glow sweeping around the screen edge while waiting for a selection
            if (sel == null) {
                val g = 3.dp.toPx(); val brush = Brush.sweepGradient(listOf(col.copy(alpha = 0f), col, Color.White.copy(alpha = .9f), col, col.copy(alpha = 0f)), Offset(size.width * sweep, size.height * (1 - sweep)))
                drawRoundRect(brush, Offset(g / 2, g / 2), androidx.compose.ui.geometry.Size(size.width - g, size.height - g), androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()), style = Stroke(g), alpha = appear.value)
            }
            if (path.size > 1 && sel == null) {
                val p = Path().apply { moveTo(path[0].x, path[0].y); path.drop(1).forEach { lineTo(it.x, it.y) } }
                drawPath(p, col.copy(alpha = .35f), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(p, Color.White, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 0f), phase)))
            }
        }
        // Header
        Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp).graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * -40f }
            .clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(20.dp)).padding(start = 14.dp, end = 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Lasso, null, Modifier.size(16.dp), tint = C.agent); Spacer(Modifier.width(8.dp))
            Text(if (done == null) "Circle or tap anything" else "Ask about your selection", color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).clickable { close() }, contentAlignment = Alignment.Center) { Icon(Ic.Close, "Cancel", Modifier.size(16.dp), tint = C.muted) }
        }
        // Action sheet, always on screen at the bottom (never pushed off-screen by a low selection)
        androidx.compose.animation.AnimatedVisibility(done != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(160)) + slideInVertically(cairnSpring(420f, .82f)) { it }, exit = fadeOut(tween(120)) + slideOutVertically { it }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(C.bg).border(1.dp, C.line2, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .navigationBarsPadding().imePadding().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    crop?.let { Image(it.asImageBitmap(), "Selection", Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, C.agent.copy(alpha = .6f), RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                    Spacer(Modifier.width(10.dp))
                    Row(Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(23.dp)).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.text.BasicTextField(ask, { ask = it }, Modifier.weight(1f), singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = C.text, fontSize = 15.sp, fontFamily = Inter), cursorBrush = SolidColor(C.agent),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { go(ask.ifBlank { null }) }),
                            decorationBox = { inner -> if (ask.isEmpty()) Text("Ask anything about this…", color = C.faint, fontSize = 15.sp); inner() })
                        Box(Modifier.size(38.dp).clip(CircleShape).background(C.accent).clickable { go(ask.ifBlank { null }) }, contentAlignment = Alignment.Center) { Icon(Ic.ArrowUpward, "Ask", Modifier.size(18.dp), tint = C.accentInk) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(Ic.Sparkle, "Explain", null as String?), Triple(Ic.Search, "Search this", "Search the web for what's in my selection and tell me what it is, with sources"),
                        Triple(Ic.ShoppingBag, "Find cheaper", "Identify this product and find it cheaper from a trusted store"), Triple(Ic.Translate, "Translate", "Translate the text in my selection into English"),
                        Triple(Ic.ScanText, "Copy text", "__copy")).forEach { (ic, label, prompt) ->
                        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(16.dp)).clickable {
                            Haptics.tick()
                            if (prompt == "__copy") {
                                val txt = region?.optString("text").orEmpty()
                                if (txt.isBlank()) go("Transcribe all the text in my selection exactly, nothing else.") else { copyText(act, "Text", txt); act.toast("Text copied"); close() }
                            } else go(prompt)
                        }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ic, null, Modifier.size(15.dp), tint = C.agent); Spacer(Modifier.width(7.dp)); Text(label, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
