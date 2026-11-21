package app.cairn.analytics

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Dashboard key + last summary, shared by the app and the home-screen widget. The key never leaves this device except to the Cairn server. */
object Stats {
    private const val PREFS = "cairn_analytics"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(ctx: Context): String = prefs(ctx).getString("key", "") ?: ""
    fun api(ctx: Context): String = (prefs(ctx).getString("api", "") ?: "").ifBlank { BuildConfig.DEFAULT_SERVER }.trimEnd('/')
    fun saveKey(ctx: Context, key: String, api: String) {
        prefs(ctx).edit().putString("key", key.trim()).putString("api", api.trim()).apply()
        if (key.isBlank()) prefs(ctx).edit().remove("summary").apply()
    }

    data class Summary(val active: Int, val apps: Int, val web: Int, val today: Int, val fresh: Int, val total: Int, val webToday: Int, val goal: Int, val at: Long)

    fun cached(ctx: Context): Summary? = prefs(ctx).getString("summary", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    fun store(ctx: Context, s: Summary) {
        prefs(ctx).edit().putString("summary", JSONObject().put("active", s.active).put("apps", s.apps).put("web", s.web).put("today", s.today)
            .put("fresh", s.fresh).put("total", s.total).put("webToday", s.webToday).put("goal", s.goal).put("at", s.at).toString()).apply()
    }
    private fun parse(o: JSONObject) = Summary(o.optInt("active"), o.optInt("apps"), o.optInt("web"), o.optInt("today"), o.optInt("fresh"),
        o.optInt("total"), o.optInt("webToday"), o.optInt("goal", 10_000), o.optLong("at"))

    /** From the full /api/admin/analytics response. */
    fun fromOverview(o: JSONObject): Summary {
        val rt = o.optJSONObject("realtime") ?: JSONObject(); val k = o.optJSONObject("kpis") ?: JSONObject()
        return Summary(rt.optInt("active"), rt.optInt("apps"), rt.optInt("web"), k.optInt("dau"), k.optInt("newToday"), k.optInt("totalUsers"),
            k.optInt("webVisitorsToday"), k.optInt("goal", 10_000), System.currentTimeMillis())
    }

    /** Blocking; call off the main thread. Returns null when there is no key, the key is wrong, or the server is unreachable. */
    fun fetch(ctx: Context): Summary? {
        val key = key(ctx); if (key.isBlank()) return null
        val c = (URL("${api(ctx)}/api/admin/analytics?days=7").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000 // free Render instances can take ~50 s to wake up
            setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Accept", "application/json")
        }
        return try {
            if (c.responseCode != 200) null
            else fromOverview(JSONObject(c.inputStream.bufferedReader().use { it.readText() })).also { store(ctx, it) }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }
}
