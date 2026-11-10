package ir.elviran.sam

import ir.elviran.sam.voice.engine.WakeWordMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {
    @Test fun accepts_exact_sam() {
        assertTrue(WakeWordMatcher.isWake("سام"))
        assertTrue(WakeWordMatcher.isWake("  سام  "))
    }

    @Test fun accepts_sam_with_short_context() {
        assertTrue(WakeWordMatcher.isWake("سام جان"))
    }

    @Test fun rejects_near_homophones() {
        assertFalse(WakeWordMatcher.isWake("شام"))
        assertFalse(WakeWordMatcher.isWake("جام"))
        assertFalse(WakeWordMatcher.isWake("سامسونگ"))
        assertFalse(WakeWordMatcher.isWake("نام"))
    }

    @Test fun rejects_long_phrases() {
        assertFalse(WakeWordMatcher.isWake("سام یک کار برای من انجام بده لطفا"))
    }

    @Test fun confidence_gate_blocks_low_confidence() {
        assertFalse(WakeWordMatcher.isWake("سام", confidence = 0.3, minConfidence = 0.6))
        assertTrue(WakeWordMatcher.isWake("سام", confidence = 0.8, minConfidence = 0.6))
    }

    @Test fun nan_confidence_is_never_full_certainty() {
        // A NaN confidence must be treated as "unknown" and blocked by any positive gate,
        // not silently read as full certainty.
        assertFalse(WakeWordMatcher.isWake("سام", confidence = Double.NaN, minConfidence = 0.6))
        assertFalse(WakeWordMatcher.isWake("سام", confidence = Double.NaN, minConfidence = 0.01))
        // With no gate (minConfidence = 0) the token itself still decides, NaN cannot force a false negative here.
        assertTrue(WakeWordMatcher.isWake("سام", confidence = Double.NaN, minConfidence = 0.0))
    }

    @Test fun negative_confidence_blocked_by_gate() {
        assertFalse(WakeWordMatcher.isWake("سام", confidence = -1.0, minConfidence = 0.6))
    }

    @Test fun normalizes_arabic_variants() {
        // Arabic kaf/yeh variants should not matter; سام has none but ensure no crash and empty stays empty
        assertFalse(WakeWordMatcher.isWake(""))
    }
}
