package ai.ciris.mobile.shared.calls.media

/**
 * One member's playout (jitter) buffer: packets wait here and leave on the
 * playout clock, one every 20 ms, whenever they arrived.
 *
 * This is the fixed-delay buffer every voice stack has in some form (Mumble,
 * toxav, the simple mode of WebRTC's NetEq): arrival is bursty, a speaker wants
 * a steady stream, so sound starts only once [TARGET_DEPTH] packets are waiting
 * and an empty buffer is concealed at the moment it would have played, not when
 * the next packet turns up.
 *
 * Concealment is decided by TIME, never by `chunk_seq`. A publisher's audio and
 * video share one stream and one counter (CIRISEdge seals every chunk of a stream
 * with the next `chunk_seq`, whatever its codec), so a jump in the counter between
 * two audio packets is usually a video frame, not lost sound. Audio has no counter
 * of its own (`frame_seq` counts video), so this clock decides. From Edge 45 a frame
 * also carries the publisher's capture time (`source_ts_ms`); this buffer does not
 * read it yet, and lip sync, which needs it, is not built.
 *
 * What it is not: adaptive. NetEq stretches and compresses speech to move its
 * delay without a click; this holds a fixed target and, when it has fallen
 * behind, drops whole 20 ms packets to come back.
 */
internal class AudioPlayout {
    sealed interface Step {
        /** Decode and play this packet. */
        class Packet(val bytes: ByteArray) : Step
        /** Nothing arrived in time: ask the decoder for concealment. */
        data object Conceal : Step
        /** Not talking, or still filling: play nothing. */
        data object Silence : Step
    }

    private val waiting = ArrayDeque<ByteArray>()
    private var playing = false
    private var concealed = 0
    private var ticksOver = 0

    val depth: Int get() = waiting.size

    fun offer(packet: ByteArray) {
        waiting.addLast(packet)
        // A stall just ended in a burst, or the sender's clock runs fast: this much
        // is delay nobody asked for. Skip ahead to the target.
        if (waiting.size > MAX_DEPTH) {
            while (waiting.size > TARGET_DEPTH) waiting.removeFirst()
        }
    }

    /** What the next 20 ms of this member's sound is. Call once per playout tick. */
    fun next(): Step {
        if (!playing) {
            if (waiting.size < TARGET_DEPTH) return Step.Silence
            playing = true
            ticksOver = 0
        }
        val packet = waiting.removeFirstOrNull()
        if (packet == null) {
            if (concealed < MAX_CONCEALED) {
                concealed++
                return Step.Conceal
            }
            // They stopped (muted, or gone quiet on the wire): fill again before the next word.
            playing = false
            concealed = 0
            return Step.Silence
        }
        concealed = 0
        // Standing delay over the target, a second long: give back 20 ms of it.
        if (waiting.size > TARGET_DEPTH) {
            if (++ticksOver >= LEAK_AFTER_TICKS) {
                ticksOver = 0
                return Step.Packet(waiting.removeFirst())
            }
        } else {
            ticksOver = 0
        }
        return Step.Packet(packet)
    }

    companion object {
        /** Sound starts once this many packets wait: 40 ms. */
        const val TARGET_DEPTH = 2
        /** More than this waiting is skipped down to the target: 200 ms. */
        const val MAX_DEPTH = 10
        /** At most this many 20 ms frames of concealment in a row: 100 ms. */
        const val MAX_CONCEALED = 5
        /** One second of ticks over the target before a packet is dropped to come back. */
        const val LEAK_AFTER_TICKS = 50
    }
}
