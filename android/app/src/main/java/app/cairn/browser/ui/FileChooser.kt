package app.cairn.browser.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * `<input type=file>` support that behaves like Chrome: accept lists with extensions (".png,.jpg") or several MIME types
 * ("image/png,image/jpeg") are normalised, multi-select works, and image inputs also offer the camera. Picked content://
 * URIs are handed to WebView with read permission so uploads on sites like Product Hunt, X or Gmail work first time.
 */
object FileChooser {
    private val EXT = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif", "svg" to "image/svg+xml", "bmp" to "image/bmp",
        "pdf" to "application/pdf", "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "mp3" to "audio/mpeg",
        "csv" to "text/csv", "txt" to "text/plain", "doc" to "application/msword", "zip" to "application/zip",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

    /** Normalised MIME types for the input's accept attribute (empty = anything). */
    fun mimeTypes(accept: Array<String>?): List<String> = accept.orEmpty()
        .flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .mapNotNull { a ->
            when {
                a.startsWith('.') -> EXT[a.drop(1)] ?: runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.drop(1)) }.getOrNull()
                a == "image" || a == "video" || a == "audio" -> "$a/*"
                a.contains('/') -> a
                else -> null
            }
        }.distinct()

    /** The single MIME type for Intent.setType: the shared family (image/png + image/webp gives image/any), else any type. */
    fun primaryType(types: List<String>): String = when {
        types.isEmpty() -> "*/*"
        types.size == 1 -> types[0]
        types.map { it.substringBefore('/') }.distinct().size == 1 -> types[0].substringBefore('/') + "/*"
        else -> "*/*"
    }

    fun intent(ctx: Context, params: WebChromeClient.FileChooserParams): Pair<Intent, Uri?> {
        val types = mimeTypes(params.acceptTypes)
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = primaryType(types)
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val wantsImage = types.isEmpty() || types.any { it.startsWith("image/") }
        // The app declares CAMERA, so ACTION_IMAGE_CAPTURE is only allowed once that permission is granted.
        val camOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        var camUri: Uri? = null
        val extras = mutableListOf<Intent>()
        if (wantsImage && camOk) runCatching {
            val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "upload-${System.currentTimeMillis()}.jpg")
            camUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            extras += Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, camUri)
                clipData = ClipData.newRawUri("", camUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        // capture="environment" on an image input means "open the camera directly".
        if (params.isCaptureEnabled && extras.isNotEmpty()) return extras[0] to camUri
        val chooser = Intent.createChooser(pick, params.title ?: "Choose a file")
        if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        return chooser to camUri
    }

    /** Turns the picker result into the URIs WebView expects (null = cancelled, which re-arms the input). */
    fun result(ctx: Context, code: Int, data: Intent?, camUri: Uri?): Array<Uri>? {
        if (code != Activity.RESULT_OK) return null
        val out = mutableListOf<Uri>()
        data?.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(out::add) }
        if (out.isEmpty()) data?.data?.let(out::add)
        if (out.isEmpty() && camUri != null) {
            // The camera wrote straight to our FileProvider file; only use it if a photo was actually saved.
            val f = File(File(ctx.cacheDir, "camera"), camUri.lastPathSegment ?: "")
            if (!f.exists() || f.length() > 0) out += camUri
        }
        // Keep read access for the WebView's upload, even after the picker activity is gone.
        out.forEach { u -> runCatching { ctx.grantUriPermission(ctx.packageName, u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        return out.toTypedArray().takeIf { it.isNotEmpty() }
    }
}
