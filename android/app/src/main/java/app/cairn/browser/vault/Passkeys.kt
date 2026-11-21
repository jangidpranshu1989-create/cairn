package app.cairn.browser.vault

import app.cairn.browser.browser.Browser
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import kotlin.coroutines.resume
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

class PasskeyFallback : Exception("fallback")
class WebAuthnError(val name: String, msg: String) : Exception(msg)

/**
 * Cairn's own synced passkey provider (a WebAuthn "virtual authenticator").
 *
 * Pages call navigator.credentials.create/get as usual; a shim forwards the request here together with the
 * *browser-verified* origin of the calling frame. Keys are ES256 (P-256), created on the device, unlocked with
 * the device's biometric / screen lock, stored in the Keystore-encrypted vault and synced end-to-end encrypted.
 * If the user cancels or has no Cairn passkey for the site, the request falls through to the platform
 * (Google Password Manager / security keys), so nothing ever breaks.
 */
object Passkeys {
    // Stable AAGUID that identifies "Cairn passkeys" to relying parties.
    private val AAGUID = byteArrayOf(0x63, 0x61, 0x69, 0x72, 0x6e, 0x2d, 0x70, 0x6b, 0x2d, 0x76, 0x31, 0x2d, 0x32, 0x30, 0x32, 0x36)
    private val rnd = SecureRandom()

    /** rpId must equal the origin's host or be a registrable parent of it, and the origin must be secure. */
    fun checkRp(origin: String, rpId: String) {
        val u = android.net.Uri.parse(origin); val host = u.host.orEmpty().lowercase()
        val secure = u.scheme == "https" || (u.scheme == "http" && (host == "localhost" || host.endsWith(".localhost")))
        if (!secure) throw WebAuthnError("SecurityError", "Passkeys need a secure (https) page")
        val rp = rpId.lowercase()
        if (!(host == rp || host.endsWith(".$rp")) || (!rp.contains('.') && rp != "localhost")) throw WebAuthnError("SecurityError", "The relying party ID is not valid for this page")
    }

    private fun clientData(type: String, challenge: String, origin: String) =
        JSONObject().put("type", type).put("challenge", challenge).put("origin", origin).put("crossOrigin", false).toString().replace("\\/", "/").toByteArray()

    private fun coseKey(pub: ECPublicKey): ByteArray {
        fun fixed(b: BigInteger) = b.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return Cbor.encode(linkedMapOf(1 to 2, 3 to -7, -1 to 1, -2 to fixed(pub.w.affineX), -3 to fixed(pub.w.affineY)))
    }

    // flags: UP(0x01) UV(0x04) BE(0x08, backup eligible) BS(0x10, backed up = synced) AT(0x40)
    private fun authData(rpId: String, flags: Int, attested: ByteArray? = null): ByteArray {
        val b = ByteBuffer.allocate(37 + (attested?.size ?: 0))
        b.put(sha256(rpId.toByteArray())); b.put(flags.toByte()); b.putInt(0); attested?.let { b.put(it) }
        return b.array()
    }

    suspend fun create(origin: String, o: JSONObject): JSONObject {
        val rp = o.optJSONObject("rp") ?: JSONObject()
        val rpId = rp.optString("id").ifEmpty { android.net.Uri.parse(origin).host.orEmpty() }
        checkRp(origin, rpId)
        val algs = o.optJSONArray("pubKeyCredParams") ?: JSONArray()
        if ((0 until algs.length()).none { algs.getJSONObject(it).optInt("alg") == -7 }) throw PasskeyFallback()
        val user = o.optJSONObject("user") ?: JSONObject()
        val excl = o.optJSONArray("excludeCredentials")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") }.toSet() } ?: emptySet()
        val mine = Vault.passkeys(rpId)
        if (mine.any { it.credId in excl }) throw WebAuthnError("InvalidStateError", "You already have a passkey for this account in Cairn")
        val name = user.optString("name").ifEmpty { user.optString("displayName") }
        if (!Browser.activity.authenticate("Create a passkey for $rpId", "${name.ifEmpty { "Your account" }} · saved in Cairn and synced to your devices")) throw PasskeyFallback()
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
        val credId = ByteArray(16).also(rnd::nextBytes)
        val cose = coseKey(kp.public as ECPublicKey)
        val attested = AAGUID + byteArrayOf((credId.size shr 8).toByte(), credId.size.toByte()) + credId + cose
        val ad = authData(rpId, 0x01 or 0x04 or 0x08 or 0x10 or 0x40, attested)
        val att = Cbor.encode(linkedMapOf("fmt" to "none", "attStmt" to emptyMap<String, Any>(), "authData" to ad))
        val cd = clientData("webauthn.create", o.getString("challenge"), origin)
        val now = System.currentTimeMillis()
        Vault.savePasskey(Passkey(b64u(credId), rpId, user.optString("id"), name, user.optString("displayName"), kp.private.encoded, now, now))
        app.cairn.browser.vault.Sync.soon()
        return JSONObject().put("id", b64u(credId)).put("clientDataJSON", b64u(cd)).put("attestationObject", b64u(att)).put("authenticatorData", b64u(ad))
            .put("publicKey", b64u(kp.public.encoded)).put("alg", -7).put("transports", JSONArray(listOf("internal", "hybrid")))
    }

    /** The Cairn passkeys that can answer this sign-in request (rpId checked against the real page origin). */
    fun candidates(origin: String, o: JSONObject): Pair<String, List<Passkey>> {
        val rpId = o.optString("rpId").ifEmpty { android.net.Uri.parse(origin).host.orEmpty() }
        checkRp(origin, rpId)
        val allow = o.optJSONArray("allowCredentials")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") }.toSet() } ?: emptySet()
        return rpId to Vault.passkeys(rpId).filter { allow.isEmpty() || it.credId in allow }
    }

    suspend fun get(origin: String, o: JSONObject): JSONObject {
        val (rpId, cands) = candidates(origin, o)
        if (cands.isEmpty()) throw PasskeyFallback()
        val pick = if (cands.size == 1) cands[0] else choose(rpId, cands) ?: throw PasskeyFallback()
        return assert(origin, rpId, o, pick) ?: throw PasskeyFallback()
    }

    /** Asks for fingerprint / face / screen lock, then signs the sign-in challenge with [pick]. Null = user cancelled. */
    suspend fun assert(origin: String, rpId: String, o: JSONObject, pick: Passkey): JSONObject? {
        if (!Browser.activity.authenticate("Sign in to $rpId", "with your passkey for ${pick.userName.ifEmpty { pick.displayName }}")) return null
        val ad = authData(rpId, 0x01 or 0x04 or 0x08 or 0x10)
        val cd = clientData("webauthn.get", o.getString("challenge"), origin)
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pick.privPkcs8))
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(ad); update(sha256(cd)) }.sign()
        Vault.touchPasskey(pick.credId)
        return JSONObject().put("id", pick.credId).put("clientDataJSON", b64u(cd)).put("authenticatorData", b64u(ad)).put("signature", b64u(sig)).put("userHandle", pick.userHandle)
    }

    private suspend fun choose(rpId: String, list: List<Passkey>): Passkey? = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        val a = Browser.activity
        a.runOnUiThread {
            var done = false
            android.app.AlertDialog.Builder(a).setTitle("Choose a passkey for $rpId")
                .setItems(list.map { it.userName.ifEmpty { it.displayName.ifEmpty { "Passkey" } } }.toTypedArray()) { _, i -> if (!done) { done = true; c.resume(list[i]) } }
                .setOnDismissListener { if (!done) { done = true; c.resume(null) } }.show()
        }
    }
}
