package ir.elviran.sam.ai.local

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Panel = Color(0xFF1B2932); private val Teal = Color(0xFF55D6C2)
private val Muted = Color(0xFF9AAEB7); private val Amber = Color(0xFFE0A33E); private val Red = Color(0xFFE06C6C)

/**
 * Settings card for the on-device generative model (llama.cpp). It drives the real
 * [LlamaCppEngine]: pick AI mode, pick a GGUF model from the catalog, download it (with SHA/size
 * verification done inside the manager), load it into memory and unload it. The card renders the
 * engine's live [LlmState] 1:1 so the user always sees the true lifecycle; no state is faked.
 *
 * Self-contained: it reads/writes [LlmSettingsStore] itself, so it can be dropped into any screen.
 */
@Composable
fun OfflineLlmSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LlamaCppEngine.get(context) }
    val store = remember { LlmSettingsStore(context) }
    val manager = remember { LlmModelManager(context) }
    val state by engine.state.collectAsState()

    var prefs by remember { mutableStateOf(LlmPrefs()) }
    var loaded by remember { mutableStateOf(false) }
    val deviceRam = remember { runCatching { manager.deviceRamMb() }.getOrDefault(0) }

    LaunchedEffect(Unit) {
        prefs = store.read(); loaded = true
        engine.refreshInstallState(LlmModelCatalog.byId(prefs.selectedModelId) ?: LlmModelCatalog.default)
    }

    val spec = LlmModelCatalog.byId(prefs.selectedModelId) ?: LlmModelCatalog.default

    fun persist(updated: LlmPrefs) {
        prefs = updated
        scope.launch {
            store.write(updated)
            engine.refreshInstallState(LlmModelCatalog.byId(updated.selectedModelId) ?: LlmModelCatalog.default)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("هوش مصنوعی روی دستگاه (آفلاین)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            // --- AI mode ---
            Text("حالت پاسخ‌گویی", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AiMode.entries.forEach { m ->
                    FilterChip(
                        selected = prefs.mode == m,
                        onClick = { persist(prefs.copy(mode = m)) },
                        label = { Text(when (m) { AiMode.ONLINE -> "فقط آنلاین"; AiMode.OFFLINE -> "فقط آفلاین"; AiMode.HYBRID -> "ترکیبی" }, fontSize = 11.sp) }
                    )
                }
            }
            Text(when (prefs.mode) {
                AiMode.ONLINE -> "همیشه از سرویس ابری استفاده می‌شود؛ مدل روی دستگاه بارگذاری نمی‌شود."
                AiMode.OFFLINE -> "هیچ درخواستی به اینترنت ارسال نمی‌شود؛ پاسخ‌ها فقط از مدل روی دستگاه می‌آیند."
                AiMode.HYBRID -> "اگر مدل روی دستگاه بارگذاری شده باشد از آن استفاده می‌شود؛ در غیر این صورت به سرویس آنلاین برمی‌گردد."
            }, color = Muted, fontSize = 11.sp)

            HorizontalDivider(color = Color(0xFF26343D))

            // --- model picker ---
            Text("انتخاب مدل", color = Muted, fontSize = 12.sp)
            LlmModelCatalog.models.forEach { m ->
                val fits = deviceRam == 0 || m.fitsRam(deviceRam)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = prefs.selectedModelId == m.id, onClick = { persist(prefs.copy(selectedModelId = m.id)) })
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName, color = Color.White, fontSize = 13.sp)
                        Text("حدود ${m.sizeBytes / 1_000_000}MB • ${m.quant} • حداقل RAM ${m.minRamMb}MB" + if (!fits) " • برای این دستگاه سنگین است" else "",
                            color = if (!fits) Amber else Muted, fontSize = 10.sp)
                    }
                }
            }

            if (deviceRam > 0) Text("حافظه این دستگاه: ${deviceRam}MB", color = Muted, fontSize = 10.sp)

            // --- native availability ---
            if (!engine.nativeAvailable) Text(
                "کتابخانهٔ نیتیو llama.cpp در این بیلد کامپایل نشده است" + (engine.nativeLoadError?.let { " ($it)" } ?: "") +
                    "؛ برای فعال‌سازی باید APK با پرچم -Psam.llama=true بیلد شود.",
                color = Amber, fontSize = 11.sp
            )

            HorizontalDivider(color = Color(0xFF26343D))

            // --- live LlmState + actions ---
            val (label, labelColor) = when (val s = state) {
                is LlmState.NotInstalled -> "مدل نصب نشده" to Muted
                is LlmState.Downloading -> "در حال دانلود ${s.percent}٪" to Amber
                is LlmState.ReadyToLoad -> "آمادهٔ بارگذاری (دانلود شده)" to Teal
                is LlmState.Loading -> "در حال بارگذاری در حافظه…" to Amber
                is LlmState.Ready -> "بارگذاری شد و آماده است" to Teal
                is LlmState.Generating -> "در حال تولید پاسخ…" to Amber
                is LlmState.Error -> "خطا: ${s.message}" to Red
            }
            Text("وضعیت: $label", color = labelColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            (state as? LlmState.Downloading)?.let { LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth()) }

            val busy = state is LlmState.Downloading || state is LlmState.Loading || state is LlmState.Generating
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is LlmState.NotInstalled, is LlmState.Error ->
                        Button(enabled = !busy, onClick = { scope.launch { engine.download(spec) } },
                            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF101820)),
                            modifier = Modifier.weight(1f)) { Text("دانلود مدل", fontSize = 12.sp) }
                    is LlmState.ReadyToLoad ->
                        Button(enabled = !busy && engine.nativeAvailable, onClick = { scope.launch { engine.load(spec) } },
                            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color(0xFF101820)),
                            modifier = Modifier.weight(1f)) { Text("بارگذاری در حافظه", fontSize = 12.sp) }
                    is LlmState.Ready, is LlmState.Generating ->
                        OutlinedButton(enabled = state is LlmState.Ready, onClick = { engine.unload() }, modifier = Modifier.weight(1f)) {
                            Text("تخلیه از حافظه", color = Red, fontSize = 12.sp)
                        }
                    else -> {}
                }
            }
            Text("مدل یک‌بار دانلود می‌شود و در حافظهٔ داخلی برنامه می‌ماند؛ داخل فایل نصب (APK) قرار ندارد.",
                color = Muted, fontSize = 10.sp)
        }
    }
}
