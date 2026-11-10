package ir.elviran.sam

import ir.elviran.sam.voice.engine.VadController
import org.junit.Assert.assertEquals
import org.junit.Test

class VadControllerTest {
    private fun vad() = VadController(speechThreshold = 0.02, endSilenceMs = 500, minSpeechMs = 100, bargeInMs = 300, bargeInThreshold = 0.05)

    @Test fun detects_speech_start_then_end_of_utterance() {
        val v = vad()
        assertEquals(VadController.Event.SPEECH_START, v.onFrame(0.1, 0))
        assertEquals(VadController.Event.SPEECH, v.onFrame(0.1, 120))   // passes minSpeechMs
        assertEquals(VadController.Event.NONE, v.onFrame(0.001, 300))   // silence, not long enough
        assertEquals(VadController.Event.END_OF_UTTERANCE, v.onFrame(0.001, 650)) // 650-120 >= 500
    }

    @Test fun ignores_short_noise_blip() {
        val v = vad()
        v.onFrame(0.1, 0)              // SPEECH_START
        // immediate silence before minSpeechMs elapses -> never a real utterance -> no END fired
        assertEquals(VadController.Event.NONE, v.onFrame(0.0, 50))
        assertEquals(VadController.Event.NONE, v.onFrame(0.0, 600))
    }

    @Test fun barge_in_requires_sustained_loud_speech() {
        val v = vad()
        assertEquals(VadController.Event.SPEECH, v.onFrameWhileSpeaking(0.08, 0))
        assertEquals(VadController.Event.SPEECH, v.onFrameWhileSpeaking(0.08, 200))
        assertEquals(VadController.Event.BARGE_IN, v.onFrameWhileSpeaking(0.08, 320)) // 320-0 >= 300
    }

    @Test fun barge_in_resets_on_quiet() {
        val v = vad()
        v.onFrameWhileSpeaking(0.08, 0)
        assertEquals(VadController.Event.NONE, v.onFrameWhileSpeaking(0.01, 100)) // quiet resets accumulator
        assertEquals(VadController.Event.SPEECH, v.onFrameWhileSpeaking(0.08, 200))
        assertEquals(VadController.Event.NONE, v.onFrameWhileSpeaking(0.0, 260))
    }

    @Test fun rms_of_silence_is_zero() {
        val v = vad()
        assertEquals(0.0, v.rms(ShortArray(320)), 1e-9)
    }
}
