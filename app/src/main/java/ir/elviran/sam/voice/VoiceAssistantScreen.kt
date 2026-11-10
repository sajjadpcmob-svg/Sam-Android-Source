package ir.elviran.sam.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.elviran.sam.ai.local.OfflineLlmSection
import kotlinx.coroutines.launch

private val Ink = Color(0xFF101820); private val Panel = Color(0xFF1B2932); private val Teal = Color(0xFF55D6C2); private val Muted = Color(0xFF9AAEB7); private val Amber = Color(0xFFE0A33E); private val Red = Color(0xFFE06C6C)

private fun phaseColor(p: VoicePhase): Color = when (p) {
    VoicePhase.LISTENING -> Teal
    VoicePhase.PROCESSING -> Amber
    VoicePhase.SPEAKING -> Color(0xFF6CA8E0)
    VoicePhase.INTERRUPTED -> Amber
    VoicePhase.ERROR, VoicePhase.OFFLINE -> Red
    VoicePhase.WAKE_LISTENING -> Teal.copy(alpha = .7f)
    VoicePhase.IDLE -> Muted
}
private fun phaseLabel(p: VoicePhase): String = when (p) {
    VoicePhase.IDLE -> "غیرفعال"; VoicePhase.WAKE_LISTENING -> "منتظر «سام»"; VoicePhase.LISTENING -> "در حال شنیدن"
    VoicePhase.PROCESSING -> "در حال پردازش"; VoicePhase.SPEAKING -> "در حال صحبت"; VoicePhase.INTERRUPTED -> "قطع شد"
    VoicePhase.OFFLINE -> "آفلاین"; VoicePhase.ERROR -> "خطا"
}

@Composable
fun VoiceAssistantScreen(onActivate: () -> Unit, onAiSettings: () -> Unit = {}) {
    val controller by VoiceForegroundService.controllerFlow.collectAsState()
    // collect the controller's live state when it exists
    val realUi = controller?.ui?.collectAsState()?.value ?: VoiceUiState()
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var installPct by remember { mutableStateOf(0) }
    var installMsg by remember { mutableStateOf("") }

    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    val active = realUi.phase == VoicePhase.LISTENING || realUi.phase == VoicePhase.SPEAKING || realUi.phase == VoicePhase.PROCESSING
    val scaleFactor = if (active) pulse else 1f
    val color = phaseColor(realUi.phase)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("دستیار صوتی سام", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(if (realUi.online) "آنلاین" else "آفلاین", if (realUi.online) Teal else Muted)
            StatusChip(realUi.engine, Muted)
        }

        Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(190.dp).scale(scaleFactor).background(color.copy(alpha = .16f), CircleShape))
            Box(Modifier.size(140.dp).scale(scaleFactor).background(color.copy(alpha = .28f), CircleShape))
            Box(Modifier.size(96.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    when (realUi.phase) { VoicePhase.SPEAKING -> Icons.Default.VolumeUp; VoicePhase.PROCESSING -> Icons.Default.Autorenew; VoicePhase.ERROR -> Icons.Default.ErrorOutline; else -> Icons.Default.Mic },
                    null, tint = Ink, modifier = Modifier.size(40.dp)
                )
            }
        }
        Text(phaseLabel(realUi.phase), color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(realUi.status, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)

        if (realUi.partial.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text("در حال تشخیص: ${realUi.partial}", color = Color.White, modifier = Modifier.padding(14.dp))
        }
        if (realUi.lastUserText.isNotBlank()) SubtitleCard("شما", realUi.lastUserText, Teal)
        if (realUi.lastReply.isNotBlank()) SubtitleCard("سام", realUi.lastReply, Color.White)

        // model install card
        if (controller != null && !realUi.modelInstalled) Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("مدل آفلاین فارسی نصب نیست", color = Amber, fontWeight = FontWeight.Bold)
                Text("برای تشخیص عبارت «سام» و گفتار آفلاین، مدل Vosk (حدود ۵۰ مگابایت) یک‌بار دانلود می‌شود.", color = Muted, fontSize = 12.sp)
                if (installing) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { installPct / 100f }, modifier = Modifier.fillMaxWidth()); Text("$installPct٪", color = Muted, fontSize = 11.sp) }
                if (installMsg.isNotBlank()) Text(installMsg, color = Red, fontSize = 12.sp)
                Button(enabled = !installing, onClick = {
                    val c = controller ?: return@Button
                    installing = true; installMsg = ""
                    scope.launch {
                        val err = c.installModel { installPct = it }
                        installing = false
                        if (err == null) c.start() else installMsg = err
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink), modifier = Modifier.padding(top = 8.dp)) { Text("دانلود مدل آفلاین") }
            }
        }

        // controls
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onActivate, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Ink)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("فعال‌سازی سام") }
            FilledTonalButton(onClick = { controller?.stopSpeakingOnly() }) { Icon(Icons.Default.Stop, null) }
        }
        OutlinedButton(
            onClick = { controller?.listenNow() },
            enabled = controller != null && realUi.phase != VoicePhase.PROCESSING && realUi.phase != VoicePhase.SPEAKING,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Mic, null, tint = Teal)
            Spacer(Modifier.width(8.dp))
            Text("صحبت با سام (شروع دستی)")
        }
        Text(
            "برای آزمایش اولیه، بدون گفتن کلمه «سام» این دکمه را بزن. تشخیص آنلاین به سرویس گفتار فارسی گوشی نیاز دارد؛ پاسخ هوشمند آنلاین نیز پس از ثبت API در تنظیمات فعال می‌شود.",
            color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { controller?.muteNow() }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.MicOff, null, tint = Red); Spacer(Modifier.width(6.dp)); Text("قطع فوری میکروفون", color = Red) }
        }
        TextButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Default.Tune, null, tint = Teal); Spacer(Modifier.width(6.dp)); Text(if (showSettings) "بستن تنظیمات" else "تنظیمات صوتی", color = Teal) }
        OutlinedButton(onClick = onAiSettings, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, null, tint = Teal)
            Spacer(Modifier.width(8.dp))
            Text("تنظیم اتصال هوش مصنوعی آنلاین")
        }
        if (showSettings) { VoiceSettingsSection(controller); OfflineLlmSection() }
        Text("شنود عبارت «سام» روی دستگاه و بدون ارسال دائمی صدا انجام می‌شود. اجرای پایدار در پس‌زمینه به معافیت از بهینه‌سازی باتری سامسونگ نیاز دارد.", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 20.dp))
    }
}

@Composable private fun StatusChip(text: String, tint: Color) { Surface(color = tint.copy(alpha = .16f), shape = RoundedCornerShape(50)) { Text(text, color = tint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) } }
@Composable private fun SubtitleCard(who: String, text: String, tint: Color) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(who, color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp); Text(text, color = Color.White) } } }
