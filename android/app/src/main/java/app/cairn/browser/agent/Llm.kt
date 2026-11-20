package app.cairn.browser.agent

import app.cairn.browser.data.Api
import app.cairn.browser.data.ApiException
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

open class NoAiException(msg: String = "NO_AI") : Exception(msg)

/**
 * Every AI route failed. [attempts] lists each provider with its exact HTTP status / reason (e.g. "Cairn Cloud 404",
 * "LLM7 429"), so the UI can show a real diagnosis instead of a bare "Not found".
 */
class AiUnavailableException(val attempts: List<String>) : NoAiException("AI unavailable — " + attempts.joinToString(" · ")) {
    val summary get() = attempts.joinToString(" · ")
}

/**
 * Chat completions with OpenAI-format tool calling. Zero setup, no keys on the device (like Comet):
 *  1. Cairn server AI (your account quota when signed in, otherwise an anonymous per-install token). The server
 *     holds the provider keys (Groq → NVIDIA NIM → keyless fallback), so they never ship inside the APK.
 *  2. If the server is unreachable, a keyless OpenAI-compatible endpoint called straight from this phone.
 */
object Llm {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private val JSON_T = "application/json".toMediaType()

    var lastProvider = ""; private set
    /** Always true: Cairn's AI is built in. Kept for callers that check before showing AI features. */
    val configured get() = true

    private fun body(messages: JSONArray, tools: JSONArray?, tier: String, json: Boolean, maxTokens: Int, temperature: Double, stream: Boolean) =
        JSONObject().put("messages", messages).put("tier", tier).put("maxTokens", maxTokens).put("temperature", temperature).put("stream", stream).also { b ->
            if (tools != null) b.put("tools", tools); if (json) b.put("json", true) }

    /** Server addresses to try: the configured one first, then the built-in default (covers a stale/mistyped address). */
    private fun bases(): List<String> {
        val cur = Prefs.server.trim().trimEnd('/').removeSuffix("/api").trimEnd('/')
        return listOf(cur, app.cairn.browser.BuildConfig.DEFAULT_SERVER.trimEnd('/')).filter { it.startsWith("http") }.distinct()
    }
    /** Anonymous device tokens for servers other than [Prefs.server] (that one lives in Prefs.anonAiToken). */
    private val altTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun altToken(base: String): String? = altTokens[base] ?: runCatching {
        http.newCall(Request.Builder().url("$base/api/ai/device").post(JSONObject().put("platform", "android").toString().toRequestBody(JSON_T)).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "device token ${r.code}")
            JSONObject(r.body?.string().orEmpty()).optString("token").takeIf { it.startsWith("anon_") }?.also { altTokens[base] = it }
        }
    }.getOrElse { if (it is ApiException) throw it; null }

    /** Opens a request to the Cairn server AI with the right credentials (account or anonymous device token). */
    private fun cloudCall(path: String, base: String = Prefs.server.trimEnd('/'), build: Request.Builder.() -> Unit): okhttp3.Response {
        if (base != Prefs.server.trimEnd('/')) {
            val t = altToken(base) ?: throw ApiException(503, "Cairn AI is unreachable")
            val r = http.newCall(Request.Builder().url("$base/api$path").header("Authorization", "Bearer $t").apply(build).build()).execute()
            if (r.code == 401) altTokens.remove(base)
            return r
        }
        if (Prefs.isSignedIn) return try { Api.authed { url(Api.ep(path)); build() } } catch (e: ApiException) { if (e.status == 401) anonCall(path, build) else throw e }
        return anonCall(path, build)
    }
    private fun anonCall(path: String, build: Request.Builder.() -> Unit): okhttp3.Response {
        val t = Api.anonToken() ?: throw ApiException(503, "Cairn AI is unreachable")
        val r = http.newCall(Request.Builder().url(Api.ep(path)).header("Authorization", "Bearer $t").apply(build).build()).execute()
        if (r.code == 401) { Prefs.anonAiToken = "" }
        return r
    }

    /** Progress line for the UI while Cairn waits for the server to wake ("Waking up Cairn Cloud…"). Set by Agent. */
    @Volatile var onStatus: ((String) -> Unit)? = null
    @Volatile private var lastWarm = 0L
    @Volatile private var lastCloudOk = 0L
    @Volatile private var downAt = 0L
    /**
     * Wakes Cairn Cloud in the background (app start, assistant opened, task started). Render's free tier sleeps
     * after 15 idle minutes and its edge answers 404 (x-render-routing: no-server) for the ~30-60 s it takes to
     * wake, so pinging early means the server is up by the time the first real AI request goes out.
     */
    fun warm() {
        val now = System.currentTimeMillis()
        if (now - lastWarm < 60_000 || now - lastCloudOk < 5 * 60_000) return
        lastWarm = now
        Thread {
            for (b in bases()) runCatching {
                http.newCall(Request.Builder().url("$b/api/health").header("Cache-Control", "no-cache").build()).execute().use { if (it.isSuccessful) lastCloudOk = System.currentTimeMillis() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun okText(r: okhttp3.Response): String = r.use {
        val t = it.body?.string().orEmpty()
        if (!it.isSuccessful) {
            val j = runCatching { JSONObject(t) }.getOrNull()
            val msg = j?.optString("error")?.ifEmpty { null } ?: "AI error ${it.code}"
            // A daily-quota 429 from Cairn Cloud is not worth waiting for; mark it so the patient retry skips it.
            throw ApiException(it.code, if (j?.optString("code") == "quota") QUOTA + msg else msg)
        }
        t
    }

    /** A keyless or personal-key OpenAI-compatible endpoint called straight from the phone. */
    private class Direct(val name: String, val url: String, val key: String?, val smart: String, val fast: String, val json: Boolean, val small: String? = null)
    /** Provider → time until which it is skipped after a hard rate limit, so the next request goes straight to another key. */
    private val coolUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Direct fallbacks, best first: a personal key (Settings → AI), then LLM7's keyless gateway (supports tool calls).
     */
    private fun directs(): List<Direct> = buildList {
        // Legacy single key (2.6.0) counts for its provider if the new per-provider field is empty.
        val legacy = Prefs.aiKey.trim(); val lp = Prefs.aiKeyProvider
        fun keyOf(p: String, v: String): String = v.trim().ifEmpty { if (lp == p) legacy else "" }
        val groq = keyOf("groq", Prefs.groqKey); val gem = keyOf("gemini", Prefs.geminiKey); val orKey = keyOf("openrouter", Prefs.openrouterKey)
        if (groq.isNotEmpty()) add(Direct("Groq (your key)", "https://api.groq.com/openai/v1/chat/completions", groq, "openai/gpt-oss-120b", "openai/gpt-oss-20b", true, small = "llama-3.1-8b-instant"))
        if (gem.isNotEmpty()) add(Direct("Gemini (your key)", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", gem, "gemini-flash-latest", "gemini-flash-lite-latest", true, small = "gemini-flash-lite-latest"))
        if (orKey.isNotEmpty()) add(Direct("OpenRouter (your key)", "https://openrouter.ai/api/v1/chat/completions", orKey, "openrouter/free", "openrouter/free", false))
        add(Direct("LLM7", "https://api.llm7.io/v1/chat/completions", null, "default", "default", false))
        // text.pollinations.ai is retired (402 Payment Required / 404); it is no longer called.
    }.let { all -> val now = System.currentTimeMillis(); all.filter { (coolUntil[it.name] ?: 0L) < now }.ifEmpty { all } }

    /** Skip the server only when the phone can't reach it at all (offline / DNS), and only briefly. */
    @Volatile private var cloudDownUntil = 0L

    private fun reason(e: Throwable): String = when (e) {
        is ApiException -> "${e.status}"
        is java.net.UnknownHostException, is java.net.ConnectException -> "offline"
        is java.net.SocketTimeoutException -> "timeout"
        is java.io.InterruptedIOException -> if (e.message == "timeout") "timeout" else "interrupted"
        is org.json.JSONException -> "bad response"
        else -> e.message?.take(40) ?: e.javaClass.simpleName
    }
    /** A real cancellation (the user pressed Stop) must propagate; a timeout must fall through to the next provider. */
    private fun cancelled(e: Throwable) = e is java.io.InterruptedIOException && e !is java.net.SocketTimeoutException && e.message != "timeout"
    /** Worth one more try on the server: busy (503), gateway (502/504), cold start (timeout) or a proxy 404 during a redeploy. */
    private const val QUOTA = "[quota] "
    private fun transient(e: Throwable) = e.message?.startsWith(QUOTA) != true && (e as? ApiException)?.status?.let { it == 404 || it == 408 || it == 425 || it == 429 || it >= 500 || it == 0 } ?: (e is java.net.SocketTimeoutException || e is java.io.IOException)

    private fun directChat(d: Direct, messages: JSONArray, tools: JSONArray?, tier: String, json: Boolean, maxTokens: Int, temperature: Double): JSONObject {
        val b = JSONObject().put("model", if (tier == "fast") d.fast else d.smart).put("messages", messages).put("temperature", temperature).put("max_tokens", maxTokens)
        if (tools != null) b.put("tools", tools).put("tool_choice", "auto")
        if (json && d.json) b.put("response_format", JSONObject().put("type", "json_object"))
        var last: ApiException? = null
        var usedSmall = false
        repeat(3) { attempt ->
            val rq = Request.Builder().url(d.url).post(b.toString().toRequestBody(JSON_T)).apply { d.key?.let { header("Authorization", "Bearer $it") } }.build()
            http.newCall(rq).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    val m = JSONObject(t).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    val calls = m.optJSONArray("tool_calls")
                    if (m.optString("content").let { it.isBlank() || it == "null" } && (calls == null || calls.length() == 0)) throw ApiException(204, "empty reply")
                    if (calls != null && calls.length() == 0) m.remove("tool_calls")
                    m.remove("reasoning"); m.remove("reasoning_content")
                    lastProvider = d.name; downAt = 0; return m
                }
                last = ApiException(r.code, t.take(200))
                // Short rate limits ("retry in 1 seconds") are waited out on the same provider; anything else moves on.
                val wait = r.header("Retry-After")?.toDoubleOrNull() ?: runCatching { JSONObject(t).optJSONObject("error")?.optDouble("retry_after") }.getOrNull()?.takeIf { !it.isNaN() }
                if (r.code != 429) throw last!!
                // Rate limited: back off 1.5 s and retry once on the provider's small, fast model (much higher TPM on
                // Groq's free tier); if that is limited too, park this key for 45 s and let the next key answer.
                if (d.small != null && !usedSmall) { usedSmall = true; Thread.sleep(1500); b.put("model", d.small); if (tools != null && d.small.startsWith("llama-3.1")) b.put("parallel_tool_calls", false); return@repeat }
                if (attempt == 2 || (wait ?: 2.0) > 5) { coolUntil[d.name] = System.currentTimeMillis() + 45_000; throw last!! }
                Thread.sleep(((wait ?: 1.5) * 1000).toLong().coerceIn(500, 5000))
            }
        }
        if (last?.status == 429) coolUntil[d.name] = System.currentTimeMillis() + 45_000
        throw last ?: ApiException(0, "failed")
    }

    /** One Cairn Cloud chat call on [base]. */
    private fun cloudChat(payload: String, base: String = bases().first()): JSONObject {
        val j = JSONObject(okText(cloudCall("/ai/chat", base) { post(payload.toRequestBody(JSON_T)) }))
        lastProvider = j.optString("_provider", "cloud"); cloudDownUntil = 0; lastCloudOk = System.currentTimeMillis(); downAt = 0
        val m = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        m.optJSONArray("tool_calls")?.let { if (it.length() == 0) m.remove("tool_calls") }
        return m
    }

    /** Pauses between patient Cairn Cloud retries: ~45 s in total, enough to ride out a Render cold start or redeploy. */
    internal val PATIENT_WAITS = longArrayOf(2_000, 3_000, 5_000, 7_000, 10_000, 12_000)

    /**
     * Keeps retrying Cairn Cloud (alternating the configured and default addresses) while it answers with a
     * transient status: 404 from Render's edge while the instance wakes, 429/502/503/504, timeouts and network
     * blips. Gives up early only for a definite error (400/401/403/413) or when the phone is offline for good.
     */
    private fun cloudPatient(payload: String, fails: MutableList<String>): JSONObject? {
        val bs = bases()
        var lastReason = ""
        var offline = 0
        for ((i, wait) in PATIENT_WAITS.withIndex()) {
            onStatus?.invoke(if (i == 0) "Waking up Cairn Cloud…" else "Waking up Cairn Cloud… (${PATIENT_WAITS.take(i + 1).sum() / 1000}s)")
            Thread.sleep(wait)
            val base = bs[i % bs.size]
            try { return cloudChat(payload, base).also { onStatus?.invoke("Thinking…") } } catch (e: Exception) {
                if (cancelled(e)) throw e
                lastReason = reason(e)
                if (e is java.net.UnknownHostException || e is java.net.ConnectException) { if (++offline >= 3) break; continue }
                if (!transient(e)) break
            }
        }
        if (lastReason.isNotEmpty()) fails += "Cairn Cloud $lastReason (after retries)"
        return null
    }

    /**
     * Provider chain: Cairn Cloud → your own key → LLM7 → Cairn Cloud again with patient retries (~45 s, which covers
     * a free-tier cold start). Only if every route fails is an [AiUnavailableException] thrown with each status.
     */
    private fun route(messages: JSONArray, tools: JSONArray?, tier: String, json: Boolean, maxTokens: Int, temperature: Double): JSONObject {
        val fails = mutableListOf<String>()
        val payload = body(messages, tools, tier, json, maxTokens, temperature, false).toString()
        val tryCloud = System.currentTimeMillis() > cloudDownUntil
        if (tryCloud) try { return cloudChat(payload) } catch (e: Exception) {
            if (cancelled(e)) throw e
            fails += "Cairn Cloud ${reason(e)}"
            if (e is java.net.UnknownHostException || e is java.net.ConnectException) cloudDownUntil = System.currentTimeMillis() + 20_000
            warm()
        }
        for (d in directs()) {
            try { return directChat(d, messages, tools, tier, json, maxTokens, temperature) }
            catch (e: Exception) { if (cancelled(e)) throw e; fails += "${d.name} ${reason(e)}" }
        }
        // Everything else failed: wait for the server patiently (it is usually just waking up) before giving up.
        // Right after a full outage (e.g. the on-device shopping fallback's helper calls) don't wait again.
        if (System.currentTimeMillis() - downAt > 30_000) cloudPatient(payload, fails)?.let { return it }
        downAt = System.currentTimeMillis()
        throw AiUnavailableException(fails)
    }

    suspend fun chat(messages: JSONArray, tools: JSONArray? = null, tier: String = "smart", json: Boolean = false, maxTokens: Int = 3000, temperature: Double = 0.2): JSONObject =
        runInterruptible(Dispatchers.IO) { route(messages, tools, tier, json, maxTokens, temperature) }

    private fun readSse(r: okhttp3.Response, onDelta: (String) -> Unit): String {
        val out = StringBuilder()
        r.use {
            if (!it.isSuccessful) throw ApiException(it.code, "AI error ${it.code}")
            val src = it.body!!.source()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val d = line.removePrefix("data:").trim()
                if (d == "[DONE]") break
                val delta = runCatching { JSONObject(d).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")?.optString("content").orEmpty() }.getOrDefault("")
                if (delta.isNotEmpty() && delta != "null") { out.append(delta); onDelta(delta) }
            }
        }
        return out.toString()
    }

    /**
     * Streams a plain-text completion token by token (Server-Sent Events). Used by Cairn Search answers and chat replies.
     * Falls back to a single non-streamed call, delivering the whole text as one chunk.
     */
    fun streamBlocking(messages: JSONArray, tier: String = "smart", maxTokens: Int = 1600, onDelta: (String) -> Unit): String {
        val partial = StringBuilder()
        val track: (String) -> Unit = { partial.append(it); onDelta(it) }
        try {
            val payload = body(messages, null, tier, false, maxTokens, 0.3, true).toString()
            if (System.currentTimeMillis() > cloudDownUntil) {
                val t = readSse(cloudCall("/ai/chat") { header("Accept", "text/event-stream"); post(payload.toRequestBody(JSON_T)) }, track)
                if (t.isNotEmpty()) { lastProvider = "cloud"; return t }
            }
        } catch (e: java.io.InterruptedIOException) { if (partial.isNotEmpty()) return partial.toString(); throw e
        } catch (e: Exception) { if (partial.isNotEmpty()) return partial.toString() }
        val t = stripThinking(route(messages, null, tier, false, maxTokens, 0.3).optString("content").trim())
        onDelta(t); return t
    }

    suspend fun complete(system: String, user: String, tier: String = "smart", maxTokens: Int = 1500, json: Boolean = false): String {
        val m = chat(JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)), tier = tier, json = json, maxTokens = maxTokens)
        return m.optString("content").trim()
    }

    suspend fun completeJson(system: String, user: String, tier: String = "smart", maxTokens: Int = 2000): JSONObject {
        val t = complete("$system\nRespond with a single valid JSON object only.", user, tier, maxTokens, json = true)
        return runCatching { JSONObject(t) }.getOrElse { Regex("\\{[\\s\\S]*\\}").find(t)?.value?.let { JSONObject(it) } ?: throw Exception("The AI returned an invalid response") }
    }

    /** Strict language mirroring: the reply language is decided here, deterministically, never by the model. */
    fun langInstruction(userText: String?): String = when (Prefs.replyLanguage) {
        "auto" -> Lang.rule(Lang.detect(userText.orEmpty()))
        "hinglish" -> Lang.rule("Hinglish")
        else -> Lang.rule(Prefs.replyLanguage)
    }

    /** Removes hidden reasoning some open models print before the answer. */
    fun stripThinking(t: String) = t.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*?</think>"), "").trim()

    /** Image understanding (lasso, "ask about this image") through the Cairn server's multimodal models. */
    suspend fun vision(prompt: String, jpegB64: String, maxTokens: Int = 1200): String = runInterruptible(Dispatchers.IO) {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")))
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val payload = body(msgs, null, "vision", false, maxTokens, 0.2, false).toString()
        try {
            val j = JSONObject(okText(cloudCall("/ai/chat") { post(payload.toRequestBody(JSON_T)) }))
            lastProvider = j.optString("_provider", "cloud")
            stripThinking(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"))
        } catch (e: Exception) {
            if (cancelled(e)) throw e
            // A personal Gemini or OpenRouter key can see images too.
            val d = directs().firstOrNull { it.key != null && (it.name.startsWith("Gemini") || it.name.startsWith("OpenRouter")) } ?: throw e
            stripThinking(directChat(d, msgs, null, "smart", false, maxTokens, 0.2).optString("content"))
        }
    }

    /** Speech to text (Whisper) through the Cairn server. */
    suspend fun transcribe(file: java.io.File, language: String? = null): String = runInterruptible(Dispatchers.IO) {
        fun form() = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }.build()
        JSONObject(okText(cloudCall("/ai/stt") { post(form()) })).optString("text").trim()
    }
}

/** Deterministic language detection used to lock the reply language to the user's own. */
object Lang {
    private val HINGLISH = setOf("hai", "hain", "kya", "kyu", "kyun", "mujhe", "mere", "mera", "meri", "mujhko", "chahiye", "chaiye", "karo", "karna", "kar", "kr", "krdo", "kardo", "krde", "karde", "de", "do", "dena", "sabse", "sasta", "saste", "ka", "ki", "ko", "ke", "mein", "me", "nahi", "nhi", "aur", "bhi", "kaise", "kitna", "kitne", "wala", "waala", "yaar", "bhai", "bata", "batao", "dikha", "dikhao", "dhoondho", "dhundo", "padh", "padhke", "sunao", "suna", "isko", "usko", "iss", "uss", "ye", "yeh", "vo", "woh", "hoga", "hota", "tha", "thi", "abhi", "jaldi", "accha", "acha", "theek", "lekin", "par", "pe", "se", "liye", "wali", "chalo", "bolo", "samjha", "samjhao", "kaun", "kahan", "kab")
    fun detect(text: String): String {
        val t = text.replace(Regex("@\\S+|https?://\\S+"), " ")
        val letters = t.count { it.isLetter() }.coerceAtLeast(1)
        fun share(r: IntRange) = t.count { it.code in r }.toDouble() / letters
        when {
            share(0x0900..0x097F) > 0.3 -> return "Hindi"
            share(0x0980..0x09FF) > 0.3 -> return "Bengali"
            share(0x0B80..0x0BFF) > 0.3 -> return "Tamil"
            share(0x0C00..0x0C7F) > 0.3 -> return "Telugu"
            share(0x0C80..0x0CFF) > 0.3 -> return "Kannada"
            share(0x0D00..0x0D7F) > 0.3 -> return "Malayalam"
            share(0x0A80..0x0AFF) > 0.3 -> return "Gujarati"
            share(0x0A00..0x0A7F) > 0.3 -> return "Punjabi"
            share(0x0600..0x06FF) > 0.3 -> return "Arabic or Urdu (match the user's script)"
            share(0x0400..0x04FF) > 0.3 -> return "Russian"
            share(0x3040..0x30FF) > 0.2 -> return "Japanese"
            share(0xAC00..0xD7AF) > 0.2 -> return "Korean"
            share(0x4E00..0x9FFF) > 0.2 -> return "Chinese"
        }
        val words = Regex("[a-zA-Z]+").findAll(t.lowercase()).map { it.value }.toList()
        val hits = words.count { it in HINGLISH }
        if (words.isNotEmpty() && (hits >= 2 && hits.toDouble() / words.size >= 0.15)) return "Hinglish"
        val es = words.count { it in setOf("el", "la", "los", "las", "quiero", "precio", "barato", "para", "por", "dónde", "como", "qué") }
        if (es >= 2) return "Spanish"
        val fr = words.count { it in setOf("le", "les", "je", "veux", "moins", "cher", "pour", "avec", "est", "une", "des") }
        if (fr >= 2) return "French"
        val de = words.count { it in setOf("ich", "der", "die", "das", "und", "billigste", "möchte", "für", "mit", "ist") }
        if (de >= 2) return "German"
        return "English"
    }
    fun rule(lang: String) = when (lang) {
        "English" -> "LANGUAGE LOCK: Reply ONLY in English. Do not use Hindi or any other language, even if web pages or earlier messages are in another language."
        "Hinglish" -> "LANGUAGE LOCK: Reply ONLY in Hinglish — Hindi written in Latin (English) script, like the user. Never use Devanagari."
        "Hindi" -> "LANGUAGE LOCK: Reply ONLY in Hindi written in Devanagari script."
        else -> "LANGUAGE LOCK: Reply ONLY in $lang."
    }
}
