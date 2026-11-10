package ir.elviran.sam.ai.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.llmStore by preferencesDataStore("sam_llm_settings")

/** How SAM chooses between the on-device model and the online service. */
enum class AiMode { ONLINE, OFFLINE, HYBRID }

/**
 * User-tunable runtime knobs for the on-device model. Defaults are tuned for a 12 GB Galaxy S24
 * Ultra: 4 threads, 4096 context, ~384 max new tokens keep latency and heat reasonable.
 */
data class LlmPrefs(
    val mode: AiMode = AiMode.HYBRID,
    val selectedModelId: String = LlmModelCatalog.default.id,
    val threads: Int = 4,
    val contextLength: Int = 4096,
    val maxTokens: Int = 384,
    /** When true the model may read SAM long-term memory / factory data as grounding context. */
    val allowMemoryContext: Boolean = true
)

class LlmSettingsStore(context: Context) {
    private val app = context.applicationContext
    suspend fun read(): LlmPrefs {
        val p = app.llmStore.data.first()
        return LlmPrefs(
            mode = runCatching { AiMode.valueOf(p[MODE] ?: "HYBRID") }.getOrDefault(AiMode.HYBRID),
            selectedModelId = p[MODEL_ID] ?: LlmModelCatalog.default.id,
            threads = p[THREADS] ?: 4,
            contextLength = p[CTX] ?: 4096,
            maxTokens = p[MAXTOK] ?: 384,
            allowMemoryContext = p[MEM] ?: true
        )
    }
    suspend fun write(prefs: LlmPrefs) {
        app.llmStore.edit {
            it[MODE] = prefs.mode.name
            it[MODEL_ID] = prefs.selectedModelId
            it[THREADS] = prefs.threads
            it[CTX] = prefs.contextLength
            it[MAXTOK] = prefs.maxTokens
            it[MEM] = prefs.allowMemoryContext
        }
    }
    companion object {
        private val MODE = stringPreferencesKey("ai_mode")
        private val MODEL_ID = stringPreferencesKey("llm_model_id")
        private val THREADS = intPreferencesKey("llm_threads")
        private val CTX = intPreferencesKey("llm_ctx")
        private val MAXTOK = intPreferencesKey("llm_max_tokens")
        private val MEM = booleanPreferencesKey("llm_allow_memory")
    }
}
