package app.cairn.browser.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cairn.browser.data.hostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** A numbered source the assistant can cite as [n]. */
data class Cite(val n: Int, val title: String, val url: String) {
    val host get() = hostOf(url)
    val label: String get() = app.cairn.browser.ui.siteName(host).ifBlank { host }.let { if (it.length > 16) it.take(15) + "…" else it }
}

/**
 * A photo or document the user attached to a message. Text is extracted on-device right after picking
 * (PDF, Word, PowerPoint, Excel, text and code files); images are understood with a vision model at send time.
 */
class Attachment(val name: String, val mime: String, val uri: Uri?, val bitmap: Bitmap?) {
    val key = java.util.UUID.randomUUID().toString()
    val isImage get() = bitmap != null
    var text by mutableStateOf<String?>(null)
    var pages by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var ready by mutableStateOf(bitmap != null)
    val kindLabel: String get() = when {
        isImage -> "Image"
        mime.contains("pdf") || name.endsWith(".pdf", true) -> "PDF"
        name.endsWith(".docx", true) || mime.contains("word") -> "Word"
        name.endsWith(".pptx", true) || mime.contains("presentation") -> "Slides"
        name.endsWith(".xlsx", true) || mime.contains("sheet") -> "Sheet"
        name.endsWith(".csv", true) -> "CSV"
        else -> name.substringAfterLast('.', "File").uppercase().take(5)
    }

    fun jpegB64(maxSide: Int = 1280): String {
        val b = bitmap ?: return ""
        val s = minOf(1f, maxSide.toFloat() / maxOf(b.width, b.height))
        val sc = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        val o = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 86, o)
        return android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP)
    }

    companion object {
        const val MAX_TEXT = 60_000

        fun displayName(ctx: Context, uri: Uri): String = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

        fun fromImage(ctx: Context, uri: Uri): Attachment? {
            val bmp = decodeScaled(ctx, uri, 1600) ?: return null
            return Attachment(displayName(ctx, uri), ctx.contentResolver.getType(uri) ?: "image/jpeg", uri, bmp)
        }

        fun fromCamera(bmp: Bitmap) = Attachment("Photo", "image/jpeg", null, bmp)

        /** Full-resolution camera photo (written by the camera app to our cache), upright and sharp enough for OCR. */
        fun fromCameraFile(ctx: Context, uri: Uri): Attachment? {
            val bmp = decodeScaled(ctx, uri, 2400) ?: return null
            return Attachment("Photo", "image/jpeg", null, bmp)
        }

        /** A fresh file in cache/camera for the camera app to write into. Old photos are cleaned up. */
        fun newCameraUri(ctx: Context): Uri {
            val dir = java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 6 * 3600_000L }?.forEach { it.delete() }
            val f = java.io.File(dir, "photo_${System.currentTimeMillis()}.jpg")
            return androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        }

        fun fromDocument(ctx: Context, uri: Uri): Attachment {
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            if (mime.startsWith("image/")) fromImage(ctx, uri)?.let { return it }
            return Attachment(displayName(ctx, uri), mime, uri, null)
        }

        private fun decodeScaled(ctx: Context, uri: Uri, max: Int): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0) return@runCatching null
            var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
            // Phone cameras store photos sideways plus an EXIF rotation flag; apply it so text is upright for OCR/vision.
            val deg = runCatching { ctx.contentResolver.openInputStream(uri)?.use { when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90; android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180; android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 } } }.getOrNull() ?: 0
            val big = maxOf(raw.width, raw.height).toFloat()
            val sc = if (big > max) max / big else 1f
            if (deg == 0 && sc == 1f) raw
            else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postScale(sc, sc); postRotate(deg.toFloat()) }, true)
        }.getOrNull()
    }

    /** Pulls readable text out of the document, off the main thread. */
    suspend fun extract(ctx: Context) = withContext(Dispatchers.IO) {
        if (isImage) { ready = true; return@withContext }
        val u = uri ?: return@withContext
        try {
            val lower = name.lowercase()
            val t = when {
                mime.contains("pdf") || lower.endsWith(".pdf") -> pdf(ctx, u)
                lower.endsWith(".docx") -> zipXml(ctx, u) { it == "word/document.xml" }
                lower.endsWith(".pptx") -> zipXml(ctx, u) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                lower.endsWith(".xlsx") -> zipXml(ctx, u) { it == "xl/sharedStrings.xml" || it.startsWith("xl/worksheets/sheet") }
                lower.endsWith(".odt") || lower.endsWith(".odp") || lower.endsWith(".ods") -> zipXml(ctx, u) { it == "content.xml" }
                lower.endsWith(".html") || lower.endsWith(".htm") || mime.contains("html") -> android.text.Html.fromHtml(readText(ctx, u), 0).toString()
                mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript") ||
                    Regex("\\.(txt|md|csv|tsv|json|xml|yaml|yml|log|kt|java|js|ts|py|c|cpp|h|go|rs|rb|php|swift|sql|ini|conf|srt)$").containsMatchIn(lower) -> readText(ctx, u)
                else -> readText(ctx, u).takeIf { s -> s.isNotBlank() && s.count { it == '\uFFFD' || it.code < 9 } < s.length / 50 } ?: throw Exception("This file type can't be read")
            }
            text = t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_TEXT)
            if (text.isNullOrBlank() && scanned.isEmpty()) error = if (mime.contains("pdf") || lower.endsWith(".pdf")) "No selectable text in this PDF" else "The file is empty"
        } catch (e: Exception) { error = e.message ?: "Couldn't read this file" }
        ready = true
    }

    private fun readText(ctx: Context, u: Uri) = ctx.contentResolver.openInputStream(u)?.use { it.readBytes().let { b -> String(b, 0, minOf(b.size, 400_000)) } }.orEmpty()

    private fun zipXml(ctx: Context, u: Uri, want: (String) -> Boolean): String {
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(u)?.use { ins -> ZipInputStream(ins).use { z ->
            var e = z.nextEntry
            val parts = sortedMapOf<String, String>(compareBy({ Regex("\\d+").find(it)?.value?.toIntOrNull() ?: 0 }, { it }))
            while (e != null) {
                if (want(e.name)) {
                    val xml = z.readBytes().toString(Charsets.UTF_8)
                    parts[e.name] = xml.replace(Regex("</w:p>|</a:p>|</text:p>|</si>|</row>"), "\n").replace(Regex("<w:tab/>|</c>"), "\t").replace(Regex("<[^>]+>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                }
                e = z.nextEntry
            }
            parts.forEach { (k, v) -> if (k.startsWith("ppt/slides")) sb.append("\n--- Slide ${Regex("\\d+").find(k)?.value} ---\n"); sb.append(v).append('\n') }
        } }
        return sb.toString()
    }

    private fun pdf(ctx: Context, u: Uri): String {
        // Text layer first (fast, exact). Scanned PDFs fall back to page images read by the vision model at send time.
        val txt = runCatching {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
            ctx.contentResolver.openInputStream(u)?.use { ins ->
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(ins).use { doc ->
                    pages = doc.numberOfPages
                    com.tom_roush.pdfbox.text.PDFTextStripper().apply { endPage = minOf(doc.numberOfPages, 60) }.getText(doc)
                }
            }.orEmpty()
        }.getOrDefault("")
        if (txt.trim().length > 40) return txt
        scanned = renderPages(ctx, u, 3)
        return ""
    }

    /** Page images of a scanned PDF (no text layer), for OCR by the vision model. */
    var scanned: List<Bitmap> = emptyList(); private set
    private fun renderPages(ctx: Context, u: Uri, max: Int): List<Bitmap> = runCatching {
        ctx.contentResolver.openFileDescriptor(u, "r")?.use { fd -> PdfRenderer(fd).use { r ->
            pages = r.pageCount
            (0 until minOf(max, r.pageCount)).map { i -> r.openPage(i).use { p ->
                val w = 1100; val h = (w * p.height / p.width.coerceAtLeast(1))
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            } }
        } }.orEmpty()
    }.getOrDefault(emptyList())
}
