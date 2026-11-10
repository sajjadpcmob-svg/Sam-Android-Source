package ir.elviran.sam.voice.engine

/**
 * Pure, Android-free matcher for the Persian wake word «سام».
 *
 * The actual audio -> text comes from the offline Vosk keyword recogniser; this class only decides
 * whether a decoded hypothesis should count as a wake. Keeping it pure makes false-positive /
 * false-negative behaviour unit-testable with no device.
 *
 * False-positive reduction strategy:
 *  - normalise Persian/Arabic glyph variants and strip diacritics,
 *  - accept only short hypotheses (the keyword grammar should not emit long phrases),
 *  - accept an exact token سام or a very small set of close homophones,
 *  - require the token to stand (nearly) alone, not be a substring of an unrelated longer word,
 *  - a configurable minimum Vosk word-confidence gate is applied by the caller.
 */
object WakeWordMatcher {

    /** Canonical wake token and a short allow-list of acoustically identical spellings. */
    private val ACCEPTED = setOf("سام", "saam", "sam")

    /** Tokens that sound close but must NOT trigger, to cut random activations. */
    private val REJECTED = setOf("شام", "جام", "گام", "نام", "سامان", "سامسونگ")

    fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        // unify Arabic/Persian variants
        s = s.replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک')
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ء', 'ا')
        // strip Arabic diacritics / tatweel / zero-width
        s = s.filterNot { it in '\u064b'..'\u0652' || it == '\u0640' || it == '\u200c' || it == '\u200f' || it == '\u200e' }
        return s.trim()
    }

    /**
     * @param hypothesis decoded text from the keyword recogniser (result or partial)
     * @param confidence 0..1 word confidence reported by Vosk (use 1.0 if unknown)
     * @param minConfidence sensitivity gate; higher = fewer false positives
     */
    fun isWake(hypothesis: String, confidence: Double = 1.0, minConfidence: Double = 0.0): Boolean {
        // Fail-safe: a missing / NaN / negative confidence must NOT be read as full certainty.
        val c = if (confidence.isNaN()) Double.NEGATIVE_INFINITY else confidence
        if (minConfidence > 0.0 && c < minConfidence) return false
        val norm = normalize(hypothesis)
        if (norm.isEmpty()) return false
        val tokens = norm.split(Regex("[\\s\u060c,.!؟?]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 3) return false
        if (tokens.any { it in REJECTED }) return false
        return tokens.any { it in ACCEPTED }
    }
}
