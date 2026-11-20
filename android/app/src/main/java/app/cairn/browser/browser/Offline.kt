package app.cairn.browser.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Pages saved for offline reading (WebView web archives, kept privately inside the app). */
object Offline {
    data class Page(val id: String, val title: String, val url: String, val at: Long, val size: Long, val file: File)
    val version = mutableIntStateOf(0)
    private fun dir(ctx: Context) = File(ctx.filesDir, "offline").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "index.json")

    fun list(ctx: Context): List<Page> = runCatching {
        val a = JSONArray(index(ctx).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val f = File(dir(ctx), o.getString("id") + ".mht")
            if (!f.exists()) null else Page(o.getString("id"), o.optString("title"), o.optString("url"), o.optLong("at"), f.length(), f)
        }.sortedByDescending { it.at }
    }.getOrDefault(emptyList())

    private fun write(ctx: Context, pages: List<Page>) {
        val a = JSONArray(); pages.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("at", it.at)) }
        index(ctx).writeText(a.toString()); version.intValue++
    }

    fun save(ctx: Context, web: WebView, title: String, url: String, done: (Boolean) -> Unit) {
        val id = System.currentTimeMillis().toString(36)
        val f = File(dir(ctx), "$id.mht")
        web.saveWebArchive(f.absolutePath, false) { path ->
            val ok = path != null && File(path).length() > 0
            if (ok) write(ctx, list(ctx).filter { it.url != url } + Page(id, title.ifBlank { url }, url, System.currentTimeMillis(), f.length(), f))
            done(ok)
        }
    }

    fun delete(ctx: Context, id: String) { val all = list(ctx); all.find { it.id == id }?.file?.delete(); write(ctx, all.filter { it.id != id }) }
}
