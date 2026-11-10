package ir.elviran.sam.ai.local

/**
 * Thin JNI surface over the bundled llama.cpp build (see app/src/main/cpp). Every `external` method
 * maps to a function in `llama_jni.cpp`. The native library is OPTIONAL at runtime: if it was not
 * compiled into this build (enable it with the Gradle flag `-PsamLlama`, see README), [available]
 * stays false and the higher layers fall back to the online / command engines instead of crashing.
 *
 * Handles are opaque `long` pointers owned by the native side. Do not call this object directly from
 * feature code: go through [LlmSession] (via [NativeLlamaBackend]), which serialises access and makes
 * sure a handle is never used after it was freed.
 */
object LlamaNative {
    @Volatile var available: Boolean = false
        private set
    @Volatile var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("samllama")
            available = true
        } catch (t: Throwable) {
            available = false
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    // --- native entry points (implemented in llama_jni.cpp) ---
    external fun loadModel(path: String, nGpuLayers: Int): Long
    external fun freeModel(modelHandle: Long)
    external fun newContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long
    external fun freeContext(ctxHandle: Long)

    /** Empties the KV cache of this context and clears a pending stop request. */
    external fun resetContext(ctxHandle: Long)

    /** Number of tokens [text] occupies, counted exactly as [generate] will tokenize it. */
    external fun tokenCount(modelHandle: Long, text: String, addSpecial: Boolean): Int

    /**
     * Blocking generation from a complete prompt. Always starts from an empty KV cache and leaves it
     * empty. Returns the raw UTF-8 bytes (decoded by the caller, so a character cut by the token limit
     * can never produce invalid JNI strings). Stops at the first of [stops], at an end-of-generation
     * token, at [maxTokens], when the context is full, or after [requestStop].
     * @throws IllegalStateException on tokenization / decode failure or when the prompt does not fit.
     */
    external fun generate(ctxHandle: Long, prompt: String, maxTokens: Int, addSpecial: Boolean, stops: Array<String>): ByteArray

    /** Cooperatively asks an in-flight [generate] on this context to stop as soon as possible. */
    external fun requestStop(ctxHandle: Long)
    external fun systemInfo(): String
}
