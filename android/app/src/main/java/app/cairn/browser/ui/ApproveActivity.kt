package app.cairn.browser.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.cairn.browser.data.Api
import app.cairn.browser.data.AppState
import app.cairn.browser.data.Approval
import app.cairn.browser.data.ApprovalKey
import app.cairn.browser.data.Prefs
import app.cairn.browser.service.Notifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full-screen approval sheet. Approving requires fingerprint / face / device PIN; denying does not. */
class ApproveActivity : FragmentActivity() {
    private var approvalId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // no screenshots of payment details
        approvalId = intent.getStringExtra(EXTRA_ID)
        setContent { CairnTheme { Surface(color = C.bg, modifier = Modifier.fillMaxSize()) { Sheet() } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); approvalId = intent.getStringExtra(EXTRA_ID) }

    private enum class Phase { REVIEW, SENDING, APPROVED, DENIED, GONE, ERROR }

    @Composable
    private fun Sheet() {
        val list by AppState.approvals.collectAsStateWithLifecycle()
        var fetched by remember { mutableStateOf<Approval?>(null) }
        val a = list.firstOrNull { it.id == approvalId } ?: fetched
        var phase by remember(approvalId) { mutableStateOf(Phase.REVIEW) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(approvalId) {
            if (list.none { it.id == approvalId }) runCatching { Api.pendingApprovals() }.onSuccess { l ->
                AppState.approvals.value = l; fetched = l.firstOrNull { it.id == approvalId }; if (fetched == null) phase = Phase.GONE }
        }
        // Expiry countdown
        var left by remember { mutableLongStateOf(0L) }
        LaunchedEffect(a?.expiresAt) { while (a != null) { left = ((a.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0); if (left == 0L && phase == Phase.REVIEW) phase = Phase.GONE; delay(1000) } }

        fun decide(approve: Boolean, verified: Boolean, signature: String? = null) {
            val id = a?.id ?: return
            phase = Phase.SENDING
            lifecycleScope.launch {
                try {
                    Api.decide(id, approve, verified, signature)
                    AppState.removeApproval(id); Notifier.cancelApproval(this@ApproveActivity, id)
                    phase = if (approve) Phase.APPROVED else Phase.DENIED
                    delay(1400); finish()
                } catch (e: Exception) { error = e.message; phase = Phase.ERROR }
            }
        }
        fun authenticate() {
            val authenticators = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            val can = BiometricManager.from(this).canAuthenticate(authenticators)
            if (can != BiometricManager.BIOMETRIC_SUCCESS) { error = "Set up a screen lock (fingerprint, face or PIN) on this phone to approve payments."; phase = Phase.ERROR; return }
            // Android 11+: the fingerprint / screen lock unlocks this phone's Keystore approval key for exactly one
            // signature, and the server checks that signature (see ApprovalKey). Older phones send the verified flag.
            val id = a?.id
            val signer = if (Prefs.approvalKeySent) ApprovalKey.signer() else null
            if (Prefs.approvalKeySent && ApprovalKey.supported && signer == null) {
                error = "This phone's approval key was reset (for example after the screen lock was removed). Pair the phone again from your computer."; phase = Phase.ERROR; return
            }
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.signature
                    if (signer != null && id != null) {
                        val sig = runCatching { ApprovalKey.sign(unlocked ?: signer, id) }.getOrNull()
                        if (sig == null) { error = "Couldn't sign the approval. Try again."; return }
                        decide(true, verified = true, signature = sig)
                    } else decide(true, verified = true)
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON && code != BiometricPrompt.ERROR_CANCELED) { error = msg.toString() }
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (a?.kind == "payment") "Confirm payment" else "Confirm it's you")
                .setSubtitle(listOf(a?.amount, a?.merchant).filter { !it.isNullOrEmpty() }.joinToString(" · "))
                .setAllowedAuthenticators(authenticators).setConfirmationRequired(true).build()
            if (signer != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(signer)) else prompt.authenticate(info)
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(28.dp); Spacer(Modifier.width(10.dp)); Text("Cairn", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (phase == Phase.REVIEW && left > 0) Text("Expires in ${left / 60}:${"%02d".format(left % 60)}", color = C.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(0.6f))
            when (phase) {
                Phase.APPROVED -> Result(Ic.CheckCircle, C.ok, "Approved", "Cairn will continue on your computer.")
                Phase.DENIED -> Result(Ic.Cancel, C.err, "Denied", "Nothing was paid. Cairn has stopped.")
                Phase.GONE -> Result(Ic.HourglassEmpty, C.muted, "No longer pending", "This request was already answered or expired.")
                else -> if (a == null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.accent) } else Details(a)
            }
            Spacer(Modifier.weight(1f))
            error?.let { Text(it, color = C.err, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
            if (a != null && (phase == Phase.REVIEW || phase == Phase.ERROR || phase == Phase.SENDING)) {
                Button(onClick = { error = null; authenticate() }, enabled = phase != Phase.SENDING, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
                    if (phase == Phase.SENDING) CircularProgressIndicator(Modifier.size(20.dp), color = C.accentInk, strokeWidth = 2.dp)
                    else { Icon(Ic.Fingerprint, null); Spacer(Modifier.width(10.dp)); Text(if (a.kind == "payment") "Approve ${a.amount}".trim() else "Approve", fontSize = 16.sp) }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { decide(false, false) }, enabled = phase != Phase.SENDING, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, C.line)) { Text("Deny", color = C.text, fontSize = 16.sp) }
                Spacer(Modifier.height(12.dp))
                Text("Only approve if you started this on your computer.", color = C.faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else if (phase == Phase.GONE) {
                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text("Close", color = C.text) }
            }
        }
    }

    @Composable
    private fun Details(a: Approval) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(C.agentSoft), contentAlignment = Alignment.Center) {
                Icon(if (a.kind == "payment") Ic.Payments else if (a.kind == "login") Ic.Key else Ic.VerifiedUser, null, tint = C.agent, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(when (a.kind) { "payment" -> "Approve this payment?"; "purchase" -> "Place this order?"; "login" -> "Allow this sign-in?"; else -> "Approve this action?" }, color = C.muted, fontSize = 15.sp)
            if (a.amount.isNotEmpty()) Text(a.amount, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 4.dp))
            else Text(a.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (a.merchant.isNotEmpty()) Kv("Merchant", a.merchant)
                if (a.item.isNotEmpty()) Kv("Item", a.item)
                if (a.amount.isNotEmpty() && a.title.isNotEmpty()) Kv("Action", a.title)
                if (a.url.isNotEmpty()) Kv("Website", runCatching { android.net.Uri.parse(a.url).host ?: a.url }.getOrDefault(a.url))
                if (a.note.isNotEmpty()) Kv("Note", a.note)
            }
        }
    }

    @Composable private fun Kv(k: String, v: String) = Row {
        Text(k, color = C.muted, fontSize = 14.sp, modifier = Modifier.width(92.dp))
        Text(v, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    private fun Result(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, title: String, sub: String) {
        val s = remember { Animatable(0.6f) }
        LaunchedEffect(Unit) { s.animateTo(1f, tween(320)) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).scale(s.value).clip(CircleShape).background(color.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(52.dp)) }
            Spacer(Modifier.height(18.dp)); Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp)); Text(sub, color = C.muted, textAlign = TextAlign.Center)
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(ctx: Context, id: String) = Intent(ctx, ApproveActivity::class.java).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
