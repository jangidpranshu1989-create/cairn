package app.saarthi.browser.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.saarthi.browser.data.Api
import app.saarthi.browser.data.ApiException
import app.saarthi.browser.data.AppState
import app.saarthi.browser.data.Approval
import app.saarthi.browser.data.Conn
import app.saarthi.browser.data.Prefs
import app.saarthi.browser.data.Transfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

/**
 * Keeps a WebSocket open to the Saarthi server so payment approvals arrive in about a second.
 * Reconnects with exponential backoff, refreshes the access token, and catches up on anything
 * that arrived while offline via the /pending endpoints.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var loop: Job? = null
    private var attempt = 0
    private val handled = mutableSetOf<String>()
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (AppState.conn.value != Conn.ONLINE) reconnectNow() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this, 7, Notifier.service(this, "Ready for approvals and files"),
            when { Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING; Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC; else -> 0 })
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb)
        AppState.refreshFiles()
        run()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_SYNC) scope.launch { catchUp() }
        return START_STICKY
    }

    private fun run() {
        loop?.cancel()
        loop = scope.launch {
            while (true) {
                if (!Prefs.isPaired) { AppState.conn.value = Conn.SIGNED_OUT; stopSelf(); return@launch }
                AppState.conn.value = Conn.CONNECTING
                try {
                    // Make sure the access token is fresh before opening the socket
                    if (!Api.refresh()) { AppState.conn.value = Conn.SIGNED_OUT; AppState.paired.value = false; stopSelf(); return@launch }
                    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    ws = Api.http.newWebSocket(Request.Builder().url(Api.wsUrl()).build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            attempt = 0; AppState.conn.value = Conn.ONLINE; AppState.lastError.value = null
                            scope.launch { catchUp() }
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            AppState.lastError.value = t.message; closed.complete(Unit)
                        }
                    })
                    // App-level keepalive (server answers "pong"); OkHttp also sends protocol pings.
                    val ping = launch { while (true) { delay(30_000); ws?.send("{\"type\":\"ping\"}") } }
                    closed.await(); ping.cancel()
                } catch (e: ApiException) {
                    if (e.status == 401) { AppState.conn.value = Conn.SIGNED_OUT; AppState.paired.value = false; stopSelf(); return@launch }
                    AppState.lastError.value = e.message
                } catch (e: Exception) { AppState.lastError.value = e.message }
                AppState.conn.value = Conn.OFFLINE
                attempt++
                delay(min(60_000L, 1000L * (1 shl min(attempt, 6))))
            }
        }
    }

    private fun reconnectNow() { attempt = 0; ws?.cancel() }

    private fun handle(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (m.optString("type")) {
            "approval_request" -> m.optJSONObject("approval")?.let { onApproval(Approval.from(it)) }
            "approval_decided" -> m.optJSONObject("approval")?.optString("id")?.let { AppState.removeApproval(it); Notifier.cancelApproval(this, it) }
            "transfer_ready" -> m.optJSONObject("transfer")?.let { onTransfer(Transfer.from(it)) }
            "device_removed" -> if (m.optString("id") == Prefs.deviceId) { Prefs.clearAccount(); AppState.paired.value = false; stopSelf() }
        }
    }

    private fun onApproval(a: Approval) {
        if (a.expiresAt in 1 until System.currentTimeMillis()) return
        AppState.upsertApproval(a)
        if (handled.add("a:" + a.id)) Notifier.approval(this, a)
    }

    private fun onTransfer(t: Transfer) {
        if (!handled.add("t:" + t.id) || !Prefs.autoSave) return
        scope.launch { runCatching { FileSaver.save(this@SyncService, t) }.onFailure { handled.remove("t:" + t.id); AppState.lastError.value = "Could not save ${t.filename}: ${it.message}" } }
    }

    private suspend fun catchUp() {
        runCatching { Api.pendingApprovals() }.onSuccess { list -> AppState.approvals.value = list; list.forEach(::onApproval) }
        runCatching { Api.pendingTransfers() }.onSuccess { it.forEach(::onTransfer) }
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCb) }
        ws?.close(1000, "bye"); scope.cancel()
        if (AppState.conn.value != Conn.SIGNED_OUT) AppState.conn.value = Conn.OFFLINE
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_SYNC = "sync"
        fun start(ctx: Context, action: String? = null) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java).setAction(action))
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, SyncService::class.java))
    }
}
