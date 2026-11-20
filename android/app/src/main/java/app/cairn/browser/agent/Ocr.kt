package app.cairn.browser.agent

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * On-device text recognition (ML Kit, bundled models — works offline, no Google Play services, no server).
 * Reads printed English and Hindi/Marathi/Sanskrit (Devanagari), so a photo of a school book page, a bill or a sign
 * can always be answered, even when no cloud vision model is available.
 */
object Ocr {
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val deva by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    private suspend fun run(client: com.google.mlkit.vision.text.TextRecognizer, img: InputImage): String =
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { c ->
                client.process(img)
                    .addOnSuccessListener { r -> if (c.isActive) c.resume(r.textBlocks.joinToString("\n\n") { b -> b.lines.joinToString("\n") { it.text } }) }
                    .addOnFailureListener { if (c.isActive) c.resume("") }
            }
        } ?: ""

    /** All text in the picture, in reading order. Empty when there is no text (or recognition failed). */
    suspend fun read(bmp: Bitmap): String {
        val src = if (maxOf(bmp.width, bmp.height) > 2600) {
            val s = 2600f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else bmp
        val img = InputImage.fromBitmap(src, 0)
        val l = runCatching { run(latin, img) }.getOrDefault("")
        // The Devanagari model also reads Latin letters, so keep whichever recognised more real characters.
        val d = runCatching { run(deva, img) }.getOrDefault("")
        val dn = d.count { it.code in 0x0900..0x097F }
        val best = if (dn > 20 && dn > l.count { it.isLetter() } / 4) d else l
        return best.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }
}
