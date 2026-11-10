package ir.elviran.sam.voice.engine

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.voiceStore by preferencesDataStore("sam_voice_settings")

/** Battery / sensitivity profile for the always-listening wake-word stage. */
enum class PowerMode { HIGH_ACCURACY, BALANCED, BATTERY_SAVER }

data class VoicePrefs(
    val powerMode: PowerMode = PowerMode.BALANCED,
    /** Master switch for the hands-free wake-word assistant (user can disable it from settings). */
    val wakeWordEnabled: Boolean = true,
    /** Wake-word strictness 0..1 (higher = fewer false positives). */
    val wakeSensitivity: Double = 0.55,
    /** Prefer online STT when the network is available. */
    val preferOnline: Boolean = true,
    /** Keep actively listening for a follow-up after SAM answers (multi-turn). */
    val followUp: Boolean = true,
    /** Allow saving raw audio to disk. OFF by default (privacy). */
    val allowRawAudio: Boolean = false,
    val speechRate: Double = 0.96,
    val pitch: Double = 1.0
) {
    /** Map the power mode to VAD / cool-down timing. */
    fun endSilenceMs(): Long = when (powerMode) {
        PowerMode.HIGH_ACCURACY -> 1100L; PowerMode.BALANCED -> 900L; PowerMode.BATTERY_SAVER -> 750L
    }
    fun wakeCooldownMs(): Long = when (powerMode) {
        PowerMode.HIGH_ACCURACY -> 1200L; PowerMode.BALANCED -> 1500L; PowerMode.BATTERY_SAVER -> 2200L
    }
}

class VoiceSettingsStore(context: Context) {
    private val app = context.applicationContext
    suspend fun read(): VoicePrefs {
        val p = app.voiceStore.data.first()
        return VoicePrefs(
            powerMode = runCatching { PowerMode.valueOf(p[MODE] ?: "BALANCED") }.getOrDefault(PowerMode.BALANCED),
            wakeWordEnabled = p[WAKE_ON] ?: true,
            wakeSensitivity = p[SENS] ?: 0.55,
            preferOnline = p[ONLINE] ?: true,
            followUp = p[FOLLOW] ?: true,
            allowRawAudio = p[RAW] ?: false,
            speechRate = p[RATE] ?: 0.96,
            pitch = p[PITCH] ?: 1.0
        )
    }
    suspend fun write(prefs: VoicePrefs) {
        app.voiceStore.edit {
            it[MODE] = prefs.powerMode.name
            it[WAKE_ON] = prefs.wakeWordEnabled
            it[SENS] = prefs.wakeSensitivity
            it[ONLINE] = prefs.preferOnline
            it[FOLLOW] = prefs.followUp
            it[RAW] = prefs.allowRawAudio
            it[RATE] = prefs.speechRate
            it[PITCH] = prefs.pitch
        }
    }
    companion object {
        private val MODE = androidx.datastore.preferences.core.stringPreferencesKey("power_mode")
        private val WAKE_ON = booleanPreferencesKey("wake_word_enabled")
        private val SENS = doublePreferencesKey("wake_sensitivity")
        private val ONLINE = booleanPreferencesKey("prefer_online")
        private val FOLLOW = booleanPreferencesKey("follow_up")
        private val RAW = booleanPreferencesKey("allow_raw_audio")
        private val RATE = doublePreferencesKey("speech_rate")
        private val PITCH = doublePreferencesKey("pitch")
        @Suppress("unused") private val DUMMY = intPreferencesKey("_")
    }
}
