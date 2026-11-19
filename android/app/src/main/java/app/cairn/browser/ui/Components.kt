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

/** A source citation shown inline in an answer: site name in a soft capsule; tap opens the page. */
@Composable
fun CitePill(c: app.cairn.browser.agent.Cite, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 1.dp).clip(RoundedCornerShape(50)).background(C.surface3).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(c.label, color = C.muted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, lineHeight = 12.sp)
    }
}

/** Some models emit fullwidth citation marks like 【3】 or 【3†L1-L4】; normalise to [3]. */
private val FW_CITE = Regex("【(\\d{1,2})(?:†[^】]*)?】|\\[(\\d{1,2})†[^\\]]*]")
fun normCites(s: String) = if (s.contains('【') || s.contains('†')) FW_CITE.replace(s) { "[" + (it.groupValues[1].ifEmpty { it.groupValues[2] }) + "]" } else s

/** Lightweight Markdown renderer for assistant replies: headings, bullets, numbered lists, bold, code and links. */
@Composable
fun Markdown(md0: String, modifier: Modifier = Modifier, color: Color = C.text, cites: List<app.cairn.browser.agent.Cite>? = null, onCite: (app.cairn.browser.agent.Cite) -> Unit = {}) {
    val md = remember(md0) { normCites(md0) }
    val link = C.agent; val codeBg = C.surface3
    val citeSet = cites?.map { it.n }?.toSet()
    val inline = remember(cites) { cites?.associate { c ->
        "cite:${c.n}" to InlineTextContent(Placeholder((c.label.length * 6.3f + 17f).sp, 19.sp, PlaceholderVerticalAlign.TextCenter)) { CitePill(c) { onCite(c) } }
    } ?: emptyMap() }
    @Composable fun Text(text: AnnotatedString, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) =
        androidx.compose.material3.Text(text, modifier = modifier, style = style, color = color, inlineContent = inline)
    fun inlineMd(s: String, linkColor: Color, codeBg: Color) = inlineMd(s, linkColor, codeBg, citeSet)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (raw in md.trim().lines()) {
            val l = raw.trimEnd()
            if (l.isBlank()) continue
            val t = l.trimStart()
            val indent = ((l.length - t.length) / 2).coerceAtMost(3)
            when {
                t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ") ->
                    Text(inlineMd(t.trimStart('#', ' '), link, codeBg), style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(top = 6.dp))
                Regex("^[-*•]\\s+").containsMatchIn(t) -> Row(Modifier.padding(start = (indent * 14).dp)) {
                    Text("•", color = C.muted, modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(inlineMd(t.replaceFirst(Regex("^[-*•]\\s+"), ""), link, codeBg), style = MaterialTheme.typography.bodyLarge, color = color)
                }
                Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> Row(Modifier.padding(start = (indent * 14).dp)) {
                    Text(t.substringBefore(' '), color = C.muted, modifier = Modifier.widthIn(min = 22.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(inlineMd(t.substringAfter(' '), link, codeBg), style = MaterialTheme.typography.bodyLarge, color = color)
                }
                t.startsWith("|") && t.endsWith("|") -> if (!Regex("^\\|[\\s:|-]+\\|$").matches(t))
                    Text(inlineMd(t.trim('|').split("|").joinToString("  ·  ") { it.trim() }, link, codeBg), style = MaterialTheme.typography.bodyMedium, color = color)
                t.startsWith("> ") -> Text(inlineMd(t.drop(2), link, codeBg), style = MaterialTheme.typography.bodyLarge, color = C.muted, modifier = Modifier.padding(start = 10.dp))
                else -> Text(inlineMd(t, link, codeBg), style = MaterialTheme.typography.bodyLarge, color = color)
            }
        }
    }
}
