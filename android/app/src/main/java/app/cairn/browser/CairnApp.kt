package app.cairn.browser

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.cairn.browser.data.Prefs
import app.cairn.browser.service.SyncService

class CairnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        app.cairn.browser.data.BrowserDb.init(this)
        app.cairn.browser.vault.Vault.init(this)
        app.cairn.browser.service.Downloader.init(this)
        app.cairn.browser.vault.Sync.init(this)
        app.cairn.browser.browser.Shield.init(this)
        runCatching { app.cairn.browser.browser.SecureDns.apply() }
        app.cairn.browser.agent.PageJs.init(this)
        app.cairn.browser.service.ReadAloud.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_APPROVALS, "Payment approvals", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Approve or deny payments and purchases started by Cairn on your computer"
            enableVibration(true); vibrationPattern = longArrayOf(0, 250, 120, 250)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(CH_FILES, "Files from your computer", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "PDFs, articles and screenshots sent from Cairn"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps Cairn connected so approvals arrive instantly"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_MEDIA, "Read aloud", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Playback controls while Cairn reads a page to you"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_PRICES, "Price drops & stock alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when a product you track gets cheaper or comes back in stock"
        })
        nm.createNotificationChannel(NotificationChannel(CH_AGENT, "Assistant working", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while the assistant finishes a task in the background"; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_AGENT_DONE, "Assistant results", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when a background task is done"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SITES, "Website notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Notifications from websites you allowed in Site settings"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Download progress, speed and time left"; setShowBadge(false)
        })
        app.cairn.browser.data.Recall.init(this)
        app.cairn.browser.browser.Favicons.init(this)
        app.cairn.browser.data.CairnSearch.init(this)
        app.cairn.browser.service.PriceWatch.schedule(this)
        // "Stay signed in" off → start every session signed out (cookies wiped); on → cookies persist and are flushed to disk often.
        if (!Prefs.keepSignedIn) runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
        if (Prefs.isPaired) SyncService.start(this)
    }

    companion object {
        const val CH_APPROVALS = "approvals"
        const val CH_FILES = "files"
        const val CH_SERVICE = "service"
        const val CH_MEDIA = "media"
        const val CH_PRICES = "prices"
        const val CH_AGENT = "agent"
        const val CH_AGENT_DONE = "agent_done"
        const val CH_SITES = "sites"
        const val CH_DOWNLOADS = "downloads"
    }
}
