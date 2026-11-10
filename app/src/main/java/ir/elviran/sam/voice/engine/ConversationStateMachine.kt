package ir.elviran.sam.voice.engine

/**
 * Pure, Android-free state machine for the hands-free conversation loop. The live
 * [ir.elviran.sam.voice.ConversationController] owns the audio/TTS side effects; this type owns the
 * legal transitions so loop-prevention and barge-in behaviour can be unit-tested with no device.
 *
 * Cycle: WAKE_LISTENING -> (wake) -> LISTENING -> (final text) -> PROCESSING -> (reply) -> SPEAKING
 *        -> (done) -> LISTENING (follow-up window) or WAKE_LISTENING.
 * Barge-in: while SPEAKING, user speech -> INTERRUPTED -> LISTENING.
 */
enum class VoicePhase { IDLE, WAKE_LISTENING, LISTENING, PROCESSING, SPEAKING, INTERRUPTED, OFFLINE, ERROR }

sealed interface VoiceSignal {
    data object Start : VoiceSignal            // user turned the assistant on
    data object Stop : VoiceSignal             // user turned it fully off
    data object WakeDetected : VoiceSignal
    data class Transcript(val text: String) : VoiceSignal   // final recognised user text
    data object EmptyTranscript : VoiceSignal  // listening ended with nothing usable
    data class Reply(val text: String) : VoiceSignal        // assistant produced an answer
    data object SpeakingDone : VoiceSignal
    data object BargeIn : VoiceSignal          // user started talking over TTS
    data class Failure(val reason: String) : VoiceSignal
    data object NetworkLost : VoiceSignal
    data object NetworkBack : VoiceSignal
}

/**
 * @param followUpAfterReply if true, after SAM finishes speaking it keeps actively listening for a
 *        follow-up (continuous multi-turn) instead of dropping back to wake-word mode.
 */
class ConversationStateMachine(private val followUpAfterReply: Boolean = true) {
    var phase: VoicePhase = VoicePhase.IDLE; private set
    private var offline = false

    fun next(signal: VoiceSignal): VoicePhase {
        phase = reduce(phase, signal)
        return phase
    }

    private fun reduce(current: VoicePhase, s: VoiceSignal): VoicePhase = when (s) {
        VoiceSignal.Stop -> VoicePhase.IDLE
        VoiceSignal.Start -> if (current == VoicePhase.IDLE) VoicePhase.WAKE_LISTENING else current
        is VoiceSignal.Failure -> VoicePhase.ERROR
        VoiceSignal.NetworkLost -> { offline = true; current }
        VoiceSignal.NetworkBack -> { offline = false; current }
        VoiceSignal.WakeDetected -> if (current == VoicePhase.WAKE_LISTENING || current == VoicePhase.ERROR) VoicePhase.LISTENING else current
        is VoiceSignal.Transcript -> if (current == VoicePhase.LISTENING) VoicePhase.PROCESSING else current
        VoiceSignal.EmptyTranscript -> if (current == VoicePhase.LISTENING) idleOrWake() else current
        is VoiceSignal.Reply -> if (current == VoicePhase.PROCESSING) VoicePhase.SPEAKING else current
        VoiceSignal.SpeakingDone -> if (current == VoicePhase.SPEAKING) afterSpeaking() else current
        VoiceSignal.BargeIn -> if (current == VoicePhase.SPEAKING) VoicePhase.INTERRUPTED else current
    }.let { resolved ->
        // INTERRUPTED is a momentary phase that immediately hands off to active listening.
        if (resolved == VoicePhase.INTERRUPTED) VoicePhase.LISTENING else resolved
    }

    private fun afterSpeaking(): VoicePhase = if (followUpAfterReply) VoicePhase.LISTENING else VoicePhase.WAKE_LISTENING
    private fun idleOrWake(): VoicePhase = VoicePhase.WAKE_LISTENING
    fun isOffline(): Boolean = offline
    fun reset() { phase = VoicePhase.IDLE; offline = false }
}
