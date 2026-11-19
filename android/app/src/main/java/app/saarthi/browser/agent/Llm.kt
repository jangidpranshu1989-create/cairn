package app.saarthi.browser.agent

import app.saarthi.browser.data.Api
import app.saarthi.browser.data.ApiException
import app.saarthi.browser.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NoAiException : Exception("NO_AI")

/**
 * Chat completions with OpenAI-format tool calling.
 * Saarthi Cloud (signed in) proxies Groq → NVIDIA NIM with server-side keys; "Your own keys" calls the providers directly.
 */
object Llm {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    private val JSON_T = "application/json".toMediaType()
    private val bases = mapOf("groq" to "https://api.groq.com/openai/v1", "nvidia" to "https://integrate.api.nvidia.com/v1")
    private val labels = mapOf("groq" to "Groq", "nvidia" to "NVIDIA NIM")

    var lastProvider = ""; private set

    val configured get() = Prefs.isSignedIn || Prefs.groqKey.isNotBlank() || Prefs.nvidiaKey.isNotBlank()

    private fun ownProviders(): List<String> {
        val have = listOf("groq", "nvidia").filter { (if (it == "groq") Prefs.groqKey else Prefs.nvidiaKey).isNotBlank() }
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
        if (providers.isEmpty()) throw NoAiException()
        var last: Exception? = null
        for (p in providers) {
            val model = if (p == "groq") (if (tier == "fast") Prefs.groqFastModel else Prefs.groqModel) else (if (tier == "fast") Prefs.nvidiaFastModel else Prefs.nvidiaModel)
            val body = JSONObject().put("model", model).put("messages", messages).put("temperature", temperature).put("max_tokens", maxTokens)
            if (tools != null) body.put("tools", tools).put("tool_choice", "auto")
            if (json) body.put("response_format", JSONObject().put("type", "json_object"))
            if (p == "nvidia") body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            if (p == "groq" && model.contains("gpt-oss")) body.put("reasoning_effort", "low")
            try {
                val req = Request.Builder().url("${bases[p]}/chat/completions").header("Authorization", "Bearer ${if (p == "groq") Prefs.groqKey else Prefs.nvidiaKey}")
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

    fun langInstruction(userText: String?): String = when (Prefs.replyLanguage) {
        "auto" -> if (userText.isNullOrBlank()) "Write in English." else "Write in the same language and script as this request: \"${userText.take(200)}\"."
        "hinglish" -> "Write in Hinglish (Hindi in Latin script)."
        else -> "Write in ${Prefs.replyLanguage}."
    }
}
