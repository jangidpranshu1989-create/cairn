package app.cairn.browser.data

import android.os.Build
import android.os.Handler
import android.os.Looper
import app.cairn.browser.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.TimeZone

/**
 * Anonymous usage stats (Settings → Privacy → "Share anonymous usage stats"; on by default, one tap to turn off).
 *
 * Sent: a random install id made on this phone (not linked to your account, Google id or IMEI), the event name
 * (app opened, still in use, search made, agent started...), Cairn version, Android version, language and time zone.
 * Never sent: URLs, page titles, search words, messages, passwords or anything you type.
 */
object Telemetry {
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<JSONObject>()
    private var running = false
    private var lastStop = 0L
    private const val SESSION_GAP = 30 * 60_000L
    private const val BEAT = 60_000L
    private val json = "application/json".toMediaType()

    private val id: String by lazy {
        Prefs.telemetryId.ifEmpty {
            val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
            android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                .also { Prefs.telemetryId = it }
        }
    }

    private val beat = object : Runnable { override fun run() { if (!running) return; event("heartbeat"); send(); main.postDelayed(this, BEAT) } }

    /** Call when the browser comes to the foreground. */
    fun start() {
        if (running) return
        running = true
        if (Prefs.telemetryId.isEmpty()) event("install")
        if (System.currentTimeMillis() - lastStop > SESSION_GAP) event("app_open")
        send()
        main.postDelayed(beat, BEAT)
    }

    /** Call when the browser goes to the background. */
    fun stop() {
        if (!running) return
        running = false; lastStop = System.currentTimeMillis()
        main.removeCallbacks(beat); send()
    }

    /** Records an event by name only; [k] is a short fixed label (never user content). */
    fun event(name: String, k: String = "") {
        if (!runCatching { Prefs.usageStats }.getOrDefault(false)) return
        val o = JSONObject().put("i", id).put("p", "android").put("e", name).put("v", BuildConfig.VERSION_NAME)
            .put("os", "Android ${Build.VERSION.RELEASE}").put("l", java.util.Locale.getDefault().toLanguageTag().take(15))
            .put("tz", TimeZone.getDefault().id)
        if (k.isNotEmpty()) o.put("k", k.take(40))
        synchronized(queue) { queue.add(o); while (queue.size > 100) queue.removeAt(0) }
    }

    private fun send() {
        if (!runCatching { Prefs.usageStats }.getOrDefault(false)) { synchronized(queue) { queue.clear() }; return }
        val batch = synchronized(queue) { if (queue.isEmpty()) return; ArrayList(queue).also { queue.clear() } }
        val body = JSONObject().put("batch", JSONArray(batch)).toString().toRequestBody(json)
        val url = runCatching { Prefs.server.trimEnd('/') + "/api/t" }.getOrNull() ?: return
        runCatching {
            Api.http.newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { requeue(batch) }
                override fun onResponse(call: Call, response: Response) { response.close(); if (response.code >= 500) requeue(batch) }
            })
        }.onFailure { requeue(batch) }
    }

    private fun requeue(batch: List<JSONObject>) {
        // Heartbeats are only useful live; keep the rest for the next try.
        synchronized(queue) { queue.addAll(0, batch.filter { it.optString("e") != "heartbeat" }); while (queue.size > 100) queue.removeAt(0) }
    }
}
