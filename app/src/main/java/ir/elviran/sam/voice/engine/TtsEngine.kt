package ir.elviran.sam.voice.engine

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persian Text-to-Speech with fast start, a chunked queue (so a long answer begins speaking from
 * the first sentence instead of waiting for the whole text), instant barge-in stop, number/Jalali
 * normalisation via [PersianSpeech], and clear callbacks the conversation controller uses to gate
 * the microphone while SAM is talking.
 */
class TtsEngine(
    context: Context,
    private val onReady: (Boolean) -> Unit,
    private val onStart: () -> Unit,
    private val onDone: () -> Unit,
    private val onError: (String) -> Unit
) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(app, this)
    private var ready = false
    private val speaking = AtomicBoolean(false)
    private var rate = 0.96f
    private var pitch = 1.0f

    val isSpeaking: Boolean get() = speaking.get()

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) { ready = false; onReady(false); return }
        val faIr = Locale("fa", "IR")
        val avail = runCatching { engine.isLanguageAvailable(faIr) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val locale = if (avail >= TextToSpeech.LANG_AVAILABLE) faIr else Locale("fa")
        val res = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setSpeechRate(rate); engine.setPitch(pitch)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (speaking.compareAndSet(false, true)) onStart() }
            override fun onDone(id: String?) { if (id != null && id.endsWith("-last")) { speaking.set(false); onDone() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking.set(false); onError("پخش صدا ناموفق بود") }
            override fun onError(id: String?, code: Int) { speaking.set(false); onError("خطای موتور گفتار: $code") }
        })
        onReady(ready)
    }

    fun setVoiceParams(speechRate: Double, pitch: Double) {
        this.rate = speechRate.toFloat(); this.pitch = pitch.toFloat()
        tts?.setSpeechRate(this.rate); tts?.setPitch(this.pitch)
    }

    /** Speak an answer. Splits into sentence chunks and queues them so playback starts quickly. */
    fun speak(text: String) {
        val engine = tts
        if (!ready || engine == null) { onError("صدای فارسی آماده نیست"); return }
        val norm = PersianSpeech.normalizeForSpeech(text.trim()).take(4000)
        if (norm.isBlank()) return
        val chunks = norm.split(Regex("(?<=[.!؟?۔\n])")).map { it.trim() }.filter { it.isNotBlank() }
        val stamp = SystemClock.elapsedRealtime()
        if (chunks.isEmpty()) { engine.speak(norm, TextToSpeech.QUEUE_FLUSH, Bundle(), "sam-$stamp-last"); return }
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "sam-$stamp-$i" + if (i == chunks.lastIndex) "-last" else ""
            engine.speak(chunk, mode, Bundle(), id)
        }
    }

    /** Instant barge-in / interrupt. Pass notify=false when the caller drives the next phase itself. */
    fun stop(notify: Boolean = true) { runCatching { tts?.stop() }; val wasSpeaking = speaking.getAndSet(false); if (wasSpeaking && notify) onDone() }

    fun shutdown() { runCatching { tts?.stop() }; runCatching { tts?.shutdown() }; tts = null; ready = false; speaking.set(false) }
}
