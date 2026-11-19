package app.cairn.browser.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.cairn.browser.data.Api
import app.cairn.browser.data.AppState
import app.cairn.browser.data.Prefs
import app.cairn.browser.data.SavedFile
import app.cairn.browser.data.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Downloads a transfer and saves it to Download/Cairn/<folder> via MediaStore (no storage permission on Android 10+). */
object FileSaver {
    private fun cleanFolder(f: String) = f.split('/').map { it.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "") }.filter { it.isNotEmpty() && it != ".." }
        .joinToString("/").ifEmpty { "Cairn" }

    suspend fun save(ctx: Context, t: Transfer): SavedFile = withContext(Dispatchers.IO) {
        val folder = cleanFolder(t.folder)
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/Cairn/$folder"
        val uri: Uri
        Api.download(t.id).use { resp ->
            val body = resp.body ?: throw IllegalStateException("Empty file")
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, t.filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val cr = ctx.contentResolver
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Could not create file")
                try {
                    cr.openOutputStream(uri)!!.use { out -> body.byteStream().copyTo(out) }
                    cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) { cr.delete(uri, null, null); throw e }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cairn/$folder").apply { mkdirs() }
                var f = File(dir, t.filename); var n = 1
                while (f.exists()) f = File(dir, t.filename.substringBeforeLast('.') + " ($n)." + t.filename.substringAfterLast('.', "bin")).also { n++ }
                f.outputStream().use { out -> body.byteStream().copyTo(out) }
                uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            }
        }
        val saved = SavedFile(t.id, t.filename, "Download/Cairn/$folder", uri.toString(), t.size, System.currentTimeMillis())
        Prefs.addFile(saved); AppState.refreshFiles()
        Api.ack(t.id, saved.path)
        Notifier.fileSaved(ctx, t.filename, saved.path, uri, t.mime)
        saved
    }
}
