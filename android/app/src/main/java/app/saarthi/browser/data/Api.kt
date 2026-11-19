package app.saarthi.browser.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

data class Approval(val id: String, val kind: String, val title: String, val merchant: String, val amount: String,
                    val item: String, val url: String, val note: String, val expiresAt: Long) {
    companion object {
        fun from(o: JSONObject): Approval { val d = o.optJSONObject("details") ?: JSONObject()
            return Approval(o.getString("id"), o.optString("kind"), o.optString("title"), d.optString("merchant"), d.optString("amount"),
                d.optString("item"), d.optString("url"), d.optString("note"), o.optLong("expiresAt")) }
    }
}
data class Transfer(val id: String, val filename: String, val mime: String, val size: Long, val folder: String) {
    companion object {
        fun from(o: JSONObject) = Transfer(o.getString("id"), o.optString("filename", "file"), o.optString("mime", "application/octet-stream"),
            o.optLong("size"), o.optString("folder", "Saarthi"))
    }
}

object Api {
    private val JSON = "application/json".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val refreshLock = Any()

    fun ep(path: String) = Prefs.server + "/api" + path

    private fun exec(req: Request): Response = http.newCall(req).execute()

    /** Rotating refresh tokens: only one refresh may run at a time. */
    fun refresh(): Boolean = synchronized(refreshLock) {
        val rt = Prefs.refreshToken ?: return false
        val r = exec(Request.Builder().url(ep("/auth/refresh")).post(JSONObject().put("refreshToken", rt).toString().toRequestBody(JSON)).build())
        r.use {
            if (it.code == 401) { Prefs.accessToken = null; Prefs.refreshToken = null; return false }
            if (!it.isSuccessful) throw ApiException(it.code, "Server error ${it.code}")
            val j = JSONObject(it.body!!.string())
            Prefs.accessToken = j.getString("accessToken"); Prefs.refreshToken = j.getString("refreshToken")
            return true
        }
    }

    fun authed(build: Request.Builder.() -> Unit): Response {
        fun make() = Request.Builder().apply(build).header("Authorization", "Bearer ${Prefs.accessToken ?: ""}").build()
        val r = exec(make())
        if (r.code != 401) return r
        r.close()
        if (!refresh()) throw ApiException(401, "You've been signed out. Sign in again to continue.")
        return exec(make())
    }

    private fun Response.json(): String = use { if (!it.isSuccessful) {
        val msg = runCatching { JSONObject(it.body!!.string()).optString("error") }.getOrNull()
        throw ApiException(it.code, msg?.ifEmpty { null } ?: "Server error ${it.code}") }; it.body!!.string() }

    suspend fun completePairing(server: String, code: String): Unit = withContext(Dispatchers.IO) {
        Prefs.server = server
        val body = JSONObject().put("code", code).put("name", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(80))
        val r = exec(Request.Builder().url(ep("/devices/pair/complete")).post(body.toString().toRequestBody(JSON)).build())
        val j = JSONObject(r.json())
        Prefs.deviceId = j.getString("deviceId"); Prefs.userJson = j.getJSONObject("user").toString()
        Prefs.accessToken = j.getString("accessToken"); Prefs.refreshToken = j.getString("refreshToken")
    }

    suspend fun pendingApprovals(): List<Approval> = withContext(Dispatchers.IO) {
        val a = JSONArray(authed { url(ep("/approvals/pending")) }.json()); (0 until a.length()).map { Approval.from(a.getJSONObject(it)) }
    }

    suspend fun decide(id: String, approve: Boolean, verified: Boolean) = withContext(Dispatchers.IO) {
        authed { url(ep("/approvals/$id/decision")).post(JSONObject().put("approve", approve).put("verified", verified).toString().toRequestBody(JSON)) }.json()
    }

    suspend fun pendingTransfers(): List<Transfer> = withContext(Dispatchers.IO) {
        val a = JSONArray(authed { url(ep("/transfers/pending")) }.json()); (0 until a.length()).map { Transfer.from(a.getJSONObject(it)) }
    }

    fun download(id: String): Response = authed { url(ep("/transfers/$id/download")) }.also { if (!it.isSuccessful) { it.close(); throw ApiException(it.code, "Download failed (${it.code})") } }

    suspend fun ack(id: String, savedTo: String) = withContext(Dispatchers.IO) {
        authed { url(ep("/transfers/$id/ack")).post(JSONObject().put("savedTo", savedTo).toString().toRequestBody(JSON)) }.json()
    }

    suspend fun unpair() = withContext(Dispatchers.IO) {
        runCatching { Prefs.deviceId?.let { d -> authed { url(ep("/devices/$d")).delete() }.close() } }
        runCatching { authed { url(ep("/auth/logout")).post("{}".toRequestBody(JSON)) }.close() }
        Prefs.clearAccount()
    }

    data class Captcha(val svg: String, val token: String, val devAnswer: String?)

    suspend fun captcha(): Captcha = withContext(Dispatchers.IO) {
        val j = JSONObject(exec(Request.Builder().url(ep("/auth/captcha")).build()).json())
        Captcha(j.getString("svg"), j.getString("token"), j.optString("devAnswer").ifEmpty { null })
    }

    /** Email sign-in / sign-up, then registers this phone as a device so approvals and files can reach it. */
    suspend fun signIn(email: String, password: String, captchaToken: String?, captchaAnswer: String?, name: String? = null, create: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (captchaToken != null) body.put("captchaToken", captchaToken).put("captchaAnswer", captchaAnswer ?: "")
        if (create) body.put("name", name ?: "")
        val r = exec(Request.Builder().url(ep(if (create) "/auth/signup" else "/auth/login")).post(body.toString().toRequestBody(JSON)).build())
        val raw = r.use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val j = runCatching { JSONObject(txt) }.getOrNull()
                throw ApiException(if (j?.optBoolean("captcha") == true) 428 else resp.code, j?.optString("error")?.ifEmpty { null } ?: "Server error ${resp.code}")
            }
            txt
        }
        val j = JSONObject(raw)
        val at = j.getString("accessToken"); val rt = j.getString("refreshToken")
        // Turn the account session into a device session (same flow as scanning the desktop QR code)
        val start = JSONObject(exec(Request.Builder().url(ep("/devices/pair/start")).header("Authorization", "Bearer $at")
            .post("{}".toRequestBody(JSON)).build()).json())
        val pairBody = JSONObject().put("code", start.getString("code")).put("name", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(80))
        val d = JSONObject(exec(Request.Builder().url(ep("/devices/pair/complete")).post(pairBody.toString().toRequestBody(JSON)).build()).json())
        runCatching { exec(Request.Builder().url(ep("/auth/logout")).header("Authorization", "Bearer $at").post("{}".toRequestBody(JSON)).build()).close() }
        Prefs.deviceId = d.getString("deviceId"); Prefs.userJson = d.getJSONObject("user").toString()
        Prefs.accessToken = d.getString("accessToken"); Prefs.refreshToken = d.getString("refreshToken")
        if (rt.isEmpty()) Unit
    }

    suspend fun me(): JSONObject = withContext(Dispatchers.IO) { JSONObject(authed { url(ep("/auth/me")) }.json()).also { Prefs.userJson = (it.optJSONObject("user") ?: it).toString() } }

    suspend fun devices(): JSONArray = withContext(Dispatchers.IO) { JSONArray(authed { url(ep("/devices")) }.json()) }

    /** OpenAI-format chat completion through the Saarthi Cloud proxy (Groq first, NVIDIA NIM fallback, server-side keys). */
    fun aiChat(body: JSONObject): JSONObject {
        val r = authed { url(ep("/ai/chat")).post(body.toString().toRequestBody(JSON)) }
        return JSONObject(r.json())
    }

    suspend fun forgot(email: String) = withContext(Dispatchers.IO) {
        exec(Request.Builder().url(ep("/auth/forgot")).post(JSONObject().put("email", email.trim()).toString().toRequestBody(JSON)).build()).json()
    }

    fun wsUrl(): String = Prefs.server.replaceFirst("http", "ws") + "/ws?token=" + (Prefs.accessToken ?: "")
}
