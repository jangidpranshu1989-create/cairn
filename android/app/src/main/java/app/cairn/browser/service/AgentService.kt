package app.cairn.browser.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.cairn.browser.CairnApp
import app.cairn.browser.R
import app.cairn.browser.agent.Agent
import app.cairn.browser.agent.AiMsg
import app.cairn.browser.agent.ApprovalItem
import app.cairn.browser.agent.AskItem
import app.cairn.browser.ui.BrowserActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps an assistant task alive when the user leaves Cairn (like Comet's background agent): a foreground service
 * with a live progress notification, a Stop action, and a "done" / "needs you" notification when the task
 * finishes or is waiting for an approval or answer.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + app.cairn.browser.Crash.handler)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this, ID_RUNNING, progress("Working on it…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        scope.launch {
            snapshotFlow { Triple(Agent.running, Agent.status, Agent.items.lastOrNull()) }.distinctUntilChanged().collect { (running, status, last) ->
                val nm = NotificationManagerCompat.from(this@AgentService)
                if (!running) { finish(last); return@collect }
                when {
                    last is ApprovalItem && last.state == "pending" -> post(ID_ATTENTION, "Cairn needs your approval", last.title, true)
                    last is AskItem && last.answer == null -> post(ID_ATTENTION, "Cairn has a question", last.question, true)
                    else -> nm.cancel(ID_ATTENTION)
                }
                if (canPost()) nm.notify(ID_RUNNING, progress(status.ifBlank { "Working on it…" }))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Agent.stop() }
        return START_NOT_STICKY
    }

    private fun finish(last: Any?) {
        if (!foreground) {
            val text = (last as? AiMsg)?.markdown?.replace(Regex("[#*_`>\\[\\]]|\\(https?://[^)]+\\)"), "")?.trim()?.take(300).orEmpty()
            val err = (last as? AiMsg)?.error == true
            post(ID_DONE, if (err) "Task stopped" else "Task done", text.ifBlank { "Tap to see the result" }, false)
        }
        NotificationManagerCompat.from(this).cancel(ID_ATTENTION)
        stopSelf()
    }

    private fun canPost() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun open() = PendingIntent.getActivity(this, 11, Intent(this, BrowserActivity::class.java).setAction(BrowserActivity.ACTION_ASSISTANT)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun progress(text: String) = NotificationCompat.Builder(this, CairnApp.CH_AGENT)
        .setSmallIcon(R.drawable.ic_stat_cairn).setColor(0xFFB89A6A.toInt())
        .setContentTitle("Cairn is working in the background").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setProgress(0, 0, true).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setContentIntent(open())
        .addAction(0, "Stop", PendingIntent.getService(this, 12, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()

    private fun post(id: Int, title: String, text: String, urgent: Boolean) {
        if (!canPost()) return
        NotificationManagerCompat.from(this).notify(id, NotificationCompat.Builder(this, if (urgent) CairnApp.CH_APPROVALS else CairnApp.CH_AGENT_DONE)
            .setSmallIcon(R.drawable.ic_stat_cairn).setColor(0xFFB89A6A.toInt())
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open()).setAutoCancel(true).build())
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ID_RUNNING = 21; const val ID_ATTENTION = 22; const val ID_DONE = 23
        const val ACTION_STOP = "app.cairn.agent.STOP"
        /** Whether Cairn's UI is on screen (set by BrowserActivity). */
        @Volatile var foreground = true
        fun start(ctx: Context) {
            NotificationManagerCompat.from(ctx).cancel(ID_DONE)
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AgentService::class.java)) }
        }
    }
}
