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
 * Chat completions with OpenAI-format tool calling.
 * Cairn Cloud (signed in) proxies Groq → NVIDIA NIM with server-side keys; "Your own keys" calls the providers directly.
 */
object Llm {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private val JSON_T = "application/json".toMediaType()
    private val bases = mapOf("groq" to "https://api.groq.com/openai/v1", "nvidia" to "https://integrate.api.nvidia.com/v1")
    private val labels = mapOf("groq" to "Groq", "nvidia" to "NVIDIA NIM")

    var lastProvider = ""; private set

    /** Keys shipped inside the app (see build.gradle.kts), unmasked on first use. */
    private fun unmask(b64: String): String = runCatching {
        if (b64.isEmpty()) return "" 
        val salt = "cairn-stones-v2".toByteArray()
        val x = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        String(ByteArray(x.size) { i -> (x[i].toInt() xor salt[i % salt.size].toInt()).toByte() })
    }.getOrDefault("")
    private val builtIn by lazy { mapOf("groq" to unmask(app.cairn.browser.BuildConfig.K_GROQ), "nvidia" to unmask(app.cairn.browser.BuildConfig.K_NVIDIA)) }
    val hasBuiltIn get() = builtIn.values.any { it.isNotBlank() }

    /** The key used for a provider: the user's own key wins, otherwise the built-in one. */
    fun keyFor(p: String): String = (if (p == "groq") Prefs.groqKey else Prefs.nvidiaKey).ifBlank { if (Prefs.aiMode == "byok") "" else builtIn[p].orEmpty() }

    val configured get() = Prefs.isSignedIn || hasBuiltIn || Prefs.aiMode == "builtin" || Prefs.groqKey.isNotBlank() || Prefs.nvidiaKey.isNotBlank()

    /** Cairn Cloud as the built-in backstop: account quota when signed in, otherwise an anonymous per-install token. */
    private fun cloudChat(body: JSONObject): JSONObject {
        val j = if (Prefs.isSignedIn) Api.aiChat(body) else Api.anonChat(body)
        lastProvider = j.optString("_provider", "cloud")
        return j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
    }

    private fun ownProviders(): List<String> {
        val have = listOf("groq", "nvidia").filter { keyFor(it).isNotBlank() }
        return have.sortedBy { if (it == Prefs.preferredProvider) 0 else 1 }
    }

    suspend fun chat(messages: JSONArray, tools: JSONArray? = null, tier: String = "smart", json: Boolean = false, maxTokens: Int = 3000, temperature: Double = 0.2): JSONObject = runInterruptible(Dispatchers.IO) {
        val useCloud = Prefs.aiMode == "cloud" && Prefs.isSignedIn
        if (useCloud) {
            try {
                val body = JSONObject().put("messages", messages).put("tier", tier).put("maxTokens", maxTokens).put("temperature", temperature).put("stream", false)
                if (tools != null) body.put("tools", tools)
                if (json) body.put("json", true)
                val j = Api.aiChat(body)
                lastProvider = j.optString("_provider", "cloud")
                return@runInterruptible j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            } catch (e: ApiException) {
                // Daily limit or outage: fall back to the user's own keys if they added any
                if (ownProviders().isEmpty()) throw e
            }
        }
        val providers = ownProviders()
        val cloudBody = { JSONObject().put("messages", messages).put("tier", tier).put("maxTokens", maxTokens).put("temperature", temperature).put("stream", false).also { b -> if (tools != null) b.put("tools", tools); if (json) b.put("json", true) } }
        if (providers.isEmpty()) {
            // Built-in mode works with zero setup: no keys in this build → Cairn Cloud serves the request.
            if (Prefs.aiMode != "byok") try { return@runInterruptible cloudChat(cloudBody()) } catch (e: java.io.InterruptedIOException) { throw e } catch (e: Exception) { throw if (e is ApiException && e.status == 429) e else NoAiException() }
            throw NoAiException()
        }
        var last: Exception? = null
        for (p in providers) {
            val model = if (p == "groq") (if (tier == "fast") Prefs.groqFastModel else Prefs.groqModel) else (if (tier == "fast") Prefs.nvidiaFastModel else Prefs.nvidiaModel)
            val body = JSONObject().put("model", model).put("messages", messages).put("temperature", temperature).put("max_tokens", maxTokens)
            if (tools != null) body.put("tools", tools).put("tool_choice", "auto")
            if (json) body.put("response_format", JSONObject().put("type", "json_object"))
            if (p == "nvidia") body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            if (p == "groq" && model.contains("gpt-oss")) body.put("reasoning_effort", "low")
            try {
                val req = Request.Builder().url("${bases[p]}/chat/completions").header("Authorization", "Bearer ${keyFor(p)}")
                    .post(body.toString().toRequestBody(JSON_T)).build()
                http.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        last = Exception(if (r.code == 401) "${labels[p]} API key is invalid. Update it in Settings → AI." else "${labels[p]} error ${r.code}: ${txt.take(200)}")
                        return@use
                    }
                    lastProvider = p
                    return@runInterruptible JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                }
            } catch (e: java.io.InterruptedIOException) { throw e } catch (e: Exception) { last = e }
        }
        // Every direct provider failed (rate limit, revoked key, outage): try Cairn Cloud before giving up.
        if (Prefs.aiMode != "byok" && !useCloud) runCatching { return@runInterruptible cloudChat(cloudBody()) }
        throw last ?: Exception("AI request failed")
    }

    /**
     * Streams a plain-text completion token by token (Server-Sent Events). Used by Cairn Search answers and chat replies.
     * Falls back to a single non-streamed call (e.g. Cairn Cloud), delivering the whole text as one chunk.
     */
    fun streamBlocking(messages: JSONArray, tier: String = "smart", maxTokens: Int = 1600, onDelta: (String) -> Unit): String {
        val providers = ownProviders()
        if (providers.isEmpty() || (Prefs.aiMode == "cloud" && Prefs.isSignedIn)) {
            val t = kotlinx.coroutines.runBlocking { chat(messages, tier = tier, maxTokens = maxTokens) }.optString("content").trim()
            onDelta(t); return t
        }
        var last: Exception? = null
        for (p in providers) {
            val model = if (p == "groq") (if (tier == "fast") Prefs.groqFastModel else Prefs.groqModel) else (if (tier == "fast") Prefs.nvidiaFastModel else Prefs.nvidiaModel)
            val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.3).put("max_tokens", maxTokens).put("stream", true)
            if (p == "nvidia") body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            if (p == "groq" && model.contains("gpt-oss")) body.put("reasoning_effort", "low")
            val out = StringBuilder()
            try {
                http.newCall(Request.Builder().url("${bases[p]}/chat/completions").header("Authorization", "Bearer ${keyFor(p)}").header("Accept", "text/event-stream")
                    .post(body.toString().toRequestBody(JSON_T)).build()).execute().use { r ->
                    if (!r.isSuccessful) { last = Exception("${labels[p]} error ${r.code}"); return@use }
                    val src = r.body!!.source()
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val d = line.removePrefix("data:").trim()
                        if (d == "[DONE]") break
                        val delta = runCatching { JSONObject(d).getJSONArray("choices").getJSONObject(0).optJSONObject("delta")?.optString("content").orEmpty() }.getOrDefault("")
                        if (delta.isNotEmpty() && delta != "null") { out.append(delta); onDelta(delta) }
                    }
                    lastProvider = p
                }
                if (out.isNotEmpty()) return out.toString()
            } catch (e: java.io.InterruptedIOException) { if (out.isNotEmpty()) return out.toString(); last = e } catch (e: Exception) { if (out.isNotEmpty()) return out.toString(); last = e }
        }
        throw last ?: Exception("AI request failed")
    }

    suspend fun complete(system: String, user: String, tier: String = "smart", maxTokens: Int = 1500, json: Boolean = false): String {
        val m = chat(JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)), tier = tier, json = json, maxTokens = maxTokens)
        return m.optString("content").trim()
    }

    suspend fun completeJson(system: String, user: String, tier: String = "smart", maxTokens: Int = 2000): JSONObject {
        val t = complete("$system\nRespond with a single valid JSON object only.", user, tier, maxTokens, json = true)
        return runCatching { JSONObject(t) }.getOrElse { Regex("\\{[\\s\\S]*\\}").find(t)?.value?.let { JSONObject(it) } ?: throw Exception("The AI returned an invalid response") }
    }

    /** Validates a key by listing models. */
    suspend fun testKey(provider: String, key: String): Boolean = runInterruptible(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url("${bases[provider]}/models").header("Authorization", "Bearer $key").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Strict language mirroring: the reply language is decided here, deterministically, never by the model. */
    fun langInstruction(userText: String?): String = when (Prefs.replyLanguage) {
        "auto" -> Lang.rule(Lang.detect(userText.orEmpty()))
        "hinglish" -> Lang.rule("Hinglish")
        else -> Lang.rule(Prefs.replyLanguage)
    }

    /** Removes hidden reasoning some open models print before the answer. */
    fun stripThinking(t: String) = t.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*?</think>"), "").trim()

    /**
     * Image understanding (lasso, "ask about this image"). Order: Cairn Cloud (signed in, cloud mode) → Groq Qwen 3.8
     * (fast, multimodal) → NVIDIA vision models. Each failure falls through to the next, so one retired model never
     * breaks Circle-to-ask.
     */
    suspend fun vision(prompt: String, jpegB64: String, maxTokens: Int = 1200): String = runInterruptible(Dispatchers.IO) {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")))
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        var last: Exception? = null
        if (Prefs.isSignedIn && (Prefs.aiMode == "cloud" || ownProviders().isEmpty())) {
            try {
                val j = Api.aiChat(JSONObject().put("messages", msgs).put("tier", "vision").put("maxTokens", maxTokens).put("temperature", 0.2).put("stream", false))
                lastProvider = j.optString("_provider", "cloud")
                return@runInterruptible stripThinking(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"))
            } catch (e: Exception) { last = e }
        }
        val models = buildList {
            if (keyFor("groq").isNotBlank()) add("groq" to "qwen/qwen3.8-27b")
            if (keyFor("nvidia").isNotBlank()) {
                Prefs.nvidiaModel.takeIf { it.contains("vl") || it.contains("vision") || it.contains("omni") }?.let { add("nvidia" to it) }
                add("nvidia" to "nvidia/nemotron-3.5-super-vl-120b-a12b"); add("nvidia" to "google/gemma-4-31b-it")
                add("nvidia" to "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning"); add("nvidia" to "meta/llama-3.2-90b-vision-instruct")
            }
        }.distinct()
        if (models.isEmpty() && last == null) throw NoAiException()
        for ((p, model) in models) {
            val body = JSONObject().put("model", model).put("messages", msgs).put("max_tokens", maxTokens).put("temperature", 0.2)
            if (p == "nvidia") body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            try {
                http.newCall(Request.Builder().url("${bases[p]}/chat/completions").header("Authorization", "Bearer ${keyFor(p)}").post(body.toString().toRequestBody(JSON_T)).build()).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (r.isSuccessful) {
                        val out = stripThinking(JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"))
                        if (out.isNotBlank()) { lastProvider = p; return@runInterruptible out }
                    }
                    last = Exception("${labels[p]} vision error ${r.code}")
                }
            } catch (e: java.io.InterruptedIOException) { throw e } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("Image analysis failed")
    }

    /** Speech to text with Whisper on Groq — fully in-app, no system speech service. */
    suspend fun transcribe(file: java.io.File, language: String? = null): String = runInterruptible(Dispatchers.IO) {
        val key = keyFor("groq").ifBlank { throw Exception("Voice input needs the Groq AI service. Add a Groq key in Settings → AI.") }
        val b = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3-turbo").addFormDataPart("response_format", "json").addFormDataPart("temperature", "0")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
        if (!language.isNullOrBlank()) b.addFormDataPart("language", language)
        http.newCall(Request.Builder().url("${bases["groq"]}/audio/transcriptions").header("Authorization", "Bearer $key").post(b.build()).build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw Exception("Couldn't transcribe (${r.code})")
            JSONObject(t).optString("text").trim()
        }
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
