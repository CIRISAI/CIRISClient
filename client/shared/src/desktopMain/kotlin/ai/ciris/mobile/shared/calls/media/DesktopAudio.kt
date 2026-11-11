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
 * Java Sound has no echo cancellation, so the call brings its own ([EchoCanceller]):
 * the speaker hands it every frame it plays, and the microphone passes every frame
 * it captures through it. The platform's own does this on Android, iOS and the web.
 */
private val FORMAT = AudioFormat(CallAudioFormat.SAMPLE_RATE.toFloat(), 16, 1, true, false)
private const val FRAME_BYTES = CallAudioFormat.FRAME_SAMPLES * 2

/** True when this machine has a microphone line Java Sound can open in the call's format. */
fun desktopMicrophoneAvailable(): Boolean =
    runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, FORMAT)) }.getOrDefault(false)

fun desktopSpeakerAvailable(): Boolean =
    runCatching { AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, FORMAT)) }.getOrDefault(false)

/**
 * The microphone, read 20 ms at a time on its own thread, through [echo] when
 * there is one (the same canceller the call's speaker feeds).
 */
class DesktopMicrophoneSource(private val echo: EchoCanceller? = null) : AudioSource {
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
                if (off == FRAME_BYTES) {
                    val pcm = pcmOf(buf)
                    onFrame(echo?.capture(pcm) ?: pcm)
                }
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
 * The speaker: one line, and this class mixes the publishers into it. One line
 * because the echo canceller has to know exactly what was played and in what
 * order; with a line per publisher the system mixer decides that, and nobody can
 * read it back.
 *
 * A writer thread keeps the line from running dry: it writes a mixed frame as
 * soon as there is one and room for it, and a frame of silence when the line is
 * down to [LOW_WATER_FRAMES]. So the line never stops, and "written" maps onto
 * "played" by a steady offset, which is what [EchoCanceller] relies on. Frames
 * come from the call's playout clock, a timer a few milliseconds early or late
 * each time; the line's few frames of cushion absorb that. A publisher with more
 * than [MAX_QUEUED] frames waiting is late, and its oldest frames are dropped.
 */
class DesktopSpeakerSink(private val echo: EchoCanceller? = null) : AudioSink {
    private val lock = Object()
    private val queues = LinkedHashMap<String, ArrayDeque<ShortArray>>()
    @Volatile private var line: SourceDataLine? = null
    private var thread: Thread? = null

    override fun play(publisher: String, pcm: ShortArray) {
        synchronized(lock) {
            val q = queues.getOrPut(publisher) { ArrayDeque() }
            q.addLast(pcm)
            while (q.size > MAX_QUEUED) q.removeFirst()
            if (line == null) open()
        }
    }

    private fun open() {
        val l = AudioSystem.getSourceDataLine(FORMAT).apply { open(FORMAT, FRAME_BYTES * LINE_FRAMES); start() }
        line = l
        thread = Thread({ writeLoop(l) }, "ciris-call-speaker").apply { isDaemon = true; start() }
    }

    private fun writeLoop(l: SourceDataLine) {
        val bytes = ByteArray(FRAME_BYTES)
        while (line === l) {
            val room = l.available() >= FRAME_BYTES
            val queued = (l.bufferSize - l.available()) / FRAME_BYTES
            val frame = if (!room) null else synchronized(lock) { mixOne() } ?: if (queued < LOW_WATER_FRAMES) SILENCE else null
            if (frame == null) {
                Thread.sleep(4)
                continue
            }
            for (i in frame.indices) {
                bytes[2 * i] = frame[i].toByte()
                bytes[2 * i + 1] = (frame[i].toInt() shr 8).toByte()
            }
            l.write(bytes, 0, FRAME_BYTES)
            echo?.farEnd(frame)
        }
    }

    /** One frame from every publisher that has one, summed and clipped; null when none has. */
    private fun mixOne(): ShortArray? {
        var mix: IntArray? = null
        for (q in queues.values) {
            val f = q.removeFirstOrNull() ?: continue
            val m = mix ?: IntArray(CallAudioFormat.FRAME_SAMPLES).also { mix = it }
            for (i in m.indices) m[i] += f[i]
        }
        val m = mix ?: return null
        return ShortArray(m.size) { m[it].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    override fun remove(publisher: String) {
        synchronized(lock) { queues.remove(publisher) }
    }

    override fun stop() {
        val l = synchronized(lock) {
            queues.clear()
            line.also { line = null }
        } ?: return
        thread?.join(500)
        thread = null
        runCatching { l.stop(); l.close() }
    }

    private companion object {
        /** 120 ms of line. */
        const val LINE_FRAMES = 6
        /** Below 40 ms queued the writer adds silence rather than let the line stop. */
        const val LOW_WATER_FRAMES = 2
        const val MAX_QUEUED = 5
        val SILENCE = ShortArray(CallAudioFormat.FRAME_SAMPLES)
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
