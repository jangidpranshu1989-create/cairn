package app.cairn.browser.data

import app.cairn.browser.BuildConfig
import okhttp3.Request
import org.json.JSONObject

/**
 * Where the Cairn server lives. The address is published in the public repo (server.json) so every install, on any
 * phone, finds the same account server, just like signing in to one Google account anywhere. A hand-entered address
 * (pairing with a self-hosted server) is never overwritten.
 */
object ServerConfig {
    private const val URL = "https://raw.githubusercontent.com/jangidpranshu1989-create/cairn/main/server.json"

    /** Installs that still point at the retired pplx.app preview host move to the live server right away, offline. */
    fun migrate() {
        if (Prefs.server.contains("pplx.app") || Prefs.server.isBlank()) {
            Prefs.server = BuildConfig.DEFAULT_SERVER
            Prefs.anonAiToken = ""
        }
    }

    fun refresh() {
        migrate()
        if (!Prefs.serverAuto) return
        runCatching {
            Api.http.newCall(Request.Builder().url(URL).header("Cache-Control", "no-cache").build()).execute().use { r ->
                if (!r.isSuccessful) return
                val s = JSONObject(r.body!!.string()).optString("server").trim().trimEnd('/')
                if (s.startsWith("https://") && s != Prefs.server) {
                    // Only switch when signed out, or when the current server is the unreachable placeholder.
                    if (!Prefs.isSignedIn || Prefs.server == BuildConfig.DEFAULT_SERVER) {
                        Prefs.server = s
                        Prefs.anonAiToken = ""
                    }
                }
            }
        }
    }
}
