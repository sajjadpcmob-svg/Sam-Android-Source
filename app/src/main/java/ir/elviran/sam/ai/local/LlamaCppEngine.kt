package ir.elviran.sam.ai.local

import android.content.Context
import ir.elviran.sam.ai.LocalModelEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real on-device generative engine backed by llama.cpp through [LlamaNative]. It implements the
 * app's existing [LocalModelEngine] contract, so the already-wired [ir.elviran.sam.ai.AiRouter]
 * uses it with no API change: when [isReady] is true the router generates fully offline.
 *
 * Responsibilities: load / free the GGUF model and its context, run bounded generation off the main
 * thread (so the UI never blocks), expose a [state] flow for the UI, stop generation on request and
 * guard against loading a model larger than the device can hold.
 *
 * A single process-wide instance keeps the loaded model in memory across voice turns.
 */
class LlamaCppEngine private constructor(context: Context) : LocalModelEngine {
    private val app = context.applicationContext
    private val manager = LlmModelManager(app)
    private val settings = LlmSettingsStore(app)
    private val mutex = Mutex()

    @Volatile private var modelHandle: Long = 0L
    @Volatile private var ctxHandle: Long = 0L
    @Volatile private var loadedSpecId: String? = null

    private val _state = MutableStateFlow<LlmState>(LlmState.NotInstalled)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    val nativeAvailable: Boolean get() = LlamaNative.available
    val nativeLoadError: String? get() = LlamaNative.loadError

    /** Ready for the router only when native lib is present AND a model context is loaded. */
    override suspend fun isReady(): Boolean = LlamaNative.available && ctxHandle != 0L

    fun refreshInstallState(spec: LlmModelSpec) {
        _state.value = when {
            ctxHandle != 0L && loadedSpecId == spec.id -> LlmState.Ready
            manager.isInstalled(spec) -> LlmState.ReadyToLoad
            else -> LlmState.NotInstalled
        }
    }

    suspend fun download(spec: LlmModelSpec): String? {
        _state.value = LlmState.Downloading(0)
        val err = manager.download(spec) { p -> _state.value = LlmState.Downloading(p) }
        _state.value = if (err == null) LlmState.ReadyToLoad else LlmState.Error(err)
        return err
    }

    /** Load the model + context into memory. Safe to call from a coroutine; UI stays responsive. */
    suspend fun load(spec: LlmModelSpec): String? = mutex.withLock {
        if (!LlamaNative.available) {
            val msg = "کتابخانه نیتیو llama.cpp در این بیلد کامپایل نشده است" +
                (LlamaNative.loadError?.let { " ($it)" } ?: "")
            _state.value = LlmState.Error(msg); return msg
        }
        val path = manager.installedPath(spec) ?: run {
            _state.value = LlmState.NotInstalled; return "مدل نصب نشده است"
        }
        if (!spec.fitsRam(manager.deviceRamMb())) {
            val msg = "حافظه دستگاه برای بارگذاری این مدل کافی نیست"
            _state.value = LlmState.Error(msg); return msg
        }
        _state.value = LlmState.Loading
        return withContext(Dispatchers.IO) {
            freeInternal()
            val cfg = settings.read()
            val mh = runCatching { LlamaNative.loadModel(path, 0) }.getOrDefault(0L)
            if (mh == 0L) { _state.value = LlmState.Error("بارگذاری مدل ناموفق بود"); return@withContext "بارگذاری مدل ناموفق بود" }
            val ctx = runCatching { LlamaNative.newContext(mh, cfg.contextLength.coerceAtMost(spec.recommendedContext), cfg.threads) }.getOrDefault(0L)
            if (ctx == 0L) { LlamaNative.freeModel(mh); _state.value = LlmState.Error("ساخت زمینه مدل ناموفق بود"); return@withContext "ساخت زمینه مدل ناموفق بود" }
            modelHandle = mh; ctxHandle = ctx; loadedSpecId = spec.id
            _state.value = LlmState.Ready
            null
        }
    }

    override suspend fun generate(prompt: String): String {
        if (!LlamaNative.available || ctxHandle == 0L) return "مدل آفلاین آماده نیست"
        val cfg = settings.read()
        _state.value = LlmState.Generating
        return try {
            withContext(Dispatchers.IO) {
                LlamaNative.generate(ctxHandle, modelHandle, prompt, cfg.maxTokens)
            }
        } catch (t: Throwable) {
            "خطا در تولید پاسخ آفلاین: ${t.message}"
        } finally {
            if (ctxHandle != 0L) _state.value = LlmState.Ready
        }
    }

    /** Cooperative stop for an in-flight generation. */
    fun stopGeneration() { if (LlamaNative.available && ctxHandle != 0L) runCatching { LlamaNative.requestStop(ctxHandle) } }

    /** Free all native memory. Call when the user unloads the model or the process ends. */
    fun unload() {
        freeInternal()
        _state.value = LlmState.NotInstalled
    }

    private fun freeInternal() {
        if (ctxHandle != 0L) { runCatching { LlamaNative.freeContext(ctxHandle) }; ctxHandle = 0L }
        if (modelHandle != 0L) { runCatching { LlamaNative.freeModel(modelHandle) }; modelHandle = 0L }
        loadedSpecId = null
    }

    companion object {
        @Volatile private var INSTANCE: LlamaCppEngine? = null
        fun get(context: Context): LlamaCppEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlamaCppEngine(context).also { INSTANCE = it }
            }
    }
}
