package ai.ciris.mobile.shared.calls.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build

/**
 * Android call audio (CSD-134), in the call's format: 48 kHz, 16-bit, mono.
 *
 * Capture uses the VOICE_COMMUNICATION source, which routes through the device's
 * voice processing, plus the platform echo canceller and noise suppressor where
 * the device has them. Playback is a voice call (USAGE_VOICE_COMMUNICATION), and
 * on API 29+ it refuses playback capture (ALLOW_CAPTURE_BY_NONE), so a screen
 * share's audio never records the call it is in.
 *
 * Needs RECORD_AUDIO, asked by the call screen at the moment of the call
 * (`callPermissionStep`), never at launch.
 */
private const val SAMPLE_RATE = CallAudioFormat.SAMPLE_RATE
private const val FRAME_SAMPLES = CallAudioFormat.FRAME_SAMPLES

/** The microphone, read 20 ms at a time on its own thread. */
class AndroidMicrophoneSource : AudioSource {
    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** True when the device's echo canceller is on for this call. */
    val echoCancelled: Boolean get() = aec?.enabled == true

    @SuppressLint("MissingPermission") // the call screen holds RECORD_AUDIO before this runs
    override fun start(onFrame: (ShortArray) -> Unit) {
        if (record != null) return
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, FRAME_SAMPLES * 2 * 4),
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "the microphone could not be opened" }
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        r.startRecording()
        record = r
        thread = Thread({
            while (record === r) {
                val frame = ShortArray(FRAME_SAMPLES)
                var off = 0
                while (off < FRAME_SAMPLES && record === r) {
                    val n = r.read(frame, off, FRAME_SAMPLES - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == FRAME_SAMPLES) onFrame(frame)
            }
        }, "ciris-call-mic").apply { isDaemon = true; start() }
    }

    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        thread?.join(500)
        thread = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        r.release()
    }
}

/**
 * The speaker: one AudioTrack per publisher (the system mixes them), opened on a
 * member's first sound and released when they leave. Writes never block the
 * decoder: a frame that does not fit the ~100 ms buffer is late, and dropped.
 */
class AndroidSpeakerSink : AudioSink {
    private val tracks = mutableMapOf<String, AudioTrack>()

    private fun open(): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .apply { if (Build.VERSION.SDK_INT >= 29) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE) }
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack(attrs, format, maxOf(min, FRAME_SAMPLES * 2 * 5), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            .apply { play() }
    }

    @Synchronized
    override fun play(publisher: String, pcm: ShortArray) {
        val t = tracks.getOrPut(publisher) { open() }
        if (Build.VERSION.SDK_INT >= 23) {
            t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
        } else {
            t.write(pcm, 0, pcm.size)
        }
    }

    @Synchronized
    override fun remove(publisher: String) {
        tracks.remove(publisher)?.let { runCatching { it.stop() }; it.release() }
    }

    @Synchronized
    override fun stop() {
        tracks.values.forEach { runCatching { it.stop() }; it.release() }
        tracks.clear()
    }
}
