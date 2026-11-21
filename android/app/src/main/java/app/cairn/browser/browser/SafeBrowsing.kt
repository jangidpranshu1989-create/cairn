package app.cairn.browser.browser

import app.cairn.browser.data.Api
import app.cairn.browser.data.Prefs
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cairn Safe Browsing. Each host suffix is hashed with SHA-256 and only the first 32 bits are sent to the Cairn server;
 * full hashes come back and are compared on the phone, so the server never learns which site you visit. Verdicts are
 * cached for 30 minutes.
 *
 * The check never blocks page loading: [checkAsync] runs off the WebView thread and the page starts loading straight
 * away. If the host turns out to be dangerous, the caller stops the load and shows the warning. When the server can't be
 * reached, lookups pause for a minute instead of retrying (and waiting) on every page.
 */
object SafeBrowsing {
    data class Verdict(val kind: String?, val at: Long)
    private val cache = ConcurrentHashMap<String, Verdict>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val allowed = ConcurrentHashMap.newKeySet<String>()   // "Proceed anyway" for this session
    private val client by lazy { Api.http.newBuilder().callTimeout(4000, TimeUnit.MILLISECONDS).build() }
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "cairn-safety").apply { isDaemon = true } }
    private const val TTL = 30 * 60_000L
    private const val BACKOFF = 60_000L
    @Volatile private var failedAt = 0L

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun suffixes(host: String): List<String> {
        val h = host.lowercase().trimEnd('.')
        if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(h) || h.contains(':')) return listOf(h)
        val p = h.split('.'); val out = mutableListOf<String>()
        for (i in 0 until p.size - 1) { if (out.size >= 5) break; out.add(p.subList(i, p.size).joinToString(".")) }
        return out
    }

    fun allow(host: String) { allowed.add(host.lowercase()) }

    private fun skip(h: String) = !Prefs.safeBrowsing || h.isEmpty() || h in allowed || h == "localhost" || h.endsWith(".local")

    /** Cached verdict, if we have a fresh one: "phishing" / "malware" / null (safe or unknown). Never does I/O. */
    fun cached(host: String?): String? {
        val h = host?.lowercase() ?: return null
        if (skip(h)) return null
        return cache[h]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.kind
    }

    /** Looks [host] up in the background and calls [onDanger] (on a worker thread) only if it is dangerous. */
    fun checkAsync(host: String?, onDanger: (String) -> Unit) {
        val h = host?.lowercase() ?: return
        if (skip(h)) return
        cache[h]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { v -> v.kind?.let(onDanger); return }
        if (System.currentTimeMillis() - failedAt < BACKOFF) return
        if (!inFlight.add(h)) return
        pool.execute {
            try { check(h)?.let(onDanger) } finally { inFlight.remove(h) }
        }
    }

    /** Returns "phishing" / "malware" for a dangerous host, or null. Blocking: call off the main thread. */
    fun check(host: String?): String? {
        val h = host?.lowercase() ?: return null
        if (skip(h)) return null
        cache[h]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.kind }
        if (System.currentTimeMillis() - failedAt < BACKOFF) return null
        val hashes = suffixes(h).associateBy { sha(it) }
        val verdict = runCatching {
            val url = Prefs.server.trimEnd('/') + "/api/safety/lookup?p=" + hashes.keys.joinToString(",") { it.take(8) }
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) { failedAt = System.currentTimeMillis(); return null }
                val m = JSONObject(r.body!!.string()).optJSONArray("matches")
                var kind: String? = null
                if (m != null) for (i in 0 until m.length()) { val o = m.getJSONObject(i); if (o.optString("hash") in hashes) { kind = o.optString("kind", "phishing"); break } }
                kind
            }
        }.getOrElse { failedAt = System.currentTimeMillis(); return null }
        failedAt = 0L
        cache[h] = Verdict(verdict, System.currentTimeMillis())
        return verdict
    }
}

data class UnsafeSite(val url: String, val kind: String)
