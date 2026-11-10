package ir.elviran.sam.voice.engine

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Real offline Persian speech-to-text powered by the Vosk Kaldi recogniser running fully on-device.
 * This is an actual acoustic recogniser (not a rule parser): the deterministic [OfflineBrain] still
 * handles command *intent*, but the words themselves come from Vosk here.
 *
 * Audio frames arrive from the shared [AudioCapture]; end-of-utterance is driven by the external
 * [VadController] (the controller calls [finalize] on sustained silence), with Vosk's own result as
 * a fallback.
 */
class OfflineSpeechEngine(model: Model, sampleRate: Float) {
    private val recognizer = Recognizer(model, sampleRate).apply { setWords(false) }
    private var closed = false
    private var lastPartial = ""

    /** Feed a frame; returns the current partial transcript (may be empty). */
    fun accept(frame: ShortArray, len: Int): String {
        if (closed) return ""
        val complete = recognizer.acceptWaveForm(frame, len)
        val json = if (complete) recognizer.result else recognizer.partialResult
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return lastPartial
        lastPartial = (if (complete) obj.optString("text") else obj.optString("partial")).trim()
        return lastPartial
    }

    /** Flush and return the final recognised sentence, then reset for the next utterance. */
    fun finalize(): String {
        if (closed) return ""
        val obj = runCatching { JSONObject(recognizer.finalResult) }.getOrNull()
        val text = obj?.optString("text").orEmpty().trim()
        recognizer.reset(); lastPartial = ""
        return text
    }

    fun reset() { runCatching { recognizer.reset() }; lastPartial = "" }
    fun close() { if (!closed) { closed = true; runCatching { recognizer.close() } } }
}
