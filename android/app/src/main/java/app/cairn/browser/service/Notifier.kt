package app.cairn.browser.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.cairn.browser.R
import app.cairn.browser.CairnApp
import app.cairn.browser.data.Approval
import app.cairn.browser.ui.ApproveActivity
import app.cairn.browser.ui.BrowserActivity

object Notifier {
    private const val ACCENT = 0xFFB89A6A.toInt()
    private fun canPost(ctx: Context) = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun approvalId(id: String) = 1000 + (id.hashCode() and 0xFFFF)

    fun approval(ctx: Context, a: Approval) {
        if (!canPost(ctx)) { ctx.startActivity(ApproveActivity.intent(ctx, a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        val open = PendingIntent.getActivity(ctx, approvalId(a.id), ApproveActivity.intent(ctx, a.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val deny = PendingIntent.getBroadcast(ctx, approvalId(a.id) + 1,
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.DENY).putExtra("id", a.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (a.kind) { "payment" -> "Approve payment${if (a.amount.isNotEmpty()) " of ${a.amount}" else ""}?"; "login" -> "Approve sign-in?"; else -> "Cairn needs your approval" }
        val text = listOf(a.merchant, a.item.ifEmpty { a.title }).filter { it.isNotEmpty() }.joinToString(" · ")
        val n = NotificationCompat.Builder(ctx, CairnApp.CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_cairn).setColor(ACCENT)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(ctx, CairnApp.CH_APPROVALS).setSmallIcon(R.drawable.ic_stat_cairn)
                .setContentTitle("Cairn needs your approval").build())
            .setContentIntent(open).setFullScreenIntent(open, true)
            .addAction(0, "Review & approve", open).addAction(0, "Deny", deny)
            .setAutoCancel(true).setOngoing(false)
            .setTimeoutAfter((a.expiresAt - System.currentTimeMillis()).coerceIn(10_000, 600_000))
            .build()
        NotificationManagerCompat.from(ctx).notify(approvalId(a.id), n)
    }

    /** A notification posted by a website the user allowed. Tapping it opens the page in Cairn. */
    fun site(ctx: Context, origin: String, title: String, body: String, url: String) {
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(ctx, origin.hashCode(), Intent(ctx, BrowserActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url.ifBlank { origin }))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(ctx).notify((origin + title).hashCode(), NotificationCompat.Builder(ctx, CairnApp.CH_SITES)
            .setSmallIcon(R.drawable.ic_stat_cairn).setColor(ACCENT).setContentTitle(title.ifBlank { Uri.parse(origin).host ?: origin })
            .setContentText(body).setSubText(Uri.parse(origin).host).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    fun cancelApproval(ctx: Context, id: String) = NotificationManagerCompat.from(ctx).cancel(approvalId(id))

    fun fileSaved(ctx: Context, name: String, folder: String, uri: Uri, mime: String) {
        if (!canPost(ctx)) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, name.hashCode(), Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(ctx).notify(name.hashCode(), NotificationCompat.Builder(ctx, CairnApp.CH_FILES)
            .setSmallIcon(R.drawable.ic_stat_cairn).setColor(ACCENT)
            .setContentTitle("Saved from your computer").setContentText("$name → $folder")
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    fun service(ctx: Context, text: String): Notification {
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, BrowserActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CairnApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_cairn).setColor(ACCENT)
            .setContentTitle("Cairn is connected").setContentText(text)
            .setContentIntent(pi).setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
    }
}
