package ir.elviran.sam.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.elviran.sam.voice.engine.PowerMode
import ir.elviran.sam.voice.engine.VoicePrefs
import kotlinx.coroutines.launch

private val Panel = Color(0xFF1B2932); private val Teal = Color(0xFF55D6C2); private val Muted = Color(0xFF9AAEB7)

@Composable
fun VoiceSettingsSection(controller: ConversationController?) {
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(VoicePrefs()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(controller) { controller?.let { prefs = it.loadPrefs(); loaded = true } }

    fun persist(updated: VoicePrefs) { prefs = updated; controller?.let { c -> scope.launch { c.savePrefs(updated) } } }

    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!loaded && controller == null) Text("برای تغییر تنظیمات، ابتدا سام را فعال کن.", color = Muted, fontSize = 12.sp)
            ToggleRow("فعال‌بودن دستیار صوتی و عبارت «سام»", prefs.wakeWordEnabled) { persist(prefs.copy(wakeWordEnabled = it)) }
            if (!prefs.wakeWordEnabled) Text("دستیار صوتی خاموش است؛ با روشن‌کردن این گزینه و زدن دکمهٔ شروع، شنود عبارت بیدارباش آغاز می‌شود.", color = Muted, fontSize = 11.sp)
            Text("حالت مصرف باتری / حساسیت", color = Color.White, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PowerMode.entries.forEach { mode ->
                    FilterChip(selected = prefs.powerMode == mode, onClick = { persist(prefs.copy(powerMode = mode)) }, label = { Text(when (mode) { PowerMode.HIGH_ACCURACY -> "دقّت بالا"; PowerMode.BALANCED -> "متعادل"; PowerMode.BATTERY_SAVER -> "ذخیره باتری" }, fontSize = 11.sp) })
                }
            }
            Text("حساسیت عبارت بیدارباش: ${(prefs.wakeSensitivity * 100).toInt()}٪ (بالاتر = خطای کمتر)", color = Muted, fontSize = 12.sp)
            Slider(value = prefs.wakeSensitivity.toFloat(), onValueChange = { prefs = prefs.copy(wakeSensitivity = it.toDouble()) }, onValueChangeFinished = { persist(prefs) }, valueRange = 0f..1f)
            ToggleRow("ترجیح موتور آنلاین در صورت اتصال", prefs.preferOnline) { persist(prefs.copy(preferOnline = it)) }
            ToggleRow("ادامه خودکار مکالمه (چندمرحله‌ای)", prefs.followUp) { persist(prefs.copy(followUp = it)) }
            ToggleRow("اجازه ذخیره صدای خام (پیش‌فرض: خاموش)", prefs.allowRawAudio) { persist(prefs.copy(allowRawAudio = it)) }
            Text("سرعت گفتار: ${(prefs.speechRate * 100).toInt()}٪", color = Muted, fontSize = 12.sp)
            Slider(value = prefs.speechRate.toFloat(), onValueChange = { prefs = prefs.copy(speechRate = it.toDouble()) }, onValueChangeFinished = { persist(prefs) }, valueRange = 0.5f..1.6f)
            Text("زیر و بمی صدا: ${(prefs.pitch * 100).toInt()}٪", color = Muted, fontSize = 12.sp)
            Slider(value = prefs.pitch.toFloat(), onValueChange = { prefs = prefs.copy(pitch = it.toDouble()) }, onValueChangeFinished = { persist(prefs) }, valueRange = 0.6f..1.5f)
        }
    }
}

@Composable private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
