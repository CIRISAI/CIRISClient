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
 *                | epoch u64 | chunk_seq u64 | payload…
 * ```
 *
 * Pinned to CIRISServer's golden vectors (`tests/vectors/call_frames_v1.json`,
 * release/0.5.246 at 92d5d864), written by an independent generator. Each
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
            u64(frame.epoch) + u64(frame.chunkSeq) + frame.payload
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
        return ReceivedFrame(codec, layer, publisher, streamId, epoch, chunkSeq, r.rest())
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
    private fun u64(v: ULong) = ByteArray(8) { i -> (v shr (56 - 8 * i)).toByte() }

    private class Reader(private val buf: ByteArray) {
        private var at = 0
        val remaining get() = buf.size - at
        fun u8(): Int {
            if (at >= buf.size) throw CallFrameException(CallFrameError.TRUNCATED)
            return buf[at++].toInt() and 0xFF
        }
        fun u16(): Int = (u8() shl 8) or u8()
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
) {
    override fun equals(other: Any?) = other is ReceivedFrame && codecId == other.codecId &&
        layer == other.layer && publisher == other.publisher && streamId.contentEquals(other.streamId) &&
        epoch == other.epoch && chunkSeq == other.chunkSeq && payload.contentEquals(other.payload)
    override fun hashCode() = listOf(codecId, layer, publisher, streamId.contentHashCode(), epoch, chunkSeq, payload.contentHashCode()).hashCode()
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
