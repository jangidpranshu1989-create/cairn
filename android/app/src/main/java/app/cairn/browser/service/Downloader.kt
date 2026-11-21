package app.cairn.browser.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import app.cairn.browser.CairnApp
import app.cairn.browser.R
import app.cairn.browser.data.Api
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.DownloadItem
import app.cairn.browser.ui.BrowserActivity
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cairn's own download engine: pause/resume (HTTP Range), survives the app going to the background (foreground
 * service + progress notification with speed and time left), and resumes after a restart. Finished files go to
 * Downloads/Cairn through MediaStore, so no storage permission is needed on Android 10+.
 */
object Downloader {
    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED }
    class Job(val id: Long, val url: String, var name: String, var mime: String, val ua: String?, val referer: String?, val folder: String) {
        var state by mutableStateOf(State.QUEUED)
        var received by mutableLongStateOf(0L)
        var total by mutableLongStateOf(-1L)
        var speed by mutableLongStateOf(0L)      // bytes per second (smoothed)
        var error by mutableStateOf<String?>(null)
        @Volatile var cancelled = false
        @Volatile var paused = false
        @Volatile var netWait = false            // failed because the network went away; resumes when it's back
        val etaSec get() = if (speed > 0 && total > 0) ((total - received) / speed) else -1
    }

    const val ENGINE = -2L   // dm_id marker for downloads handled by this engine
    val jobs = mutableStateMapOf<Long, Job>()
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "cairn-dl").apply { isDaemon = true } }
    private val client by lazy { Api.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build() }
    private lateinit var app: Context

    private fun partial(id: Long) = File(app.filesDir, "downloads/$id.part").apply { parentFile?.mkdirs() }
    private fun metaFile(id: Long) = File(app.filesDir, "downloads/$id.meta")

    /**
     * Sidecar kept next to the partial file: the server's validators (ETag / Last-Modified, so a resume never glues
     * two different versions of a file together), the request headers needed to resume after a restart, and the
     * progress of each parallel segment.
     */
    private class Seg(val start: Long, val end: Long, @Volatile var done: Long) { val left get() = end - start + 1 - done }
    private class Meta(var etag: String? = null, var lastMod: String? = null, var ua: String? = null, var referer: String? = null,
                       var folder: String = "", var total: Long = -1, var segs: List<Seg>? = null)

    private fun saveMeta(id: Long, m: Meta) = runCatching {
        val o = org.json.JSONObject().put("etag", m.etag).put("lastMod", m.lastMod).put("ua", m.ua).put("referer", m.referer).put("folder", m.folder).put("total", m.total)
        m.segs?.let { l -> o.put("segs", org.json.JSONArray(l.map { org.json.JSONArray(listOf(it.start, it.end, it.done)) })) }
        val f = metaFile(id); val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
    }
    private fun loadMeta(id: Long): Meta? = runCatching {
        val o = org.json.JSONObject(metaFile(id).readText())
        fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && !o.isNull(k) }
        Meta(str("etag"), str("lastMod"), str("ua"), str("referer"), o.optString("folder"), o.optLong("total", -1),
            o.optJSONArray("segs")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { Seg(it.getLong(0), it.getLong(1), it.getLong(2)) } } })
    }.getOrNull()

    /** Restores unfinished downloads (as paused) after the app restarts. */
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        runCatching {
            BrowserDb.downloads().filter { it.dmId == ENGINE && it.uri.isEmpty() && !jobs.containsKey(it.id) }.forEach { d ->
                val f = partial(d.id); val m = loadMeta(d.id)
                jobs[d.id] = Job(d.id, d.url, d.name, d.mime, m?.ua, m?.referer, m?.folder ?: "").apply {
                    received = m?.segs?.sumOf { it.done } ?: if (f.exists()) f.length() else 0
                    total = m?.total?.takeIf { it > 0 } ?: d.size.takeIf { it > 0 } ?: -1; state = State.PAUSED; paused = true
                }
            }
        }
        // Like Chrome: a download that failed because the network dropped carries on by itself when it's back.
        runCatching {
            app.getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    android.os.Handler(app.mainLooper).postDelayed({ jobs.values.filter { it.state == State.FAILED && it.netWait }.forEach { resume(it.id) } }, 1500)
                }
            })
        }
    }

    fun start(ctx: Context, url: String, name: String, mime: String, ua: String?, referer: String?, incognito: Boolean, folder: String = ""): Long {
        init(ctx)
        val id = BrowserDb.addDownload(DownloadItem(0, ENGINE, name, url, mime, "", 0, System.currentTimeMillis()))
        val j = Job(id, url, name, mime, ua, referer, folder)
        jobs[id] = j
        if (incognito) BrowserDb.deleteDownload(id) // private downloads aren't kept in the list
        else saveMeta(id, Meta(ua = ua, referer = referer, folder = folder))
        run(j)
        return id
    }

    fun pause(id: Long) { jobs[id]?.let { it.paused = true } }
    fun resume(id: Long) { jobs[id]?.let { if (it.state == State.PAUSED || it.state == State.FAILED) { it.paused = false; it.error = null; it.netWait = false; run(it) } } }
    fun cancel(id: Long) {
        jobs[id]?.let { it.cancelled = true; it.paused = true }
        jobs.remove(id); runCatching { partial(id).delete(); metaFile(id).delete() }; BrowserDb.deleteDownload(id)
        NotificationManagerCompat.from(app).cancel(nid(id)); DownloadService.refresh(app)
    }

    private fun nid(id: Long) = 7000 + (id % 1000).toInt()

    private fun online() = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun run(j: Job) {
        j.state = State.QUEUED; j.cancelled = false; j.paused = false
        DownloadService.refresh(app)
        pool.execute {
            try {
                // Network hiccups are retried automatically with back-off (2 s, 4 s, 8 s, 16 s); each try resumes
                // from where the last one stopped, so nothing is downloaded twice.
                var attempt = 0
                while (true) {
                    try { download(j); break }
                    catch (e: java.io.IOException) {
                        if (j.cancelled || j.paused || e is HttpError && e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                        if (++attempt > 4 || !online()) { j.netWait = true; throw e }
                        j.state = State.QUEUED; j.error = "Connection lost · retrying"; j.speed = 0; notify(j)
                        Thread.sleep(1000L shl attempt)
                        if (j.cancelled || j.paused) { j.state = State.PAUSED; return@execute }
                        j.error = null
                    }
                }
            }
            catch (e: Exception) { if (!j.cancelled) { j.state = if (j.paused) State.PAUSED else State.FAILED; j.error = if (j.netWait) "Waiting for network" else e.message } }
            finally { notify(j); DownloadService.refresh(app) }
        }
    }

    private class HttpError(val code: Int) : java.io.IOException("Server said $code")
    private const val SEGMENT_MIN = 4L * 1024 * 1024   // files bigger than this download over parallel connections
    private const val SEGMENTS = 4

    private fun request(j: Job, m: Meta, from: Long, to: Long? = null): Request {
        val rb = Request.Builder().url(j.url)
            .header("User-Agent", j.ua ?: android.webkit.WebSettings.getDefaultUserAgent(app).replace("; wv)", ")"))
            .header("Accept-Encoding", "identity")
        CookieManager.getInstance().getCookie(j.url)?.let { rb.header("Cookie", it) }
        j.referer?.takeIf { it.startsWith("http") }?.let { rb.header("Referer", it) }
        rb.header("Range", "bytes=$from-" + (to?.toString() ?: ""))
        // If-Range: the server only sends the rest when the file is still the same one; otherwise it sends it whole.
        if (from > 0) (m.etag?.takeIf { !it.startsWith("W/") } ?: m.lastMod)?.let { rb.header("If-Range", it) }
        return rb.build()
    }

    private fun download(j: Job) {
        val f = partial(j.id)
        val m = loadMeta(j.id) ?: Meta(ua = j.ua, referer = j.referer, folder = j.folder)
        if (m.segs != null && m.total > 0 && f.exists()) { segmented(j, f, m); return }
        var have = if (f.exists()) f.length() else 0L
        j.state = State.RUNNING
        client.newCall(request(j, m, have)).execute().use { r ->
            if (r.code == 416 && have > 0) { j.total = have; j.received = have; finish(j, f); return }
            if (!r.isSuccessful) throw HttpError(r.code)
            val resumed = r.code == 206 && have > 0
            if (!resumed) have = 0
            r.header("ETag")?.let { m.etag = it }; r.header("Last-Modified")?.let { m.lastMod = it }
            val len = r.body!!.contentLength()
            val full = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: if (len > 0) len + have else -1
            j.total = full; m.total = full
            if (j.mime.isBlank()) j.mime = r.body!!.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            // Big file on a server that supports ranges: switch to parallel segments, like Chrome's parallel downloading.
            if (r.code == 206 && have == 0L && full >= SEGMENT_MIN && r.header("Content-Range")?.startsWith("bytes 0-") == true) {
                val size = (full + SEGMENTS - 1) / SEGMENTS
                m.segs = (0 until SEGMENTS).map { i -> Seg(i * size, minOf(full - 1, (i + 1) * size - 1), 0) }.filter { it.start <= it.end }
                r.close(); f.delete(); saveMeta(j.id, m)
                segmented(j, f, m); return
            }
            saveMeta(j.id, m)
            j.received = have
            FileOutputStream(f, resumed).use { out ->
                val inp = r.body!!.byteStream(); val buf = ByteArray(64 * 1024)
                var lastT = System.nanoTime(); var lastB = have; var lastNotify = 0L
                while (true) {
                    if (j.cancelled) return
                    if (j.paused) { j.state = State.PAUSED; j.speed = 0; return }
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); j.received += n
                    val now = System.nanoTime()
                    if (now - lastT > 500_000_000L) {
                        val inst = (j.received - lastB) * 1_000_000_000L / (now - lastT)
                        j.speed = if (j.speed == 0L) inst else (j.speed * 0.7 + inst * 0.3).toLong()
                        lastT = now; lastB = j.received
                    }
                    if (now - lastNotify > 1_000_000_000L) { lastNotify = now; notify(j) }
                }
            }
            if (j.total > 0 && j.received < j.total) throw java.io.IOException("Connection closed early")
        }
        finish(j, f)
    }

    /** Parallel download: each segment has its own connection and writes into its own part of the file. */
    private fun segmented(j: Job, f: File, m: Meta) {
        val segs = m.segs!!
        j.state = State.RUNNING; j.total = m.total
        java.io.RandomAccessFile(f, "rw").use { if (it.length() < m.total) it.setLength(m.total) }
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
        val restart = java.util.concurrent.atomic.AtomicBoolean(false)
        val threads = segs.filter { it.left > 0 }.map { s ->
            Thread({
                try {
                    client.newCall(request(j, m, s.start + s.done, s.end)).execute().use { r ->
                        if (r.code == 200) { restart.set(true); return@use } // file changed on the server: start over
                        if (r.code != 206) throw HttpError(r.code)
                        java.io.RandomAccessFile(f, "rw").use { raf ->
                            raf.seek(s.start + s.done)
                            val inp = r.body!!.byteStream(); val buf = ByteArray(64 * 1024)
                            while (s.left > 0) {
                                if (j.cancelled || j.paused || restart.get() || errors.isNotEmpty()) return@use
                                val n = inp.read(buf, 0, minOf(buf.size.toLong(), s.left).toInt()); if (n < 0) break
                                raf.write(buf, 0, n); s.done += n
                            }
                        }
                        if (s.left > 0 && !j.cancelled && !j.paused) throw java.io.IOException("Connection closed early")
                    }
                } catch (e: Exception) { errors.add(e) }
            }, "cairn-dl-seg").apply { isDaemon = true; start() }
        }
        var lastT = System.nanoTime(); var lastB = segs.sumOf { it.done }; var lastSave = 0L
        j.received = lastB
        while (threads.any { it.isAlive }) {
            threads.first { it.isAlive }.join(500)
            val now = System.nanoTime(); val got = segs.sumOf { it.done }
            j.received = got
            if (now - lastT > 500_000_000L) {
                val inst = (got - lastB) * 1_000_000_000L / (now - lastT)
                j.speed = if (j.speed == 0L) inst else (j.speed * 0.7 + inst * 0.3).toLong(); lastT = now; lastB = got
                notify(j)
            }
            if (now - lastSave > 2_000_000_000L) { lastSave = now; saveMeta(j.id, m) }
        }
        j.received = segs.sumOf { it.done }
        if (j.cancelled) return
        if (restart.get()) { m.segs = null; m.etag = null; m.lastMod = null; f.delete(); saveMeta(j.id, m); throw java.io.IOException("The file changed on the server, starting again") }
        saveMeta(j.id, m)
        if (j.paused) { j.state = State.PAUSED; j.speed = 0; return }
        errors.peek()?.let { throw if (it is java.io.IOException) it else java.io.IOException(it.message, it) }
        finish(j, f)
    }

    private fun finish(j: Job, f: File) {
        val mime = j.mime.substringBefore(';').ifBlank { MimeTypeMap.getSingleton().getMimeTypeFromExtension(j.name.substringAfterLast('.', "")) ?: "application/octet-stream" }
        val clean = j.folder.split('/').map { it.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "") }.filter { it.isNotEmpty() && it != ".." }.joinToString("/")
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/Cairn" + if (clean.isEmpty()) "" else "/$clean"
        val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
            val cr = app.contentResolver
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, j.name); put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.RELATIVE_PATH, rel); put(MediaStore.MediaColumns.IS_PENDING, 1) }
            val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("Could not create the file")
            cr.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o, 256 * 1024) } }
            cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null); u
        } else {
            @Suppress("DEPRECATION") val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cairn" + if (clean.isEmpty()) "" else "/$clean").apply { mkdirs() }
            var out = File(dir, j.name); var i = 1
            while (out.exists()) out = File(dir, "${j.name.substringBeforeLast('.')} (${i++}).${j.name.substringAfterLast('.', "bin")}")
            f.copyTo(out); FileProvider.getUriForFile(app, app.packageName + ".files", out)
        }
        f.delete(); metaFile(j.id).delete()
        j.mime = mime; j.total = j.received; j.speed = 0; j.state = State.DONE
        BrowserDb.finishDownload(j.id, uri.toString(), j.received, mime)
        jobs.remove(j.id)
        doneNotification(j, uri)
    }

    fun categoryOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp") -> "images"
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "webm", "mov", "avi", "3gp") -> "videos"
            mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus") -> "audio"
            mime == "application/vnd.android.package-archive" || ext in setOf("apk", "apks", "xapk") -> "apks"
            mime.contains("pdf") || mime.startsWith("text/") || mime.contains("document") || mime.contains("sheet") || mime.contains("presentation") || mime.contains("msword")
                || ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "odt", "rtf", "epub", "md") -> "docs"
            else -> "other"
        }
    }

    fun eta(sec: Long): String = when { sec < 0 -> ""; sec < 60 -> "${sec}s left"; sec < 3600 -> "${sec / 60} min left"; else -> "${sec / 3600} h ${(sec % 3600) / 60} min left" }

    // ---------- Notifications ----------
    private fun action(j: Job, act: String) = PendingIntent.getBroadcast(app, (j.id * 10 + act.length).toInt(),
        Intent(app, DownloadActionReceiver::class.java).setAction(act).putExtra("id", j.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun openApp() = PendingIntent.getActivity(app, 77, Intent(app, BrowserActivity::class.java).putExtra("open", "downloads").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun progressNotification(j: Job): Notification {
        val sizeTxt = Formatter.formatShortFileSize(app, j.received) + if (j.total > 0) " of " + Formatter.formatShortFileSize(app, j.total) else ""
        val text = when (j.state) {
            State.PAUSED -> "Paused · $sizeTxt"
            State.FAILED -> "Failed · ${j.error ?: "tap Retry"}"
            else -> listOf(sizeTxt, if (j.speed > 0) Formatter.formatShortFileSize(app, j.speed) + "/s" else "", eta(j.etaSec)).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val b = NotificationCompat.Builder(app, CairnApp.CH_DOWNLOADS).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(j.name).setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(j.state == State.RUNNING || j.state == State.QUEUED).setContentIntent(openApp()).setSilent(true)
        if (j.state == State.RUNNING || j.state == State.QUEUED) {
            if (j.total > 0) b.setProgress(1000, (j.received * 1000 / j.total).toInt(), false) else b.setProgress(0, 0, true)
            b.addAction(0, "Pause", action(j, DownloadActionReceiver.PAUSE))
        } else if (j.state == State.PAUSED || j.state == State.FAILED) b.addAction(0, if (j.state == State.FAILED) "Retry" else "Resume", action(j, DownloadActionReceiver.RESUME))
        b.addAction(0, "Cancel", action(j, DownloadActionReceiver.CANCEL))
        return b.build()
    }

    private fun canPost() = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun notify(j: Job) { if (canPost() && jobs.containsKey(j.id)) runCatching { NotificationManagerCompat.from(app).notify(nid(j.id), progressNotification(j)) } }

    private fun doneNotification(j: Job, uri: Uri) {
        if (!canPost()) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, j.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(app, nid(j.id), view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { NotificationManagerCompat.from(app).notify(nid(j.id), NotificationCompat.Builder(app, CairnApp.CH_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(j.name).setContentText("Download complete · " + Formatter.formatShortFileSize(app, j.received))
            .setContentIntent(pi).setAutoCancel(true).build()) }
    }

    val active get() = jobs.values.any { it.state == State.RUNNING || it.state == State.QUEUED }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getLongExtra("id", -1); if (id < 0) return
        Downloader.init(ctx)
        when (i.action) { PAUSE -> Downloader.pause(id); RESUME -> Downloader.resume(id); CANCEL -> Downloader.cancel(id) }
    }
    companion object { const val PAUSE = "app.cairn.browser.DL_PAUSE"; const val RESUME = "app.cairn.browser.DL_RESUME"; const val CANCEL = "app.cairn.browser.DL_CANCEL" }
}

/** Keeps downloads going while Cairn is in the background. Stops itself when nothing is downloading. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val j = Downloader.jobs.values.firstOrNull { it.state == Downloader.State.RUNNING || it.state == Downloader.State.QUEUED }
        if (j == null) { stopSelf(); return START_NOT_STICKY }
        runCatching {
            ServiceCompat.startForeground(this, 6999, Downloader.progressNotification(j),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }
        return START_NOT_STICKY
    }
    companion object {
        fun refresh(ctx: Context) {
            android.os.Handler(ctx.mainLooper).postDelayed({
                val i = Intent(ctx, DownloadService::class.java)
                if (Downloader.active) runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, i) }
                else runCatching { ctx.stopService(i) }
            }, 150)
        }
    }
}
