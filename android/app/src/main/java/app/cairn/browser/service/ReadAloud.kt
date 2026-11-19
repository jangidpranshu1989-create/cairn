package app.cairn.browser.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.cairn.browser.R
import app.cairn.browser.CairnApp
import app.cairn.browser.data.Prefs
import app.cairn.browser.ui.BrowserActivity
import java.util.Locale

/** Text-to-speech reader with pause/resume, speed and a media notification so it keeps going with the screen off. */
object ReadAloud {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var chunks: List<String> = emptyList()
    private var pendingStart: (() -> Unit)? = null
    private lateinit var app: Context

    var title by mutableStateOf("")
    var active by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var index by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun engine(then: () -> Unit) {
        if (tts != null && ready) return then()
        pendingStart = then
        if (tts == null) tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) { id.toIntOrNull()?.let { index = it } }
                    override fun onDone(id: String) { if (id.toIntOrNull() == chunks.size - 1) finish() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String) {}
                    override fun onError(id: String, code: Int) { if (id.toIntOrNull() == chunks.size - 1) finish() }
                })
                pendingStart?.invoke(); pendingStart = null
            }
        }
    }

    private fun split(text: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder()
        // Split on sentence ends (incl. the Devanagari danda) and keep chunks well under the engine limit
        for (s in text.replace(Regex("\\s+\\n"), "\n").split(Regex("(?<=[.!?।])\\s+|\\n+"))) {
            if (s.isBlank()) continue
            if (sb.length + s.length > 1200) { out += sb.toString(); sb.clear() }
            sb.append(s.trim()).append(' ')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    fun locale(text: String, lang: String?): Locale = when {
        !lang.isNullOrBlank() -> Locale.forLanguageTag(lang)
        text.take(2000).count { it in '\u0900'..'\u097F' } > 40 -> Locale("hi", "IN")
        else -> Locale.getDefault().takeIf { it.language == "en" } ?: Locale("en", "IN")
    }

    fun speak(title: String, text: String, lang: String? = null) {
        stop()
        this.title = title.ifBlank { "Reading aloud" }
        chunks = split(text); total = chunks.size; index = 0
        if (chunks.isEmpty()) return
        active = true
        engine {
            val loc = locale(text, lang)
            val r = tts?.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale.US
            Prefs.ttsVoice.takeIf { it.isNotBlank() }?.let { v -> tts?.voices?.firstOrNull { it.name == v }?.let { tts?.voice = it } }
            from(0)
        }
        ReadAloudService.start(app)
    }

    private fun from(i: Int) {
        val t = tts ?: return
        t.setSpeechRate(Prefs.ttsRate / 100f)
        t.stop()
        for (k in i until chunks.size) t.speak(chunks[k], TextToSpeech.QUEUE_ADD, Bundle(), k.toString())
        index = i; playing = true
        ReadAloudService.update(app)
    }

    fun pause() { tts?.stop(); playing = false; ReadAloudService.update(app) }
    fun resume() { if (active) engine { from(index) } }
    fun toggle() = if (playing) pause() else resume()
    fun skip(delta: Int) { if (active) from((index + delta).coerceIn(0, (chunks.size - 1).coerceAtLeast(0))) }
    fun setRate(pct: Int) { Prefs.ttsRate = pct; if (playing) from(index) }

    private fun finish() { playing = false; active = false; index = 0; ReadAloudService.stop(app) }
    fun stop() { tts?.stop(); if (active) { active = false; playing = false; ReadAloudService.stop(app) } }

    fun voices(): List<String> = runCatching { tts?.voices?.filter { !it.isNetworkConnectionRequired }?.map { it.name }?.sorted() }.getOrNull() ?: emptyList()
}

class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> ReadAloud.toggle()
            ACTION_STOP -> { ReadAloud.stop(); stopSelf(); return START_NOT_STICKY }
        }
        if (!ReadAloud.active) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, 77, notification(this),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_TOGGLE = "toggle"; const val ACTION_STOP = "stop"
        fun start(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, ReadAloudService::class.java)) }
        fun update(ctx: Context) {
            if (!ReadAloud.active) return
            runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(77, notification(ctx)) }
        }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, ReadAloudService::class.java)) }

        private fun pi(ctx: Context, action: String) = PendingIntent.getService(ctx, action.hashCode(), Intent(ctx, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun notification(ctx: Context) = NotificationCompat.Builder(ctx, CairnApp.CH_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_cairn).setColor(0xFFB89A6A.toInt())
            .setContentTitle(ReadAloud.title).setContentText(if (ReadAloud.playing) "Cairn is reading aloud" else "Paused")
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, BrowserActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, if (ReadAloud.playing) "Pause" else "Play", pi(ctx, ACTION_TOGGLE))
            .addAction(0, "Stop", pi(ctx, ACTION_STOP))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1))
            .setOngoing(ReadAloud.playing).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
    }
}
