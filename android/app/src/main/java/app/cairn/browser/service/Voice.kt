package app.cairn.browser.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cairn.browser.agent.Llm
import kotlinx.coroutines.*
import java.io.File

/**
 * In-app voice input: records with MediaRecorder and transcribes with Whisper (Groq). No system speech service,
 * no Google. Stops automatically after ~1.6 s of silence or 60 s.
 */
object Voice {
    enum class Phase { Idle, Listening, Transcribing }
    var phase by mutableStateOf(Phase.Idle); private set
    var level by mutableFloatStateOf(0f); private set      // 0..1 live input level for the waveform
    var error by mutableStateOf<String?>(null)
    /** Shown to the user when recording or transcription fails after the mic sheet has closed (otherwise it fails silently). */
    var onError: ((String) -> Unit)? = null
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var onText: ((String) -> Unit)? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + app.cairn.browser.Crash.handler)

    fun start(ctx: Context, then: (String) -> Unit) {
        if (phase != Phase.Idle) return
        error = null; onText = then
        val f = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a"); file = f
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1); setAudioSamplingRate(16_000); setAudioEncodingBitRate(48_000)
            setOutputFile(f.path)
        }
        try { r.prepare(); r.start() } catch (e: Exception) { r.release(); error = "Microphone is unavailable"; return }
        rec = r; phase = Phase.Listening
        job = scope.launch {
            val started = System.currentTimeMillis(); var heard = false; var quietSince = System.currentTimeMillis()
            while (isActive && phase == Phase.Listening) {
                val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                val l = (amp / 12_000f).coerceIn(0f, 1f)
                level = level * 0.55f + l * 0.45f
                if (l > 0.035f) { heard = true; quietSince = System.currentTimeMillis() }
                val now = System.currentTimeMillis()
                if ((heard && now - quietSince > 1600) || now - started > 60_000 || (!heard && now - started > 10_000)) { finish(); break }
                delay(70)
            }
        }
    }

    /** Stop recording and transcribe. */
    fun finish() {
        val r = rec ?: return; rec = null
        runCatching { r.stop() }; r.release(); level = 0f
        val f = file ?: return
        phase = Phase.Transcribing
        scope.launch {
            try {
                val text = app.cairn.browser.agent.Intents.cleanTranscript(Llm.transcribe(f))
                phase = Phase.Idle
                if (text.isNotBlank()) onText?.invoke(text) else fail("Didn't catch that — try again")
            } catch (e: CancellationException) { phase = Phase.Idle }
            catch (e: Exception) { phase = Phase.Idle; fail(if (e.message?.contains("Groq", true) == true || e.message?.contains("503") == true) "Voice is unavailable right now — please type instead" else "Couldn't hear that (${e.message?.take(60) ?: "network"}) — try again") }
            finally { f.delete() }
        }
    }

    private fun fail(msg: String) { error = msg; onError?.invoke(msg) }

    fun cancel() {
        job?.cancel(); rec?.let { runCatching { it.stop() }; it.release() }; rec = null; file?.delete(); level = 0f; phase = Phase.Idle
    }
}
