package ir.elviran.sam

import ir.elviran.sam.voice.engine.ConversationStateMachine
import ir.elviran.sam.voice.engine.VoicePhase
import ir.elviran.sam.voice.engine.VoiceSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationStateMachineTest {
    @Test fun full_cycle_with_follow_up() {
        val m = ConversationStateMachine(followUpAfterReply = true)
        assertEquals(VoicePhase.WAKE_LISTENING, m.next(VoiceSignal.Start))
        assertEquals(VoicePhase.LISTENING, m.next(VoiceSignal.WakeDetected))
        assertEquals(VoicePhase.PROCESSING, m.next(VoiceSignal.Transcript("سلام")))
        assertEquals(VoicePhase.SPEAKING, m.next(VoiceSignal.Reply("سلام بر تو")))
        // follow-up keeps listening after speaking
        assertEquals(VoicePhase.LISTENING, m.next(VoiceSignal.SpeakingDone))
    }

    @Test fun without_follow_up_returns_to_wake() {
        val m = ConversationStateMachine(followUpAfterReply = false)
        m.next(VoiceSignal.Start); m.next(VoiceSignal.WakeDetected)
        m.next(VoiceSignal.Transcript("x")); m.next(VoiceSignal.Reply("y"))
        assertEquals(VoicePhase.WAKE_LISTENING, m.next(VoiceSignal.SpeakingDone))
    }

    @Test fun barge_in_while_speaking_goes_to_listening() {
        val m = ConversationStateMachine()
        m.next(VoiceSignal.Start); m.next(VoiceSignal.WakeDetected)
        m.next(VoiceSignal.Transcript("x")); m.next(VoiceSignal.Reply("y"))
        assertEquals(VoicePhase.SPEAKING, m.phase)
        assertEquals(VoicePhase.LISTENING, m.next(VoiceSignal.BargeIn))
    }

    @Test fun empty_transcript_returns_to_wake() {
        val m = ConversationStateMachine()
        m.next(VoiceSignal.Start); m.next(VoiceSignal.WakeDetected)
        assertEquals(VoicePhase.WAKE_LISTENING, m.next(VoiceSignal.EmptyTranscript))
    }

    @Test fun stop_always_idles() {
        val m = ConversationStateMachine()
        m.next(VoiceSignal.Start); m.next(VoiceSignal.WakeDetected)
        assertEquals(VoicePhase.IDLE, m.next(VoiceSignal.Stop))
    }

    @Test fun wake_ignored_when_not_listening_for_wake() {
        val m = ConversationStateMachine()
        m.next(VoiceSignal.Start); m.next(VoiceSignal.WakeDetected)
        m.next(VoiceSignal.Transcript("x")) // now PROCESSING
        assertEquals(VoicePhase.PROCESSING, m.next(VoiceSignal.WakeDetected))
    }

    @Test fun network_flags_tracked() {
        val m = ConversationStateMachine()
        m.next(VoiceSignal.Start)
        m.next(VoiceSignal.NetworkLost)
        assertEquals(true, m.isOffline())
        m.next(VoiceSignal.NetworkBack)
        assertEquals(false, m.isOffline())
    }
}
