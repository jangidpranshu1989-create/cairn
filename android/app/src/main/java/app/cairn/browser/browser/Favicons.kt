package app.cairn.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import app.cairn.browser.data.hostOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * High-resolution site icons, straight from each site: the largest <link rel="apple-touch-icon"/"icon"> it declares,
 * then /apple-touch-icon.png, then /favicon.ico. DuckDuckGo's icon service is the last resort. Cached on disk.
 */
object Favicons {
    private lateinit var dir: File
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS).followRedirects(true).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    private val inFlight = HashSet<String>()
    private val missing = HashSet<String>()
    /** host → icon. Compose observes this map, so icons pop in as they arrive. */
    val icons = mutableStateMapOf<String, Bitmap>()

    fun init(ctx: Context) { dir = File(ctx.filesDir, "icons").apply { mkdirs() } }

    private fun keyOf(url: String) = hostOf(url).removePrefix("www.").removePrefix("m.")
    private fun file(host: String) = File(dir, host.replace(Regex("[^a-z0-9.-]"), "_") + ".png")

    /** Returns the cached icon now (or null) and fetches it in the background if needed. */
    fun get(url: String): Bitmap? {
        val host = keyOf(url); if (host.isEmpty()) return null
        icons[host]?.let { return it }
        synchronized(inFlight) { if (host in inFlight || host in missing) return null; inFlight += host }
        scope.launch {
            try {
                val f = file(host)
                val bmp = if (f.exists() && System.currentTimeMillis() - f.lastModified() < 14L * 86_400_000) BitmapFactory.decodeFile(f.path)
                    else gate.withPermit { fetch(hostOf(url).ifEmpty { host }) }?.also { save(f, it) }
                if (bmp != null) icons[host] = bmp else synchronized(inFlight) { missing += host }
            } catch (_: Exception) { synchronized(inFlight) { missing += host } }
            finally { synchronized(inFlight) { inFlight -= host } }
        }
        return null
    }

    /** Blocking fetch (tests and warm-up): returns and caches the icon for [url]'s site. */
    fun fetchNow(url: String): Bitmap? = keyOf(url).takeIf { it.isNotEmpty() }?.let { h -> icons[h] ?: runCatching { fetch(hostOf(url)) }.getOrNull()?.also { icons[h] = it } }

    /** A page reported its own icon (WebChromeClient.onReceivedIcon): keep it only if it is better than what we have. */
    fun offer(url: String, bmp: Bitmap) {
        val host = keyOf(url); if (host.isEmpty()) return
        val have = icons[host]
        if (have == null || bmp.width > have.width) { icons[host] = bmp; scope.launch { runCatching { save(file(host), bmp) } } }
    }

    private fun save(f: File, b: Bitmap) { f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    private fun bytes(url: String, max: Int): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept", "image/png,image/x-icon,image/webp,image/jpeg,*/*;q=0.5").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.byteStream()?.use { s -> val b = s.upTo(max); b.takeIf { it.isNotEmpty() } }
        }
    }.getOrNull()

    private fun decode(b: ByteArray?): Bitmap? = b?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }?.takeIf { it.width >= 16 }

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun fetch(host: String): Bitmap? {
        val base = "https://$host"
        val html = runCatching {
            http.newCall(Request.Builder().url("$base/").header("User-Agent", UA).header("Accept", "text/html").build()).execute().use { r ->
                if (!r.isSuccessful) "" else r.body?.byteStream()?.use { String(it.upTo(256 * 1024)) }.orEmpty()
            }
        }.getOrDefault("")
        val finalBase = base
        data class Cand(val href: String, val score: Int)
        val cands = mutableListOf<Cand>()
        Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val tag = m.value
            val rel = Regex("rel\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: return@forEach
            if (!rel.contains("icon")) return@forEach
            val href = Regex("href\\s*=\\s*[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: return@forEach
            if (href.endsWith(".svg") || href.startsWith("data:") || rel.contains("mask")) return@forEach
            val size = Regex("sizes\\s*=\\s*[\"'](\\d+)x", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull()
            val score = (if (rel.contains("apple-touch")) 180 else 0) + (size ?: if (rel.contains("apple-touch")) 0 else 32).coerceAtMost(256)
            cands += Cand(runCatching { java.net.URL(java.net.URL("$finalBase/"), href.replace("&amp;", "&")).toString() }.getOrDefault(href), score)
        }
        val ordered = cands.sortedByDescending { it.score }.map { it.href } +
            listOf("$base/apple-touch-icon.png", "$base/apple-touch-icon-precomposed.png", "$base/favicon.ico", "https://icons.duckduckgo.com/ip3/$host.ico")
        var fallback: Bitmap? = null
        for (u in ordered.distinct()) {
            val b = decode(bytes(u, 512 * 1024)) ?: continue
            if (b.width >= 96) return b
            if (fallback == null || b.width > fallback.width) fallback = b
        }
        return fallback
    }
}

private fun java.io.InputStream.upTo(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
    while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n <= 0) break; out.write(buf, 0, n); total += n }
    return out.toByteArray()
}
