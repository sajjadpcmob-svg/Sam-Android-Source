package ir.elviran.sam.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ir.elviran.sam.security.SecretVault
import ir.elviran.sam.SafeMath
import ir.elviran.sam.core.Intent
import ir.elviran.sam.core.OfflineBrain
import ir.elviran.sam.core.SafeMathBridge
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

private val Context.aiStore by preferencesDataStore("sam_ai_settings")

data class AiSettings(val endpoint: String, val model: String)
data class ChatTurn(val role: String, val content: String)
interface LocalModelEngine { suspend fun isReady(): Boolean; suspend fun generate(prompt: String): String }
class NotConfiguredLocalEngine : LocalModelEngine {
    override suspend fun isReady() = false
    override suspend fun generate(prompt: String) = "مدل آفلاین هنوز نصب و پیکربندی نشده است."
}

/** Online OpenAI-compatible chat router. Network I/O always runs on Dispatchers.IO. */
class AiRouter(
    private val context: Context,
    private val local: LocalModelEngine = NotConfiguredLocalEngine(),
    /**
     * When false (AiMode.OFFLINE) the router must NEVER make a network request: if the on-device
     * model is not ready it answers from the deterministic command brain or returns a clear Persian
     * message, instead of silently calling an online service. This enforces the "offline = no
     * network" rule end-to-end.
     */
    private val networkAllowed: Boolean = true
) {
    private val vault = SecretVault()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS).build()

    init {
        // Bind the bounded arithmetic parser so the offline command brain can calculate with no network.
        SafeMathBridge.bind { expr -> SafeMath.evaluate(expr) }
    }

    /**
     * Deterministic, fully-offline command understanding (no model, no network). Returns a useful
     * Persian reply for the commands it recognises, or null when it cannot handle the input so the
     * caller can fall back to the online model.
     */
    fun offlineReply(prompt: String): String? = when (val intent = OfflineBrain.interpret(prompt)) {
        is Intent.Calculate -> "نتیجه: ${trimNumber(intent.result)}"
        is Intent.AddTask -> "درخواست ثبت کار شناسایی شد: «${intent.title}». از بخش کارها آن را ذخیره کن."
        is Intent.AddNote -> "یادداشت شناسایی شد: «${intent.text}»."
        is Intent.AddMoney -> "ثبت ${if (intent.isIncome) "درآمد" else "هزینه"} به مبلغ ${trimNumber(intent.amount.toDouble())} شناسایی شد."
        is Intent.Query -> "درخواست «${intent.topic}» شناسایی شد؛ برای نتیجه به بخش مربوطه برو."
        Intent.Unknown -> null
    }

    private fun trimNumber(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    suspend fun readSettings(): AiSettings {
        val p = context.aiStore.data.first()
        return AiSettings(p[ENDPOINT].orEmpty(), p[MODEL].orEmpty())
    }

    suspend fun configure(endpoint: String, apiKey: String, model: String) {
        val cleanEndpoint = endpoint.trim()
        val uri = runCatching { URI(cleanEndpoint) }.getOrNull() ?: error("نشانی سرویس معتبر نیست")
        require(uri.scheme == "https" || (uri.scheme == "http" && (uri.host == "localhost" || uri.host == "127.0.0.1" || uri.host == "10.0.2.2"))) { "برای امنیت، HTTPS لازم است؛ HTTP فقط برای localhost مجاز است." }
        require(!uri.host.isNullOrBlank() && !model.isBlank()) { "نشانی و شناسه مدل را کامل وارد کن" }
        context.aiStore.edit {
            it[ENDPOINT] = cleanEndpoint
            if (apiKey.isNotBlank()) it[KEY] = vault.encrypt(apiKey)
            it[MODEL] = model.trim()
        }
    }

    suspend fun respond(prompt: String, history: List<ChatTurn> = emptyList()): String {
        require(prompt.isNotBlank()) { "پیام خالی است" }
        val safeHistory = history.filter { it.role == "user" || it.role == "assistant" }.takeLast(20).map { it.copy(content = it.content.take(8000)) }
        if (local.isReady()) {
            val contextualPrompt = buildString {
                safeHistory.forEach { append(if(it.role=="user") "کاربر: " else "سام: "); append(it.content); append('\n') }
                append("کاربر: "); append(prompt)
            }
            return runCatching { local.generate(contextualPrompt) }.getOrElse { "خطا در مدل آفلاین: ${it.message}" }
        }
        val p = context.aiStore.data.first()
        val endpoint = p[ENDPOINT].orEmpty()
        val key = runCatching { vault.decrypt(p[KEY].orEmpty()) }.getOrDefault("")
        val model = p[MODEL].orEmpty()
        if (!networkAllowed) {
            // OFFLINE mode: no network allowed. Use the command brain or say so plainly.
            offlineReply(prompt)?.let { return it }
            return "در حالت آفلاین هستی و مدل محلی هنوز آماده نیست. از تنظیمات، مدل آفلاین را دانلود و بارگذاری کن، یا حالت را به آنلاین/ترکیبی تغییر بده. دستیار آفلاین فقط فرمان‌های ساده را می‌شناسد."
        }
        if (endpoint.isBlank() || model.isBlank()) {
            offlineReply(prompt)?.let { return it }
            return "هوش مصنوعی آنلاین هنوز تنظیم نشده است. از بخش تنظیمات، نشانی سرویس و شناسه مدل را وارد کن. دستیار آفلاین فقط فرمان‌های ساده (محاسبه، کار، یادداشت، ثبت مالی) را می‌شناسد."
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "تو سام، دستیار فارسی دقیق و مسئولیت‌پذیر هستی. پاسخ را فارسی و روشن بده. عملیات حساس را فقط پس از تأیید انجام بده.")))
                safeHistory.forEach { turn -> body.put("messages", body.getJSONArray("messages").put(JSONObject().put("role", turn.role).put("content", turn.content))) }
                body.put("messages", body.getJSONArray("messages").put(JSONObject().put("role", "user").put("content", prompt)))
                    .toString()
                val builder = Request.Builder().url(endpoint).post(body.toRequestBody("application/json".toMediaType()))
                if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) error("پاسخ سرویس: HTTP ${response.code}")
                    val json = JSONObject(response.body?.string().orEmpty())
                    json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        ?.takeIf { it.isNotBlank() } ?: error("پاسخ قابل‌خواندن دریافت نشد")
                }
            }.getOrElse { "ارتباط با سرویس هوش مصنوعی ناموفق بود: ${it.message ?: "خطای نامشخص"}" }
        }
    }

    companion object {
        private val ENDPOINT = stringPreferencesKey("endpoint")
        private val KEY = stringPreferencesKey("api_key")
        private val MODEL = stringPreferencesKey("model")
    }
}
