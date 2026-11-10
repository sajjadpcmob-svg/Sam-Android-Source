package ir.elviran.sam.ai.local

import android.content.Context
import ir.elviran.sam.ai.ChatTurn
import ir.elviran.sam.ai.LocalModelEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device generative engine backed by llama.cpp. It implements the app's [LocalModelEngine]
 * contract, so [ir.elviran.sam.ai.AiRouter] uses it unchanged: when [isReady] is true the router
 * generates fully offline.
 *
 * The thread-safety / KV-cache / stop / free rules live in [LlmSession] (unit-tested with a fake
 * backend). This class adds the Android parts: model files ([LlmModelManager]), settings, the chat
 * template of the loaded model, and the [state] flow for the UI.
 *
 * Every request is formatted with the loaded model's [ChatTemplate]; a raw un-templated string is
 * never sent to the model. A single process-wide instance keeps the model in memory across turns.
 */
class LlamaCppEngine private constructor(context: Context) : LocalModelEngine {
    private val app = context.applicationContext
    private val manager = LlmModelManager(app)
    private val settings = LlmSettingsStore(app)
    private val session = LlmSession(NativeLlamaBackend())
    private val loadMutex = Mutex()
    private val activeRequests = AtomicInteger(0)

    @Volatile private var loadedSpecId: String? = null
    @Volatile private var loadedTemplate: ChatTemplate? = null

    private val _state = MutableStateFlow<LlmState>(LlmState.NotInstalled)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    val nativeAvailable: Boolean get() = LlamaNative.available
    val nativeLoadError: String? get() = LlamaNative.loadError

    /** Ready for the router only when the native lib is present AND a model context is loaded. */
    override suspend fun isReady(): Boolean = LlamaNative.available && session.isLoaded && loadedTemplate != null

    fun refreshInstallState(spec: LlmModelSpec) {
        if (_state.value is LlmState.Downloading || _state.value is LlmState.Loading ||
            _state.value is LlmState.Verifying || _state.value is LlmState.Generating) return
        _state.value = when {
            session.isLoaded && loadedSpecId == spec.id -> LlmState.Ready
            manager.isInstalled(spec) -> LlmState.ReadyToLoad
            else -> LlmState.NotInstalled
        }
    }

    /** Trust level of the selected model's file, for the UI (fast, no hashing). */
    fun trust(spec: LlmModelSpec): ModelTrust = manager.trust(spec)

    suspend fun download(spec: LlmModelSpec): String? {
        _state.value = LlmState.Downloading(0)
        val err = manager.download(spec) { p -> _state.value = LlmState.Downloading(p) }
        _state.value = if (err == null) LlmState.ReadyToLoad else LlmState.Error(err)
        return err
    }

    /** Imports a GGUF chosen by the user (e.g. via the system file picker). Verified before it is installed. */
    suspend fun importModel(spec: LlmModelSpec, input: InputStream, sizeBytes: Long = -1L): String? {
        _state.value = LlmState.Downloading(0)
        val err = manager.importModel(spec, input, sizeBytes) { p -> _state.value = LlmState.Downloading(p) }
        _state.value = if (err == null) LlmState.ReadyToLoad else LlmState.Error(err)
        return err
    }

    /** Verifies the file, then loads model + context into memory. Returns null on success or a Persian error. */
    suspend fun load(spec: LlmModelSpec): String? = loadMutex.withLock {
        fun fail(msg: String): String { _state.value = LlmState.Error(msg); return msg }
        if (!LlamaNative.available) {
            return fail("کتابخانه نیتیو llama.cpp در این بیلد کامپایل نشده است" + (LlamaNative.loadError?.let { " ($it)" } ?: ""))
        }
        val template = ChatTemplateRegistry.forSpec(spec)
            ?: return fail("قالب مکالمه برای این مدل ثبت نشده است (${spec.templateId})")
        if (manager.installedPath(spec) == null) { _state.value = LlmState.NotInstalled; return "مدل نصب نشده است" }
        if (!spec.fitsRam(manager.deviceRamMb())) return fail("حافظه دستگاه برای بارگذاری این مدل کافی نیست")

        _state.value = LlmState.Verifying
        manager.ensureIntegrity(spec)?.let { return fail(it) }
        val path = manager.installedPath(spec) ?: return fail("مدل نصب نشده است")

        _state.value = LlmState.Loading
        val cfg = settings.read()
        val err = session.load(path, cfg.contextLength.coerceAtMost(spec.recommendedContext), cfg.threads)
        if (err != null) {
            loadedSpecId = null; loadedTemplate = null
            return fail(err)
        }
        loadedSpecId = spec.id; loadedTemplate = template
        _state.value = LlmState.Ready
        null
    }

    /** Chat entry point used by the router: the complete conversation for THIS request. */
    override suspend fun chat(messages: List<ChatTurn>): String {
        val template = loadedTemplate
        if (!LlamaNative.available || !session.isLoaded || template == null) return LlmSession.MSG_NOT_LOADED
        val cfg = settings.read()
        if (activeRequests.getAndIncrement() == 0) _state.value = LlmState.Generating
        return try {
            when (val r = session.chat(messages, template, cfg.maxTokens)) {
                is LlmSession.ChatResult.Text -> r.text.ifBlank { "مدل پاسخی تولید نکرد" }
                is LlmSession.ChatResult.Failure -> r.message
            }
        } finally {
            if (activeRequests.decrementAndGet() == 0 && session.isLoaded) _state.value = LlmState.Ready
        }
    }

    /** Raw prompts are wrapped as a single user message, so they still get the proper chat template. */
    override suspend fun generate(prompt: String): String = chat(listOf(ChatTurn(ChatRole.USER, prompt)))

    /** Cooperative stop for an in-flight generation (returns the partial text to the caller). */
    fun stopGeneration() = session.stop()

    /** Stops generation, waits for it to end, then frees all native memory. */
    suspend fun unload() {
        session.unload()
        loadedSpecId = null; loadedTemplate = null
        _state.value = LlmState.NotInstalled
    }

    companion object {
        @Volatile private var INSTANCE: LlamaCppEngine? = null
        fun get(context: Context): LlamaCppEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlamaCppEngine(context).also { INSTANCE = it }
            }
    }
}
