package ai.ciris.mobile.shared.calls.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM Opus (Concentus) against the agreed call audio (CSD-134): a 440 Hz
 * tone through encode and decode keeps its pitch, packets sit inside the 244 B
 * frame budget, and a lost packet is concealed with a full frame.
 */
class OpusJvmTest {
    private fun dominantHz(pcm: ShortArray): Double {
        var crossings = 0
        for (i in 1 until pcm.size) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++
        return crossings / 2.0 / (pcm.size.toDouble() / CallAudioFormat.SAMPLE_RATE)
    }

    @Test fun aToneKeepsItsPitchThroughOpus() {
        val tone = ToneSource(kotlinx.coroutines.GlobalScope)
        val enc = createOpusEncoder()
        val dec = createOpusDecoder()
        val sizes = mutableListOf<Int>()
        val out = ArrayList<Short>()
        repeat(50) { // one second
            val packet = enc.encode(tone.nextFrame())
            sizes += packet.size
            if (it >= 10) out.addAll(dec.decode(packet).toList()) // after the codec settles
        }
        val hz = dominantHz(out.toShortArray())
        assertTrue(abs(hz - 440) < 15, "decoded pitch $hz Hz, sent 440 Hz")
        assertTrue(sizes.all { it in 1..244 }, "every packet inside the 244 B budget: $sizes")
        assertTrue(sizes.drop(10).average() in 30.0..120.0, "about 24–32 kbit/s at 20 ms: ${sizes.drop(10).average()} B")
    }

    @Test fun aLostPacketIsConcealedWithAFullFrame() {
        val dec = createOpusDecoder()
        val enc = createOpusEncoder()
        val tone = ToneSource(kotlinx.coroutines.GlobalScope)
        repeat(5) { dec.decode(enc.encode(tone.nextFrame())) }
        assertEquals(CallAudioFormat.FRAME_SAMPLES, dec.decode(null).size)
    }

    /** The server's vector payload is a real libopus packet: Concentus decodes it. */
    @Test fun theServersOpusVectorDecodes() {
        val hex = "789f6701e7ff835eee7a0ad259100cc21ed655634006963a32e5f4145666d96820f5a499408b4b1edf9be926314772adadfde69703d8f04f41f789bd"
        val packet = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        val pcm = createOpusDecoder().decode(packet)
        assertEquals(CallAudioFormat.FRAME_SAMPLES, pcm.size)
        assertTrue(pcm.any { it.toInt() != 0 }, "the 440 Hz tone decodes to sound")
    }
}
