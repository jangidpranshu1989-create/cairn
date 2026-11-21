package app.cairn.browser.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's payment-approval key: an EC P-256 key pair created inside the Android Keystore (never leaves the secure
 * hardware) that can only sign right after the user passes fingerprint / face / screen lock, once per signature.
 *
 * The public key is registered with Cairn Cloud when the phone is paired. Approving a payment signs
 * "cairn-approval-v1:<approvalId>:approve", and the server only accepts the approval with a valid signature — so a
 * stolen session token can't approve a payment by just sending "verified: true".
 *
 * Needs Android 11+ (per-use keys that accept the screen lock as well as strong biometrics). Older phones keep the
 * previous flag-based approval.
 */
object ApprovalKey {
    private const val ALIAS = "cairn_approval_v1"
    val supported get() = Build.VERSION.SDK_INT >= 30

    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun message(approvalId: String) = "cairn-approval-v1:$approvalId:approve".toByteArray()

    /** Base64 SPKI public key, creating the key pair on first use. Null when unsupported or there is no screen lock. */
    fun publicKey(): String? {
        if (!supported) return null
        return runCatching {
            val ks = ks()
            val existing = ks.getCertificate(ALIAS)?.publicKey
            val pub = existing ?: run {
                val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(false)
                    .apply { if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL) }
                    .build()
                g.initialize(spec); g.generateKeyPair().public
            }
            Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
        }.getOrNull()
    }

    /** A Signature ready for BiometricPrompt.CryptoObject, or null when this phone has no usable key. */
    fun signer(): Signature? {
        if (!supported) return null
        return runCatching {
            val key = ks().getKey(ALIAS, null) as? PrivateKey ?: return null
            Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        }.getOrNull()
    }

    /** Signs with a Signature that BiometricPrompt has just unlocked. */
    fun sign(unlocked: Signature, approvalId: String): String {
        unlocked.update(message(approvalId))
        return Base64.encodeToString(unlocked.sign(), Base64.NO_WRAP)
    }

    fun wipe() { runCatching { ks().deleteEntry(ALIAS) } }
}
