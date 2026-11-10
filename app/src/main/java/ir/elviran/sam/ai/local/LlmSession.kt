package ir.elviran.sam.ai.local

import ir.elviran.sam.ai.ChatTurn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns one loaded model + context and guarantees, independent of Android:
 *
 *  1. **Independent requests.** Every request is a complete prompt; the KV cache is reset before it
 *     starts and again after it ends, so no token state travels between requests. (The native side
 *     clears it as well; see llama_jni.cpp.)
 *  2. **One generation at a time.** All requests, [load] and [unload] share one [Mutex].
 *  3. **Safe stop.** [stop] only reaches the native context while a generation is really running, and
 *     cancelling the calling coroutine stops the native loop.
 *  4. **No use-after-free.** Handles are only freed while holding the mutex (no generation running),
 *     and [stop] / free are ordered by [stateLock] so a stop can never hit a freed context.
 */
class LlmSession(
    private val backend: LlamaBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    enum class FailureReason { NOT_LOADED, PROMPT_TOO_LONG, INVALID_REQUEST, NATIVE_ERROR }

    sealed interface ChatResult {
        data class Text(val text: String, val droppedTurns: Int = 0) : ChatResult
        data class Failure(val reason: FailureReason, val message: String) : ChatResult
    }

    private val mutex = Mutex()
    private val stateLock = Any()
    private val pendingUnloads = AtomicInteger(0)

    @Volatile private var modelHandle = 0L
    @Volatile private var ctxHandle = 0L
    @Volatile private var contextSize = 0
    @Volatile private var generating = false

    val isLoaded: Boolean get() = ctxHandle != 0L && modelHandle != 0L

    /** Loads (replacing any loaded model). Returns null on success or a Persian error message. */
    suspend fun load(path: String, nCtx: Int, nThreads: Int): String? {
        stop() // let an in-flight generation end so the lock becomes free
        return mutex.withLock {
            withContext<String?>(ioDispatcher) {
                freeLocked()
                val ctxSize = nCtx.coerceAtLeast(MIN_CONTEXT)
                val mh = runCatching { backend.loadModel(path, 0) }.getOrDefault(0L)
                if (mh == 0L) return@withContext MSG_LOAD_MODEL
                val cx = runCatching { backend.newContext(mh, ctxSize, nThreads) }.getOrDefault(0L)
                if (cx == 0L) {
                    runCatching { backend.freeModel(mh) }
                    return@withContext MSG_NEW_CONTEXT
                }
                synchronized(stateLock) { modelHandle = mh; ctxHandle = cx; contextSize = ctxSize }
                null
            }
        }
    }

    /**
     * Runs one request. [messages] is the complete conversation for this request (system, history,
     * newest user message); nothing from earlier requests is implied.
     */
    suspend fun chat(messages: List<ChatTurn>, template: ChatTemplate, maxTokens: Int): ChatResult =
        mutex.withLock<ChatResult> {
            if (pendingUnloads.get() > 0 || !isLoaded) return@withLock failure(FailureReason.NOT_LOADED, MSG_NOT_LOADED)
            val ctx = ctxHandle
            val model = modelHandle
            val ctxSize = contextSize
            val maxNew = maxTokens.coerceIn(1, maxOf(1, ctxSize / 2))
            val budget = ctxSize - maxNew - SAFETY_MARGIN_TOKENS
            withContext<ChatResult>(ioDispatcher) {
                val fitted = try {
                    PromptFitter.fit(template, messages, budget) { backend.tokenCount(model, it, template.addSpecialTokens) }
                } catch (e: IllegalArgumentException) {
                    return@withContext failure(FailureReason.INVALID_REQUEST, e.message ?: "درخواست نامعتبر است")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    return@withContext failure(FailureReason.NATIVE_ERROR, "شمارش توکن ناموفق بود: ${t.message}")
                }
                if (fitted is PromptFitter.Result.TooLong) {
                    return@withContext failure(FailureReason.PROMPT_TOO_LONG, MSG_PROMPT_TOO_LONG)
                }
                fitted as PromptFitter.Result.Fitted

                // Fresh start: empty KV cache + no stale stop request, BEFORE the generation becomes stoppable.
                backend.resetContext(ctx)
                synchronized(stateLock) { generating = true }
                // If the caller's coroutine is cancelled mid-generation, stop the native loop.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { stop() }
                }
                try {
                    val bytes = backend.generate(ctx, fitted.prompt, maxNew, template.addSpecialTokens, template.stopStrings.toTypedArray())
                    ChatResult.Text(template.cleanOutput(String(bytes, Charsets.UTF_8)), fitted.droppedTurns)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    failure(FailureReason.NATIVE_ERROR, "خطا در تولید پاسخ آفلاین: ${t.message ?: "نامشخص"}")
                } finally {
                    synchronized(stateLock) { generating = false }
                    watcher.cancel()
                    // Release this request's token state; the next request must not see it.
                    runCatching { backend.resetContext(ctx) }
                }
            }
        }

    /** Asks a running generation to stop (returns its partial text). No-op when nothing is running. */
    fun stop() {
        synchronized(stateLock) {
            if (generating && ctxHandle != 0L) runCatching { backend.requestStop(ctxHandle) }
        }
    }

    /**
     * Stops any running generation, waits for it to finish, then frees the native memory. Requests
     * that were already queued behind the running one are answered with NOT_LOADED instead of running.
     */
    suspend fun unload() {
        pendingUnloads.incrementAndGet()
        try {
            stop()
            mutex.withLock { withContext(ioDispatcher) { freeLocked() } }
        } finally {
            pendingUnloads.decrementAndGet()
        }
    }

    /** Must be called with [mutex] held (no generation can be running). */
    private fun freeLocked() {
        val ctx: Long
        val model: Long
        synchronized(stateLock) {
            ctx = ctxHandle; model = modelHandle
            ctxHandle = 0L; modelHandle = 0L; contextSize = 0
        }
        if (ctx != 0L) runCatching { backend.freeContext(ctx) }
        if (model != 0L) runCatching { backend.freeModel(model) }
    }

    private fun failure(reason: FailureReason, message: String) = ChatResult.Failure(reason, message)

    companion object {
        const val MIN_CONTEXT = 512
        const val SAFETY_MARGIN_TOKENS = 16
        const val MSG_LOAD_MODEL = "بارگذاری مدل ناموفق بود"
        const val MSG_NEW_CONTEXT = "ساخت زمینه مدل ناموفق بود"
        const val MSG_NOT_LOADED = "مدل آفلاین آماده نیست"
        const val MSG_PROMPT_TOO_LONG = "پیام برای طول زمینهٔ مدل بیش از حد بلند است؛ کوتاه‌تر بنویس یا طول زمینه را بیشتر کن"
    }
}
