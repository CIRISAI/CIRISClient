package ai.ciris.mobile.shared.calls

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The call frames codec against CIRISServer's golden vectors
 * (`tests/vectors/call_frames_v1.json`, release/0.5.246 at b08f3eca), copied
 * byte for byte. Every valid frame decodes to its fields and re-encodes to the
 * same bytes, in both directions; every refusal carries its token.
 */
class CallFramesVectorsTest {
    private val file: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/vectors/call_frames_v1.json")) { "vectors missing" }.readText(),
    ).jsonObject

    private fun cases(name: String) = file[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.layer() = this["layer"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }.let { Layer(it[0], it[1], it[2]) }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test fun theFileIsThere() {
        // A parser that finds nothing must fail loudly.
        assertTrue(cases("client_to_node").size >= 3)
        assertTrue(cases("node_to_client").size >= 3)
        assertTrue(cases("client_to_node_invalid").size >= 3)
        assertTrue(cases("node_to_client_invalid").size >= 3)
        assertEquals("opus/48000/1/20ms", file.s("audio_payload"))
    }

    @Test fun everyOutgoingFrameRoundTrips() {
        for (c in cases("client_to_node")) {
            val name = c.s("name")
            val got = try { CallFrames.decodeOutgoing(hex(c.s("hex"))) } catch (e: Exception) { fail("$name: $e") }
            assertEquals(c.s("codec_id").toInt(), got.codecId, name)
            assertEquals(c.layer(), got.layer, name)
            assertEquals(c.s("payload_hex"), got.payload.hex(), name)
            assertEquals(c.s("hex"), CallFrames.encode(got).hex(), "$name re-encodes")
        }
    }

    @Test fun everyReceivedFrameRoundTrips() {
        for (c in cases("node_to_client")) {
            val name = c.s("name")
            val got = try { CallFrames.decodeReceived(hex(c.s("hex"))) } catch (e: Exception) { fail("$name: $e") }
            assertEquals(c.s("codec_id").toInt(), got.codecId, name)
            assertEquals(c.layer(), got.layer, name)
            assertEquals(c.s("publisher"), got.publisher, name)
            assertEquals(c.s("stream_id_hex"), got.streamId.hex(), name)
            assertEquals(c.s("epoch").toULong(), got.epoch, name)
            assertEquals(c.s("chunk_seq").toULong(), got.chunkSeq, name)
            assertEquals(c.s("payload_hex"), got.payload.hex(), name)
            assertEquals(c.s("source_ts_ms").toULong(), got.sourceTsMs, name)
            assertEquals(c["frame_seq"]!!.jsonPrimitive.contentOrNull?.toUInt(), got.frameSeq, name)
            assertEquals(c.s("hex"), CallFrames.encode(got).hex(), "$name re-encodes")
        }
    }

    /** Sound carries no frame counter and video does: the vectors hold one of each, and the counter's largest value. */
    @Test fun theFrameCounterIsOnVideoAndNotOnSound() {
        val byName = cases("node_to_client").associate { it.s("name") to CallFrames.decodeReceived(hex(it.s("hex"))) }
        assertEquals(null, byName.getValue("audio_from_alice").frameSeq)
        assertEquals(0u, byName.getValue("video_h264_keyframe_from_bob").frameSeq, "0 is a count, not 'absent'")
        assertEquals(UInt.MAX_VALUE, byName.getValue("max_publisher_empty_payload").frameSeq)
        assertEquals(1794168000033uL, byName.getValue("video_h264_keyframe_from_bob").sourceTsMs)
    }

    @Test fun everyRefusalCarriesItsToken() {
        for ((set, decode) in listOf<Pair<String, (ByteArray) -> Any>>(
            "client_to_node_invalid" to CallFrames::decodeOutgoing,
            "node_to_client_invalid" to CallFrames::decodeReceived,
        )) {
            for (c in cases(set)) {
                val e = assertFailsWith<CallFrameException>(c.s("name")) { decode(hex(c.s("hex"))) }
                assertEquals(c.s("error"), e.error.token, c.s("name"))
            }
        }
    }

    /** Every event example in the vectors parses, and every `left` value is one this client names. */
    @Test fun everyEventExampleParses() {
        val events = file["events"]!!.jsonObject
        val examples = events["examples"]!!.jsonArray
        assertTrue(examples.size >= 10)
        for (e in examples) {
            assertTrue(CallEvent.parse(e.toString()) != null, "unparsed: $e")
        }
        for (l in events["left_values"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertEquals(l, LeaveReason.of(l).wire, "left value $l")
        }
        // Every stream_id in an event is the frames' raw 32 bytes as hex.
        val replaced = examples.map { it.jsonObject }.first { it.s("type") == "stream_replaced" }
        assertTrue(CallSession.streamIdBytes(replaced.s("new_stream_id")) != null)
    }

    /** The server's real H.264 vector is a keyframe by the payload format: SPS, PPS, IDR. */
    @Test fun theH264VectorIsAKeyframeAccessUnit() {
        val c = cases("client_to_node").first { it.s("name") == "video_h264_keyframe" }
        val payload = hex(c.s("payload_hex"))
        assertEquals(listOf(7, 8, 5), ai.ciris.mobile.shared.calls.media.Keyframes.h264NalTypes(payload))
        assertTrue(ai.ciris.mobile.shared.calls.media.Keyframes.isKeyframe(0x04, payload))
    }

    /** The audio pin: the vector's payload is a real Opus packet, TOC 0x78 (one 20 ms full-band frame). */
    @Test fun theAudioVectorIsOneOpusPacket() {
        val audio = cases("client_to_node").first { it.s("name") == "audio_opus_20ms" }
        val frame = CallFrames.decodeOutgoing(hex(audio.s("hex")))
        assertEquals(CallFrames.CODEC_OPAQUE, frame.codecId)
        assertEquals(Layer.BASE, frame.layer)
        assertEquals(0x78, frame.payload[0].toInt() and 0xFF)
    }

    /** The encoder refuses what the decoder refuses, so this device never sends a frame the node would drop. */
    @Test fun theEncoderRefusesAnOpaqueFrameOffTheBaseLayer() {
        val e = assertFailsWith<CallFrameException> {
            CallFrames.encode(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer(0, 1, 0), byteArrayOf(0x78)))
        }
        assertEquals(CallFrameError.OPAQUE_LAYER_NOT_BASE, e.error)
    }
}
