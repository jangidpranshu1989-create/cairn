package app.cairn.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.agent.Agent
import app.cairn.browser.agent.ChatMeta
import app.cairn.browser.agent.Chats
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.delay
import java.util.Calendar

// ------------------------------------------------------------------ Live greeting

/** Ticks every 20 s so the greeting and clock are always the real local time — never stuck on "Good morning". */
@Composable
fun rememberNow(): Calendar {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000 - System.currentTimeMillis() % 20_000); now = Calendar.getInstance() } }
    return now
}

fun firstName(): String = Prefs.displayName.let { n -> if (n.contains('@')) "" else n.trim().substringBefore(' ') }.take(18)

fun greetingFor(c: Calendar): String {
    val g = when (c.get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..20 -> "Good evening"; else -> "Good night" }
    val n = firstName()
    return if (n.isNotBlank()) "$g, $n" else g
}

fun clockLine(c: Calendar): String =
    java.text.SimpleDateFormat("EEEE, d MMMM · h:mm a", java.util.Locale.getDefault()).format(c.time)

// ------------------------------------------------------------------ Comet-style motion

/** Soft rotating aurora orb used on the empty assistant screen; speeds up and brightens while the agent works. */
@Composable
fun AuroraOrb(size: Dp = 72.dp, active: Boolean = false) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 2600 else 9000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(.92f, 1.06f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val cols = listOf(Color(0xFF7C9CFF), Color(0xFF9F7BFF), Color(0xFF4FD1C5), Color(0xFFFF8FB1), Color(0xFF7C9CFF))
    Box(Modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 1.35f).blur(22.dp)) {
            rotate(spin) { drawCircle(Brush.sweepGradient(cols), radius = this.size.minDimension / 2.4f * breathe, alpha = if (active) .75f else .5f) }
        }
        Canvas(Modifier.size(size)) {
            rotate(-spin * .7f) { drawCircle(Brush.sweepGradient(cols)) }
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .55f), Color.Transparent), center = Offset(this.size.width * .35f, this.size.height * .3f), radius = this.size.minDimension * .55f))
        }
    }
}

/** Text with a light sweep passing over it — the "thinking" label. */
@Composable
fun ShimmerText(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 13.5.sp) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "x")
    var w by remember { mutableFloatStateOf(400f) }
    val brush = Brush.linearGradient(listOf(C.muted, C.text, C.agent, C.muted), start = Offset(w * x - w * .5f, 0f), end = Offset(w * x + w * .5f, 0f))
    Text(text, modifier.onSizeChangedPx { w = it }, style = androidx.compose.ui.text.TextStyle(brush = brush, fontSize = fontSize, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun Modifier.onSizeChangedPx(f: (Float) -> Unit) = this.then(Modifier.drawWithContent { f(size.width.coerceAtLeast(1f)); drawContent() })

/** Animated gradient border that orbits a rounded shape while [active] (the composer while Cairn is working). */
fun Modifier.glowBorder(active: Boolean, phase: Float, radius: Dp, width: Dp = 1.6.dp): Modifier = if (!active) this else drawBehind {
    val r = radius.toPx(); val sw = width.toPx()
    val c = Offset(size.width / 2, size.height / 2)
    val rad = Math.toRadians(phase.toDouble()); val len = maxOf(size.width, size.height) / 2
    val d = Offset((kotlin.math.cos(rad) * len).toFloat(), (kotlin.math.sin(rad) * len).toFloat())
    val cols = listOf(Color(0xFF7C9CFF), Color(0xFF9F7BFF), Color(0xFF4FD1C5), Color(0xFFFF8FB1))
    val brush = Brush.linearGradient(cols, start = c - d, end = c + d)
    val halo = Brush.linearGradient(cols.map { it.copy(alpha = .22f) }, start = c - d, end = c + d)
    drawRoundRect(brush = halo, cornerRadius = CornerRadius(r + sw * 2), topLeft = Offset(-sw * 2, -sw * 2),
        size = androidx.compose.ui.geometry.Size(size.width + sw * 4, size.height + sw * 4), style = Stroke(sw * 3))
    drawRoundRect(brush = brush, cornerRadius = CornerRadius(r), style = Stroke(sw))
}

@Composable
fun rememberGlowPhase(active: Boolean): Float {
    if (!active) return 0f
    val t = rememberInfiniteTransition(label = "glow")
    val p by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    return p
}

// ------------------------------------------------------------------ Chat history (ChatGPT-style)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatHistoryPanel(onClose: () -> Unit) {
    BackHandler { onClose() }
    var q by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<ChatMeta?>(null) }
    var rename by remember { mutableStateOf<ChatMeta?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(q, Chats.list.toList()) { Chats.search(q) }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.ArrowBack, "Back", tint = C.text, size = 42.dp) { onClose() }
            Text("Chats", color = C.text, fontSize = 18.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (Chats.list.isNotEmpty()) IconBtn(Ic.DeleteSweep, "Delete all chats", tint = C.muted, size = 40.dp) { confirmClear = true }
        }
        Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, null, Modifier.size(17.dp), tint = C.muted); Spacer(Modifier.width(10.dp))
            BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.text, fontSize = 15.sp),
                cursorBrush = SolidColor(C.agent), decorationBox = { inner -> if (q.isEmpty()) Text("Search chats", color = C.faint, fontSize = 15.sp); inner() })
            if (q.isNotEmpty()) IconBtn(Ic.Close, "Clear", tint = C.muted, size = 32.dp) { q = "" }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { Haptics.tick(); Agent.newChat(); Ui.mentions.clear(); onClose() }
            .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(C.agentSoft), contentAlignment = Alignment.Center) { Icon(Ic.EditNote, null, Modifier.size(16.dp), tint = C.agent) }
            Spacer(Modifier.width(12.dp)); Text("New chat", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Ic.QuestionAnswer, null, Modifier.size(34.dp), tint = C.faint); Spacer(Modifier.height(12.dp))
                Text(if (q.isBlank()) "No chats yet" else "No chats match \"$q\"", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("Your conversations with Cairn are saved on this phone so you can come back and continue them. Private-tab chats are never saved.",
                    color = C.muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp)) {
            var last = ""
            val rows = shown.map { m -> (if (m.pinned) "Pinned" else Chats.group(m.updated)) to m }
            rows.forEach { (g, m) ->
                if (g != last) { last = g; item(key = "h_$g") { Text(g, color = C.faint, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp)) } }
                item(key = m.id) {
                    val cur = m.id == Agent.chatId && Agent.items.isNotEmpty()
                    Box {
                        Row(Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (cur) C.surface else Color.Transparent)
                            .combinedClickable(onLongClick = { Haptics.gesture(); menuFor = m }) { Haptics.tick(); if (!Agent.openChat(m.id)) act.toast("Couldn't open that chat"); onClose() }
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (m.pinned) { Icon(Ic.Star, null, Modifier.size(12.dp), tint = C.agent); Spacer(Modifier.width(5.dp)) }
                                    Text(m.title, color = C.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (m.preview.isNotBlank()) Text(m.preview, color = C.muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconBtn(Ic.MoreVert, "Chat options", tint = C.faint, size = 36.dp) { menuFor = m }
                        }
                        DropdownMenu(menuFor?.id == m.id, { menuFor = null }, containerColor = C.surface2) {
                            DropdownMenuItem({ Text(if (m.pinned) "Unpin" else "Pin", color = C.text) }, { Chats.pin(m.id); menuFor = null }, leadingIcon = { Icon(Ic.Star, null, tint = C.muted) })
                            DropdownMenuItem({ Text("Rename", color = C.text) }, { rename = m; menuFor = null }, leadingIcon = { Icon(Ic.Edit, null, tint = C.muted) })
                            DropdownMenuItem({ Text("Delete", color = C.err) }, { Agent.deleteChat(m.id); menuFor = null }, leadingIcon = { Icon(Ic.Delete, null, tint = C.err) })
                        }
                    }
                }
            }
        }
    }
    rename?.let { m ->
        var name by remember(m.id) { mutableStateOf(m.title) }
        AlertDialog({ rename = null }, containerColor = C.surface2,
            title = { Text("Rename chat", color = C.text) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ Agent.renameChat(m.id, name); rename = null }) { Text("Save", color = C.agent) } },
            dismissButton = { TextButton({ rename = null }) { Text("Cancel", color = C.muted) } })
    }
    if (confirmClear) AlertDialog({ confirmClear = false }, containerColor = C.surface2,
        title = { Text("Delete all chats?", color = C.text) },
        text = { Text("This removes every saved conversation from this phone. It can't be undone.", color = C.muted) },
        confirmButton = { TextButton({ Chats.clearAll(); Agent.newChat(); confirmClear = false }) { Text("Delete all", color = C.err) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel", color = C.muted) } })
}

// ------------------------------------------------------------------ Profile button (Chrome / Comet style)

@Composable
fun ProfileAvatar(size: Dp = 34.dp, onClick: () -> Unit) {
    val name = Prefs.displayName
    Box(Modifier.size(size + 8.dp).clip(CircleShape).clickable { Haptics.tick(); onClick() }, contentAlignment = Alignment.Center) {
        if (name.isNotBlank())
            Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7C9CFF), Color(0xFF9F7BFF)))), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Color.White, fontSize = (size.value * .44f).sp, fontWeight = FontWeight.SemiBold)
            }
        else Box(Modifier.size(size).clip(CircleShape).background(C.surface2).border(1.dp, C.line2, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Ic.Person, "Profile", Modifier.size(size * .55f), tint = C.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(onClose: () -> Unit) {
    ModalBottomSheet(onClose, containerColor = C.surface, dragHandle = { BottomSheetDefaults.DragHandle(color = C.line2) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 22.dp)) {
            val signed = Prefs.hasAccount
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(48.dp) { }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (signed) Prefs.userName.ifBlank { "Cairn user" } else "Not signed in", color = C.text, fontSize = 17.sp, fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (signed) Prefs.userEmail.ifBlank { "Stored on this phone" } else "Sign in to sync bookmarks, passwords and chats", color = C.muted, fontSize = 13.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton({ onClose(); Ui.go("settings/account") }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, C.line2)) {
                Text(if (signed) "Manage your Cairn account" else "Sign in or create account", color = C.text)
            }
            Spacer(Modifier.height(10.dp))
            val rows = listOf(
                Triple(Ic.QuestionAnswer, "Chats with Cairn (${Chats.list.size})") { onClose(); Ui.chats = true; Ui.assistant = true },
                Triple(Ic.History, "History") { onClose(); Ui.go("history") },
                Triple(Ic.BookmarkBorder, "Bookmarks") { onClose(); Ui.go("bookmarks") },
                Triple(Ic.Key, "Passwords") { onClose(); Ui.go("settings/passwords") },
                Triple(Ic.Devices, "Phone and computer") { onClose(); Ui.go("settings/pair") },
                Triple(Ic.Settings, "Settings") { onClose(); Ui.go("settings") },
            )
            rows.forEach { (ic, label, go) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Haptics.tick(); go() }.padding(horizontal = 6.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ic, null, Modifier.size(19.dp), tint = C.muted); Spacer(Modifier.width(16.dp)); Text(label, color = C.text, fontSize = 15.sp)
                }
            }
        }
    }
}
