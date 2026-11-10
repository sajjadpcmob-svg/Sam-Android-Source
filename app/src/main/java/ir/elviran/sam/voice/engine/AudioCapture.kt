package ir.elviran.sam.voice.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single owner of the microphone. Captures 16 kHz / 16-bit / mono PCM (what Vosk expects) on a
 * dedicated thread and pushes frames to a callback. One capture stream feeds BOTH the wake-word
 * recogniser and the offline dictation recogniser, so there is never a second AudioRecord fighting
 * for the mic.
 *
 * Echo cancellation / noise suppression / AGC are enabled on the capture session when the device
 * exposes them (the Galaxy S24 Ultra does). This is what makes real barge-in possible: SAM's own
 * TTS leaking back into the mic is largely cancelled, so the VAD does not treat it as the user.
 */
class AudioCapture(
    val sampleRate: Int = 16_000,
    /** ~20 ms frames keep VAD latency low. */
    private val frameSamples: Int = 320
) {
    fun interface FrameSink { fun onFrame(frame: ShortArray, len: Int, tsMs: Long) }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(sink: FrameSink, onError: (String) -> Unit) {
        if (running.get()) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { onError("پیکربندی میکروفون پشتیبانی نمی‌شود"); return }
        val bufSize = maxOf(minBuf, frameSamples * 2 * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Throwable) { onError("ساخت AudioRecord ناموفق بود: ${e.message}"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); onError("میکروفون آماده نشد"); return }
        record = rec
        enableEffects(rec.audioSessionId)
        running.set(true)
        rec.startRecording()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(frameSamples)
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) sink.onFrame(buf, n, System.currentTimeMillis())
                else if (n < 0) { onError("خطای خواندن صدا: $n"); break }
            }
        }, "sam-audio-capture").apply { start() }
    }

    private fun enableEffects(sessionId: Int) {
        runCatching { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(true) } }
        runCatching { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sessionId)?.also { it.setEnabled(true) } }
        runCatching { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.also { it.setEnabled(true) } }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { thread?.join(500) }
        thread = null
        runCatching { aec?.release() }; runCatching { ns?.release() }; runCatching { agc?.release() }
        aec = null; ns = null; agc = null
        val rec = record; record = null
        runCatching { if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
        runCatching { rec?.release() }
    }
}
