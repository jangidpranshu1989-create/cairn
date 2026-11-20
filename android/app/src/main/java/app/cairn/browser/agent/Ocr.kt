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

    /**
     * Self-healing click target: finds on-screen text matching one of [labels] (e.g. "Add to Cart") and returns the centre of
     * its box in bitmap pixels. Used when a site's DOM hides the button (canvas, shadow DOM, obfuscated markup).
     */
    suspend fun locate(bmp: Bitmap, labels: List<String>): Triple<Float, Float, String>? {
        val img = InputImage.fromBitmap(bmp, 0)
        val res = withTimeoutOrNull(15_000) { suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { c ->
            latin.process(img).addOnSuccessListener { if (c.isActive) c.resume(it) }.addOnFailureListener { if (c.isActive) c.resume(null) }
        } } ?: return null
        fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9₹ ]"), " ").replace(Regex("\\s+"), " ").trim()
        val wants = labels.map(::norm).filter { it.isNotEmpty() }
        var best: Triple<Float, Float, String>? = null; var bestScore = 0
        for (b in res.textBlocks) for (l in b.lines) {
            val t = norm(l.text); val box = l.boundingBox ?: continue
            for ((rank, w) in wants.withIndex()) {
                // Exact button phrases win; a single word ("add") is only accepted as the whole line, never inside a sentence.
                val multi = w.contains(' ')
                val score = when {
                    t == w -> 100
                    multi && t.startsWith(w) && t.length <= w.length + 6 -> 85
                    multi && t.contains(w) && t.length <= w.length + 12 -> 60
                    else -> 0
                } - rank * 2
                if (score <= 0 || box.height() < 12) continue
                if (score > bestScore) { bestScore = score; best = Triple(box.exactCenterX(), box.exactCenterY(), l.text) }
            }
        }
        return best
    }
}
