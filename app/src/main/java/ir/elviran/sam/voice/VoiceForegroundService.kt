package ir.elviran.sam.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ir.elviran.sam.MainActivity
import ir.elviran.sam.ai.AiRouter
import ir.elviran.sam.ai.ChatTurn
import ir.elviran.sam.ai.LocalModelEngine
import ir.elviran.sam.ai.NotConfiguredLocalEngine
import ir.elviran.sam.ai.local.AiMode
import ir.elviran.sam.ai.local.LlamaCppEngine
import ir.elviran.sam.ai.local.LlmSettingsStore
import ir.elviran.sam.core.Intent as SamIntent
import ir.elviran.sam.core.OfflineBrain
import ir.elviran.sam.data.ChatMessageEntity
import ir.elviran.sam.data.MoneyEntryEntity
import ir.elviran.sam.data.NoteEntity
import ir.elviran.sam.data.SamDatabase
import ir.elviran.sam.data.TaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers

/**
 * Foreground microphone service that keeps the hands-free loop alive outside the Activity, with a
 * persistent, user-controllable notification. It declares `microphone` foreground type (required
 * from Android 14+). It does NOT claim to survive aggressive OEM battery optimisation — on Samsung
 * the user must exempt SAM from battery optimisation for reliable background wake detection; the
 * service recovers via START_STICKY only within what Android allows.
 */
class VoiceForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: ConversationController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_MUTE -> { controller?.muteNow(); return START_STICKY }
        }
        startForegroundSafely()
        if (controller == null) {
            val ctrl = ConversationController(applicationContext, scope, ::respond)
            controller = ctrl
            activeController.value = ctrl
            ctrl.start()
        }
        return START_STICKY
    }

    /** Voice responder: offline intents first (persisted to Room), then the configured online model. */
    private suspend fun respond(userText: String): String {
        val db = SamDatabase.get(applicationContext); val dao = db.dao()
        // Route per the user's AI mode. OFFLINE forbids network; HYBRID prefers the on-device model
        // when it is loaded and falls back to online; ONLINE never touches the local model.
        val llmPrefs = runCatching { LlmSettingsStore(applicationContext).read() }.getOrNull()
        val mode = llmPrefs?.mode ?: AiMode.HYBRID
        val localEngine: LocalModelEngine =
            if (mode == AiMode.ONLINE) NotConfiguredLocalEngine() else LlamaCppEngine.get(applicationContext)
        val ai = AiRouter(applicationContext, local = localEngine, networkAllowed = mode != AiMode.OFFLINE)
        runCatching { dao.addChatMessage(ChatMessageEntity(role = "user", content = userText)) }
        val reply = when (val intent = OfflineBrain.interpret(userText)) {
            is SamIntent.AddTask -> { dao.addTask(TaskEntity(title = intent.title)); "کار «${intent.title}» ثبت شد." }
            is SamIntent.AddNote -> { dao.addNote(NoteEntity(title = intent.text.take(48).ifBlank { "یادداشت صوتی" }, body = intent.text)); "یادداشتت ذخیره شد." }
            is SamIntent.AddMoney -> { dao.addMoneyEntry(MoneyEntryEntity(title = intent.title, amount = intent.amount, kind = if (intent.isIncome) "درآمد" else "هزینه", category = "ثبت صوتی")); "${if (intent.isIncome) "درآمد" else "هزینه"} ثبت شد." }
            is SamIntent.Calculate -> "نتیجه محاسبه: ${intent.result}"
            is SamIntent.Query -> when (intent.topic) {
                "کارها" -> { val open = dao.tasks().first().filter { !it.done }; if (open.isEmpty()) "هیچ کار بازی نداری." else "${open.size} کار باز داری: " + open.take(5).joinToString("، ") { it.title } }
                "مالی" -> { val e = dao.moneyEntries().first(); val bal = e.sumOf { if (it.kind == "درآمد") it.amount else -it.amount }; "مانده ثبت‌شده: $bal تومان، بر اساس ${e.size} تراکنش." }
                else -> { val o = dao.orders().first(); "${o.size} سفارش تولید ثبت شده است." }
            }
            SamIntent.Unknown -> {
                val history = runCatching { dao.allChatMessages().takeLast(20).map { ChatTurn(it.role, it.content) } }.getOrDefault(emptyList())
                ai.respond(userText, history)
            }
        }
        runCatching { dao.addChatMessage(ChatMessageEntity(role = "assistant", content = reply)) }
        return reply
    }

    private fun startForegroundSafely() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "دستیار صوتی سام", NotificationManager.IMPORTANCE_LOW).apply { description = "شنود عبارت بیدارباش سام" })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mute = PendingIntent.getService(this, 1, Intent(this, VoiceForegroundService::class.java).setAction(ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("سام فعال است")
            .setContentText("منتظر عبارت «سام»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "قطع میکروفون", mute)
            .addAction(0, "توقف", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, notif)
    }

    private fun stopEverything() {
        controller?.shutdown(); controller = null; activeController.value = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        controller?.shutdown(); controller = null; activeController.value = null
        scope.cancel(); super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sam_voice"
        private const val NOTIF_ID = 4711
        const val ACTION_STOP = "ir.elviran.sam.voice.STOP"
        const val ACTION_MUTE = "ir.elviran.sam.voice.MUTE"

        /** The running controller, so the Compose UI can observe state without binding. */
        private val activeController = MutableStateFlow<ConversationController?>(null)
        val controllerFlow: StateFlow<ConversationController?> = activeController.asStateFlow()

        fun start(context: Context) {
            val i = Intent(context, VoiceForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) { context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_STOP)) }
    }
}
