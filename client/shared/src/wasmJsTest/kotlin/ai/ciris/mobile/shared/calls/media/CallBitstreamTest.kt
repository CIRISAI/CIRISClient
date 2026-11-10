package ai.ciris.mobile.shared.calls.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallBitstreamTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val sps = b(0x67, 0x42, 0xE0, 0x1F, 0xAA)
    private val pps = b(0x68, 0xCE, 0x3C, 0x80)
    private val idr = b(0x65, 0x88, 0x84, 0x00, 0x10)
    private val slice = b(0x41, 0x9A, 0x02)

    @Test
    fun splitsThreeAndFourByteStartCodes() {
        val au = b(0, 0, 0, 1) + sps + b(0, 0, 1) + pps + b(0, 0, 0, 1) + idr
        val nals = AnnexB.split(au)
        assertEquals(listOf(7, 8, 5), nals.map(AnnexB::type))
        assertContentEquals(idr, nals[2])
        assertTrue(AnnexB.isKeyframe(au))
        assertFalse(AnnexB.isKeyframe(AnnexB.join(listOf(slice))))
    }

    @Test
    fun keyframeWithoutParameterSetsGetsTheLastOnesSeen() {
        val p = H264Packetizer()
        val first = p.accessUnit(AnnexB.join(listOf(sps, pps, idr)), keyframe = true)
        assertEquals(listOf(7, 8, 5), AnnexB.split(first).map(AnnexB::type))
        assertEquals(listOf(1), AnnexB.split(p.accessUnit(AnnexB.join(listOf(slice)), keyframe = false)).map(AnnexB::type))
        val bare = p.accessUnit(AnnexB.join(listOf(idr)), keyframe = true)
        assertEquals(listOf(7, 8, 5), AnnexB.split(bare).map(AnnexB::type))
    }

    @Test
    fun avcCDescriptionSuppliesParameterSetsAndUnwrapsLengthPrefixed() {
        val avcC = b(1, 0x42, 0xE0, 0x1F, 0xFF, 0xE1, 0, sps.size) + sps + b(1, 0, pps.size) + pps
        val c = assertNotNull(AnnexB.parseAvcC(avcC))
        assertEquals(4, c.lengthSize)
        val p = H264Packetizer()
        p.onDescription(avcC)
        val lengthPrefixed = b(0, 0, 0, idr.size) + idr
        val out = p.accessUnit(lengthPrefixed, keyframe = true)
        assertEquals(listOf(7, 8, 5), AnnexB.split(out).map(AnnexB::type))
        assertContentEquals(idr, AnnexB.split(out)[2])
    }

    private val seqHeader = b(0x0A, 0x03, 0x00, 0x00, 0x00) // type 1, has_size, 3-byte payload
    private val frameNoSize = b(0x30, 0x11, 0x22, 0x33) // type 6 (frame), no size field: runs to the end

    @Test
    fun obusGainSizeFieldsAndKeyframesGainTheSequenceHeader() {
        val p = Av1Packetizer()
        val key = p.temporalUnit(seqHeader + frameNoSize, keyframe = true)
        val units = assertNotNull(Obu.parse(key))
        assertEquals(listOf(1, 6), units.map { it.type })
        assertTrue(key.size == seqHeader.size + frameNoSize.size + 1) // one leb128 byte added
        assertTrue(units.all { (it.header[0].toInt() and 0x02) != 0 })
        assertTrue(Obu.isKeyframe(key))

        val bareKey = p.temporalUnit(frameNoSize, keyframe = true)
        assertEquals(listOf(1, 6), assertNotNull(Obu.parse(bareKey)).map { it.type })
        val delta = p.temporalUnit(frameNoSize, keyframe = false)
        assertEquals(listOf(6), assertNotNull(Obu.parse(delta)).map { it.type })
        assertFalse(Obu.isKeyframe(delta))
    }

    @Test
    fun av1CDescriptionSuppliesTheSequenceHeader() {
        val av1C = b(0x81, 0x00, 0x0C, 0x00) + seqHeader
        val p = Av1Packetizer()
        p.onDescription(av1C)
        val out = p.temporalUnit(frameNoSize, keyframe = true)
        assertEquals(listOf(1, 6), assertNotNull(Obu.parse(out)).map { it.type })
    }

    @Test
    fun leb128() {
        assertContentEquals(b(0x00), Obu.leb128(0))
        assertContentEquals(b(0x7F), Obu.leb128(127))
        assertContentEquals(b(0x80, 0x01), Obu.leb128(128))
    }

    @Test
    fun bytesAndSamplesSurviveTheTripThroughJs() {
        for (n in listOf(0, 1, 2, 3, 255, 20_001)) {
            val bytes = ByteArray(n) { (it * 37 + 11).toByte() }
            assertContentEquals(bytes, bytes.toJsBytes().jsToByteArray(), "n=$n")
        }
        // Every 16-bit pattern, lone surrogates included.
        val all = ByteArray(131_072) { (if (it % 2 == 0) it / 2 else it / 2 shr 8).toByte() }
        assertContentEquals(all, all.toJsBytes().jsToByteArray())
        val pcm = ShortArray(960) { (it * 71 - 32768).toShort() }
        assertContentEquals(pcm, pcm.toJsInt16().jsToShortArray())
    }
}
