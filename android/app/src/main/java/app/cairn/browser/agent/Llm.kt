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

class NoAiException : Exception("NO_AI")

/**
 * Chat completions with OpenAI-format tool calling. Zero setup, no keys on the device (like Comet):
 *  1. Cairn server AI (your account quota when signed in, otherwise an anonymous per-install token). The server
 *     holds the provider keys (Groq → NVIDIA NIM → keyless fallback), so they never ship inside the APK.
 *  2. If the server is unreachable, a keyless OpenAI-compatible endpoint called straight from this phone.
 */
object Llm {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private val JSON_T = "application/json".toMediaType()
    private const val FREE_URL = "https://text.pollinations.ai/openai"

    var lastProvider = ""; private set
    /** Always true: Cairn's AI is built in. Kept for callers that check before showing AI features. */
    val configured get() = true

    private fun body(messages: JSONArray, tools: JSONArray?, tier: String, json: Boolean, maxTokens: Int, temperature: Double, stream: Boolean) =
        JSONObject().put("messages", messages).put("tier", tier).put("maxTokens", maxTokens).put("temperature", temperature).put("stream", stream).also { b ->
            if (tools != null) b.put("tools", tools); if (json) b.put("json", true) }

    /** Opens a request to the Cairn server AI with the right credentials (account or anonymous device token). */
    private fun cloudCall(path: String, build: Request.Builder.() -> Unit): okhttp3.Response {
        if (Prefs.isSignedIn) return try { Api.authed { url(Api.ep(path)); build() } } catch (e: ApiException) { if (e.status == 401) anonCall(path, build) else throw e }
        return anonCall(path, build)
    }
    private fun anonCall(path: String, build: Request.Builder.() -> Unit): okhttp3.Response {
        val t = Api.anonToken() ?: throw ApiException(503, "Cairn AI is unreachable")
        val r = http.newCall(Request.Builder().url(Api.ep(path)).header("Authorization", "Bearer $t").apply(build).build()).execute()
        if (r.code == 401) { Prefs.anonAiToken = "" }
        return r
    }
    private fun okText(r: okhttp3.Response): String = r.use {
        val t = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw ApiException(it.code, runCatching { JSONObject(t).optString("error") }.getOrNull()?.ifEmpty { null } ?: "AI error ${it.code}")
        t
    }

    private fun freeChat(messages: JSONArray, tools: JSONArray?, maxTokens: Int, temperature: Double): JSONObject {
        val b = JSONObject().put("model", "openai").put("messages", messages).put("temperature", temperature).put("max_tokens", maxTokens)
        if (tools != null) b.put("tools", tools).put("tool_choice", "auto")
        val t = okText(http.newCall(Request.Builder().url(FREE_URL).post(b.toString().toRequestBody(JSON_T)).build()).execute())
        lastProvider = "free"
        return JSONObject(t).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    }

    suspend fun chat(messages: JSONArray, tools: JSONArray? = null, tier: String = "smart", json: Boolean = false, maxTokens: Int = 3000, temperature: Double = 0.2): JSONObject = runInterruptible(Dispatchers.IO) {
        val payload = body(messages, tools, tier, json, maxTokens, temperature, false).toString()
        val cloudErr = try {
            val j = JSONObject(okText(cloudCall("/ai/chat") { post(payload.toRequestBody(JSON_T)) }))
            lastProvider = j.optString("_provider", "cloud")
            return@runInterruptible j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        } catch (e: java.io.InterruptedIOException) { throw e } catch (e: Exception) { e }
        if (cloudErr is ApiException && cloudErr.status == 429 && cloudErr.message?.contains("Daily", true) == true) throw cloudErr
        try { freeChat(messages, tools, maxTokens, temperature) }
        catch (e: java.io.InterruptedIOException) { throw e }
        catch (e: Exception) { throw if (cloudErr is ApiException && cloudErr.status != 503) cloudErr else NoAiException() }
    }

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
            val t = readSse(cloudCall("/ai/chat") { header("Accept", "text/event-stream"); post(payload.toRequestBody(JSON_T)) }, track)
            if (t.isNotEmpty()) { lastProvider = "cloud"; return t }
        } catch (e: java.io.InterruptedIOException) { if (partial.isNotEmpty()) return partial.toString(); throw e
        } catch (e: Exception) { if (partial.isNotEmpty()) return partial.toString() }
        val t = kotlinx.coroutines.runBlocking { chat(messages, tier = tier, maxTokens = maxTokens) }.optString("content").trim()
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
        val j = JSONObject(okText(cloudCall("/ai/chat") { post(payload.toRequestBody(JSON_T)) }))
        lastProvider = j.optString("_provider", "cloud")
        stripThinking(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"))
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
