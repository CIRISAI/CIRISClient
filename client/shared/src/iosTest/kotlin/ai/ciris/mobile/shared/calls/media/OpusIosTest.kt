package ai.ciris.mobile.shared.calls.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AudioToolbox's Opus, run. Until this file the iOS call audio codec was compiled
 * by CI and run by nothing.
 *
 * Two questions:
 *  - Does the 20 ms codec a call uses today carry sound, and decode the server's
 *    packet? (CSD-134)
 *  - Does AudioToolbox make and take 40 and 60 ms packets? CIRISEdge 45.1 lets a
 *    sender on a thin uplink use them, and a member that cannot DECODE them holds
 *    the whole room at 20 ms, so the answer is what iOS declares to the room.
 *
 * This runs on the simulator, whose AudioToolbox is the Mac's. It says nothing
 * certain about a phone's.
 */
class OpusIosTest {
    private class Tone(private val hz: Double = 440.0, private val amplitude: Int = 8_000) {
        private var phase = 0.0

        fun next(samples: Int): ShortArray {
            val step = 2 * PI * hz / CallAudioFormat.SAMPLE_RATE
            return ShortArray(samples) {
                val v = (sin(phase) * amplitude).roundToInt().toShort()
                phase += step
                if (phase > 2 * PI) phase -= 2 * PI
                v
            }
        }
    }

    private fun dominantHz(pcm: List<Short>): Double {
        if (pcm.size < 2) return 0.0
        var crossings = 0
        for (i in 1 until pcm.size) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++
        return crossings / 2.0 / (pcm.size.toDouble() / CallAudioFormat.SAMPLE_RATE)
    }

    private fun unhex(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The milliseconds of sound an Opus packet says it holds (RFC 6716, section 3.1), or null when it is not a packet. */
    private fun packetMs(packet: ByteArray): Double? {
        if (packet.isEmpty()) return null
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameMs = when {
            config < 12 -> doubleArrayOf(10.0, 20.0, 40.0, 60.0)[config % 4] // SILK
            config < 16 -> doubleArrayOf(10.0, 20.0)[config % 2] // hybrid
            else -> doubleArrayOf(2.5, 5.0, 10.0, 20.0)[config % 4] // CELT
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) return null else packet[1].toInt() and 0x3F
        }
        return frames * frameMs
    }

    @Test fun aPacketSaysHowLongItIs() {
        assertEquals(20.0, packetMs(unhex(serverPacket)))
        assertEquals(40.0, packetMs(unhex(concentus40.first()))) // two frames of unlike size
        assertEquals(40.0, packetMs(unhex(concentus40.last()))) // two frames of like size
        assertEquals(60.0, packetMs(unhex(concentus60.first()))) // a counted three
    }

    /** A second of a 440 Hz tone through AudioToolbox's encoder and back, one packet of [ms] at a time. */
    private fun roundTrip(ms: Int) {
        val samples = CallAudioFormat.SAMPLE_RATE * ms / 1000
        val enc = assertNotNull(AudioToolboxOpusEncoder.open(samples), "AudioToolbox makes no $ms ms Opus encoder")
        val dec = assertNotNull(AudioToolboxOpusDecoder.open(samples), "AudioToolbox makes no $ms ms Opus decoder")
        val tone = Tone()
        val calls = 1000 / ms + 5
        val sizes = mutableListOf<Int>()
        val saidMs = mutableListOf<Double?>()
        val decodedLengths = mutableListOf<Int>()
        var silent = 0
        val heard = ArrayList<Short>()
        repeat(calls) {
            enc.encode(tone.next(samples)) { packet ->
                sizes += packet.size
                saidMs += packetMs(packet)
                dec.decode(packet) { pcm ->
                    decodedLengths += pcm.size
                    if (pcm.all { it.toInt() == 0 }) silent++
                    if (sizes.size > 5) heard.addAll(pcm.toList()) // after the codec settles
                }
            }
        }
        enc.close()
        dec.close()
        val facts = "$ms ms: ${sizes.size} packets from $calls calls; sizes $sizes; each says $saidMs ms; " +
            "decoded lengths $decodedLengths; $silent decoded to nothing"
        assertTrue(sizes.size >= calls - 2, "a packet for each call but the first two at most. $facts")
        assertTrue(saidMs.all { it == ms.toDouble() }, "every packet says it is $ms ms. $facts")
        assertTrue(decodedLengths.all { it == samples }, "every packet decodes to $samples samples. $facts")
        val hz = dominantHz(heard)
        assertTrue(abs(hz - 440) < 15, "decoded pitch $hz Hz, sent 440 Hz. $facts")
        // CIRISEdge's smallest leg carries a 241 B packet; a larger one is refused there.
        assertTrue(sizes.all { it in 1..241 }, "every packet is at most 241 B. $facts")
    }

    @Test fun twentyMillisecondsCarriesATone() = roundTrip(20)

    @Test fun fortyMillisecondsCarriesATone() = roundTrip(40)

    @Test fun sixtyMillisecondsCarriesATone() = roundTrip(60)

    @Test fun theServersTwentyMillisecondPacketDecodes() {
        val dec = assertNotNull(AudioToolboxOpusDecoder.open(), "AudioToolbox makes no Opus decoder")
        var pcm = ShortArray(0)
        dec.decode(unhex(serverPacket)) { pcm = it }
        dec.close()
        assertEquals(CallAudioFormat.FRAME_SAMPLES, pcm.size)
        assertTrue(pcm.any { it.toInt() != 0 }, "the server's 440 Hz packet decodes to sound")
    }

    /** Eight packets in a row from the encoder Android and desktop use; the pitch is read from the last four. */
    private fun decodesTheOthers(ms: Int, packets: List<String>) {
        val samples = CallAudioFormat.SAMPLE_RATE * ms / 1000
        val dec = assertNotNull(AudioToolboxOpusDecoder.open(samples), "AudioToolbox makes no $ms ms Opus decoder")
        val heard = ArrayList<Short>()
        var silent = 0
        packets.forEachIndexed { i, hex ->
            dec.decode(unhex(hex)) { pcm ->
                if (pcm.all { it.toInt() == 0 }) silent++
                if (i >= 4) heard.addAll(pcm.toList())
            }
        }
        dec.close()
        val hz = dominantHz(heard)
        assertTrue(silent == 0 && abs(hz - 440) < 15, "$ms ms packets from Concentus: decoded pitch $hz Hz of 440, $silent of ${packets.size} decoded to nothing")
    }

    @Test fun fortyMillisecondPacketsFromAndroidAndDesktopDecode() = decodesTheOthers(40, concentus40)

    @Test fun sixtyMillisecondPacketsFromAndroidAndDesktopDecode() = decodesTheOthers(60, concentus60)

    /** A real libopus packet, the one in CIRISServer's `call_frames_v1.json` and in `OpusJvmTest`. */
    private val serverPacket =
        "789f6701e7ff835eee7a0ad259100cc21ed655634006963a32e5f4145666d96820f5a499408b4b1edf9be926314772adadfde69703d8f04f41f789bd"

    // Concentus 1.0.2, configured as `Opus.jvm.kt` configures it (48 kHz mono, VOIP, VBR at
    // 28 kbit/s, no DTX, no FEC), given the 440 Hz tone above from phase 0, eight packets in a row.
    // Concentus decodes its own last four of each at 437.5 Hz by the same zero-crossing count.
    private val concentus40 = listOf(
        "7a5d822eb76a3932900000060a2ad7967ee3638c3f33b1e2db8ce49fb64d0e6acf249c86b1879dd193652b1b32ef9f36c1b66afd40d5d3b68bf8aaed514605158d8577a60c2261c879226eff3f1cac9d0d1b5325184f5db2f76c8ccacc0e03a48881e7fc954d4a3935d3ee68812270052c152ca61b90215db3f0669c435ba24b3395f1858366d0179cf28b9fe99e4062631ba1c36f306d42860d86f27e",
        "7a319c90b9fc3b89cdd020d4ea24a39fb85f70f5b952bf64f7a780c5ac94d883740af69d31182350a7042d6fe6f644b9365d5f9c93df73236c82d0ff8b9cbed61bad8d091dc5a25ab6c2ed32b60681376cb2dc7aefce153be8002e20afaed7d38143a19a2c1fd64bce56",
        "7a439c93df732dd22d996d3e5eea92286ef8a6e2849e1fd58cc26847608cf3a91ee2481653ba2f2f58e34496d8fdb9e0a681889bd993eeadc99d05bc1832763f42c2b026609c90b9fc3b89cdd020d4e9d38abc2eabf94472eef2f44a504c82d95d70a80f4b6ad2c7c2b81c70f578649ddde8935d1d908f0b3e57",
        "7a3e9c93df732dd22d996e7a413ec3eb98b6790f691690165f6e8ed90653302341def7837386b7c89a8d95192e7dd721794fc20a1e051301a1a9aa9931abf4479c90b9fc3b89cdd020ca0f44f6fb404371c546ad0e10710fb79e7e5fe595f7aa74176291202d846516a3fa06044a0bffe6f65278aa6267",
        "7a3d9c93df73258b7fffc890281878e2fc86157b41b3beae485de3d2811e4b0d0335ef26883e694d5befe436de7b6b58edaffda3cd8934ab09e2c9b27e3e579c93df732dd22d996da83036ea2831fb9c8065b607a1a65791935933912137a02adca03ed01bf9cbb1d252d31b89e895279887d1ad8c99f13bf9a54d2d57",
        "7a3c9c90b9fc3b89cdd020dbb477adfd93c109222388bd6fe13d3ea68c1dc615e57c90cd4f0078c8ef80162bda114d9c7cd75e578d7d540350631598b94f9c93df732dd22d996d3d7f690738164590ccb274b207885b69c8bb4489d91b8080104007a87a4af8ffed42653797b7bdb807be7743a4d75e",
        "7a399c90b9fc3b89cdd020d0ba4737cd400522697619f8dffb4f81ea16e42361a68f0389f256f30abb4df5036cb2718bd56f050b381e1e98474a569c93df73258b7fffc95be3596d5a509c29e674df5bada38888bd72a0e730e0589093fd9f1e601248b4c2ced19a9983b8ce3c7d806683dead39104557",
        "799c93df732dd22d996e03c29414564922da03303518849dc81644145a604430e1c8647f83c41ab6473b767767e4db201d6a5a0c453e7ac430d130579c90b9fc3b89cdd020cb6759eed8a9f56e0ab81bcde57276795e9103176253b6a224021a76c7a9bc79a8f35cd5f297e39ec23c09b55c89ac0de956",
    )

    private val concentus60 = listOf(
        "7b835d3e822eb76a3932900000060a2ad7967ee3638c3f33b1e2db8ce49fb64d0e6acf249c86b1879dd193652b1b32ef9f36c1b66afd40d5d3b68bf8aaed514605158d8577a60c2261c879226eff3f1cac9d0d1b5325184f5db2f76c8ccacc0e03a48881e7fc954d4a3935d3ee68812270052c152ca61b90215db3f0669c435ba24b3395f1858366d0179cf28b9fe99e4062631ba1c36f306d42860d86f27e9c90b9fc3b89cdd020d4ea24a39fb85f70f5b952bf64f7a780c5ac94d883740af69d31182350a7042d6fe6f644b9365d5f",
        "7b8337439c93df73236c82d0ff8b9cbed61bad8d091dc5a25ab6c2ed32b60681376cb2dc7aefce153be8002e20afaed7d38143a19a2c1fd64bce569c93df732dd22d996d3e5eea92286ef8a6e2849e1fd58cc26847608cf3a91ee2481653ba2f2f58e34496d8fdb9e0a681889bd993eeadc99d05bc1832763f42c2b026609c90b9fc3b89cdd020d4e9d38abc2eabf94472eef2f44a504c82d95d70a80f4b6ad2c7c2b81c70f578649ddde8935d1d908f0b3e57",
        "7b833e379c93df732dd22d996e7a413ec3eb98b6790f691690165f6e8ed90653302341def7837386b7c89a8d95192e7dd721794fc20a1e051301a1a9aa9931abf4479c90b9fc3b89cdd020ca0f44f6fb404371c546ad0e10710fb79e7e5fe595f7aa74176291202d846516a3fa06044a0bffe6f65278aa62679c93df73258b7fffc890281878e2fc86157b41b3beae485de3d2811e4b0d0335ef26883e694d5befe436de7b6b58edaffda3cd8934ab09e2c9b27e3e57",
        "7b833e3c9c93df732dd22d996da83036ea2831fb9c8065b607a1a65791935933912137a02adca03ed01bf9cbb1d252d31b89e895279887d1ad8c99f13bf9a54d2d579c90b9fc3b89cdd020dbb477adfd93c109222388bd6fe13d3ea68c1dc615e57c90cd4f0078c8ef80162bda114d9c7cd75e578d7d540350631598b94f9c93df732dd22d996d3d7f690738164590ccb274b207885b69c8bb4489d91b8080104007a87a4af8ffed42653797b7bdb807be7743a4d75e",
        "7b83393c9c90b9fc3b89cdd020d0ba4737cd400522697619f8dffb4f81ea16e42361a68f0389f256f30abb4df5036cb2718bd56f050b381e1e98474a569c93df73258b7fffc95be3596d5a509c29e674df5bada38888bd72a0e730e0589093fd9f1e601248b4c2ced19a9983b8ce3c7d806683dead391045579c93df732dd22d996e03c29414564922da03303518849dc81644145a604430e1c8647f83c41ab6473b767767e4db201d6a5a0c453e7ac430d13057",
        "7b833b3d9c90b9fc3b89cdd020cb6759eed8a9f56e0ab81bcde57276795e9103176253b6a224021a76c7a9bc79a8f35cd5f297e39ec23c09b55c89ac0de9569c93df732dd22d996d3cce6f34d0a21946da62ed226af4b8e5061a3f304855f9dece0b38e7c89e0a5b0623da4037d7f10675057ffa288b5b43bdd6074f9c90b9fc3b89cdd020d63aaafa116484ea94158b7e034be77b0003eacc7d95eccb1c5afcc8aefd486a176729cdc44c120e3100b6f9202d56",
        "7b83363a9c90b9fc3b89cdd020da572c0551711e9f81185c6bc2c936f15041a949bcf26b75fcec66ce0bc138e35c174bde49b3a1548c5b5073609c93df732dd22d996d3d7ab1d12eb8b5586cbcb6afea24051bccaa4784e47b50c5deb866c98c859f2143031b74c8373933bfd85e9335b002c64d9c90b9fc3b89cdd020d0a5d753ca27aa4dd9d0304f6ebee691da2fa4b0a90c77ee5efb4f351f7261c732ecb409ad3f10c94a37eff0f08641ff796a5f",
        "7b8334399c93df73236c82d10068fe761448c49a8feea7517b830de594dc9fa7c536bab70bcdec56da4a64dbda6a366884671a87a0a485579c93df732dd22d996e6d93139befd0ab8a45e389f18ed5d2deaecfff78b051144b007cd2fc8183c29ce34b4c57dbaf45d63d995389efb587559c90b9fc3b89cd98578096be6b9e242399eb5ecb70aed12d0a5c3eeaea19447eb01c3a0e5ce199e6ae0772b39c8be445789bfb5343ad83970632328d333cfee482b5cf5247",
    )
}
