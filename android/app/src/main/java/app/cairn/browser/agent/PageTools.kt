package app.cairn.browser.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PdfExporter
import android.print.PrintAttributes
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import app.cairn.browser.browser.Browser
import app.cairn.browser.browser.Downloads
import app.cairn.browser.browser.Tab
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.DownloadItem
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

data class Article(val title: String, val url: String, val lang: String, val text: String, val byline: String = "")

/** Runs the shared page scripts (assets/agent.js) inside a WebView. */
object PageJs {
    lateinit var script: String
    fun init(ctx: Context) { script = ctx.assets.open("agent.js").bufferedReader().readText() }

    /** Evaluates `window.__cairn.<call>` and returns the result as JSON text (or null). */
    suspend fun call(w: WebView, call: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c ->
            w.evaluateJavascript("(function(){$script\ntry{return JSON.stringify(window.__cairn.$call)}catch(e){return JSON.stringify({__error:String(e)})}})()") { r ->
                val inner = runCatching { if (r == null || r == "null") null else JSONTokener(r).nextValue() as? String }.getOrNull()
                if (c.isActive) c.resume(inner)
            }
        }
    }
    suspend fun obj(w: WebView, call: String): JSONObject? = call(w, call)?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { !it.has("__error") }
    fun q(s: String) = JSONObject.quote(s)
}

object PageTools {
    private val main = Handler(Looper.getMainLooper())
    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    fun slug(s: String) = s.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60).ifEmpty { "page" }

    suspend fun article(w: WebView): Article {
        val j = PageJs.obj(w, "article()") ?: throw Exception("Couldn't read this page")
        return Article(j.optString("title"), j.optString("url"), j.optString("lang"), j.optString("text"), j.optString("byline"))
    }

    // ---------- Background pages ----------
    /** A page the agent is working on out of sight. The UI shows these in the live mini-preview (PiP). */
    class LivePage(val label: String, val web: WebView) { var url by androidx.compose.runtime.mutableStateOf("") }
    val live = androidx.compose.runtime.mutableStateListOf<LivePage>()

    /** A hidden, attached WebView (attached so timers and layout run normally). */
    suspend fun backgroundWebView(desktop: Boolean): WebView = withContext(Dispatchers.Main) {
        val act = Browser.activity
        WebView(act).also { w ->
            Browser.configure(w, incognito = false, desktop = desktop)
            w.settings.blockNetworkImage = false
            val d = act.resources.displayMetrics.density
            val (cw, ch) = if (desktop) (1280 * d).toInt() to (900 * d).toInt() else act.resources.displayMetrics.widthPixels to act.resources.displayMetrics.heightPixels
            act.backgroundHost().addView(w, FrameLayout.LayoutParams(cw, ch))
        }
    }
    suspend fun destroyBackground(w: WebView) = withContext(NonCancellable + Dispatchers.Main) {
        live.removeAll { it.web == w }
        (w.parent as? ViewGroup)?.removeView(w); w.stopLoading(); w.destroy()
    }
    /** Loads [url] in a background WebView and suspends until it finishes loading. */
    suspend fun loadAndWait(w: WebView, url: String, timeoutMs: Long = 25_000) {
        val loaded = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            live.firstOrNull { it.web == w }?.url = url
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) { if (u != "about:blank") { live.firstOrNull { it.web == w }?.url = u; loaded.complete(Unit) } }
                override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest) =
                    if (!req.isForMainFrame && app.cairn.browser.browser.Shield.isBlocked(req.url.host)) app.cairn.browser.browser.Shield.empty() else null
                override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest) = !(req.url.scheme ?: "").startsWith("http")
            }
            w.loadUrl(url, Browser.headers())
        }
        withTimeoutOrNull(timeoutMs) { loaded.await() }
    }

    suspend fun <T> withPage(url: String, desktop: Boolean = false, timeoutMs: Long = 30_000, settleMs: Long = 1200, scrolls: Int = 0, label: String? = null, block: suspend (WebView) -> T): T {
        val w = backgroundWebView(desktop)
        withContext(Dispatchers.Main) { live.add(LivePage(label ?: app.cairn.browser.data.hostOf(url), w).also { it.url = url }) }
        try {
            return withTimeout(timeoutMs) {
                loadAndWait(w, url, timeoutMs)
                delay(settleMs)
                repeat(scrolls) { PageJs.call(w, "scroll('down')"); delay(500) }
                block(w)
            }
        } finally { destroyBackground(w) }
    }

    suspend fun readUrl(url: String): Article = withPage(url, settleMs = 900) { article(it) }

    // ---------- PDF ----------
    /** Prints a WebView to PDF silently and saves it to Download/Cairn/<folder>. */
    suspend fun pdfOf(w: WebView, name: String, folder: String = "Cairn"): Downloads.Saved {
        val ctx = Browser.activity
        val tmp = File(ctx.cacheDir, "print-${System.nanoTime()}.pdf")
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { c ->
                val attrs = PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600)).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
                val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                PdfExporter.export(w.createPrintDocumentAdapter(name), attrs, fd, object : PdfExporter.Done {
                    override fun ok() { fd.close(); if (c.isActive) c.resume(Unit) }
                    override fun fail(message: String) { fd.close(); if (c.isActive) c.cancel(Exception("PDF failed: $message")) }
                })
            }
        }
        val bytes = withContext(Dispatchers.IO) { tmp.readBytes().also { tmp.delete() } }
        if (bytes.size < 200) throw Exception("PDF came out empty")
        return withContext(Dispatchers.IO) { Downloads.saveBytes(ctx, bytes, "${slug(name)}-${stamp()}.pdf", "application/pdf", folder) }
    }

    suspend fun htmlToPdf(html: String, name: String, folder: String = "Cairn"): Downloads.Saved {
        val act = Browser.activity
        val w = withContext(Dispatchers.Main) { WebView(act).also { act.backgroundHost().addView(it, FrameLayout.LayoutParams(act.resources.displayMetrics.widthPixels, act.resources.displayMetrics.heightPixels)) } }
        try {
            withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                w.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String) { done.complete(Unit) } }
                w.loadDataWithBaseURL("https://cairn.local/", html, "text/html", "utf-8", null)
                withTimeout(15_000) { done.await() }
            }
            delay(300)
            return pdfOf(w, name, folder)
        } finally { withContext(NonCancellable + Dispatchers.Main) { (w.parent as? ViewGroup)?.removeView(w); w.destroy() } }
    }

    fun documentHtml(title: String, url: String, md: String): String {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><style>
  body{font:12pt/1.6 Georgia,'Noto Serif','Noto Serif Devanagari',serif;color:#1b1a17;max-width:720px;margin:36px auto;padding:0 28px}
  h1{font:700 22pt/1.2 sans-serif;margin:0 0 6px} h2{font:700 14pt sans-serif;margin:22px 0 6px} h3{font:700 12pt sans-serif}
  .src{color:#777;font:9pt sans-serif;margin-bottom:24px;word-break:break-all} li{margin:4px 0} a{color:#2F6AA8}
  .ft{margin-top:40px;color:#999;font:8pt sans-serif;border-top:1px solid #ddd;padding-top:8px}
</style></head><body><h1>${esc(title)}</h1><div class="src">${esc(url)}</div>${mdToHtml(md)}<div class="ft">Saved with Cairn · ${esc(java.text.DateFormat.getDateTimeInstance().format(Date()))}</div></body></html>"""
    }

    fun mdToHtml(md: String): String {
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val inline = { s: String -> esc(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>").replace(Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"), "<a href=\"$2\">$1</a>") }
        val sb = StringBuilder(); var inList = false
        for (raw in md.lines()) {
            val l = raw.trim()
            val bullet = Regex("^([-*•]|\\d+[.)])\\s+(.*)").find(l)
            if (bullet != null) { if (!inList) { sb.append("<ul>"); inList = true }; sb.append("<li>").append(inline(bullet.groupValues[2])).append("</li>"); continue }
            if (inList) { sb.append("</ul>"); inList = false }
            when {
                l.isEmpty() -> {}
                l.startsWith("### ") -> sb.append("<h3>").append(inline(l.drop(4))).append("</h3>")
                l.startsWith("## ") -> sb.append("<h2>").append(inline(l.drop(3))).append("</h2>")
                l.startsWith("# ") -> sb.append("<h2>").append(inline(l.drop(2))).append("</h2>")
                else -> sb.append("<p>").append(inline(l)).append("</p>")
            }
        }
        if (inList) sb.append("</ul>")
        return sb.toString()
    }

    // ---------- Screenshots ----------
    private suspend fun copy(w: WebView): Bitmap = withContext(Dispatchers.Main) {
        val bmp = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2); w.getLocationInWindow(loc)
        suspendCancellableCoroutine { c ->
            PixelCopy.request(Browser.activity.window, Rect(loc[0], loc[1], loc[0] + w.width, loc[1] + w.height), bmp, { r ->
                if (r == PixelCopy.SUCCESS) c.resume(bmp) else {
                    // Fallback: software draw of the visible area
                    Canvas(bmp).apply { translate(-w.scrollX.toFloat(), -w.scrollY.toFloat()) }.also { w.draw(it) }; c.resume(bmp)
                }
            }, main)
        }
    }

    suspend fun visibleScreenshot(t: Tab): Downloads.Saved {
        val w = t.web ?: throw Exception("Nothing to capture on this tab")
        return saveImage(copy(w), "${slug(t.title.ifEmpty { t.host })}-${stamp()}.png")
    }

    /** Scroll-and-stitch "long screenshot" of the whole page (fixed headers are hidden after the first frame). */
    @SuppressLint("NewApi")
    suspend fun longScreenshot(t: Tab, onProgress: (Int) -> Unit = {}): Downloads.Saved {
        val w = t.web ?: throw Exception("Nothing to capture on this tab")
        val m = PageJs.obj(w, "metrics()") ?: throw Exception("Couldn't measure the page")
        val vh = m.getDouble("h"); val total = m.getDouble("sh").coerceAtLeast(vh); val startY = m.getDouble("y")
        val scale = w.height / vh                       // device px per CSS px
        val maxPx = 16000
        val outScale = if (total * scale > maxPx) maxPx / (total * scale) else 1.0
        val out = Bitmap.createBitmap((w.width * outScale).toInt(), (total * scale * outScale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var drawn = 0.0; var first = true
        try {
            while (drawn < total - 1) {
                w.evaluateJavascript("scrollTo(0, $drawn)", null); delay(if (first) 350 else 280)
                val y = PageJs.obj(w, "metrics()")?.getDouble("y") ?: drawn
                val shot = copy(w)
                val from = ((drawn - y) * scale).toInt().coerceIn(0, shot.height)
                val src = Rect(0, from, shot.width, shot.height)
                val dstTop = (drawn * scale * outScale).toFloat()
                canvas.save(); canvas.translate(0f, dstTop); canvas.scale(outScale.toFloat(), outScale.toFloat())
                canvas.drawBitmap(shot, src, Rect(0, 0, shot.width, shot.height - from), null); canvas.restore()
                val advanced = (shot.height - from) / scale
                shot.recycle()
                if (advanced < 1) break
                drawn += advanced
                onProgress(((drawn / total) * 100).toInt().coerceAtMost(100))
                if (first) { PageJs.call(w, "hideFixed(true)"); first = false }
            }
        } finally {
            PageJs.call(w, "hideFixed(false)"); w.evaluateJavascript("scrollTo(0, $startY)", null)
        }
        return saveImage(out, "${slug(t.title.ifEmpty { t.host })}-full-${stamp()}.png")
    }

    /** Saves to Pictures/Cairn so screenshots show up in the gallery. */
    suspend fun saveImage(bmp: Bitmap, name: String): Downloads.Saved = withContext(Dispatchers.IO) {
        val ctx = Browser.activity
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Cairn"); put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("Couldn't save the image")
            ctx.contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
            ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null); u
        } else return@withContext Downloads.saveBytes(ctx, bytes, name, "image/png", "Cairn/Screenshots")
        BrowserDb.addDownload(DownloadItem(0, -1, name, "", "image/png", uri.toString(), bytes.size.toLong(), System.currentTimeMillis()))
        Downloads.Saved(name, uri, "Pictures/Cairn", "image/png", bytes.size.toLong())
    }
}
