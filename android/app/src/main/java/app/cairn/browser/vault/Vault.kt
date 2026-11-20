package app.cairn.browser.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import app.cairn.browser.data.SecureStore
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class Login(val id: Long, val origin: String, val username: String, val password: String, val updated: Long)
data class Passkey(val credId: String, val rpId: String, val userHandle: String, val userName: String, val displayName: String,
                   val privPkcs8: ByteArray, val created: Long, val updated: Long)

fun b64u(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
fun unb64u(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

/**
 * The Cairn vault: saved passwords and passkeys, stored on the device encrypted with a non-exportable Android
 * Keystore key, plus the account-wide sync key that end-to-end encrypts everything Cairn syncs.
 *
 * Sync key scheme (Bitwarden-style):
 *   vaultKey   = 32 random bytes, created once per account by the first device
 *   KEK        = PBKDF2-HMAC-SHA256(password, "cairn-vault:" + email, 310 000 iterations, 256 bit)
 *   wrappedKey = AES-256-GCM(KEK, vaultKey)  -> the only thing the server stores
 * Each synced item is AES-256-GCM(vaultKey, json) and its server id is HMAC-SHA256(vaultKey, kind|naturalKey),
 * so the server never learns URLs, usernames, passwords or private keys.
 */
object Vault {
    private lateinit var h: SQLiteOpenHelper
    private val db get() = h.writableDatabase
    val version = mutableIntStateOf(0)
    /** True once the sync key is available on this device. */
    val ready = mutableStateOf(false)
    private var key: ByteArray? = null
    private val rnd = SecureRandom()

    fun init(ctx: Context) {
        h = object : SQLiteOpenHelper(ctx, "vault.db", null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE logins(id INTEGER PRIMARY KEY AUTOINCREMENT, origin TEXT NOT NULL, username TEXT NOT NULL, secret TEXT NOT NULL, updated INTEGER NOT NULL, UNIQUE(origin, username))")
                d.execSQL("CREATE TABLE passkeys(cred_id TEXT PRIMARY KEY, rp_id TEXT NOT NULL, user_handle TEXT, user_name TEXT, display_name TEXT, secret TEXT NOT NULL, created INTEGER, updated INTEGER)")
                d.execSQL("CREATE TABLE never(origin TEXT PRIMARY KEY)")
            }
            override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
        }
        val p = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
        SecureStore.decrypt(p.getString("k", null))?.let { key = unb64u(it); ready.value = true }
        prefs = p
    }
    private lateinit var prefs: android.content.SharedPreferences
    private fun changed() { version.intValue++ }

    // ---------- Sync key ----------
    fun kdf(password: String, email: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), ("cairn-vault:" + email.trim().lowercase()).toByteArray(), 310_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
    fun newKey(): ByteArray = ByteArray(32).also(rnd::nextBytes)
    fun wrap(kek: ByteArray, k: ByteArray): String = seal(kek, b64u(k))
    fun unwrap(kek: ByteArray, wrapped: String): ByteArray? = open(kek, wrapped)?.let(::unb64u)
    fun setKey(k: ByteArray) { key = k; prefs.edit().putString("k", SecureStore.encrypt(b64u(k))).apply(); ready.value = true }
    fun clearKey() { key = null; prefs.edit().remove("k").apply(); ready.value = false }

    fun seal(plain: String): String = seal(key ?: error("vault locked"), plain)
    fun open(enc: String): String? = key?.let { open(it, enc) }
    private fun seal(k: ByteArray, plain: String): String {
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        return "v1." + b64u(iv + c.doFinal(plain.toByteArray()))
    }
    private fun open(k: ByteArray, enc: String): String? = runCatching {
        val b = unb64u(enc.removePrefix("v1."))
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()
    fun itemId(kind: String, natural: String): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key ?: error("vault locked"), "HmacSHA256"))
        return b64u(m.doFinal("$kind|$natural".toByteArray())).take(32)
    }

    // ---------- Passwords ----------
    fun origin(url: String): String = runCatching { android.net.Uri.parse(url).let { "${it.scheme}://${it.host}${if (it.port > 0) ":" + it.port else ""}" } }.getOrDefault("")
    private fun site(o: String) = runCatching { android.net.Uri.parse(o).host.orEmpty().removePrefix("www.").removePrefix("m.") }.getOrDefault("")

    fun logins(query: String = ""): List<Login> = db.rawQuery("SELECT id,origin,username,secret,updated FROM logins ORDER BY origin, username", null).use { c ->
        buildList { while (c.moveToNext()) add(Login(c.getLong(0), c.getString(1), c.getString(2), SecureStore.decrypt(c.getString(3)).orEmpty(), c.getLong(4))) }
    }.filter { query.isBlank() || it.origin.contains(query, true) || it.username.contains(query, true) }
    /** Logins for a page, matching the registrable site so m.example.com and example.com share credentials. */
    fun loginsFor(url: String): List<Login> { val s = site(origin(url)); if (s.isEmpty()) return emptyList(); return logins().filter { site(it.origin) == s } }
    fun saveLogin(origin: String, username: String, password: String, at: Long = System.currentTimeMillis()) {
        db.insertWithOnConflict("logins", null, ContentValues().apply { put("origin", origin); put("username", username); put("secret", SecureStore.encrypt(password)); put("updated", at) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }
    /** Removes saved passwords (and Cairn passkeys) changed since [sinceMs]; 0 = all. Deletions sync to other devices. */
    fun clearSince(sinceMs: Long, passkeys: Boolean = false) {
        db.delete("logins", "updated>=?", arrayOf(sinceMs.toString()))
        if (passkeys) db.delete("passkeys", "COALESCE(created,0)>=?", arrayOf(sinceMs.toString()))
        changed()
    }
    fun deleteLogin(origin: String, username: String) { db.delete("logins", "origin=? AND username=?", arrayOf(origin, username)); changed() }
    fun never(origin: String) = db.rawQuery("SELECT 1 FROM never WHERE origin=?", arrayOf(origin)).use { it.moveToFirst() }
    fun setNever(origin: String) { db.insertWithOnConflict("never", null, ContentValues().apply { put("origin", origin) }, SQLiteDatabase.CONFLICT_IGNORE) }

    // ---------- Passkeys ----------
    fun passkeys(rpId: String? = null): List<Passkey> = db.rawQuery(
        "SELECT cred_id,rp_id,user_handle,user_name,display_name,secret,created,updated FROM passkeys ${if (rpId != null) "WHERE rp_id=?" else ""} ORDER BY updated DESC",
        if (rpId != null) arrayOf(rpId) else null).use { c ->
        buildList { while (c.moveToNext()) { val sk = SecureStore.decrypt(c.getString(5)) ?: continue
            add(Passkey(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getString(4) ?: "", unb64u(sk), c.getLong(6), c.getLong(7))) } }
    }
    fun savePasskey(p: Passkey) {
        db.insertWithOnConflict("passkeys", null, ContentValues().apply {
            put("cred_id", p.credId); put("rp_id", p.rpId); put("user_handle", p.userHandle); put("user_name", p.userName); put("display_name", p.displayName)
            put("secret", SecureStore.encrypt(b64u(p.privPkcs8))); put("created", p.created); put("updated", p.updated) }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }
    fun touchPasskey(credId: String) { db.update("passkeys", ContentValues().apply { put("updated", System.currentTimeMillis()) }, "cred_id=?", arrayOf(credId)) }
    fun deletePasskey(credId: String) { db.delete("passkeys", "cred_id=?", arrayOf(credId)); changed() }

    fun passkeyJson(p: Passkey) = JSONObject().put("credId", p.credId).put("rpId", p.rpId).put("userHandle", p.userHandle).put("userName", p.userName)
        .put("displayName", p.displayName).put("priv", b64u(p.privPkcs8)).put("created", p.created).put("updated", p.updated)
    fun passkeyFrom(j: JSONObject) = Passkey(j.getString("credId"), j.getString("rpId"), j.optString("userHandle"), j.optString("userName"), j.optString("displayName"),
        unb64u(j.getString("priv")), j.optLong("created"), j.optLong("updated"))

    fun wipe() { db.delete("logins", null, null); db.delete("passkeys", null, null); changed() }
}
