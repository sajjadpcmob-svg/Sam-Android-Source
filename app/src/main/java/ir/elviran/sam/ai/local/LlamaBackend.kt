package ir.elviran.sam.ai.local

/**
 * Everything [LlmSession] needs from the native runtime. [NativeLlamaBackend] is the real llama.cpp
 * implementation; unit tests substitute a fake so session logic (serialisation, stop, release, KV reset)
 * can be tested on a plain JVM without the native library.
 */
interface LlamaBackend {
    val available: Boolean
    fun loadModel(path: String, nGpuLayers: Int): Long
    fun freeModel(modelHandle: Long)
    fun newContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long
    fun freeContext(ctxHandle: Long)
    fun resetContext(ctxHandle: Long)
    fun tokenCount(modelHandle: Long, text: String, addSpecial: Boolean): Int
    fun generate(ctxHandle: Long, prompt: String, maxTokens: Int, addSpecial: Boolean, stops: Array<String>): ByteArray
    fun requestStop(ctxHandle: Long)
}

/** Production backend: delegates 1:1 to [LlamaNative]. */
class NativeLlamaBackend : LlamaBackend {
    override val available: Boolean get() = LlamaNative.available
    override fun loadModel(path: String, nGpuLayers: Int) = LlamaNative.loadModel(path, nGpuLayers)
    override fun freeModel(modelHandle: Long) = LlamaNative.freeModel(modelHandle)
    override fun newContext(modelHandle: Long, nCtx: Int, nThreads: Int) = LlamaNative.newContext(modelHandle, nCtx, nThreads)
    override fun freeContext(ctxHandle: Long) = LlamaNative.freeContext(ctxHandle)
    override fun resetContext(ctxHandle: Long) = LlamaNative.resetContext(ctxHandle)
    override fun tokenCount(modelHandle: Long, text: String, addSpecial: Boolean) = LlamaNative.tokenCount(modelHandle, text, addSpecial)
    override fun generate(ctxHandle: Long, prompt: String, maxTokens: Int, addSpecial: Boolean, stops: Array<String>) =
        LlamaNative.generate(ctxHandle, prompt, maxTokens, addSpecial, stops)
    override fun requestStop(ctxHandle: Long) = LlamaNative.requestStop(ctxHandle)
}
