package app.cairn.browser.service

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.cairn.browser.BuildConfig
import app.cairn.browser.CairnApp
import app.cairn.browser.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Automatic updates for the APK people installed from GitHub (no Play Store).
 *
 * Every few hours Cairn reads `android-update.json` from the latest GitHub release, downloads the new APK in the
 * background, checks its SHA-256, and installs it with PackageInstaller the next time Cairn is in the background, so
 * nothing is interrupted. Android itself checks that the new APK is signed with the same key as the installed app.
 *
 * On Android 12+ the update installs silently once Cairn is the app's installer of record (i.e. from the second
 * auto-update on). Android asks once: the very first time, the user taps "Update" (and allows "Install unknown apps"
 * for Cairn); that is an operating-system rule for apps outside an app store.
 */
object Updater {
    const val MANIFEST = "https://github.com/jangidpranshu1989-create/cairn/releases/latest/download/android-update.json"
    private const val WORK = "cairn-auto-update"
    private const val NOTIF_ID = 7101
    private const val ACTION_STATUS = "app.cairn.browser.UPDATE_STATUS"

    /** UI state for Settings → About. */
    val state = mutableStateOf("")
    @Volatile private var ready: File? = null
    @Volatile private var readyVersion = ""
    @Volatile private var started = 0
    private lateinit var app: Application

    private val http by lazy { OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).followRedirects(true).build() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("auto", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("auto", on).apply(); if (on) schedule(ctx) else WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }

    fun init(a: Application) {
        app = a
        // Install only while no Cairn screen is visible, so an update never closes a page the user is reading.
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(x: Activity) { started++ }
            override fun onActivityStopped(x: Activity) { started = maxOf(0, started - 1); if (started == 0) Handler(Looper.getMainLooper()).postDelayed({ installIfIdle(a) }, 8_000) }
            override fun onActivityCreated(x: Activity, b: Bundle?) {}
            override fun onActivityResumed(x: Activity) {}
            override fun onActivityPaused(x: Activity) {}
            override fun onActivitySaveInstanceState(x: Activity, b: Bundle) {}
            override fun onActivityDestroyed(x: Activity) {}
        })
        // Leftovers from an update that already installed.
        runCatching { File(a.cacheDir, "updates").listFiles()?.forEach { f -> val c = f.name.substringAfter("cairn-").substringBefore(".").toIntOrNull() ?: 0; if (c <= BuildConfig.VERSION_CODE) f.delete() } }
        if (enabled(a)) schedule(a)
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setInitialDelay(2, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class UpdateWorker(ctx: Context, p: WorkerParameters) : androidx.work.Worker(ctx, p) {
        override fun doWork(): Result = runCatching { check(applicationContext, manual = false); Result.success() }.getOrElse { Result.retry() }
    }

    /** "Check for updates" in About. */
    fun checkNow(ctx: Context) { state.value = "Checking…"; Thread { runCatching { check(ctx.applicationContext, manual = true) }.onFailure { state.value = "Couldn't check for updates. Try again later." } }.start() }

    /** Downloads the newest APK if there is one. Returns true when an update is ready to install. */
    fun check(ctx: Context, manual: Boolean): Boolean {
        val m = http.newCall(Request.Builder().url(MANIFEST).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) { if (manual) state.value = "Couldn't check for updates. Try again later."; return false }
            JSONObject(r.body!!.string())
        }
        val code = m.optInt("versionCode"); val name = m.optString("versionName"); val url = m.optString("url"); val sha = m.optString("sha256").lowercase()
        if (code <= BuildConfig.VERSION_CODE || !url.startsWith("https://github.com/") || sha.length != 64) {
            if (manual) state.value = "Cairn is up to date (${BuildConfig.VERSION_NAME})"; return false
        }
        // Respect Data Saver for automatic downloads; a manual check always downloads.
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (!manual && cm?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && cm.isActiveNetworkMetered) return false
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "cairn-$code.apk")
        if (!(f.exists() && sha256(f) == sha)) {
            state.value = "Downloading Cairn $name…"
            val tmp = File(dir, "cairn-$code.part")
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) { state.value = if (manual) "Download failed. Try again later." else ""; return false }
                r.body!!.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            }
            if (sha256(tmp) != sha) { tmp.delete(); state.value = if (manual) "Download was damaged. Try again later." else ""; return false }
            tmp.renameTo(f)
        }
        ready = f; readyVersion = name
        state.value = "Cairn $name is ready. It installs when you leave Cairn."
        if (manual || started == 0) Handler(Looper.getMainLooper()).post { installIfIdle(ctx, force = manual) }
        return true
    }

    private fun busy() = app.cairn.browser.agent.Agent.running || runCatching { Downloader.active }.getOrDefault(false)

    private fun installIfIdle(ctx: Context, force: Boolean = false) {
        val f = ready ?: return
        if (!f.exists()) { ready = null; return }
        if (!force && (started > 0 || busy())) return
        Thread { runCatching { install(ctx.applicationContext, f) }.onFailure { state.value = "Update couldn't be installed. Try again from About." } }.start()
    }

    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(f.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("cairn.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS).putExtra("v", readyVersion),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(cb.intentSender)
        }
    }

    /** Android needs one tap (first auto-update, or older Android). Show it right away if Cairn is open, else notify. */
    internal fun needsUserAction(ctx: Context, confirm: Intent, version: String) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (started > 0) { runCatching { ctx.startActivity(confirm) }; return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val tap = PendingIntent.getActivity(ctx, NOTIF_ID, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CairnApp.CH_UPDATES).setSmallIcon(R.drawable.ic_stat_cairn).setColor(0xFFB89A6A.toInt())
            .setContentTitle("Cairn ${version.ifBlank { "update" }} is ready").setContentText("Tap to install. Your tabs stay open.")
            .setContentIntent(tap).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { val b = ByteArray(64 * 1024); while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else i.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) Updater.needsUserAction(ctx, confirm, i.getStringExtra("v") ?: "")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = ""
            else -> Updater.state.value = "Update couldn't be installed. Try again from About."
        }
    }
}
