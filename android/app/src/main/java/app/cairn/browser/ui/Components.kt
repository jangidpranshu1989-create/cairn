package app.cairn.browser.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.hostOf

/**
 * Site icon. Prefers the site's own high-resolution icon (apple-touch-icon / largest declared icon, fetched and cached
 * by [app.cairn.browser.browser.Favicons]); falls back to the page's reported favicon, then to a tinted monogram.
 */
@Composable
fun Favicon(url: String, bitmap: Bitmap? = null, size: Dp = 20.dp, corner: Dp = 5.dp) {
    val host = hostOf(url)
    val hd = app.cairn.browser.browser.Favicons.icons[host.removePrefix("www.").removePrefix("m.")]
    LaunchedEffect(host) { if (host.isNotEmpty()) app.cairn.browser.browser.Favicons.get(url) }
    val small = bitmap ?: remember(url) { runCatching { BrowserDb.icon(url) }.getOrNull() }
    val bmp = hd ?: small
    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(size).clip(RoundedCornerShape(corner)), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
    else {
        val hue = remember(host) { (host.hashCode() and 0x7fffffff) % 360 }
        val tint = Color.hsl(hue.toFloat(), if (C.dark) .22f else .30f, if (C.dark) .30f else .86f)
        Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(tint), contentAlignment = Alignment.Center) {
            if (host.isEmpty()) Icon(Ic.Public, null, tint = C.muted, modifier = Modifier.size(size * 0.62f))
            else Text(host.removePrefix("www.").removePrefix("m.").take(1).uppercase(), color = C.text, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, desc: String, modifier: Modifier = Modifier, tint: Color = C.text, size: Dp = 44.dp, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).then(
        if (onLongClick != null) Modifier.combinedClickableCompat(enabled, onClick, onLongClick) else Modifier.clickable(enabled = enabled, onClick = onClick)
    ), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) tint else C.faint, modifier = Modifier.size(22.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(enabled: Boolean = true, onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick))

/** Plain screen header used by History, Bookmarks, Settings… */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.ArrowBack, "Back", onClick = onBack)
        Text(title, style = MaterialTheme.typography.titleLarge, color = C.text, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun SectionLabel(t: String, modifier: Modifier = Modifier) =
    Text(t, style = MaterialTheme.typography.labelMedium, color = C.muted, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp))

@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface), content = content)

@Composable
fun Divider() = HorizontalDivider(color = C.line, thickness = 1.dp, modifier = Modifier.padding(start = 56.dp))

@Composable
fun Row2(icon: ImageVector?, title: String, sub: String? = null, tint: Color = C.text, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (tint == C.text) C.muted else tint, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(18.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, color = C.muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun Toggle(icon: ImageVector?, title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row2(icon, title, sub, onClick = { if (enabled) onChange(!checked) }) { CairnSwitch(checked, enabled, onChange) }

@Composable
fun CairnSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = C.accentInk, checkedTrackColor = C.accent, checkedBorderColor = C.accent,
        uncheckedThumbColor = C.muted, uncheckedTrackColor = C.surface2, uncheckedBorderColor = C.line2))

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick, enabled = enabled && !busy, modifier = modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.accentInk, disabledContainerColor = C.surface3, disabledContentColor = C.faint)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.accentInk, strokeWidth = 2.dp)
        else { if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }; Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = C.text, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier = modifier.heightIn(min = 46.dp), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, C.line2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Chip(text: String, icon: ImageVector? = null, selected: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (selected) C.accent else C.surface2).border(1.dp, if (selected) C.accent else C.line, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (selected) C.accentInk else C.muted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)) }
        Text(text, color = if (selected) C.accentInk else C.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.line2, unfocusedBorderColor = C.line, focusedContainerColor = C.surface, unfocusedContainerColor = C.surface,
    cursorColor = C.agent, focusedLabelColor = C.muted, unfocusedLabelColor = C.faint, focusedTextColor = C.text, unfocusedTextColor = C.text)

@Composable
fun EmptyState(icon: ImageVector, title: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(C.surface2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = C.muted, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = C.text)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = C.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

fun Modifier.noRippleClick(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick))

// ---------------- Markdown ----------------
private val linkRe = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|\\*\\*(.+?)\\*\\*|`([^`]+)`|(https?://[^\\s)]+)|\\s?\\[(\\d{1,2})]")

fun inlineMd(s: String, linkColor: Color, codeBg: Color, cites: Set<Int>? = null): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in linkRe.findAll(s)) {
        append(s.substring(i, m.range.first))
        when {
            m.groups[1] != null -> withLink(LinkAnnotation.Url(m.groupValues[2], TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.None, fontWeight = FontWeight.Medium)))) { append(m.groupValues[1]) }
            m.groups[3] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[3]) }
            m.groups[4] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(m.groupValues[4]) }
            m.groups[5] != null -> withLink(LinkAnnotation.Url(m.groupValues[5], TextLinkStyles(SpanStyle(color = linkColor)))) { append(hostOf(m.groupValues[5]).ifEmpty { m.groupValues[5] }) }
            m.groups[6] != null -> { val n = m.groupValues[6].toInt(); if (cites == null) append(m.value) else if (n in cites) { append(' '); appendInlineContent("cite:$n", "[$n]") } }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

/** A numbered source citation chip shown inline in an answer ([1], [2]); tap opens the page. */
@Composable
fun CitePill(c: app.cairn.browser.agent.Cite, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 1.dp, vertical = 1.dp).clip(RoundedCornerShape(6.dp)).background(C.agentSoft).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text("${c.n}", color = C.agent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, lineHeight = 12.sp)
    }
}

/** Some models emit fullwidth citation marks like 【3】 or 【3†L1-L4】; normalise to [3]. */
private val FW_CITE = Regex("【(\\d{1,2})(?:†[^】]*)?】|\\[(\\d{1,2})†[^\\]]*]")
fun normCites(s: String) = if (s.contains('【') || s.contains('†')) FW_CITE.replace(s) { "[" + (it.groupValues[1].ifEmpty { it.groupValues[2] }) + "]" } else s

// ---------------- Skeleton loaders ----------------
/** Placeholder for an assistant answer while the agent reasons: heading, paragraph lines and two source cards. */
@Composable
fun AnswerSkeleton() = Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
    Box(Modifier.fillMaxWidth(.45f).height(14.dp).shimmer(5.dp))
    listOf(1f, .94f, .98f, .7f).forEach { Box(Modifier.fillMaxWidth(it).height(11.dp).shimmer(4.dp)) }
    Spacer(Modifier.height(2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(2) { Box(Modifier.width(150.dp).height(64.dp).shimmer(14.dp)) } }
}

/** List rows (favicon + two lines) for history, downloads, remote tabs and chats while they load. */
@Composable
fun SkeletonRows(count: Int = 6, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth()) {
    repeat(count) { i ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).shimmer(8.dp)); Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(if (i % 2 == 0) .82f else .64f).height(12.dp).shimmer(4.dp)); Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth(.38f).height(10.dp).shimmer(4.dp))
            }
        }
    }
}
