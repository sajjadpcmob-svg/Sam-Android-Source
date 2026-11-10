package ir.elviran.sam.voice.engine

import kotlin.math.sqrt

/**
 * Pure, Android-free Voice Activity Detection and end-of-utterance logic.
 *
 * It is fed with per-frame audio energy (RMS) plus the wall-clock timestamp of that frame and
 * emits semantic events: speech started, still speaking, end-of-utterance (sustained silence after
 * speech) and a barge-in signal (speech detected while SAM is talking). All timing is driven by the
 * timestamps the caller passes in, so the whole thing is deterministic and unit-testable with no
 * real microphone or clock.
 */
class VadController(
    /** RMS above this (0..1) counts as speech. Tunable for sensitivity / battery. */
    private var speechThreshold: Double = 0.012,
    /** How long continuous silence (ms) after speech ends the utterance. */
    private var endSilenceMs: Long = 900L,
    /** Minimum speech duration (ms) before an utterance is considered real (noise guard). */
    private var minSpeechMs: Long = 200L,
    /** For barge-in while speaking: require this much continuous speech (ms) to interrupt TTS. */
    private var bargeInMs: Long = 320L,
    /** Barge-in uses a higher threshold so SAM's own leaked audio does not self-trigger. */
    private var bargeInThreshold: Double = 0.045
) {
    enum class Event { NONE, SPEECH_START, SPEECH, END_OF_UTTERANCE, BARGE_IN }

    private var inSpeech = false
    private var speechStartTs = 0L
    private var lastSpeechTs = 0L
    private var sawRealSpeech = false
    private var bargeAccumFrom = NOT_SET // NOT_SET (not 0): a frame at timestamp 0 is valid

    fun configure(
        speechThreshold: Double? = null,
        endSilenceMs: Long? = null,
        minSpeechMs: Long? = null,
        bargeInMs: Long? = null,
        bargeInThreshold: Double? = null
    ) {
        speechThreshold?.let { this.speechThreshold = it }
        endSilenceMs?.let { this.endSilenceMs = it }
        minSpeechMs?.let { this.minSpeechMs = it }
        bargeInMs?.let { this.bargeInMs = it }
        bargeInThreshold?.let { this.bargeInThreshold = it }
    }

    fun reset() {
        inSpeech = false; speechStartTs = 0L; lastSpeechTs = 0L; sawRealSpeech = false; bargeAccumFrom = NOT_SET
    }

    /** Compute normalised RMS (0..1) from a 16-bit PCM frame. */
    fun rms(frame: ShortArray, len: Int = frame.size): Double {
        if (len <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until len) { val v = frame[i].toDouble(); sum += v * v }
        return sqrt(sum / len) / 32768.0
    }

    /** Normal listening mode: detect speech start and end-of-utterance. */
    fun onFrame(energy: Double, tsMs: Long): Event {
        val loud = energy >= speechThreshold
        if (loud) {
            lastSpeechTs = tsMs
            if (!inSpeech) { inSpeech = true; speechStartTs = tsMs; return Event.SPEECH_START }
            if (!sawRealSpeech && tsMs - speechStartTs >= minSpeechMs) sawRealSpeech = true
            return Event.SPEECH
        }
        if (inSpeech && sawRealSpeech && tsMs - lastSpeechTs >= endSilenceMs) {
            inSpeech = false
            return Event.END_OF_UTTERANCE
        }
        return Event.NONE
    }

    /** Barge-in mode (used while TTS is playing): only fires once enough loud speech accrues. */
    fun onFrameWhileSpeaking(energy: Double, tsMs: Long): Event {
        if (energy >= bargeInThreshold) {
            if (bargeAccumFrom == NOT_SET) bargeAccumFrom = tsMs
            if (tsMs - bargeAccumFrom >= bargeInMs) { bargeAccumFrom = NOT_SET; return Event.BARGE_IN }
            return Event.SPEECH
        }
        bargeAccumFrom = NOT_SET
        return Event.NONE
    }

    private companion object { const val NOT_SET = -1L }
}
