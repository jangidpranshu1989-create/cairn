package app.cairn.browser.data

import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The Cairn account, created instantly on the device — no server round-trip, no waiting, works offline.
 * The password is never stored: only a salted PBKDF2-SHA256 hash (210k iterations), itself encrypted with the
 * Android Keystore. Cloud sign-in (for syncing with a computer) stays available separately.
 */
object LocalAccount {
    private const val ITER = 210_000
    val exists get() = Prefs.localAccountS.isNotEmpty()
    val email get() = runCatching { JSONObject(Prefs.localAccountS).optString("email") }.getOrDefault("")

    private fun hash(pw: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw, salt, ITER, 256)).encoded
    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun unb64(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    fun validEmail(e: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(e.trim())
    /** Returns a problem with the password, or null if it's good. */
    fun passwordProblem(pw: String): String? = when {
        pw.length < 8 -> "Use at least 8 characters"
        pw.all { it.isDigit() } -> "Add a letter or symbol — numbers alone are easy to guess"
        pw.lowercase() in setOf("password", "password1", "12345678", "qwertyui", "iloveyou", "cairn123") -> "That password is too common"
        else -> null
    }

    fun create(name: String, email: String, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val j = JSONObject().put("name", name.trim()).put("email", email.trim().lowercase()).put("plan", "free").put("local", true)
            .put("salt", b64(salt)).put("hash", b64(hash(pw.toCharArray(), salt))).put("created", System.currentTimeMillis())
        Prefs.localAccountS = j.toString(); Prefs.localSignedIn = true
        // Prefill the encrypted Delivery Profile so checkout autofill knows who you are.
        runCatching {
            val prof = Prefs.profileJson.takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: JSONObject()
            val parts = name.trim().split(Regex("\\s+"), 2)
            if (prof.optString("first").isBlank()) prof.put("first", parts.getOrElse(0) { "" })
            if (prof.optString("last").isBlank() && parts.size > 1) prof.put("last", parts[1])
            if (prof.optString("email").isBlank()) prof.put("email", email.trim().lowercase())
            Prefs.profileJson = prof.toString()
        }
    }

    fun verify(email: String, pw: String): Boolean = runCatching {
        val j = JSONObject(Prefs.localAccountS)
        if (!j.optString("email").equals(email.trim(), true)) return false
        java.security.MessageDigest.isEqual(hash(pw.toCharArray(), unb64(j.getString("salt"))), unb64(j.getString("hash")))
    }.getOrDefault(false)

    fun signIn(email: String, pw: String): Boolean = verify(email, pw).also { if (it) Prefs.localSignedIn = true }
    fun signOut() { Prefs.localSignedIn = false }
    fun rename(name: String) = runCatching { Prefs.localAccountS = JSONObject(Prefs.localAccountS).put("name", name.trim()).toString() }
    fun changePassword(old: String, new: String): Boolean {
        val j = runCatching { JSONObject(Prefs.localAccountS) }.getOrNull() ?: return false
        if (!verify(j.optString("email"), old)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Prefs.localAccountS = j.put("salt", b64(salt)).put("hash", b64(hash(new.toCharArray(), salt))).toString(); return true
    }
    fun delete() { Prefs.localAccountS = ""; Prefs.localSignedIn = false }
}
