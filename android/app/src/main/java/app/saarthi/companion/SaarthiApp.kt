package app.saarthi.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.saarthi.companion.data.Prefs
import app.saarthi.companion.service.SyncService

class SaarthiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_APPROVALS, "Payment approvals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Approve or deny payments and purchases started by Saarthi on your computer"
            enableVibration(true); vibrationPattern = longArrayOf(0, 250, 120, 250)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CH_FILES, "Files from your computer", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "PDFs, articles and screenshots sent from Saarthi"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps Saarthi connected so approvals arrive instantly"; setShowBadge(false)
        })
        if (Prefs.isPaired) SyncService.start(this)
    }

    companion object {
        const val CH_APPROVALS = "approvals"
        const val CH_FILES = "files"
        const val CH_SERVICE = "service"
    }
}
