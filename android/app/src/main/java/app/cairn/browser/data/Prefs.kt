package app.cairn.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import app.cairn.browser.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class SavedFile(val id: String, val name: String, val path: String, val uri: String, val size: Long, val at: Long)

/**
 * App preferences. Every setting is backed by Compose state, so screens that read a value
 * recompose automatically when it changes. Secrets (tokens, API keys) are AES-GCM encrypted
 * with a non-exportable Android Keystore key.
 */
object Prefs {
    private lateinit var p: SharedPreferences
    fun init(ctx: Context) { p = ctx.getSharedPreferences("cairn", Context.MODE_PRIVATE) }
    /** Used by JVM screenshot tests, where the platform SharedPreferences is a stub. */
    fun initWith(prefs: SharedPreferences) { p = prefs }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        val s by lazy { mutableStateOf(p.getString(key, def) ?: def) }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = s.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { s.value = value; p.edit().putString(key, value).apply() }
    }
    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        val s by lazy { mutableStateOf(p.getBoolean(key, def)) }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = s.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { s.value = value; p.edit().putBoolean(key, value).apply() }
    }
    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        val s by lazy { mutableStateOf(p.getInt(key, def)) }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = s.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { s.value = value; p.edit().putInt(key, value).apply() }
    }
    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any?, Long> {
        val s by lazy { mutableStateOf(p.getLong(key, def)) }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = s.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { s.value = value; p.edit().putLong(key, value).apply() }
    }
    private fun secret(key: String) = object : ReadWriteProperty<Any?, String> {
        val s by lazy { mutableStateOf(runCatching { SecureStore.decrypt(p.getString(key, null)) }.getOrNull() ?: "") }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = s.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            s.value = value; p.edit().putString(key, value.takeIf { it.isNotEmpty() }?.let { runCatching { SecureStore.encrypt(it) }.getOrNull() }).apply()
        }
    }

    // ---- Experience ----
    var haptics by bool("haptics", true)
    var memory by bool("memory", true)                  // on-device "Recall" index of pages you read
    var keepSignedIn by bool("keepSignedIn", true)      // keep site logins (cookies) across restarts
    var agentLivePreview by bool("agentPip", true)      // floating live view while the agent works in the background
    var autoOffers by bool("autoOffers", true)          // look for coupons and bank offers at checkout
    var autofillProfile by bool("autofillProfile", true)
    /** Encrypted JSON: {first,last,email,phone,line1,line2,city,state,pin,country}. */
    var profileJson by secret("profile")
    /** Encrypted JSON array of the user's cards as bank + network + type only (never numbers): [{bank,network,type}]. */
    var cardsJson by secret("cards")
    var tracksJson by str("tracks", "[]")               // price-drop / back-in-stock trackers

    // ---- Browser ----
    var theme by str("theme", "system")                 // system | dark | light
    var searchEngine by str("searchEngineV3", "cairn") // see Search.engines — Cairn Search is the default (no Google services anywhere in Cairn)
    var searchAnswers by bool("searchAnswers", true) // AI answer with sources on Cairn Search
    var searchSuggestions by bool("searchSuggestions", true)
    var shield by bool("shield", true)                   // ad & tracker blocking
    var shieldTotal by long("shieldTotal", 0)
    var blockThirdPartyCookies by bool("block3pCookies", true)
    var doNotTrack by bool("dnt", true)
    var javascript by bool("javascript", true)
    var popups by bool("popups", false)                  // allow sites to open windows without a tap
    var desktopDefault by bool("desktopDefault", false)
    var textZoom by int("textZoom", 100)
    var darkPages by bool("darkPages", false)            // algorithmic darkening of web content
    var saveHistory by bool("saveHistory", true)
    var restoreTabs by bool("restoreTabs", true)
    var httpsUpgrade by bool("httpsUpgrade", true)
    var compactBar by bool("compactBar", true)           // shrink the address bar while scrolling
    var openLinksInBackground by bool("bgLinks", false)
    var onboarded by bool("onboarded", false)
    var tabsJson by str("tabs", "[]")

    // ---- AI & agent ----
    var aiMode by str("aiModeV2", "builtin")             // builtin (keys shipped with Cairn) | cloud (Cairn account) | byok
    var groqKey by secret("groqKey")
    var nvidiaKey by secret("nvidiaKey")
    var preferredProvider by str("preferredProvider", "groq")
    var groqModel by str("groqModel", "openai/gpt-oss-120b")
    var groqFastModel by str("groqFastModel", "openai/gpt-oss-20b")
    var nvidiaModel by str("nvidiaModel", "nvidia/nemotron-3.5-super-vl-120b-a12b")
    var nvidiaFastModel by str("nvidiaFastModel", "nvidia/nemotron-3.5-bolt-e4b")
    var replyLanguage by str("replyLanguage", "auto")
    var shoppingCountry by str("shoppingCountry", "IN")
    var agentMaxSteps by int("agentMaxStepsV2", 45)
    var agentUseProfile by bool("agentUseProfile", true)  // let tasks use your name, email, phone and address
    var agentConfirmEach by bool("agentConfirmEach", false)
    var ttsRate by int("ttsRate", 100)                   // percent
    var ttsVoice by str("ttsVoice", "")

    // ---- Account / companion ----
    var server by str("server", BuildConfig.DEFAULT_SERVER)
    var deviceIdS by str("deviceId", "")
    var userJsonS by str("user", "")
    var accessTokenS by secret("at")
    var refreshTokenS by secret("rt")
    var autoSave by bool("autoSave", true)
    var localAccountS by secret("localAccount")          // on-device account: name, email, PBKDF2 password hash
    var localSignedIn by bool("localSignedIn", false)

    var deviceId: String?
        get() = deviceIdS.ifEmpty { null }
        set(v) { deviceIdS = v ?: "" }
    var userJson: String?
        get() = userJsonS.ifEmpty { null }
        set(v) { userJsonS = v ?: "" }
    var accessToken: String?
        get() = accessTokenS.ifEmpty { null }
        set(v) { accessTokenS = v ?: "" }
    var refreshToken: String?
        get() = refreshTokenS.ifEmpty { null }
        set(v) { refreshTokenS = v ?: ""; p.edit().commit() }

    val isSignedIn get() = refreshToken != null
    val isPaired get() = deviceId != null && refreshToken != null
    /** Signed in with any Cairn account — the on-device one or a cloud one. */
    val hasAccount get() = isSignedIn || (localSignedIn && localAccountS.isNotEmpty())
    private val acct get() = userJson ?: localAccountS.takeIf { localSignedIn && it.isNotEmpty() }
    val userName get() = acct?.let { runCatching { JSONObject(it).optString("name").ifEmpty { JSONObject(it).optString("email") } }.getOrNull() } ?: ""
    val userEmail get() = acct?.let { runCatching { JSONObject(it).optString("email") }.getOrNull() } ?: ""
    val userPlan get() = acct?.let { runCatching { JSONObject(it).optString("plan", "free") }.getOrNull() } ?: "free"

    fun files(): List<SavedFile> = runCatching {
        val a = JSONArray(p.getString("files", "[]")); (0 until a.length()).map { a.getJSONObject(it) }.map {
            SavedFile(it.getString("id"), it.getString("name"), it.getString("path"), it.getString("uri"), it.optLong("size"), it.getLong("at"))
        }
    }.getOrDefault(emptyList())

    fun addFile(f: SavedFile) {
        val list = (listOf(f) + files().filter { it.id != f.id }).take(100)
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("path", it.path).put("uri", it.uri).put("size", it.size).put("at", it.at)) }
        p.edit().putString("files", a.toString()).apply()
    }

    /** Signs out of the Cairn account (keeps browser settings, history and API keys). */
    fun clearAccount() {
        deviceId = null; userJson = null; accessToken = null; refreshToken = null
        p.edit().remove("files").apply()
    }
}
