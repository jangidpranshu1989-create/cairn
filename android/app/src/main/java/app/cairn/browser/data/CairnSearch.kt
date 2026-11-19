package app.cairn.browser.data

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.cairn.browser.R
import app.cairn.browser.agent.Lang
import app.cairn.browser.agent.Llm
import app.cairn.browser.agent.NoAiException
import app.cairn.browser.browser.Favicons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/** One web result. [n] is its citation number in answers. */
data class WebResult(val n: Int, val title: String, val url: String, val snippet: String) {
    val host get() = hostOf(url)
    fun json() = JSONObject().put("n", n).put("title", title).put("url", url).put("snippet", snippet).put("host", host).put("site", Sites.label(host).replaceFirstChar { it.uppercase() })
}

/**
 * Cairn Search: Cairn's own results page, served from inside the app at https://search.cairn/ (the host never reaches the network).
 * The page shows an AI answer streamed with numbered, linked sources, the web results and related searches —
 * all in Cairn's design. Results come from an independent index fetched on-device; nothing is branded by anyone else.
 */
object CairnSearch {
    const val HOST = "search.cairn"
    private lateinit var app: Context
    fun init(ctx: Context) { app = ctx.applicationContext }

    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val cache = object : LinkedHashMap<String, List<WebResult>>(32, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<WebResult>>?) = size > 30 }

    fun url(q: String) = "https://$HOST/?q=" + Uri.encode(q.trim())

    // ---------------------------------------------------------------- results
    fun results(q: String): List<WebResult> {
        val key = q.trim().lowercase()
        synchronized(cache) { cache[key] }?.let { return it }
        // Two independent indexes in parallel: DuckDuckGo first, Bing instantly if DDG is slow or blocked on this network.
        val bingF = java.util.concurrent.CompletableFuture.supplyAsync { runCatching { bing(q) }.getOrDefault(emptyList()) }
        val r = runCatching { ddgHtml(q) }.getOrDefault(emptyList())
            .ifEmpty { runCatching { bingF.get(9, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
            .ifEmpty { runCatching { ddgLite(q) }.getOrDefault(emptyList()) }
        if (r.isNotEmpty()) synchronized(cache) { cache[key] = r }
        return r
    }

    private fun strip(s: String) = android.text.Html.fromHtml(s, 0).toString().replace(Regex("\\s+"), " ").trim()
    private fun clean(u0: String): String? {
        var u = u0.replace("&amp;", "&")
        Regex("uddg=([^&]+)").find(u)?.let { u = Uri.decode(it.groupValues[1]) }
        if (u.startsWith("//")) u = "https:$u"
        if (!u.startsWith("http") || u.contains("duckduckgo.com/y.js") || u.contains("duckduckgo.com/l/?") && !u.contains("uddg")) return null
        return u
    }

    private fun get(url: String): String = http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", acceptLanguage()).build()).execute().use { it.body?.string().orEmpty() }
    private fun acceptLanguage() = java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8"

    private fun ddgHtml(q: String): List<WebResult> {
        val html = get("https://html.duckduckgo.com/html/?q=" + Uri.encode(q) + "&kl=" + region())
        val out = ArrayList<WebResult>()
        Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?(?:<a[^>]+class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>|</div>)").findAll(html).forEach { m ->
            val u = clean(m.groupValues[1]) ?: return@forEach
            if (out.any { it.url == u }) return@forEach
            out += WebResult(out.size + 1, strip(m.groupValues[2]), u, strip(m.groupValues[3]).take(300))
        }
        return out.take(10)
    }

    private fun ddgLite(q: String): List<WebResult> {
        val html = get("https://lite.duckduckgo.com/lite/?q=" + Uri.encode(q) + "&kl=" + region())
        val links = Regex("<a[^>]+href=\"([^\"]+)\"[^>]+class=['\"]result-link['\"][^>]*>([\\s\\S]*?)</a>").findAll(html).toList()
        val snippets = Regex("<td class=['\"]result-snippet['\"][^>]*>([\\s\\S]*?)</td>").findAll(html).map { strip(it.groupValues[1]) }.toList()
        val out = ArrayList<WebResult>()
        links.forEachIndexed { i, m -> val u = clean(m.groupValues[1]) ?: return@forEachIndexed; if (out.none { it.url == u }) out += WebResult(out.size + 1, strip(m.groupValues[2]), u, snippets.getOrElse(i) { "" }.take(300)) }
        return out.take(10)
    }

    private fun bing(q: String): List<WebResult> {
        val html = get("https://www.bing.com/search?q=" + Uri.encode(q).replace("%20", "+") + "&form=QBLH&cc=" + Prefs.shoppingCountry.uppercase().ifBlank { "IN" })
        val out = ArrayList<WebResult>()
        Regex("<li class=\"b_algo\"[\\s\\S]*?</li>").findAll(html).forEach { li ->
            val h = Regex("<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").find(li.value) ?: return@forEach
            var u = h.groupValues[1].replace("&amp;", "&")
            Regex("[?&]u=a1([^&]+)").find(u)?.let { m -> u = runCatching { String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)) }.getOrDefault("") }
            if (!u.startsWith("http") || u.contains("bing.com/") || out.any { it.url == u }) return@forEach
            val p = Regex("<p[^>]*>([\\s\\S]*?)</p>").find(li.value)?.groupValues?.get(1).orEmpty()
            out += WebResult(out.size + 1, strip(h.groupValues[2]), u, strip(p).removePrefix("Web ").take(300))
            if (out.size >= 10) return out
        }
        return out
    }

    private fun region(): String = when (Prefs.shoppingCountry.uppercase()) { "IN" -> "in-en"; "US" -> "us-en"; "GB", "UK" -> "uk-en"; "CA" -> "ca-en"; "AU" -> "au-en"; "DE" -> "de-de"; "FR" -> "fr-fr"; else -> "wt-wt" }

    fun suggestions(q: String): List<String> = runCatching {
        val body = get("https://duckduckgo.com/ac/?type=list&q=" + Uri.encode(q))
        val a = JSONArray(body); val l = if (a.length() > 1 && a.get(1) is JSONArray) a.getJSONArray(1) else a
        (0 until l.length()).mapNotNull { l.optString(it).takeIf { s -> s.isNotBlank() && !s.equals(q, true) } }.take(6)
    }.getOrDefault(emptyList())

    /** Readable text of a page, for grounding answers. Small and fast: 4 s, 600 KB. */
    fun pageText(url: String, max: Int = 3500): String = runCatching {
        http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", acceptLanguage()).build()).execute().use { r ->
            if (!r.isSuccessful || r.body?.contentType()?.subtype?.contains("html") != true) return@use ""
            val src = r.body!!.source(); src.request(600_000); val html = src.buffer.readUtf8(minOf(src.buffer.size, 600_000))
            val body = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form|aside)[^>]*>.*?</\\1>"), " ")
            val main = Regex("(?is)<(article|main)[^>]*>(.*?)</\\1>").find(body)?.groupValues?.get(2) ?: body
            strip(main.replace(Regex("(?i)<(br|p|li|h[1-6]|div|tr)[^>]*>"), "\n")).take(max)
        }
    }.getOrDefault("")

    // ---------------------------------------------------------------- answer
    fun answerMessages(q: String, results: List<WebResult>, pages: Map<Int, String>): JSONArray {
        val lang = Llm.langInstruction(q)
        val today = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.ENGLISH).format(java.util.Date())
        val ctx = results.take(6).joinToString("\n\n") { r ->
            "[${r.n}] ${r.title}\nURL: ${r.url}\n" + (pages[r.n]?.takeIf { it.length > 200 } ?: r.snippet)
        }
        val sys = """You are Cairn, the answer engine inside the Cairn browser. Today is $today.
Answer the user's search using ONLY the numbered sources below. Cite every factual sentence with the source number in square brackets right after it, like [1] or [2][4]. Never invent sources, numbers or links.
Start with a direct 1–2 sentence answer. Then add short sections or bullets only if they help. Use **bold** for key facts. Keep it under 220 words. If sources disagree or don't answer it, say so briefly.
$lang"""
        return JSONArray().put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", "Search: $q\n\nSources:\n$ctx"))
    }

    /** Fetches the top pages in parallel (bounded) so answers are grounded in more than snippets. */
    fun readTop(results: List<WebResult>, n: Int = 4): Map<Int, String> = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(5_000) {
            results.take(n).map { r -> async { r.n to pageText(r.url) } }.awaitAll().toMap()
        } ?: emptyMap()
    }

    // ---------------------------------------------------------------- WebView integration
    fun handles(req: WebResourceRequest) = req.url.host == HOST
    fun intercept(req: WebResourceRequest, dark: Boolean): WebResourceResponse? {
        val u = req.url
        if (u.host != HOST) return null
        // Only Cairn's own page may call the API (no cross-site use of the user's AI quota).
        val origin = req.requestHeaders["Origin"] ?: req.requestHeaders["origin"]
        val path = u.path.orEmpty()
        if (path.startsWith("/api/") && origin != null && origin != "https://$HOST") return text(403, "text/plain", "Forbidden")
        return when {
            path == "/" || path.isEmpty() -> page(u.getQueryParameter("q").orEmpty(), dark)
            path == "/api/results" -> {
                val q = u.getQueryParameter("q").orEmpty()
                val r = results(q)
                val nav = r.firstOrNull()?.takeIf { q.trim().split(Regex("\\s+")).size <= 2 && Sites.isNavigational(q, it.host) }?.url
                val j = JSONObject().put("results", JSONArray(r.map { it.json() })).put("nav", nav ?: JSONObject.NULL)
                    .put("related", JSONArray(suggestions(q))).put("answers", Prefs.searchAnswers && Llm.configured)
                text(200, "application/json", j.toString())
            }
            path == "/api/answer" -> stream(u.getQueryParameter("q").orEmpty())
            path == "/icon" -> icon(u.getQueryParameter("h").orEmpty())
            path.startsWith("/font/") -> font(path.removePrefix("/font/"))
            else -> text(404, "text/plain", "Not found")
        }
    }

    private fun text(code: Int, mime: String, body: String) = WebResourceResponse(mime, "utf-8", code, if (code == 200) "OK" else "Error",
        mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "https://$HOST"), ByteArrayInputStream(body.toByteArray()))

    private fun page(q: String, dark: Boolean): WebResourceResponse {
        val tpl = app.assets.open("search.html").bufferedReader().use { it.readText() }
        val esc = q.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val html = tpl.replace("{{Q}}", esc).replace("{{THEME}}", if (dark) "dark" else "light")
            .replace("{{LANG}}", if (Lang.detect(q) == "Hindi") "hi" else "en")
        return text(200, "text/html", html)
    }

    private fun stream(q: String): WebResourceResponse {
        val pin = PipedInputStream(16 * 1024); val out = PipedOutputStream(pin)
        Thread {
            try {
                val results = results(q)
                if (results.isEmpty()) { out.write("\u0000ERR:No results to answer from.".toByteArray()); return@Thread }
                val pages = readTop(results)
                Llm.streamBlocking(answerMessages(q, results, pages), tier = "smart", maxTokens = 900) { d -> out.write(d.toByteArray()); out.flush() }
            } catch (e: NoAiException) { runCatching { out.write("\u0000ERR:Cairn's AI isn't set up on this device.".toByteArray()) } }
            catch (e: Exception) { runCatching { out.write("\u0000ERR:${e.message ?: "The answer couldn't be generated."}".toByteArray()) } }
            finally { runCatching { out.close() } }
        }.apply { isDaemon = true }.start()
        return WebResourceResponse("text/plain", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), pin)
    }

    private fun icon(host: String): WebResourceResponse {
        val b = runCatching { Favicons.fetchNow("https://$host") }.getOrNull() ?: return text(404, "text/plain", "")
        val o = ByteArrayOutputStream(); b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o)
        return WebResourceResponse("image/png", null, 200, "OK", mapOf("Cache-Control" to "max-age=604800"), ByteArrayInputStream(o.toByteArray()))
    }

    private fun font(name: String): WebResourceResponse {
        val id = when (name) { "regular" -> R.font.inter_regular; "medium" -> R.font.inter_medium; "semibold" -> R.font.inter_semibold; "display" -> R.font.inter_display_semibold; else -> return text(404, "text/plain", "") }
        return WebResourceResponse("font/ttf", null, 200, "OK", mapOf("Cache-Control" to "max-age=31536000", "Access-Control-Allow-Origin" to "*"), app.resources.openRawResource(id))
    }
}
