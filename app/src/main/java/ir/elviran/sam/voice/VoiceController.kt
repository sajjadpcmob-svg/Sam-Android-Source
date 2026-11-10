package ir.elviran.sam.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Device-native Persian speech I/O. This is foreground push-to-talk, not a hidden wake-word service. */
class VoiceController(
    context: Context,
    private val onText: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onPartial: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = TextToSpeech(app, this)
    private var ttsReady = false
    private var listening = false
    private var retriedOnline = false
    private var closed = false
    private val speaking = AtomicBoolean(false)

    fun startListening() {
        if (closed) return
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            onState("سرویس تشخیص گفتار روی دستگاه در دسترس نیست؛ سرویس گفتار گوشی را بررسی کن")
            return
        }
        stopRecognition()
        retriedOnline = false
        onPartial("")
        val engine = runCatching { SpeechRecognizer.createSpeechRecognizer(app) }.getOrElse {
            onState("ساخت سرویس تشخیص گفتار ناموفق بود: ${it.message ?: "خطای دستگاه"}")
            return
        }
        recognizer = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; onState("در حال شنیدن؛ صحبت کن") }
            override fun onBeginningOfSpeech() { onState("صدایت را دریافت می‌کنم") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; onState("گفتار دریافت شد؛ در حال تبدیل به متن…") }
            override fun onError(error: Int) {
                listening = false
                if ((error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) && !retriedOnline) {
                    retriedOnline = true
                    onState("تشخیص آفلاین در دسترس نبود؛ یک‌بار سرویس آنلاین را امتحان می‌کنم")
                    startListeningWithNetworkPreference()
                } else onState(errorMessage(error))
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                onPartial("")
                if (result.isNotBlank()) {
                    onText(result)
                    onState("متن دریافت شد؛ برای ارسال، دکمه ارسال را بزن")
                } else onState("صدای واضحی دریافت نشد؛ دوباره تلاش کن")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotBlank()) onPartial(partial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListeningWithNetworkPreference()
    }

    private fun startListeningWithNetworkPreference() {
        val engine = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !retriedOnline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            onState("شروع میکروفن ناموفق بود: ${it.message ?: "خطای سرویس گفتار"}")
        }
    }

    override fun onInit(status: Int) {
        if (closed) return
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false
            onState("موتور تبدیل متن به گفتار راه‌اندازی نشد")
            return
        }
        val engine = tts ?: return
        val persianIran = Locale("fa", "IR")
        val availability = runCatching { engine.isLanguageAvailable(persianIran) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val selectedLocale = if (availability >= TextToSpeech.LANG_AVAILABLE) persianIran else Locale("fa")
        val result = runCatching { engine.setLanguage(selectedLocale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setSpeechRate(0.96f)
        engine.setPitch(1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking.set(true); onState("سام در حال صحبت است") }
            override fun onDone(utteranceId: String?) { speaking.set(false); onState("آماده") }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { speaking.set(false); onState("پخش صدا ناموفق بود") }
            override fun onError(utteranceId: String?, errorCode: Int) { speaking.set(false); onState("خطای موتور گفتار: $errorCode") }
        })
        onState(if (ttsReady) "صدای فارسی آماده است" else "صدای فارسی نصب نیست؛ در تنظیمات گوشی، دادهٔ گفتار فارسی را نصب کن")
    }

    fun speak(text: String) {
        if (closed || text.isBlank()) return
        val engine = tts
        if (!ttsReady || engine == null) {
            onState("صدای فارسی آماده نیست؛ متن پاسخ نمایش داده شد")
            return
        }
        val safeText = text.trim().take(3500)
        val result = engine.speak(safeText, TextToSpeech.QUEUE_FLUSH, Bundle(), "sam-${SystemClock.elapsedRealtime()}")
        if (result == TextToSpeech.ERROR) onState("موتور گفتار نتوانست پاسخ را پخش کند")
    }

    fun stopSpeaking() {
        tts?.stop()
        speaking.set(false)
        onState("پخش صدا متوقف شد")
    }

    fun stop() {
        stopRecognition()
        stopSpeaking()
        onPartial("")
        onState("متوقف شد")
    }

    private fun stopRecognition() {
        listening = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "خطای ورودی صدا؛ میکروفن یا هدست را بررسی کن"
        SpeechRecognizer.ERROR_CLIENT -> "سرویس تشخیص گفتار متوقف شد؛ دوباره تلاش کن"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز میکروفن داده نشده است"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "سرویس تشخیص گفتار به شبکه دسترسی ندارد؛ بستهٔ آفلاین فارسی را بررسی کن"
        SpeechRecognizer.ERROR_NO_MATCH -> "گفتار تشخیص داده نشد؛ واضح‌تر و نزدیک‌تر صحبت کن"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "موتور گفتار مشغول است؛ یک لحظه صبر کن"
        SpeechRecognizer.ERROR_SERVER -> "خطای سرویس تشخیص گفتار؛ دوباره تلاش کن"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "صدایی دریافت نشد؛ برای شروع دوباره میکروفن را بزن"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "درخواست‌های گفتار زیاد شده؛ چند ثانیه صبر کن"
        else -> "تشخیص گفتار با خطای $code متوقف شد"
    }

    fun close() {
        if (closed) return
        closed = true
        stopRecognition()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }
}
