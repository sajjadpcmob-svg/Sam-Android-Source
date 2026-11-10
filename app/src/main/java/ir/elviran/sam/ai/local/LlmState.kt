package ir.elviran.sam.ai.local

/**
 * Lifecycle of the on-device generative model, surfaced to the UI 1:1 with the states required by
 * the product spec. Kept as a small sealed hierarchy so Compose can render each state and so the
 * transitions are testable with no device.
 */
sealed interface LlmState {
    data object NotInstalled : LlmState
    data class Downloading(val percent: Int) : LlmState
    data object Verifying : LlmState             // hashing / checking the model file
    data object ReadyToLoad : LlmState          // file present + checked, not yet in memory
    data object Loading : LlmState
    data object Ready : LlmState                 // loaded in memory, idle
    data object Generating : LlmState
    data class Error(val message: String) : LlmState
}

/**
 * A concrete GGUF model the app can download and run. The file is NEVER bundled in the APK; it is
 * fetched once from a trusted host and checked. [minRamMb] is the realistic minimum device RAM for
 * the quantised file to load with a usable context on-device.
 *
 * [sha256] is the OFFICIAL SHA-256 of the file (64 hex chars) taken from the model host. When it is
 * non-null it is enforced after download/import and before every load. When it is null the model is
 * never presented as "verified": only size, GGUF header and a local fingerprint are checked
 * (see [ModelTrust.UNVERIFIED_NO_OFFICIAL_HASH]). Never fill it with a value that was not read from the
 * official source (tools/fetch_model_hashes.py prints the values to paste).
 *
 * [templateId] selects the [ChatTemplate] for this model family; a spec without a registered template
 * cannot be loaded.
 */
data class LlmModelSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val sizeBytes: Long,
    val quant: String,
    val paramsB: Double,
    val minRamMb: Int,
    val recommendedContext: Int,
    val sha256: String? = null,
    val templateId: String = ChatTemplateIds.UNSPECIFIED
) {
    /** True only when an official SHA-256 is pinned for this model. */
    val hasOfficialHash: Boolean get() = sha256 != null

    /** Does this model realistically fit a device with [deviceRamMb] of total RAM? */
    fun fitsRam(deviceRamMb: Int): Boolean = deviceRamMb >= minRamMb
}

/**
 * Built-in catalog of ARM64 (arm64-v8a) compatible GGUF models sized for a 12 GB Galaxy S24 Ultra.
 * All are instruction-tuned and quantised (Q4_K_M) so they load in a few GB and leave headroom.
 *
 * The default is a ~3B model: a sweet spot between Persian answer quality and on-device latency /
 * memory on a flagship phone. Larger entries are offered for users who accept slower generation.
 */
object LlmModelCatalog {
    val models: List<LlmModelSpec> = listOf(
        LlmModelSpec(
            id = "qwen2.5-3b-instruct-q4km",
            displayName = "Qwen2.5 3B Instruct (Q4_K_M)",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_100_000_000L,
            quant = "Q4_K_M",
            paramsB = 3.1,
            minRamMb = 6_000,
            recommendedContext = 4096,
            sha256 = null, // official hash not obtained yet -> shown as "unverified"
            templateId = ChatTemplateIds.CHATML_QWEN25
        ),
        LlmModelSpec(
            id = "gemma-2-2b-it-q4km",
            displayName = "Gemma 2 2B IT (Q4_K_M)",
            url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            sizeBytes = 1_700_000_000L,
            quant = "Q4_K_M",
            paramsB = 2.6,
            minRamMb = 5_000,
            recommendedContext = 4096,
            sha256 = null, // official hash not obtained yet -> shown as "unverified"
            templateId = ChatTemplateIds.GEMMA2
        ),
        LlmModelSpec(
            id = "qwen2.5-7b-instruct-q4km",
            displayName = "Qwen2.5 7B Instruct (Q4_K_M) — کندتر",
            url = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
            sizeBytes = 4_700_000_000L,
            quant = "Q4_K_M",
            paramsB = 7.6,
            minRamMb = 10_000,
            recommendedContext = 4096,
            sha256 = null, // official hash not obtained yet -> shown as "unverified"
            templateId = ChatTemplateIds.CHATML_QWEN25
        )
    )

    val default: LlmModelSpec get() = models.first()
    fun byId(id: String?): LlmModelSpec? = models.firstOrNull { it.id == id }
}
