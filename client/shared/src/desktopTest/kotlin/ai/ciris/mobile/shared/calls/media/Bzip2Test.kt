package ai.ciris.mobile.shared.calls.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Bzip2] against files made by the reference tool (Python's `bz2`, which is
 * libbzip2). The fixtures in `resources/bzip2/` are its output; what they hold is
 * rebuilt here and compared byte for byte, and its SHA-256 is the one Python
 * printed when the fixture was made.
 */
class Bzip2Test {
    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResource("/bzip2/$name.bz2")) { "fixture $name" }.readBytes()

    /** Two phrases chosen by a 31-bit LCG: long, and it compresses to a few kB. */
    private fun phrases(n: Int): ByteArray {
        val a = "the node holds the key; ".toByteArray()
        val b = "a call is two people talking. ".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        var x = 1L
        while (out.size() < n) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            out.write(if ((x shr 16) and 1 == 1L) a else b)
        }
        return out.toByteArray().copyOf(n)
    }

    private val runs: ByteArray = buildList<Byte> {
        fun add(c: Char, n: Int) = repeat(n) { add(c.code.toByte()) }
        // Either side of the first run-length pass's edges: 4 equal bytes take a count, at most 255 in one run.
        add('a', 3); add('b', 4); add('c', 5); add('d', 258); add('e', 259); add('f', 260); add('g', 1000)
        repeat(300) { add(255.toByte()) }
        add('z', 1)
    }.toByteArray()

    private val cases: Map<String, Pair<ByteArray, String>> = mapOf(
        "empty" to (ByteArray(0) to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        "text" to ("OpenH264 Video Codec provided by Cisco Systems, Inc.\n".toByteArray() to "c389b3e7eaf4023b4564b193f97d9b859f856fc73531a2e797b25f99ef9b3d68"),
        "runs" to (runs to "a9b8f85c13eaa16fb447f88ba6887596ad17dfb58a351a45a535c25334eaeee9"),
        "allbytes" to (ByteArray(1024) { if (it < 768) it.toByte() else (255 - (it - 768)).toByte() } to "1ee8e877eef46e152eda65131661e77a8d3bd0b85053432e47f35200f7a29124"),
        // Made at level 1, where a block is 100 kB: four blocks, and the stream checksum over them.
        "blocks" to (phrases(350_000) to "617d9d0252698b1329ad72b333cfc9dfe32cc8b798e1d36a30b1dd7f41823ab5"),
        "zeros" to (ByteArray(1_000_000) to "d29751f2649b32ff572b5e0a9f541ea660a50f94ff0beedfb0b692b924cc8025"),
    )

    @Test fun decodesWhatTheReferenceToolWrote() {
        for ((name, case) in cases) {
            val (expected, sha) = case
            assertEquals(sha, OpenH264Store.sha256(expected), "$name: this test rebuilds what the fixture was made from")
            val got = Bzip2.decompress(fixture(name), 2_000_000)
            assertEquals(expected.size, got.size, "$name: length")
            assertContentEquals(expected, got, name)
        }
    }

    @Test fun moreThanTheLimitIsRefusedNotAllocated() {
        // 48 bytes that unpack to a megabyte.
        val e = assertFailsWith<Bzip2.Malformed> { Bzip2.decompress(fixture("zeros"), 999_999) }
        assertTrue("larger than" in e.message!!, e.message)
        assertEquals(1_000_000, Bzip2.decompress(fixture("zeros"), 1_000_000).size, "exactly the limit is allowed")
        assertFailsWith<Bzip2.Malformed> { Bzip2.decompress(fixture("text"), 52) }
    }

    @Test fun aFileCutShortIsMalformedAtEveryLength() {
        for (name in listOf("text", "runs", "allbytes")) {
            val whole = fixture(name)
            for (n in 0 until whole.size) {
                assertFailsWith<Bzip2.Malformed>("$name cut to $n of ${whole.size} bytes") { Bzip2.decompress(whole.copyOf(n), 2_000_000) }
            }
        }
    }

    /**
     * Every single flipped bit is either refused or changes nothing, and the ones
     * that change nothing are listed. Nothing else comes out: no other exception,
     * and never different bytes.
     */
    @Test fun aFlippedBitIsRefusedOrHarmless() {
        // The flips libbz2 1.0.8 (through Python's bz2) also decodes to the same bytes: the block-size
        // digit, the padding after the last checksum, and code lengths in a Huffman table that
        // differ without changing any code in use. libbz2 accepts one more, bit 112, the
        // "randomised" bit, which this decoder refuses by name.
        val harmless = mapOf(
            "text" to listOf(28, 31, 730, 731, 732, 733, 734, 735),
            "runs" to listOf(28, 31, 605, 606, 607),
            "allbytes" to listOf(
                28, 31, 796, 797, 798, 801, 802, 815, 816, 817, 819, 820, 835, 836, 837, 839, 840,
                1069, 1070, 1071, 1074, 1075, 1088, 1089, 1090, 1092, 1093, 1108, 1109, 1110, 1112, 1113,
                1342, 1343, 1344, 1347, 1348, 1361, 1363, 1365, 1366, 1381, 1383, 1385, 1386,
                5074, 5075, 5076, 5077, 5078, 5079,
            ),
        )
        for ((name, expectedHarmless) in harmless) {
            val whole = fixture(name)
            val expected = cases.getValue(name).first
            val decoded = mutableListOf<Int>()
            for (bit in 0 until whole.size * 8) {
                val bad = whole.copyOf()
                bad[bit / 8] = (bad[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
                try {
                    assertContentEquals(expected, Bzip2.decompress(bad, 2_000_000), "$name with bit $bit flipped decoded to something else")
                    decoded += bit
                } catch (e: Bzip2.Malformed) {
                    // refused
                }
            }
            assertEquals(expectedHarmless, decoded, "$name: the flips that still decode are the ones libbz2 accepts")
        }
    }

    @Test fun whatIsNotBzip2IsRefused() {
        assertFailsWith<Bzip2.Malformed> { Bzip2.decompress(ByteArray(0), 100) }
        assertFailsWith<Bzip2.Malformed> { Bzip2.decompress("<html>404 Not Found</html>".toByteArray(), 100) }
        assertFailsWith<Bzip2.Malformed> { Bzip2.decompress(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0), 100) } // gzip
        assertFailsWith<Bzip2.Malformed> { Bzip2.decompress("BZh0".toByteArray() + ByteArray(20), 100) }
    }

    @Test fun aRandomisedBlockIsRefusedByName() {
        // The bit after the block's 48-bit magic and 32-bit checksum: byte 14, top bit.
        val bad = fixture("text")
        bad[14] = (bad[14].toInt() or 0x80).toByte()
        val e = assertFailsWith<Bzip2.Malformed> { Bzip2.decompress(bad, 100) }
        assertTrue("randomised" in e.message!!, e.message)
    }
}
