package app.cairn.browser.vault

import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import app.cairn.browser.browser.Browser
import app.cairn.browser.data.Api
import app.cairn.browser.data.ApiException
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RemoteTabs(val device: String, val at: Long, val tabs: List<Pair<String, String>>)

/**
 * Chrome-style sync for Cairn: bookmarks, history, open tabs, passwords, passkeys, settings and profile follow
 * the account to every signed-in device (Android and desktop).
 *
 * Model: each device keeps the hash of every item it last synced. A sync pulls server changes since the last
 * revision, applies them, then pushes anything that changed locally (including deletions). Payloads are
 * encrypted with the account vault key before leaving the device; the server only sees opaque ids and blobs.
 * Other devices are told over the WebSocket ("sync_changed") and pull within a second.
 */
object Sync {
    val status = mutableStateOf("")
    val busy = mutableStateOf(false)
    val remoteTabs = mutableStateListOf<RemoteTabs>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + app.cairn.browser.Crash.handler)
    private val lock = Mutex()
    private var pending: Job? = null
    private lateinit var file: File
    private lateinit var installId: String
    // id -> [kind, naturalKey, hash]
    private val state = HashMap<String, Triple<String, String, String>>()
    private var rev = 0L

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "sync_state.json")
        val p = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
        installId = p.getString("install", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("install", it).apply() }
        runCatching {
            val j = JSONObject(file.readText()); rev = j.optLong("rev")
            j.optJSONObject("items")?.let { o -> o.keys().forEach { k -> val a = o.getJSONArray(k); state[k] = Triple(a.getString(0), a.getString(1), a.getString(2)) } }
            j.optJSONArray("remoteTabs")?.let { a -> for (i in 0 until a.length()) parseTabs(a.getJSONObject(i))?.let(remoteTabs::add) }
        }
    }
    private fun save() = runCatching {
        val o = JSONObject(); state.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.first, v.second, v.third))) }
        file.writeText(JSONObject().put("rev", rev).put("items", o).put("remoteTabs", JSONArray(remoteTabs.map { r ->
            JSONObject().put("device", r.device).put("at", r.at).put("tabs", JSONArray(r.tabs.map { JSONObject().put("url", it.first).put("title", it.second) })) })).toString())
    }

    val enabled get() = Prefs.syncOn && Prefs.isSignedIn && Vault.ready.value

    /** Called right after an email/password sign-in: fetch (or create) the account vault key. */
    fun setupKey(email: String, password: String) {
        val kek = Vault.kdf(password, email)
        val k = Api.syncGet("/key")
        val wrapped = k.optString("wrappedKey").takeIf { it.isNotEmpty() && it != "null" }
        if (wrapped != null && k.optString("kdf").startsWith("passphrase")) {
            // Locked with a custom sync passphrase: Settings asks for it. The passphrase never goes to the server.
            Vault.clearKey(); Prefs.syncPassphrase = true; needPassphrase.value = true
            status.value = "Enter your sync passphrase to start syncing"
            return
        }
        if (wrapped != null) {
            val key = Vault.unwrap(kek, wrapped) ?: throw ApiException(409, "Your synced data is locked with an older password. Sign in on a device that still has it, or reset sync in Settings.")
            Vault.setKey(key)
        } else {
            val key = Vault.newKey()
            val (code, body) = Api.syncSend("/key", "PUT", JSONObject().put("wrappedKey", Vault.wrap(kek, key)).put("salt", "cairn-vault:email").put("kdf", "pbkdf2-sha256-310000").put("kcv", Vault.kcv(key)))
            if (code == 409) { Vault.unwrap(kek, body.optString("wrappedKey"))?.let(Vault::setKey) ?: throw ApiException(409, "Couldn't unlock sync") }
            else Vault.setKey(key)
        }
        Prefs.syncPassphrase = false; needPassphrase.value = false
        state.clear(); rev = 0; save()
        soon(0)
    }

    // ---------- Custom sync passphrase (true zero-knowledge, optional) ----------
    val needPassphrase = mutableStateOf(false)
    private fun email() = Prefs.userEmail.trim().lowercase().ifEmpty { throw ApiException(400, "Sign in first") }
    private fun rewrap(wrapped: String, salt: String, kdf: String) {
        val cur = Api.syncGet("/key").optString("wrappedKey")
        val (code, body) = Api.syncSend("/key/rewrap", "POST", JSONObject().put("wrappedKey", wrapped).put("salt", salt).put("kdf", kdf).put("expect", cur).put("kcv", Vault.kcv()))
        if (code !in 200..299) throw ApiException(code, body.optString("error", "Couldn't update the sync key"))
    }
    fun unlockPassphrase(pass: String) {
        val k = Api.syncGet("/key").optString("wrappedKey")
        val key = Vault.unwrap(Vault.kdfPassphrase(pass, email()), k) ?: throw ApiException(403, "That passphrase is not correct")
        Vault.setKey(key); Prefs.syncPassphrase = true; needPassphrase.value = false; status.value = ""
        state.clear(); rev = 0; save(); soon(0)
    }
    fun setPassphrase(pass: String) {
        val key = Vault.currentKey() ?: throw ApiException(400, "Sync is not unlocked on this phone")
        if (java.text.Normalizer.normalize(pass, java.text.Normalizer.Form.NFC).length < 8) throw ApiException(400, "Use at least 8 characters")
        rewrap(Vault.wrap(Vault.kdfPassphrase(pass, email()), key), "cairn-sync-passphrase:email", "passphrase-pbkdf2-sha256-310000")
        Prefs.syncPassphrase = true
    }
    fun removePassphrase(password: String) {
        val key = Vault.currentKey() ?: throw ApiException(400, "Sync is not unlocked on this phone")
        rewrap(Vault.wrap(Vault.kdf(password, email()), key), "cairn-vault:email", "pbkdf2-sha256-310000")
        Prefs.syncPassphrase = false
    }
    /** Forgot the passphrase: new key; the server copy is deleted and this phone uploads its data again. */
    fun resetSync(password: String) {
        val key = Vault.newKey()
        val (code, body) = Api.syncSend("/key", "PUT", JSONObject().put("wrappedKey", Vault.wrap(Vault.kdf(password, email()), key)).put("salt", "cairn-vault:email")
            .put("kdf", "pbkdf2-sha256-310000").put("kcv", Vault.kcv(key)).put("replace", true))
        if (code !in 200..299) throw ApiException(code, body.optString("error", "Couldn't reset sync"))
        Vault.setKey(key); Prefs.syncPassphrase = false; needPassphrase.value = false; status.value = ""
        state.clear(); rev = 0; save(); soon(0)
    }

    /** Debounced sync after a local change. */
    fun soon(delayMs: Long = 2500) {
        if (!enabled) return
        pending?.cancel(); pending = scope.launch { delay(delayMs); runCatching { syncNow() } }
    }

    suspend fun syncNow() = lock.withLock {
        if (!enabled) return@withLock
        busy.value = true
        try {
            pull(); push()
            Prefs.syncLast = System.currentTimeMillis(); status.value = ""
        } catch (e: Exception) { status.value = e.message ?: "Sync failed" }
        finally { busy.value = false; save() }
    }

    // ---------- Local snapshot ----------
    private val SETTINGS: Map<String, Pair<() -> Any, (Any) -> Unit>> = mapOf(
        "theme" to ({ Prefs.theme as Any } to { v: Any -> Prefs.theme = v.toString() }),
        "searchEngine" to ({ Prefs.searchEngine as Any } to { v: Any -> Prefs.searchEngine = v.toString() }),
        "searchAnswers" to ({ Prefs.searchAnswers as Any } to { v: Any -> Prefs.searchAnswers = v as Boolean }),
        "shield" to ({ Prefs.shield as Any } to { v: Any -> Prefs.shield = v as Boolean }),
        "block3p" to ({ Prefs.blockThirdPartyCookies as Any } to { v: Any -> Prefs.blockThirdPartyCookies = v as Boolean }),
        "dnt" to ({ Prefs.doNotTrack as Any } to { v: Any -> Prefs.doNotTrack = v as Boolean }),
        "replyLanguage" to ({ Prefs.replyLanguage as Any } to { v: Any -> Prefs.replyLanguage = v.toString() }),
        "shoppingCountry" to ({ Prefs.shoppingCountry as Any } to { v: Any -> Prefs.shoppingCountry = v.toString() }),
        "httpsUpgrade" to ({ Prefs.httpsUpgrade as Any } to { v: Any -> Prefs.httpsUpgrade = v as Boolean }),
    )

    private fun local(): Map<String, Pair<String, JSONObject>> {   // kind|key -> (kind, payload)
        val out = LinkedHashMap<String, Pair<String, JSONObject>>()
        fun put(kind: String, key: String, j: JSONObject) { out["$kind|$key"] = kind to j }
        BrowserDb.bookmarks().forEach { put("bookmarks", it.url, JSONObject().put("url", it.url).put("title", it.title).put("folder", it.folder).put("at", it.at)) }
        if (Prefs.saveHistory) BrowserDb.history(limit = 500).forEach { put("history", it.url, JSONObject().put("url", it.url).put("title", it.title).put("at", it.at).put("visits", it.visits)) }
        Vault.logins().forEach { put("passwords", it.origin + "\n" + it.username, JSONObject().put("origin", it.origin).put("username", it.username).put("password", it.password).put("at", it.updated)) }
        Vault.passkeys().forEach { put("passkeys", it.credId, Vault.passkeyJson(it).put("updated", 0)) }
        Vault.autofill("cards").forEach { put("cards", it.getString("id"), it) }
        Vault.autofill("addresses").forEach { put("addresses", it.getString("id"), it) }
        SETTINGS.forEach { (k, g) -> put("settings", k, JSONObject().put("k", k).put("v", g.first())) }
        Prefs.profileJson.takeIf { it.isNotBlank() }?.let { put("profile", "me", JSONObject().put("json", it)) }
        val tabs = runCatching { Browser.tabs.filter { !it.incognito && it.url.startsWith("http") }.take(60).map { JSONObject().put("url", it.url).put("title", it.title) } }.getOrDefault(emptyList())
        put("tabs", installId, JSONObject().put("install", installId).put("device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}").put("tabs", JSONArray(tabs)))
        return out
    }
    private fun hash(j: JSONObject) = b64u(sha256(j.toString().toByteArray())).take(22)

    private fun push() {
        val items = JSONArray(); val now = System.currentTimeMillis(); val newState = HashMap<String, Triple<String, String, String>>()
        val snap = local()
        for ((nk, v) in snap) {
            val (kind, j) = v; val key = nk.substringAfter('|'); val id = Vault.itemId(kind, key); val h = hash(j)
            newState[id] = Triple(kind, key, h)
            if (state[id]?.third == h) continue
            val payload = if (kind == "tabs") JSONObject(j.toString()).put("at", now) else j
            items.put(JSONObject().put("kind", kind).put("id", id).put("updatedAt", now).put("data", Vault.seal(payload.toString())))
        }
        // Deletions: items we had synced that no longer exist locally (history isn't deleted just because it aged out)
        for ((id, s) in state) if (id !in newState && s.first != "history") items.put(JSONObject().put("kind", s.first).put("id", id).put("updatedAt", now).put("deleted", true))
        var i = 0
        while (i < items.length()) {
            val batch = JSONArray(); for (k in i until minOf(items.length(), i + 500)) batch.put(items.get(k))
            val (code, body) = Api.syncSend("/changes", "POST", JSONObject().put("items", batch))
            if (code !in 200..299) throw ApiException(code, body.optString("error").ifEmpty { "Sync failed ($code)" })
            rev = maxOf(rev, body.optLong("rev"))
            i += 500
        }
        // keep history entries we've synced before so they aren't re-sent when they drop out of the local window
        state.entries.removeAll { (id, s) -> s.first != "history" && id !in newState }
        state.putAll(newState)
    }

    private fun pull() {
        var more = true
        while (more) {
            val r = Api.syncGet("/changes?since=$rev")
            val a = r.optJSONArray("items") ?: JSONArray()
            for (k in 0 until a.length()) {
                val it = a.getJSONObject(k); val id = it.getString("id"); val kind = it.getString("kind")
                if (it.optBoolean("deleted")) { state[id]?.let { s -> delete(kind, s.second) }; state.remove(id); continue }
                val j = Vault.open(it.optString("data"))?.let { s -> runCatching { JSONObject(s) }.getOrNull() } ?: continue
                val key = apply(kind, j) ?: continue
                state[id] = Triple(kind, key, "")   // hash recomputed from the local copy on push; "" forces no echo below
            }
            rev = r.optLong("rev", rev); more = r.optBoolean("more") && a.length() > 0
        }
        // Record what the local copy of pulled items now looks like so they aren't pushed straight back.
        val snap = local()
        for ((id, s) in state.entries.toList()) if (s.third.isEmpty()) snap["${s.first}|${s.second}"]?.let { state[id] = Triple(s.first, s.second, hash(it.second)) }
    }

    private fun apply(kind: String, j: JSONObject): String? = when (kind) {
        "bookmarks" -> j.getString("url").also { BrowserDb.upsertBookmark(it, j.optString("title"), j.optString("folder"), j.optLong("at")) }
        "history" -> j.getString("url").also { BrowserDb.mergeVisit(it, j.optString("title"), j.optLong("at"), j.optInt("visits", 1)) }
        "passwords" -> (j.getString("origin") + "\n" + j.getString("username")).also { Vault.saveLogin(j.getString("origin"), j.getString("username"), j.getString("password"), j.optLong("at")) }
        "passkeys" -> Vault.passkeyFrom(j).also { Vault.savePasskey(it) }.credId
        "settings" -> j.getString("k").also { k -> SETTINGS[k]?.second?.let { set -> runCatching { set(j.get("v")) } } }
        "profile" -> "me".also { Prefs.profileJson = j.optString("json") }
        "cards", "addresses" -> j.optString("id").takeIf { it.isNotBlank() }?.also { Vault.saveAutofill(kind, JSONObject(j.toString()), j.optLong("updated", System.currentTimeMillis())) }
        "tabs" -> if (j.optString("install") == installId) null else parseTabs(j)?.let { t -> remoteTabs.removeAll { it.device == t.device }; if (t.tabs.isNotEmpty()) remoteTabs.add(0, t); null }
        else -> null
    }
    private fun delete(kind: String, key: String) {
        when (kind) {
            "bookmarks" -> BrowserDb.deleteBookmarkUrl(key)
            "history" -> BrowserDb.deleteHistoryUrl(key)
            "passwords" -> Vault.deleteLogin(key.substringBefore('\n'), key.substringAfter('\n'))
            "passkeys" -> Vault.deletePasskey(key)
            "cards", "addresses" -> Vault.deleteAutofill(key)
        }
    }
    private fun parseTabs(j: JSONObject): RemoteTabs? {
        val a = j.optJSONArray("tabs") ?: return null
        return RemoteTabs(j.optString("device", "Device"), j.optLong("at"), (0 until a.length()).map { a.getJSONObject(it).let { t -> t.optString("url") to t.optString("title") } })
    }

    fun signOut() { pending?.cancel(); state.clear(); rev = 0; remoteTabs.clear(); save(); Vault.clearKey() }
}
