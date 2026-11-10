package ir.elviran.sam.voice.engine

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Local, on-device wake-word spotter for «سام». It uses a Vosk recogniser restricted by a tiny
 * keyword grammar (سام vs the ا[unk] filler), which is far lighter and far less false-positive-prone
 * than running an open-vocabulary recogniser in a loop. Audio comes from the shared [AudioCapture];
 * nothing is streamed to any server.
 *
 * Decision = Vosk hypothesis + [WakeWordMatcher] gate + a cool-down so one utterance of «سام» cannot
 * fire repeatedly.
 */
class WakeWordDetector(
    model: Model,
    sampleRate: Float,
    /** 0..1 sensitivity: higher = stricter = fewer false positives. */
    private var minConfidence: Double = 0.5,
    /** Ignore further triggers for this long after a hit (ms). */
    private var cooldownMs: Long = 1500L
) {
    private val grammar = "[\"سام\", \"[unk]\"]"
    private val recognizer = Recognizer(model, sampleRate, grammar).apply { setWords(true) }
    private var lastHit = 0L
    private var closed = false

    fun setSensitivity(minConfidence: Double) { this.minConfidence = minConfidence }
    fun setCooldown(ms: Long) { this.cooldownMs = ms }

    /** Feed one PCM frame. Returns true exactly when «سام» is accepted. */
    fun accept(frame: ShortArray, len: Int, tsMs: Long): Boolean {
        if (closed) return false
        if (tsMs - lastHit < cooldownMs) return false
        // Only decide on a FINALISED hypothesis. Partial results are unstable and were a source of
        // false activations on incomplete utterances, so we never fire on them.
        val complete = recognizer.acceptWaveForm(frame, len)
        if (!complete) return false
        val obj = runCatching { JSONObject(recognizer.result) }.getOrNull() ?: return false
        val text = obj.optString("text")
        if (text.isBlank()) return false
        // Vosk reports per-word confidence in "result" when setWords(true). Average the confidence of
        // the words that actually carry a "conf" field. A MISSING confidence must NOT be treated as
        // full certainty (that silently defeated the sensitivity gate and accepted wrong words).
        var sum = 0.0; var n = 0
        obj.optJSONArray("result")?.let { arr ->
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                if (w.has("conf")) { sum += w.optDouble("conf", 0.0); n++ }
            }
        }
        val hasConf = n > 0
        val avg = if (hasConf) sum / n else Double.NaN
        val valid = hasConf && !avg.isNaN()
        // Unknown / invalid confidence + an active sensitivity gate => reject (fail safe, cut false positives).
        if (!valid && minConfidence > 0.0) return false
        val conf = if (valid) avg else minConfidence
        if (WakeWordMatcher.isWake(text, conf, minConfidence)) {
            lastHit = tsMs
            runCatching { recognizer.reset() }
            return true
        }
        return false
    }

    fun reset() { runCatching { recognizer.reset() } }
    fun close() { if (!closed) { closed = true; runCatching { recognizer.close() } } }
}
