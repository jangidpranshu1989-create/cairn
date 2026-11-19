package app.cairn.browser.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.cairn.browser.data.Api
import app.cairn.browser.data.AppState
import app.cairn.browser.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Prefs.init(ctx)
        if (Prefs.isPaired) runCatching { SyncService.start(ctx) }
    }
}

/** "Deny" straight from the notification — denying never needs biometrics. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        Notifier.cancelApproval(ctx, id)
        CoroutineScope(Dispatchers.IO).launch {
            try { Api.decide(id, approve = false, verified = false); AppState.removeApproval(id) } catch (_: Exception) {} finally { pending.finish() }
        }
    }
    companion object { const val DENY = "app.cairn.browser.DENY" }
}
