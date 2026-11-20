package app.cairn.browser.browser

import app.cairn.browser.data.Api
import app.cairn.browser.data.Prefs
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Cairn Safe Browsing. Before a page loads, each host suffix is hashed with SHA-256 and only the first 32 bits are sent
 * to the Cairn server; full hashes come back and are compared on the phone, so the server never learns which site you
 * visit. Verdicts are cached for 30 minutes. If the check can't finish quickly the page loads normally (fail open).
 */
object SafeBrowsing {
    data class Verdict(val kind: String?, val at: Long)
    private val cache = ConcurrentHashMap<String, Verdict>()
    private val allowed = ConcurrentHashMap.newKeySet<String>()   // "Proceed anyway" for this session
    private val client by lazy { Api.http.newBuilder().callTimeout(1800, TimeUnit.MILLISECONDS).build() }
    private const val TTL = 30 * 60_000L

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun suffixes(host: String): List<String> {
        val h = host.lowercase().trimEnd('.')
        if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(h) || h.contains(':')) return listOf(h)
        val p = h.split('.'); val out = mutableListOf<String>()
        for (i in 0 until p.size - 1) { if (out.size >= 5) break; out.add(p.subList(i, p.size).joinToString(".")) }
        return out
    }

    fun allow(host: String) { allowed.add(host.lowercase()) }

    /** Returns "phishing" / "malware" for a dangerous host, or null. Blocking: call off the main thread. */
    fun check(host: String?): String? {
        val h = host?.lowercase() ?: return null
        if (!Prefs.safeBrowsing || h.isEmpty() || h in allowed || h == "localhost" || h.endsWith(".local")) return null
        cache[h]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let { return it.kind }
        val hashes = suffixes(h).associateBy { sha(it) }
        val verdict = runCatching {
            val url = Prefs.server.trimEnd('/') + "/api/safety/lookup?p=" + hashes.keys.joinToString(",") { it.take(8) }
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val m = JSONObject(r.body!!.string()).optJSONArray("matches")
                var kind: String? = null
                if (m != null) for (i in 0 until m.length()) { val o = m.getJSONObject(i); if (o.optString("hash") in hashes) { kind = o.optString("kind", "phishing"); break } }
                kind
            }
        }.getOrElse { return null }
        cache[h] = Verdict(verdict, System.currentTimeMillis())
        return verdict
    }
}

data class UnsafeSite(val url: String, val kind: String)
