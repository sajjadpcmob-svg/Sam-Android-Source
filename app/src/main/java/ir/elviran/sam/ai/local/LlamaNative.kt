package ir.elviran.sam.ai.local

/**
 * Thin JNI surface over the bundled llama.cpp build (see app/src/main/cpp). Every method maps to a
 * native function in `llama_jni.cpp`. The native library is OPTIONAL at runtime: if it was not
 * compiled into this build (the native build is gated by the `samLlama` Gradle property, see
 * README), [available] stays false and the higher layers degrade to the online / command engines
 * instead of crashing.
 *
 * Handles are opaque `long` pointers owned by the native side; Kotlin must call [freeModel] /
 * [freeContext] to release them.
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
    /** Blocking generation. Returns the decoded text. Honours [requestStop]. */
    external fun generate(ctxHandle: Long, modelHandle: Long, prompt: String, maxTokens: Int): String
    /** Cooperatively asks an in-flight [generate] on this context to stop as soon as possible. */
    external fun requestStop(ctxHandle: Long)
    external fun systemInfo(): String
}
