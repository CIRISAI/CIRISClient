package ai.ciris.mobile.shared.calls

/**
 * The call frames socket, version 1 (CSD-134 §3; CIRISServer `src/call_frames.rs`).
 *
 * One WebSocket binary message is one frame. Integers are big-endian.
 *
 * ```text
 * client → node  ver u8 (=1) | codec_id u8 | spatial u8 | temporal u8 | quality u8 | payload…
 * node → client  ver u8 (=1) | codec_id u8 | spatial u8 | temporal u8 | quality u8
 *                | pub_len u16 | publisher[pub_len] (UTF-8 key_id) | stream_id[32]
 *                | epoch u64 | chunk_seq u64 | source_ts_ms u64
 *                | flags u8 | frame_seq u32 | payload…
 * ```
 *
 * `chunk_seq` counts every chunk of a stream whatever its codec, so a jump in it
 * says nothing about one medium. The two fields after it do (Edge v45):
 *
 * - `source_ts_ms` is the publisher's signed capture time, milliseconds since the
 *   Unix epoch: what sound and picture are lined up by.
 * - `frame_seq` is the publisher's frame counter for this stream's video, present
 *   when `flags` bit 0 is set and absent on audio (the field is then 0, and null
 *   here, so a counter that wraps never meets a sentinel). A jump in it is a lost
 *   picture. The other `flags` bits are reserved and ignored.
 *
 * A node on Edge before v45 writes `source_ts_ms` 0 and no `frame_seq`.
 *
 * Pinned to CIRISServer's golden vectors (`tests/vectors/call_frames_v1.json`,
 * release/0.5.246 at 099cf247), written by an independent generator. Each
 * refusal carries the vectors' token, so both ends say the same thing.
 *
 * Audio is [CODEC_OPAQUE] at layer (0,0,0), and its payload is exactly one Opus
 * packet: 48 kHz mono, 20 ms, VBR. Video's codec is open (Eric and the CC), so
 * this passes any other codec id through and pins no video rule.
 *
 * What this does not check: a payload over the call's `frame_budget_bytes`. That
 * is the route's refusal, sent as a text event, never a cut here.
 */
object CallFrames {
    const val VERSION = 1
    /** Edge's `CODEC_OPAQUE`: audio. Base layer only. */
    const val CODEC_OPAQUE = 0xFF
    /** Edge's `CODEC_AV1_SVC`. */
    const val CODEC_AV1_SVC = 0x01
    const val MAX_PUBLISHER_BYTES = 256
    const val STREAM_ID_BYTES = 32
    private const val HEADER = 5
    /** `flags` bit 0: `frame_seq` is present. */
    private const val FLAG_FRAME_SEQ = 0x01

    fun encode(frame: OutgoingFrame): ByteArray {
        checkLayer(frame.codecId, frame.layer)
        return header(frame.codecId, frame.layer) + frame.payload
    }

    /** What the node reads. Here so the vectors can be checked in both directions. */
    fun decodeOutgoing(bytes: ByteArray): OutgoingFrame {
        val r = Reader(bytes)
        val (codec, layer) = readHeader(r)
        return OutgoingFrame(codec, layer, r.rest())
    }

    /** What the node writes. Here so the vectors can be checked in both directions. */
    fun encode(frame: ReceivedFrame): ByteArray {
        checkLayer(frame.codecId, frame.layer)
        val publisher = frame.publisher.encodeToByteArray()
        if (publisher.size > MAX_PUBLISHER_BYTES) throw CallFrameException(CallFrameError.PUBLISHER_TOO_LONG)
        require(frame.streamId.size == STREAM_ID_BYTES) { "stream_id is $STREAM_ID_BYTES bytes" }
        return header(frame.codecId, frame.layer) +
            u16(publisher.size) + publisher + frame.streamId +
            u64(frame.epoch) + u64(frame.chunkSeq) + u64(frame.sourceTsMs) +
            byteArrayOf(if (frame.frameSeq != null) FLAG_FRAME_SEQ.toByte() else 0) +
            u32(frame.frameSeq ?: 0u) + frame.payload
    }

    fun decodeReceived(bytes: ByteArray): ReceivedFrame {
        val r = Reader(bytes)
        val (codec, layer) = readHeader(r)
        val pubLen = r.u16()
        if (pubLen > MAX_PUBLISHER_BYTES) throw CallFrameException(CallFrameError.PUBLISHER_TOO_LONG)
        val publisher = try {
            r.bytes(pubLen).decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw CallFrameException(CallFrameError.TRUNCATED, "publisher is not UTF-8")
        }
        val streamId = r.bytes(STREAM_ID_BYTES)
        val epoch = r.u64()
        val chunkSeq = r.u64()
        val sourceTsMs = r.u64()
        val flags = r.u8()
        val seq = r.u32()
        val frameSeq = if (flags and FLAG_FRAME_SEQ != 0) seq else null
        return ReceivedFrame(codec, layer, publisher, streamId, epoch, chunkSeq, r.rest(), sourceTsMs, frameSeq)
    }

    private fun readHeader(r: Reader): Pair<Int, Layer> {
        if (r.remaining < HEADER) throw CallFrameException(CallFrameError.TRUNCATED)
        val ver = r.u8()
        if (ver != VERSION) throw CallFrameException(CallFrameError.UNSUPPORTED_VERSION, "version $ver")
        val codec = r.u8()
        val layer = Layer(r.u8(), r.u8(), r.u8())
        checkLayer(codec, layer)
        return codec to layer
    }

    private fun checkLayer(codec: Int, layer: Layer) {
        if (codec == CODEC_OPAQUE && layer != Layer.BASE) throw CallFrameException(CallFrameError.OPAQUE_LAYER_NOT_BASE)
    }

    private fun header(codec: Int, layer: Layer): ByteArray {
        require(codec in 0..255 && layer.spatial in 0..255 && layer.temporal in 0..255 && layer.quality in 0..255)
        return byteArrayOf(VERSION.toByte(), codec.toByte(), layer.spatial.toByte(), layer.temporal.toByte(), layer.quality.toByte())
    }

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: UInt) = ByteArray(4) { i -> (v shr (24 - 8 * i)).toByte() }
    private fun u64(v: ULong) = ByteArray(8) { i -> (v shr (56 - 8 * i)).toByte() }

    private class Reader(private val buf: ByteArray) {
        private var at = 0
        val remaining get() = buf.size - at
        fun u8(): Int {
            if (at >= buf.size) throw CallFrameException(CallFrameError.TRUNCATED)
            return buf[at++].toInt() and 0xFF
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): UInt {
            var v = 0u
            repeat(4) { v = (v shl 8) or u8().toUInt() }
            return v
        }
        fun u64(): ULong {
            var v = 0uL
            repeat(8) { v = (v shl 8) or u8().toULong() }
            return v
        }
        fun bytes(n: Int): ByteArray {
            if (at + n > buf.size) throw CallFrameException(CallFrameError.TRUNCATED)
            return buf.copyOfRange(at, at + n).also { at += n }
        }
        fun rest(): ByteArray = buf.copyOfRange(at, buf.size).also { at = buf.size }
    }
}

data class Layer(val spatial: Int, val temporal: Int, val quality: Int) {
    companion object {
        val BASE = Layer(0, 0, 0)
    }
}

/** A frame this device publishes. For audio: [CallFrames.CODEC_OPAQUE], [Layer.BASE], one Opus packet. */
class OutgoingFrame(val codecId: Int, val layer: Layer, val payload: ByteArray) {
    override fun equals(other: Any?) = other is OutgoingFrame && codecId == other.codecId &&
        layer == other.layer && payload.contentEquals(other.payload)
    override fun hashCode() = 31 * (31 * codecId + layer.hashCode()) + payload.contentHashCode()
}

/** A frame from another member, keyed by its [publisher] so each tile is that person. */
class ReceivedFrame(
    val codecId: Int,
    val layer: Layer,
    val publisher: String,
    val streamId: ByteArray,
    val epoch: ULong,
    val chunkSeq: ULong,
    val payload: ByteArray,
    /** The publisher's capture time, ms since the Unix epoch; 0 from a node on Edge before v45. */
    val sourceTsMs: ULong = 0uL,
    /** The publisher's video frame counter; null on audio, and from a node on Edge before v45. */
    val frameSeq: UInt? = null,
) {
    override fun equals(other: Any?) = other is ReceivedFrame && codecId == other.codecId &&
        layer == other.layer && publisher == other.publisher && streamId.contentEquals(other.streamId) &&
        epoch == other.epoch && chunkSeq == other.chunkSeq && payload.contentEquals(other.payload) &&
        sourceTsMs == other.sourceTsMs && frameSeq == other.frameSeq
    override fun hashCode() = listOf(codecId, layer, publisher, streamId.contentHashCode(), epoch, chunkSeq, payload.contentHashCode(), sourceTsMs, frameSeq).hashCode()
}

/** The vectors' refusal tokens. */
enum class CallFrameError(val token: String) {
    UNSUPPORTED_VERSION("unsupported_version"),
    TRUNCATED("truncated"),
    OPAQUE_LAYER_NOT_BASE("opaque_layer_not_base"),
    PUBLISHER_TOO_LONG("publisher_too_long"),
}

class CallFrameException(val error: CallFrameError, detail: String? = null) :
    IllegalArgumentException(detail?.let { "${error.token}: $it" } ?: error.token)
