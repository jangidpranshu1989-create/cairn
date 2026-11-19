package app.cairn.browser.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.FileProvider
import app.cairn.browser.data.BrowserDb
import app.cairn.browser.data.DownloadItem
import java.io.File

object Downloads {
    /** One-time tokens: the JS bridge only accepts bytes for downloads the browser itself started. */
    private val pending = java.util.Collections.synchronizedSet(HashSet<String>())
    data class Saved(val name: String, val uri: Uri, val path: String, val mime: String, val size: Long)

    /** Called by WebView's DownloadListener. */
    fun start(ctx: Context, t: Tab, url: String, ua: String?, contentDisposition: String?, mime: String?, length: Long) {
        when {
            url.startsWith("blob:") -> {
                // Blob URLs only exist inside the page — read them there and hand the bytes over the JS bridge.
                val name = URLUtil.guessFileName(url, contentDisposition, mime)
                val token = java.util.UUID.randomUUID().toString(); pending.add(token)
                t.web?.evaluateJavascript("""(async()=>{const k=${org.json.JSONObject.quote(token)};try{const r=await fetch(${org.json.JSONObject.quote(url)});const b=await r.blob();
                    const fr=new FileReader();fr.onload=()=>CairnDownloads.save(k,fr.result,${org.json.JSONObject.quote(name)},b.type||${org.json.JSONObject.quote(mime ?: "")});fr.readAsDataURL(b);}
                    catch(e){CairnDownloads.fail(k,String(e))}})()""", null)
            }
            url.startsWith("data:") -> saveDataUrl(ctx, url, URLUtil.guessFileName(url, contentDisposition, mime))
            else -> enqueue(ctx, url, ua, contentDisposition, mime, t.url, t.incognito)
        }
    }

    fun enqueue(ctx: Context, url: String, ua: String?, contentDisposition: String?, mime: String?, referer: String?, incognito: Boolean = false, nameOverride: String? = null, folder: String = ""): Long? = runCatching {
        val cleanMime = mime?.substringBefore(';')?.trim()?.ifEmpty { null }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
        val name = sanitize(nameOverride ?: URLUtil.guessFileName(url, contentDisposition, cleanMime))
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(cleanMime)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", ua ?: Browser.mobileUa())
            referer?.takeIf { it.startsWith("http") }?.let { addRequestHeader("Referer", it) }
            setTitle(name); setDescription(Uri.parse(url).host)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val sub = folder.split('/').map { it.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "") }.filter { it.isNotEmpty() && it != ".." }.joinToString("/")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, if (sub.isEmpty()) "Cairn/$name" else "Cairn/$sub/$name")
        }
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(req)
        if (!incognito) BrowserDb.addDownload(DownloadItem(0, id, name, url, cleanMime ?: "", "", 0, System.currentTimeMillis()))
        Toast.makeText(ctx, "Downloading $name", Toast.LENGTH_SHORT).show()
        id
    }.onFailure { Toast.makeText(ctx, "Can't download this file", Toast.LENGTH_SHORT).show() }.getOrNull()

    fun saveDataUrl(ctx: Context, dataUrl: String, name: String): Saved? = runCatching {
        val head = dataUrl.substringBefore(','); val body = dataUrl.substringAfter(',')
        val mime = head.removePrefix("data:").substringBefore(';').ifEmpty { "application/octet-stream" }
        val bytes = if (head.contains(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
        var n = sanitize(name)
        if (!n.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n += ".$it" }
        saveBytes(ctx, bytes, n, mime, "Cairn")
    }.getOrNull()

    /** Writes bytes to the public Downloads/<folder> (MediaStore on Android 10+, no storage permission needed). */
    fun saveBytes(ctx: Context, bytes: ByteArray, name: String, mime: String, folder: String = "Cairn", record: Boolean = true): Saved {
        val clean = folder.split('/').map { it.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "") }.filter { it.isNotEmpty() && it != ".." }.joinToString("/").ifEmpty { "Cairn" }
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/$clean"
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("Could not create file")
            cr.openOutputStream(uri)!!.use { it.write(bytes) }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), clean).apply { mkdirs() }
            var f = File(dir, name); var i = 1
            while (f.exists()) f = File(dir, "${name.substringBeforeLast('.')} (${i++}).${name.substringAfterLast('.', "bin")}")
            f.writeBytes(bytes)
            uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        }
        if (record) BrowserDb.addDownload(DownloadItem(0, -1, name, "", mime, uri.toString(), bytes.size.toLong(), System.currentTimeMillis()))
        return Saved(name, uri, "Download/$clean", mime, bytes.size.toLong())
    }

    fun sanitize(n: String) = n.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().take(120).ifEmpty { "download" }

    class Bridge(private val ctx: Context, private val t: Tab) {
        @JavascriptInterface fun save(token: String, dataUrl: String, name: String, mime: String) {
            if (!pending.remove(token)) return
            if (dataUrl.length > 150_000_000) { android.os.Handler(ctx.mainLooper).post { Toast.makeText(ctx, "File is too large", Toast.LENGTH_SHORT).show() }; return }
            val s = saveDataUrl(ctx, dataUrl, name)
            android.os.Handler(ctx.mainLooper).post { Toast.makeText(ctx, if (s != null) "Saved ${s.name} to ${s.path}" else "Download failed", Toast.LENGTH_SHORT).show() }
        }
        @JavascriptInterface fun fail(token: String, msg: String) {
            if (!pending.remove(token)) return
            android.os.Handler(ctx.mainLooper).post { Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show() }
        }
    }
}
