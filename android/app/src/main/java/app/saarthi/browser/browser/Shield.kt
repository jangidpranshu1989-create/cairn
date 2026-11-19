package app.saarthi.browser.browser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Saarthi Shield: blocks requests to known ad and tracker hosts (suffix match, O(labels) per request). */
object Shield {
    private val hosts = HashSet<String>()
    // A few first-party hosts that must never be blocked even if a parent domain is listed
    private val allow = setOf("accounts.google.com", "www.google.com", "maps.googleapis.com", "fonts.googleapis.com", "www.gstatic.com")

    fun init(ctx: Context) {
        runCatching { ctx.assets.open("shield.txt").bufferedReader().useLines { l -> l.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { hosts.add(it) } } }
    }

    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase() ?: return false
        if (h in allow) return false
        while (true) {
            if (h in hosts) return true
            val i = h.indexOf('.'); if (i < 0 || h.indexOf('.', i + 1) < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun empty() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
