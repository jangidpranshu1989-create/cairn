package app.cairn.browser.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved conversation in the list (ChatGPT-style history). */
data class ChatMeta(val id: String, val title: String, val updated: Long, val preview: String, val count: Int, val pinned: Boolean = false)

/**
 * Chat history, stored only on this phone (app-private storage, excluded from backups). Every conversation is a
 * small JSON file of what you saw — your messages, Cairn's answers with sources, summaries, mind maps and circled
 * images — so old chats reopen exactly as they were and can be continued. Private-tab chats are never saved.
 */
object Chats {
    val list = mutableStateListOf<ChatMeta>()
    private lateinit var dir: File
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        dir = File(ctx.filesDir, "chats").apply { mkdirs() }
        loaded = true
        reload()
    }

    private fun indexFile() = File(dir, "index.json")

    fun reload() {
        if (!loaded) return
        val a = runCatching { JSONArray(indexFile().readText()) }.getOrDefault(JSONArray())
        val out = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            ChatMeta(it.optString("id"), it.optString("title"), it.optLong("updated"), it.optString("preview"), it.optInt("count"), it.optBoolean("pinned"))
        } }.filter { File(dir, "${it.id}.json").exists() }
        list.clear(); list.addAll(out.sortedWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updated }))
    }

    private fun writeIndex() {
        val a = JSONArray()
        list.forEach { m -> a.put(JSONObject().put("id", m.id).put("title", m.title).put("updated", m.updated).put("preview", m.preview).put("count", m.count).put("pinned", m.pinned)) }
        runCatching { File(dir, "index.json.tmp").apply { writeText(a.toString()) }.renameTo(indexFile()) }
    }

    fun newId() = "c" + System.currentTimeMillis().toString(36) + java.util.UUID.randomUUID().toString().take(6)

    // ---------------- Serialising the visible conversation ----------------
    private fun citesJson(c: List<Cite>) = JSONArray().apply { c.forEach { put(JSONObject().put("n", it.n).put("t", it.title).put("u", it.url).put("s", it.snippet)) } }
    private fun citesOf(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i -> a!!.optJSONObject(i)?.let { Cite(it.optInt("n"), it.optString("t"), it.optString("u"), it.optString("s")) } }

    private fun saveImage(id: String, key: String, b: Bitmap): String {
        val f = File(dir, "${id}_${key.take(8)}.jpg")
        if (!f.exists()) runCatching {
            val s = minOf(1f, 720f / maxOf(b.width, b.height))
            val x = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
            f.outputStream().use { x.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        return f.name
    }
    private fun loadImage(name: String): Bitmap? = runCatching { BitmapFactory.decodeFile(File(dir, name).path) }.getOrNull()

    private fun encode(id: String, items: List<Item>): JSONArray {
        val a = JSONArray()
        for (it in items) when (it) {
            is UserMsg -> a.put(JSONObject().put("k", "user").put("text", it.text).put("page", it.page ?: "").put("mentions", JSONArray(it.mentions))
                .put("files", JSONArray().apply { it.attachments.forEach { f ->
                    put(JSONObject().put("name", f.name).put("mime", f.mime).apply { f.bitmap?.let { b -> put("img", saveImage(id, f.key, b)) } })
                } }))
            is AiMsg -> a.put(JSONObject().put("k", "ai").put("md", it.markdown).put("err", it.error).put("prompt", it.prompt).put("src", citesJson(it.sources))
                .put("fu", JSONArray(it.followUps.toList())))
            is SummaryItem -> a.put(JSONObject().put("k", "summary").put("title", it.title).put("url", it.url).put("md", it.markdown))
            is MindmapItem -> a.put(JSONObject().put("k", "mindmap").put("title", it.title).put("url", it.url).put("tree", it.tree))
            is LassoItem -> a.put(JSONObject().put("k", "lasso").put("text", it.text).put("img", saveImage(id, it.key, it.image)))
            is NoticeItem -> a.put(JSONObject().put("k", "notice").put("icon", it.icon).put("text", it.text))
            is SourcesItem -> a.put(JSONObject().put("k", "sources").put("s", JSONArray().apply { it.sources.forEach { (t, u, x) -> put(JSONArray(listOf(t, u, x))) } }))
            is ProductsItem -> if (it.items.isNotEmpty()) a.put(JSONObject().put("k", "ai").put("md",
                (it.headline?.let { h -> "**$h**\n\n" } ?: "") + it.items.take(6).joinToString("\n") { p -> "- [${p.title.take(80)}](${p.url}) — ₹${"%,.0f".format(p.price)} · ${p.store}" }))
            else -> {}
        }
        return a
    }

    private fun decode(a: JSONArray): List<Item> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        when (o.optString("k")) {
            "user" -> {
                val files = o.optJSONArray("files")?.let { f -> (0 until f.length()).mapNotNull { j -> f.optJSONObject(j) } }.orEmpty().map { f ->
                    Attachment(f.optString("name"), f.optString("mime").ifBlank { if (f.has("img")) "image/jpeg" else "application/octet-stream" }, null, f.optString("img").takeIf { it.isNotBlank() }?.let { loadImage(it) })
                        .apply { ready = true }
                }
                UserMsg(o.optString("text"), o.optString("page").ifBlank { null },
                    o.optJSONArray("mentions")?.let { m -> (0 until m.length()).map { m.optString(it) } }.orEmpty(), files)
            }
            "ai" -> AiMsg(o.optString("md"), error = o.optBoolean("err"), sources = citesOf(o.optJSONArray("src")), prompt = o.optString("prompt")).apply {
                revealed = true
                o.optJSONArray("fu")?.let { f -> (0 until f.length()).forEach { followUps.add(f.optString(it)) } }
            }
            "summary" -> SummaryItem(o.optString("title"), o.optString("url"), o.optString("md"))
            "mindmap" -> o.optJSONObject("tree")?.let { MindmapItem(o.optString("title"), o.optString("url"), it) }
            "lasso" -> loadImage(o.optString("img"))?.let { LassoItem(it, o.optString("text")) }
            "notice" -> NoticeItem(o.optString("icon"), o.optString("text"))
            "sources" -> o.optJSONArray("s")?.let { s -> SourcesItem((0 until s.length()).mapNotNull { j -> s.optJSONArray(j)?.let { t -> Triple(t.optString(0), t.optString(1), t.optString(2)) } }) }
            else -> null
        }
    }

    /**
     * Rebuilds a compact model context from what was said, so an old chat can be continued. Tool traces are not kept
     * (they're large and tied to pages that have changed); answers, sources and summaries are.
     */
    fun contextOf(items: List<Item>): JSONArray {
        val m = JSONArray()
        for (it in items) when (it) {
            is UserMsg -> m.put(JSONObject().put("role", "user").put("content", it.text + if (it.attachments.isNotEmpty()) "\n[Attached: ${it.attachments.joinToString { a -> a.name }}]" else ""))
            is LassoItem -> m.put(JSONObject().put("role", "user").put("content", "[I circled part of a page] ${it.text}"))
            is AiMsg -> if (!it.error) m.put(JSONObject().put("role", "assistant").put("content", it.markdown.take(6000) +
                if (it.sources.isNotEmpty()) "\n\nSources: " + it.sources.joinToString("; ") { c -> "[${c.n}] ${c.title} ${c.url}" } else ""))
            is SummaryItem -> m.put(JSONObject().put("role", "assistant").put("content", "Summary of ${it.title} (${it.url}):\n${it.markdown.take(5000)}"))
            else -> {}
        }
        return m
    }

    fun save(id: String, items: List<Item>, title: String?) {
        if (!loaded || items.isEmpty()) return
        val firstUser = items.firstNotNullOfOrNull { (it as? UserMsg)?.text ?: (it as? LassoItem)?.text }.orEmpty()
        val lastAi = items.lastOrNull { it is AiMsg && !it.error } as? AiMsg
        val old = list.firstOrNull { it.id == id }
        val t = title ?: old?.title ?: defaultTitle(firstUser)
        val o = JSONObject().put("id", id).put("title", t).put("items", encode(id, items)).put("v", 1)
        runCatching { File(dir, "$id.json.tmp").apply { writeText(o.toString()) }.renameTo(File(dir, "$id.json")) }
        val meta = ChatMeta(id, t, System.currentTimeMillis(), (lastAi?.markdown ?: firstUser).replace(Regex("[*#`>\\[\\]]|\\(https?://[^)]+\\)"), "").replace(Regex("\\s+"), " ").take(120),
            items.count { it is UserMsg || it is AiMsg || it is LassoItem }, old?.pinned ?: false)
        list.removeAll { it.id == id }; list.add(0, meta)
        list.sortWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updated })
        writeIndex()
    }

    fun load(id: String): Pair<String, List<Item>>? = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText())
        o.optString("title") to decode(o.optJSONArray("items") ?: JSONArray())
    }.getOrNull()

    fun rename(id: String, title: String) {
        val i = list.indexOfFirst { it.id == id }; if (i < 0) return
        list[i] = list[i].copy(title = title.trim().take(80).ifBlank { list[i].title })
        runCatching { val f = File(dir, "$id.json"); val o = JSONObject(f.readText()).put("title", list[i].title); f.writeText(o.toString()) }
        writeIndex()
    }

    fun pin(id: String) {
        val i = list.indexOfFirst { it.id == id }; if (i < 0) return
        list[i] = list[i].copy(pinned = !list[i].pinned)
        list.sortWith(compareByDescending<ChatMeta> { it.pinned }.thenByDescending { it.updated }); writeIndex()
    }

    fun delete(id: String) {
        list.removeAll { it.id == id }
        dir.listFiles()?.filter { it.name.startsWith(id) }?.forEach { it.delete() }
        writeIndex()
    }

    fun clearAll() { list.clear(); dir.listFiles()?.forEach { it.delete() }; writeIndex() }

    fun search(q: String): List<ChatMeta> {
        val s = q.trim(); if (s.isEmpty()) return list.toList()
        return list.filter { m -> m.title.contains(s, true) || m.preview.contains(s, true) ||
            runCatching { File(dir, "${m.id}.json").readText().contains(s, true) }.getOrDefault(false) }
    }

    fun defaultTitle(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim().trimEnd('?', '.', '!')
        if (t.isEmpty()) return "New chat"
        return if (t.length <= 48) t.replaceFirstChar { it.uppercase() } else t.take(46).substringBeforeLast(' ').ifBlank { t.take(46) } + "…"
    }

    /** "Today", "Yesterday", "Previous 7 days", "Previous 30 days", then month names — like ChatGPT. */
    fun group(ts: Long): String {
        val c = java.util.Calendar.getInstance(); c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        val today = c.timeInMillis; val day = 86_400_000L
        return when {
            ts >= today -> "Today"
            ts >= today - day -> "Yesterday"
            ts >= today - 7 * day -> "Previous 7 days"
            ts >= today - 30 * day -> "Previous 30 days"
            else -> java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
        }
    }
}
