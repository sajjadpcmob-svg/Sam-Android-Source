package ir.elviran.sam.voice

import android.content.Context
import ir.elviran.sam.voice.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live snapshot the UI renders. */
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val status: String = "غیرفعال",
    val partial: String = "",
    val lastUserText: String = "",
    val lastReply: String = "",
    val engine: String = "آفلاین",
    val online: Boolean = false,
    val modelInstalled: Boolean = false,
    val error: String = ""
)

private enum class Role { NONE, WAKE, OFFLINE_STT, BARGE_IN }

/**
 * Orchestrates the hands-free loop: wake «سام» -> listen -> recognise (offline Vosk or online) ->
 * [responder] -> speak (Persian TTS) -> listen again, with real barge-in. One [AudioCapture] feeds
 * the wake-word, offline STT and barge-in VAD; the online recogniser owns the mic only while it runs.
 *
 * All device I/O lives here; legal transitions are delegated to the pure [ConversationStateMachine].
 * The app passes [responder] to reach its AiRouter/OfflineBrain so this class stays UI-agnostic.
 */
class ConversationController(
    context: Context,
    private val scope: CoroutineScope,
    private val responder: suspend (String) -> String
) {
    private val app = context.applicationContext
    private val capture = AudioCapture()
    private val vad = VadController()
    private val connectivity = ConnectivityWatcher(app)
    private val modelManager = VoskModelManager(app)
    private val settingsStore = VoiceSettingsStore(app)
    private val online = OnlineSpeechEngine(app)
    private val machine = ConversationStateMachine()

    private val lock = Any()
    @Volatile private var role = Role.NONE
    @Volatile private var prefs = VoicePrefs()
    @Volatile private var useOnlineThisTurn = false
    /**
     * Monotonic id for the current listening turn. Every fresh [startListeningForUser], every
     * [muteNow] and every [shutdown] bumps it, so a LATE or DUPLICATE online callback that belongs
     * to a turn the user already cancelled (or that was superseded by fallback) is dropped instead
     * of starting a new recording or a second fallback. This is the race-condition guard.
     */
    private val turnSession = java.util.concurrent.atomic.AtomicInteger(0)
    private var wake: WakeWordDetector? = null
    private var offline: OfflineSpeechEngine? = null
    private var tts: TtsEngine? = null

    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui.asStateFlow()

    val isModelInstalled: Boolean get() = modelManager.isInstalled()
    suspend fun installModel(progress: (Int) -> Unit): String? = modelManager.install(progress)
    suspend fun loadPrefs(): VoicePrefs = settingsStore.read().also { prefs = it }
    suspend fun savePrefs(p: VoicePrefs) { settingsStore.write(p); prefs = p; if (!p.wakeWordEnabled) muteNow() else applyPrefs() }

    private fun applyPrefs() {
        vad.configure(endSilenceMs = prefs.endSilenceMs())
        wake?.setSensitivity(prefs.wakeSensitivity); wake?.setCooldown(prefs.wakeCooldownMs())
        tts?.setVoiceParams(prefs.speechRate, prefs.pitch)
    }

    private fun update(block: VoiceUiState.() -> VoiceUiState) { _ui.value = _ui.value.block() }

    /** Turn the assistant on: load prefs, TTS, model, then start wake listening. */
    fun start() {
        scope.launch {
            prefs = settingsStore.read()
            if (!prefs.wakeWordEnabled) {
                update { copy(phase = VoicePhase.IDLE, status = "دستیار صوتی از تنظیمات خاموش است", error = "voice-disabled") }
                return@launch
            }
            connectivity.unregister()
            connectivity.register { onlineNow -> update { copy(online = onlineNow) }; machine.next(if (onlineNow) VoiceSignal.NetworkBack else VoiceSignal.NetworkLost) }
            update { copy(online = connectivity.isOnline(), modelInstalled = modelManager.isInstalled()) }
            ensureTts()
            val model = modelManager.model()
            // Online push-to-talk must remain usable even before the optional Vosk model is
            // downloaded. The Vosk model is required only for on-device wake-word/offline STT.
            synchronized(lock) {
                wake?.close(); offline?.close()
                wake = model?.let { WakeWordDetector(it, capture.sampleRate.toFloat(), prefs.wakeSensitivity, prefs.wakeCooldownMs()) }
                offline = model?.let { OfflineSpeechEngine(it, capture.sampleRate.toFloat()) }
            }
            applyPrefs()
            machine.reset(); machine.next(VoiceSignal.Start)
            if (model == null) {
                update {
                    copy(
                        phase = VoicePhase.WAKE_LISTENING,
                        status = if (prefs.preferOnline && online.isAvailable)
                            "آماده گفتار آنلاین؛ برای صحبت دکمه «صحبت با سام» را بزن. برای بیدارباش و گفتار آفلاین، مدل Vosk را نصب کن."
                        else "مدل فارسی Vosk نصب نیست؛ برای گفتار آفلاین آن را دانلود کن. در صورت پشتیبانی سرویس گوشی می‌توانی از گفتار آنلاین استفاده کنی.",
                        modelInstalled = false,
                        engine = if (prefs.preferOnline && online.isAvailable) "آنلاین (fa-IR)" else "بدون مدل گفتار"
                    )
                }
                return@launch
            }
            enterWakeListening()
        }
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TtsEngine(app,
            onReady = { ok -> if (!ok) update { copy(status = "صدای فارسی نصب نیست؛ در تنظیمات گوشی داده گفتار فارسی را نصب کن") } },
            onStart = { update { copy(phase = VoicePhase.SPEAKING, status = "سام در حال صحبت است") }; beginBargeInWatch() },
            onDone = { onSpeakingDone() },
            onError = { msg -> update { copy(status = msg) }; onSpeakingDone() }
        )
    }

    // ---- phase helpers ----
    private fun enterWakeListening() {
        synchronized(lock) { role = Role.WAKE }
        wake?.reset(); vad.reset()
        update { copy(phase = VoicePhase.WAKE_LISTENING, status = "منتظر «سام»...", partial = "", engine = engineLabel()) }
        startCaptureIfNeeded()
    }

    private fun onWake() {
        if (machine.phase != VoicePhase.WAKE_LISTENING) return
        machine.next(VoiceSignal.WakeDetected)
        tts?.stop()
        startListeningForUser()
    }

    /**
     * Explicit push-to-talk entry point. It does not require the Vosk wake-word model when
     * Android's online SpeechRecognizer is available, so first-run voice can be tested before
     * downloading the offline model or enabling hands-free wake word.
     */
    fun listenNow() {
        scope.launch {
            if (machine.phase == VoicePhase.IDLE) {
                prefs = settingsStore.read()
                ensureTts()
                machine.reset()
                machine.next(VoiceSignal.Start)
            }
            if (machine.phase == VoicePhase.WAKE_LISTENING || machine.phase == VoicePhase.ERROR) {
                machine.next(VoiceSignal.WakeDetected)
            }
            if (machine.phase != VoicePhase.LISTENING) {
                update { copy(status = "برای شروع صحبت، ابتدا سام را فعال کن") }
                return@launch
            }
            if (offline == null && (!prefs.preferOnline || !connectivity.isOnline() || !online.isAvailable)) {
                update {
                    copy(
                        phase = VoicePhase.ERROR,
                        status = "برای گفتار، یا سرویس تشخیص آنلاین فارسی لازم است یا مدل Vosk باید نصب شود.",
                        error = "speech-engine-unavailable"
                    )
                }
                return@launch
            }
            startListeningForUser()
        }
    }

    private fun startListeningForUser(forceOffline: Boolean = false) {
        // A fresh turn may pick the online engine; a turn retried after an online failure is pinned
        // to offline so an online error can NEVER re-enter the same online path unbounded.
        val mySession = turnSession.incrementAndGet()
        useOnlineThisTurn = !forceOffline && prefs.preferOnline && connectivity.isOnline() && online.isAvailable
        vad.reset(); update { copy(phase = VoicePhase.LISTENING, status = "گوش می‌دهم؛ صحبت کن", partial = "", engine = engineLabel()) }
        if (useOnlineThisTurn) {
            capture.stop(); synchronized(lock) { role = Role.NONE }
            online.start { partial, finalText, state, error ->
                // Drop any callback that does not belong to the current turn (late / duplicate /
                // post-cancel). Without this, a stale error could trigger a second fallback or a
                // recording after the user already stopped the session.
                if (mySession == turnSession.get()) {
                    when {
                        error != null -> {
                            online.stop()
                            // Fall back to the offline engine exactly once; do not loop back into online.
                            if (offline != null) {
                                update { copy(status = "آنلاین ناموفق؛ تلاش با موتور آفلاین") }
                                startListeningForUser(forceOffline = true)
                            } else {
                                update { copy(phase = VoicePhase.ERROR, status = "تشخیص آنلاین ناموفق و مدل آفلاین نصب نیست", error = "model-missing") }
                                enterWakeListening()
                            }
                        }
                        finalText != null -> onUserTranscript(finalText)
                        partial != null -> update { copy(partial = partial) }
                        state != null -> update { copy(status = state) }
                    }
                }
            }
        } else {
            offline?.reset(); synchronized(lock) { role = Role.OFFLINE_STT }
            startCaptureIfNeeded()
        }
    }

    private fun onUserTranscript(text: String) {
        // SpeechRecognizer owns the microphone in online mode. Release it synchronously before
        // starting response/TTS/barge-in capture, otherwise the two audio sessions can overlap.
        online.stop()
        val clean = text.trim()
        if (machine.phase != VoicePhase.LISTENING) return
        if (clean.isBlank()) { machine.next(VoiceSignal.EmptyTranscript); update { copy(status = "چیزی نشنیدم") }; enterWakeListening(); return }
        machine.next(VoiceSignal.Transcript(clean))
        update { copy(phase = VoicePhase.PROCESSING, status = "در حال پردازش", partial = "", lastUserText = clean) }
        synchronized(lock) { role = Role.NONE }
        scope.launch {
            val reply = runCatching { withContext(Dispatchers.IO) { responder(clean) } }
                .getOrElse { "خطا در پردازش: ${it.message ?: "نامشخص"}" }
            machine.next(VoiceSignal.Reply(reply))
            update { copy(lastReply = reply, status = "پاسخ آماده شد") }
            ensureTts(); startCaptureIfNeeded(); tts?.speak(reply)
        }
    }

    private fun beginBargeInWatch() { vad.reset(); synchronized(lock) { role = Role.BARGE_IN }; startCaptureIfNeeded() }

    private fun onBargeIn() {
        if (machine.phase != VoicePhase.SPEAKING) return
        tts?.stop(notify = false); machine.next(VoiceSignal.BargeIn)
        update { copy(status = "شنیدم که صحبت کردی") }
        startListeningForUser()
    }

    private fun onSpeakingDone() {
        if (machine.phase != VoicePhase.SPEAKING) return
        val next = machine.next(VoiceSignal.SpeakingDone)
        if (next == VoicePhase.LISTENING && prefs.followUp) startListeningForUser() else enterWakeListening()
    }

    private fun engineLabel(): String = if (connectivity.isOnline() && prefs.preferOnline && online.isAvailable) "آنلاین (fa-IR)" else "آفلاین (Vosk)"

    private fun startCaptureIfNeeded() {
        if (capture.isRunning) return
        capture.start({ frame, len, ts -> onFrame(frame, len, ts) }, { err -> update { copy(phase = VoicePhase.ERROR, status = err, error = "audio") } })
    }

    private fun onFrame(frame: ShortArray, len: Int, ts: Long) {
        when (role) {
            Role.WAKE -> if (wake?.accept(frame, len, ts) == true) onWake()
            Role.OFFLINE_STT -> {
                val eng = offline ?: return
                val partial = eng.accept(frame, len)
                if (partial.isNotBlank()) update { copy(partial = partial) }
                when (vad.onFrame(vad.rms(frame, len), ts)) {
                    VadController.Event.END_OF_UTTERANCE -> { synchronized(lock) { role = Role.NONE }; onUserTranscript(eng.finalize()) }
                    else -> {}
                }
            }
            Role.BARGE_IN -> if (vad.onFrameWhileSpeaking(vad.rms(frame, len), ts) == VadController.Event.BARGE_IN) onBargeIn()
            Role.NONE -> {}
        }
    }

    /** Immediate microphone stop requested by the user (panic / privacy). */
    fun muteNow() {
        turnSession.incrementAndGet()   // invalidate any in-flight online/offline callback
        synchronized(lock) { role = Role.NONE }
        online.stop(); capture.stop(); tts?.stop()
        update { copy(phase = VoicePhase.IDLE, status = "شنود متوقف شد", partial = "") }
        machine.next(VoiceSignal.Stop)
    }

    fun stopSpeakingOnly() { tts?.stop() }

    fun shutdown() {
        turnSession.incrementAndGet()   // invalidate any in-flight callback before tearing down
        synchronized(lock) { role = Role.NONE }
        online.stop(); capture.stop(); connectivity.unregister()
        tts?.shutdown(); tts = null
        wake?.close(); offline?.close(); wake = null; offline = null
        modelManager.release()
        update { copy(phase = VoicePhase.IDLE, status = "غیرفعال") }
    }
}
