package app.cairn.browser

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import app.cairn.browser.browser.Browser as BrowserRef
import java.io.File

/**
 * Crash safety net.
 *  - Every uncaught exception is written to filesDir/crash/ (last 15 kept) instead of the app silently vanishing.
 *  - Background-thread and coroutine failures are logged and swallowed: the feature that failed stops, the browser keeps running.
 *  - Exceptions that escape the main looper (click handlers, callbacks) are caught by a loop guard and the UI carries on.
 *  - Only failures that leave the UI in a broken state (Compose composition, activity lifecycle, out of memory, or a crash
 *    loop) restart Cairn — tabs are restored and a short notice is shown.
 */
object Crash {
    private const val TAG = "CairnCrash"
    private lateinit var app: Application
    private val dir get() = File(app.filesDir, "crash")
    private val recent = ArrayDeque<Long>()
    @Volatile private var restarting = false

    /** Add to any long-lived CoroutineScope so a failed launch is logged instead of killing the process. */
    val handler = CoroutineExceptionHandler { _, e -> if (e !is kotlinx.coroutines.CancellationException) { save(e, Thread.currentThread(), "coroutine") } }

    fun install(a: Application) {
        app = a
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            save(e, th, "uncaught")
            val main = th === Looper.getMainLooper().thread
            if (!main && e !is OutOfMemoryError) return@setDefaultUncaughtExceptionHandler   // worker thread: just that job dies
            if (main && recoverable(e)) return@setDefaultUncaughtExceptionHandler            // e.g. a coroutine on Main failed
            if (!restart()) system?.uncaughtException(th, e)
        }
        // Main loop guard: an exception thrown out of a message (tap handler, WebView callback…) is caught here instead of
        // terminating the process. Looper.loop() is re-entered so the app keeps responding.
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try { Looper.loop() }
                catch (e: Throwable) {
                    save(e, Thread.currentThread(), "main-loop")
                    if (!recoverable(e)) { restart(); return@post }
                    runCatching { BrowserRef.activity.toast("Something went wrong — Cairn recovered") }
                }
            }
        }
    }

    private fun recoverable(e: Throwable): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recent) { recent.addLast(now); while (recent.isNotEmpty() && now - recent.first() > 15_000) recent.removeFirst(); if (recent.size > 4) return false }
        if (e is OutOfMemoryError || e is StackOverflowError) return false
        val trace = Log.getStackTraceString(e)
        // A failure inside composition/layout or activity lifecycle leaves the UI unusable: restart cleanly instead.
        return !(trace.contains("androidx.compose.runtime") || trace.contains("androidx.compose.ui.node") || trace.contains("android.app.ActivityThread.perform") ||
            trace.contains("android.app.ActivityThread.handle") || trace.contains("Recomposer"))
    }

    private fun restart(): Boolean {
        if (restarting) return true
        restarting = true
        return runCatching {
            app.getSharedPreferences("crash", 0).edit().putBoolean("recovered", true).commit()
            val i = app.packageManager.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (i != null) app.startActivity(i)
            Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10)
        }.isSuccess
    }

    fun save(e: Throwable, th: Thread, kind: String) {
        Log.e(TAG, "$kind on ${th.name}", e)
        runCatching {
            dir.mkdirs()
            val f = File(dir, "crash-${System.currentTimeMillis()}.txt")
            f.writeText(buildString {
                append("Cairn ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("When: ${java.util.Date()}\nKind: $kind · Thread: ${th.name}\n\n")
                append(Log.getStackTraceString(e))
            })
            dir.listFiles()?.sortedByDescending { it.name }?.drop(15)?.forEach { it.delete() }
        }
    }

    /** True once after Cairn restarted itself; the caller shows a short notice. */
    fun consumeRecovered(): Boolean {
        val p = app.getSharedPreferences("crash", 0)
        return p.getBoolean("recovered", false).also { if (it) p.edit().putBoolean("recovered", false).apply() }
    }

    fun logs(): List<File> = dir.listFiles()?.sortedByDescending { it.name }.orEmpty()
    fun report(): String = logs().take(5).joinToString("\n\n----------\n\n") { runCatching { it.readText() }.getOrDefault("") }
    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
