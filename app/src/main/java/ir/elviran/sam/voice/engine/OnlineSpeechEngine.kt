package ir.elviran.sam.voice.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Online Persian speech-to-text via the device [SpeechRecognizer] with network preference. It owns
 * its own microphone session, so the controller must stop [AudioCapture] before starting this and
 * restart capture afterwards. Used only when the network is up and the user allows online STT.
 */
class OnlineSpeechEngine(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    fun interface Callbacks {
        fun onEvent(partial: String?, finalText: String?, state: String?, error: String?)
    }

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(app)

    fun start(cb: Callbacks) {
        main.post {
            stopInternal()
            if (!isAvailable) { cb.onEvent(null, null, null, "سرویس تشخیص گفتار آنلاین در دسترس نیست"); return@post }
            val engine = runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrNull()
                ?: run { cb.onEvent(null, null, null, "ساخت سرویس گفتار ناموفق بود"); return@post }
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { cb.onEvent(null, null, "در حال شنیدن", null) }
                override fun onBeginningOfSpeech() { cb.onEvent(null, null, "دریافت صدا", null) }
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { cb.onEvent(null, null, "در حال تبدیل به متن", null) }
                override fun onError(code: Int) { cb.onEvent(null, null, null, errorText(code)) }
                override fun onResults(res: Bundle?) {
                    val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    cb.onEvent(null, t, null, null)
                }
                override fun onPartialResults(res: Bundle?) {
                    val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotBlank()) cb.onEvent(t, null, null, null)
                }
                override fun onEvent(type: Int, p: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            }
            runCatching { engine.startListening(intent) }.onFailure { cb.onEvent(null, null, null, "شروع میکروفون ناموفق: ${it.message}") }
        }
    }

    fun stop() {
        // RecognitionListener callbacks are delivered on the main thread. Stop immediately there
        // so the controller can safely hand the microphone to AudioCapture for VAD/TTS barge-in.
        if (Looper.myLooper() == Looper.getMainLooper()) stopInternal()
        else main.post { stopInternal() }
    }
    private fun stopInternal() {
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "شبکه در دسترس نیست"
        SpeechRecognizer.ERROR_NO_MATCH -> "گفتار تشخیص داده نشد"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "صدایی دریافت نشد"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز میکروفون داده نشده"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "موتور گفتار مشغول است"
        else -> "خطای تشخیص گفتار: $code"
    }
}
