package app.cairn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.agent.Cite
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rich Markdown for assistant replies: headings, nested bullets, numbered lists, scrollable comparison tables with the best
 * pick highlighted, `compare` bento spec cards, syntax-highlighted code with one-tap copy, callouts, quotes and rules.
 * Parsing is pure and remembered per text, so the typewriter reveal stays cheap.
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
    data class Bullet(val indent: Int, val text: String) : MdBlock()
    data class Num(val indent: Int, val marker: String, val text: String) : MdBlock()
    data class Table(val head: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Code(val lang: String, val code: String, val closed: Boolean) : MdBlock()
    data class Compare(val items: List<JSONObject>, val closed: Boolean) : MdBlock()
    data class Callout(val kind: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    object Rule : MdBlock()
}

private val CALLOUT = Regex("^(?:\\*\\*)?(tip|note|warning|caution|important|info|pro tip)\\b(?:\\*\\*)?\\s*:?\\s*(?:\\*\\*)?\\s*:?\\s*", RegexOption.IGNORE_CASE)
private val GH_CALLOUT = Regex("^\\[!(NOTE|TIP|WARNING|CAUTION|IMPORTANT)]\\s*", RegexOption.IGNORE_CASE)
private fun cells(l: String) = l.trim().trim('|').split("|").map { it.trim() }

fun parseMd(md: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = md.replace("\r", "").lines()
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim()); para.clear() }
    while (i < lines.size) {
        val raw = lines[i].trimEnd(); val t = raw.trimStart()
        val indent = ((raw.length - t.length) / 2).coerceAtMost(3)
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim().lowercase()
                val body = StringBuilder(); i++
                var closed = false
                while (i < lines.size) { if (lines[i].trimStart().startsWith("```")) { closed = true; break }; body.append(lines[i]).append('\n'); i++ }
                if (lang == "compare" || lang == "cards" || lang == "products") {
                    val arr = runCatching { JSONArray(body.toString().trim()) }.getOrNull()
                    if (arr != null) out += MdBlock.Compare((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }, closed)
                    else if (!closed) out += MdBlock.Compare(emptyList(), false)
                } else out += MdBlock.Code(lang, body.toString().trimEnd('\n'), closed)
            }
            t.isBlank() -> flush()
            Regex("^#{1,4}\\s").containsMatchIn(t) -> { flush(); out += MdBlock.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#', ' ')) }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out += MdBlock.Rule }
            t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}").containsMatchIn(lines[i + 1].trim()) -> {
                flush()
                val head = cells(t); i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                out += MdBlock.Table(head, rows); continue
            }
            t.startsWith(">") -> {
                flush()
                val q = StringBuilder(t.removePrefix(">").trim())
                while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) { i++; q.append('\n').append(lines[i].trimStart().removePrefix(">").trim()) }
                val s = q.toString().trim()
                val gh = GH_CALLOUT.find(s); val cm = CALLOUT.find(s)
                out += when {
                    gh != null -> MdBlock.Callout(gh.groupValues[1].lowercase(), s.substring(gh.range.last + 1).trim())
                    cm != null -> MdBlock.Callout(cm.groupValues[1].lowercase(), s.substring(cm.range.last + 1).trim())
                    else -> MdBlock.Quote(s)
                }
            }
            Regex("^[-*•+]\\s+").containsMatchIn(t) -> { flush(); out += MdBlock.Bullet(indent, t.replaceFirst(Regex("^[-*•+]\\s+"), "")) }
            Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> { flush(); out += MdBlock.Num(indent, t.substringBefore(' '), t.substringAfter(' ')) }
            CALLOUT.containsMatchIn(t) && t.startsWith("**") -> { flush(); val m = CALLOUT.find(t)!!; out += MdBlock.Callout(m.groupValues[1].lowercase(), t.substring(m.range.last + 1).trim()) }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

@Composable
fun Markdown(md0: String, modifier: Modifier = Modifier, color: Color = C.text, cites: List<Cite>? = null, onCite: (Cite) -> Unit = {}, onLink: ((String) -> Unit)? = null) {
    val md = remember(md0) { normCites(md0) }
    val blocks = remember(md) { parseMd(md) }
    val link = C.agent; val codeBg = C.surface3
    val citeSet = cites?.map { it.n }?.toSet()
    val inline = remember(cites) { cites?.associate { c ->
        "cite:${c.n}" to InlineTextContent(Placeholder((if (c.n >= 10) 22f else 17f).sp, 17.sp, PlaceholderVerticalAlign.TextCenter)) { CitePill(c) { onCite(c) } }
    } ?: emptyMap() }
    val body = MaterialTheme.typography.bodyLarge
    @Composable fun T(s: String, style: TextStyle = body, c: Color = color, mod: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
        androidx.compose.material3.Text(inlineMd(s, link, codeBg, citeSet), modifier = mod, style = style, color = c, inlineContent = inline, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { idx, b ->
            when (b) {
                is MdBlock.Heading -> T(b.text, if (b.level <= 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, mod = Modifier.padding(top = if (idx == 0) 0.dp else 8.dp))
                is MdBlock.Para -> T(b.text)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    Box(Modifier.padding(top = 9.dp, end = 10.dp).size(if (b.indent == 0) 5.dp else 4.dp).clip(RoundedCornerShape(50)).background(if (b.indent == 0) C.agent else C.muted))
                    T(b.text, mod = Modifier.weight(1f))
                }
                is MdBlock.Num -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    androidx.compose.material3.Text(b.marker.trimEnd('.', ')'), color = C.agent, style = body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.widthIn(min = 22.dp))
                    T(b.text, mod = Modifier.weight(1f))
                }
                is MdBlock.Table -> MdTable(b) { s, st, c, m -> T(s, st, c, m) }
                is MdBlock.Code -> CodeBlock(b)
                is MdBlock.Compare -> CompareGrid(b, onLink)
                is MdBlock.Callout -> Callout(b.kind) { T(b.text, MaterialTheme.typography.bodyMedium, mod = Modifier.weight(1f)) }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(C.line2)); Spacer(Modifier.width(12.dp))
                    T(b.text, c = C.muted)
                }
                MdBlock.Rule -> HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

private val BEST = Regex("⭐|★|✅|🏆|\\bbest( pick| choice| overall| value)?\\b|\\brecommended\\b", RegexOption.IGNORE_CASE)

@Composable
private fun MdTable(b: MdBlock.Table, cell: @Composable (String, TextStyle, Color, Modifier) -> Unit) {
    val cols = maxOf(b.head.size, b.rows.maxOfOrNull { it.size } ?: 0)
    val widths = (0 until cols).map { c ->
        val longest = (listOf(b.head.getOrElse(c) { "" }) + b.rows.map { it.getOrElse(c) { "" } }).maxOf { it.replace(Regex("\\[\\d+]|\\*\\*"), "").length }
        (longest * 7.2f + 26f).coerceIn(84f, 230f).dp
    }
    val bestRow = b.rows.indexOfFirst { r -> r.any { BEST.containsMatchIn(it) } }
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, C.line2, shape).background(C.surface)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(C.surface2)) {
                (0 until cols).forEach { c -> Box(Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    cell(b.head.getOrElse(c) { "" }, MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), C.text, Modifier)
                } }
            }
            b.rows.forEachIndexed { r, row ->
                HorizontalDivider(color = C.line, modifier = Modifier.width(widths.fold(0.dp) { a, w -> a + w }))
                Row(Modifier.background(if (r == bestRow) C.agentSoft else Color.Transparent)) {
                    (0 until cols).forEach { c -> Box(Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        cell(row.getOrElse(c) { "" }, MaterialTheme.typography.bodyMedium.copy(fontWeight = if (c == 0) FontWeight.Medium else FontWeight.Normal), C.text, Modifier)
                    } }
                }
            }
        }
    }
}

// ---------------- Code ----------------
private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while", "do", "in", "is", "as", "import", "package",
    "private", "public", "protected", "internal", "override", "suspend", "data", "sealed", "enum", "true", "false", "null", "this", "super", "new",
    "function", "const", "let", "async", "await", "export", "default", "from", "def", "self", "None", "True", "False", "lambda", "try", "catch",
    "except", "finally", "throw", "raise", "with", "yield", "static", "void", "int", "string", "bool", "boolean", "float", "double", "long", "char",
    "struct", "impl", "pub", "mut", "match", "use", "mod", "fn", "go", "func", "type", "select", "from", "where", "insert", "update", "delete", "create", "table",
    "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "JOIN", "ON", "AND", "OR", "NOT", "echo", "then", "fi", "elif", "esac",
)
private val TOKEN = Regex("(//[^\\n]*|#(?![!{])[^\\n]*|/\\*[\\s\\S]*?\\*/|--[^\\n]*)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`)|(\\b\\d+(?:\\.\\d+)?\\b)|(\\b[A-Za-z_][A-Za-z0-9_]*\\b)(\\s*\\()?")

fun highlight(code: String, lang: String, kw: Color, str: Color, num: Color, com: Color, fn: Color): AnnotatedString = buildAnnotatedString {
    val hashComments = lang in setOf("python", "py", "bash", "sh", "shell", "ruby", "rb", "yaml", "yml", "toml", "r", "") || lang.isBlank()
    var i = 0
    for (m in TOKEN.findAll(code)) {
        append(code.substring(i, m.range.first))
        val v = m.value
        when {
            m.groups[1] != null -> if (v.startsWith("#") && !hashComments || v.startsWith("--") && lang !in setOf("sql", "lua", "haskell")) append(v) else withStyle(SpanStyle(color = com)) { append(v) }
            m.groups[2] != null -> withStyle(SpanStyle(color = str)) { append(v) }
            m.groups[3] != null -> withStyle(SpanStyle(color = num)) { append(v) }
            m.groups[4] != null -> {
                val w = m.groupValues[4]
                when {
                    w in KEYWORDS -> withStyle(SpanStyle(color = kw, fontWeight = FontWeight.Medium)) { append(w) }
                    m.groups[5] != null -> withStyle(SpanStyle(color = fn)) { append(w) }
                    w.firstOrNull()?.isUpperCase() == true -> withStyle(SpanStyle(color = fn)) { append(w) }
                    else -> append(w)
                }
                m.groups[5]?.let { append(it.value) }
            }
        }
        i = m.range.last + 1
    }
    append(code.substring(i))
}

@Composable
private fun CodeBlock(b: MdBlock.Code) {
    val ctx = LocalContext.current
    val dark = C.dark
    val kw = if (dark) Color(0xFFC792EA) else Color(0xFF7A3E9D); val str = if (dark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
    val num = if (dark) Color(0xFFF2B880) else Color(0xFFB55A00); val com = C.faint; val fn = if (dark) Color(0xFF82AAFF) else Color(0xFF1F5FBF)
    val hl = remember(b.code, b.lang, dark) { if (b.code.length > 20_000) AnnotatedString(b.code) else highlight(b.code, b.lang, kw, str, num, com, fn) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1600); copied = false } }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(if (dark) Color(0xFF151517) else Color(0xFFF4F3F0)).border(1.dp, C.line, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp).height(38.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Code, null, Modifier.size(14.dp), tint = C.faint); Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text(b.lang.ifBlank { "code" }, color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { Haptics.tick(); copyText(ctx, "Code", b.code); copied = true }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (copied) Ic.Check else Ic.Copy, null, Modifier.size(14.dp), tint = if (copied) C.ok else C.muted); Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Text(if (copied) "Copied" else "Copy", color = if (copied) C.ok else C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        HorizontalDivider(color = C.line)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp)) {
            androidx.compose.material3.Text(hl, color = C.text, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp, softWrap = false)
        }
    }
}

// ---------------- Callouts ----------------
@Composable
private fun Callout(kind: String, content: @Composable RowScope.() -> Unit) {
    val (icon, tint) = when (kind) {
        "warning", "caution", "important" -> Ic.WarningAmber to C.warn
        "tip", "pro tip" -> Ic.Lightbulb to C.agent
        else -> Ic.Info to C.muted
    }
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = .1f)).border(1.dp, tint.copy(alpha = .25f), shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = tint); Spacer(Modifier.width(10.dp))
        content()
    }
}

// ---------------- Bento comparison / spec cards ----------------
@Composable
private fun CompareGrid(b: MdBlock.Compare, onLink: ((String) -> Unit)?) {
    if (b.items.isEmpty()) {   // still streaming: skeleton cards instead of half-parsed JSON
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(2) { Box(Modifier.weight(1f).height(150.dp).shimmer(16.dp)) } }
        return
    }
    val list = b.items.take(4)
    val best = list.indexOfFirst { it.optBoolean("best") }.takeIf { it >= 0 } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.chunked(2).forEachIndexed { r, row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, o -> SpecCard(o, r * 2 + c == best && list.size > 1, Modifier.weight(1f).fillMaxHeight(), onLink) }
                if (row.size == 1 && list.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun JSONObject.strs(k: String): List<String> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
    ?: optString(k).takeIf { it.isNotBlank() }?.split(Regex("[;,]"))?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

@Composable
private fun SpecCard(o: JSONObject, best: Boolean, modifier: Modifier, onLink: ((String) -> Unit)?) {
    val shape = RoundedCornerShape(16.dp)
    val url = o.optString("url").takeIf { it.startsWith("http") }
    Column(modifier.clip(shape).background(if (best) C.agentSoft else C.surface).border(if (best) 1.5.dp else 1.dp, if (best) C.agent.copy(alpha = .6f) else C.line2, shape)
        .then(if (url != null && onLink != null) Modifier.clickable { Haptics.tick(); onLink(url) } else Modifier).padding(12.dp)) {
        if (best) Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(50)).background(C.agent).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Star, null, Modifier.size(10.dp), tint = C.accentInk); Spacer(Modifier.width(4.dp))
            androidx.compose.material3.Text("Best pick", color = C.accentInk, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        }
        androidx.compose.material3.Text(o.optString("name").ifBlank { o.optString("title") }, color = C.text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        o.optString("price").takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.Text(it, color = C.text, fontSize = 18.sp, fontFamily = InterDisplay, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, maxLines = 1)
        }
        val rating = o.optString("rating").takeIf { it.isNotBlank() && it != "null" }
        val store = o.optString("store").takeIf { it.isNotBlank() }
        if (rating != null || store != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rating != null) { Icon(Ic.Star, null, Modifier.size(11.dp), tint = C.warn); Spacer(Modifier.width(3.dp)); androidx.compose.material3.Text(rating, color = C.muted, fontSize = 12.sp) }
            if (store != null) androidx.compose.material3.Text((if (rating != null) " · " else "") + store, color = C.faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val specs = o.strs("specs")
        if (specs.isNotEmpty()) { Spacer(Modifier.height(6.dp)); specs.take(4).forEach { androidx.compose.material3.Text(it, color = C.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        val pros = o.strs("pros"); val cons = o.strs("cons")
        if (pros.isNotEmpty() || cons.isNotEmpty()) Spacer(Modifier.height(8.dp))
        pros.take(3).forEach { ProCon(true, it) }
        cons.take(2).forEach { ProCon(false, it) }
    }
}

@Composable
private fun ProCon(pro: Boolean, t: String) = Row(Modifier.padding(vertical = 1.5.dp)) {
    Icon(if (pro) Ic.Plus else Ic.Minus, null, Modifier.padding(top = 2.dp).size(12.dp), tint = if (pro) C.ok else C.err); Spacer(Modifier.width(5.dp))
    androidx.compose.material3.Text(t, color = C.text, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
