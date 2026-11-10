package ai.ciris.mobile.shared.calls.media

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Desktop call audio through `javax.sound.sampled` (CSD-134): no native library.
 *
 * The format is the call's: 48 kHz, 16-bit signed, mono, little-endian. On Linux
 * Java Sound goes through ALSA to PulseAudio or PipeWire, so `PULSE_SOURCE` and
 * `PULSE_SINK` pick the devices: the five-platform runner points both at a null
 * sink and its monitor, a virtual microphone with no hardware.
 *
 * There is no echo cancellation here: javax.sound has none. Headphones, or the
 * platform's own voice processing where the OS offers it, until one is added.
 */
private val FORMAT = AudioFormat(CallAudioFormat.SAMPLE_RATE.toFloat(), 16, 1, true, false)
private const val FRAME_BYTES = CallAudioFormat.FRAME_SAMPLES * 2

/** True when this machine has a microphone line Java Sound can open in the call's format. */
fun desktopMicrophoneAvailable(): Boolean =
    runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, FORMAT)) }.getOrDefault(false)

fun desktopSpeakerAvailable(): Boolean =
    runCatching { AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, FORMAT)) }.getOrDefault(false)

/** The microphone, read 20 ms at a time on its own thread. */
class DesktopMicrophoneSource : AudioSource {
    @Volatile private var line: TargetDataLine? = null
    private var thread: Thread? = null

    override fun start(onFrame: (ShortArray) -> Unit) {
        if (line != null) return
        val l = AudioSystem.getTargetDataLine(FORMAT)
        // 320 ms of buffer: the ALSA pulse plugin's periods are larger than a 20 ms
        // frame, and a smaller line buffer stalls read() (seen on a CI runner).
        l.open(FORMAT, FRAME_BYTES * 16)
        l.start()
        line = l
        thread = Thread({
            val buf = ByteArray(FRAME_BYTES)
            while (line === l) {
                var off = 0
                while (off < FRAME_BYTES && line === l) {
                    val n = l.read(buf, off, FRAME_BYTES - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == FRAME_BYTES) onFrame(pcmOf(buf))
            }
        }, "ciris-call-mic").apply { isDaemon = true; start() }
    }

    override fun stop() {
        val l = line ?: return
        line = null
        runCatching { l.stop(); l.close() }
        thread?.join(500)
        thread = null
    }
}

/**
 * The speaker: one line per publisher, and the system mixer mixes them. Lines are
 * opened on first sound and closed when the member leaves.
 *
 * Frames come from the call's playout clock, which is a timer and so a few
 * milliseconds early or late each time. A line that has run dry is given
 * [CUSHION_FRAMES] of silence first, so that wobble is absorbed instead of heard
 * as a click on every frame. The line holds 200 ms; a frame that does not fit is
 * late, and dropped.
 */
class DesktopSpeakerSink : AudioSink {
    private val lines = mutableMapOf<String, SourceDataLine>()

    @Synchronized
    override fun play(publisher: String, pcm: ShortArray) {
        val line = lines.getOrPut(publisher) {
            AudioSystem.getSourceDataLine(FORMAT).apply { open(FORMAT, FRAME_BYTES * LINE_FRAMES); start() }
        }
        if (line.available() >= line.bufferSize) line.write(ByteArray(FRAME_BYTES * CUSHION_FRAMES), 0, FRAME_BYTES * CUSHION_FRAMES)
        val bytes = bytesOf(pcm)
        // Never block the decoder: if the line is full, this frame is late, so drop it.
        if (line.available() >= bytes.size) line.write(bytes, 0, bytes.size)
    }

    private companion object {
        const val LINE_FRAMES = 10
        const val CUSHION_FRAMES = 2
    }

    @Synchronized
    override fun remove(publisher: String) {
        lines.remove(publisher)?.let { runCatching { it.stop(); it.close() } }
    }

    @Synchronized
    override fun stop() {
        lines.values.forEach { runCatching { it.stop(); it.close() } }
        lines.clear()
    }
}

internal fun pcmOf(le: ByteArray): ShortArray =
    ShortArray(le.size / 2) { ((le[2 * it].toInt() and 0xFF) or (le[2 * it + 1].toInt() shl 8)).toShort() }

internal fun bytesOf(pcm: ShortArray): ByteArray {
    val out = ByteArray(pcm.size * 2)
    for (i in pcm.indices) {
        out[2 * i] = pcm[i].toByte()
        out[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
    }
    return out
}
