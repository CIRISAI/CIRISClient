package ai.ciris.mobile.shared.platform.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.Decoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [QrEncoder] is believed only because a decoder it shares no code with reads
 * back what it drew. zxing-core is that decoder, on the test classpath only.
 *
 * Two readings. [decodeModules] hands zxing the exact module grid, so a wrong
 * capacity table, block split, format word or mask fails loudly at every
 * version. [scan] renders the symbol to pixels with its quiet zone and runs
 * zxing's DETECTOR over it — finder patterns, timing, alignment — which is the
 * path a phone camera takes.
 */
@OptIn(ExperimentalEncodingApi::class)
class QrEncoderTest {

    // ─── The payloads the product actually shows ─────────────────────────────

    @Test
    fun node_code_qr_form_scans_back_byte_for_byte() {
        val nc = DecodedNodeCode(
            keyId = "ciris-server",
            pubkeyEd25519Base64 = Base64.encode(ByteArray(32) { (it + 1).toByte() }),
            transportHint = "https://node.example.org",
            aliasHint = "Founder Node",
        )
        val payload = NodeCodeCodec.encodeQr(nc)
        val scanned = scan(QrEncoder.encode(payload))
        assertEquals(payload, scanned.text)
        assertEquals("M", scanned.ecLevel)
        // And the scanned text is a node code, not merely the same characters.
        assertEquals(nc.keyId, NodeCodeCodec.decode(scanned.text).keyId)
    }

    @Test
    fun contact_code_sized_string_scans_back() {
        // ~300 characters, mixed case and punctuation: byte mode, a mid version.
        val contact = buildString {
            append("ciris://contact/v1?")
            while (length < 300) append("k=Ab3-x_Yz9.Qr/PmN+Lw~")
        }.take(300)
        assertEquals(300, contact.length)
        val m = QrEncoder.encode(contact)
        val scanned = scan(m)
        assertEquals(contact, scanned.text)
        assertEquals("M", scanned.ecLevel)
    }

    @Test
    fun non_ascii_text_survives_as_utf8() {
        val text = "Añadir contacto — 連絡先を追加 · Yorùbá"
        val scanned = scan(QrEncoder.encode(text))
        assertEquals(text, scanned.text)
        // zxing would GUESS UTF-8 here and get it right; a phone scanner may
        // guess Latin-1. The ECI header is what makes it not a guess, and the
        // symbology identifier is how a decoder says it saw one: ]Q2 = ECI.
        assertEquals("]Q2", scanned.symbology)
        assertEquals("]Q1", scan(QrEncoder.encode("plain ascii")).symbology)
    }

    @Test
    fun format_information_is_an_exact_codeword_in_both_copies() {
        // zxing CORRECTS up to three bad format bits, so a decode alone cannot
        // see a wrong format word. Read both copies and check them directly:
        // unmasked (xor 0x5412) the 15 bits must be a BCH(15,5) codeword under
        // generator 0x537, and its top two data bits must say level M (00).
        for (text in listOf("CIRIS", "x".repeat(300), "7".repeat(900))) {
            val m = QrEncoder.encode(text)
            val n = m.size
            var first = 0
            var second = 0
            fun bit(v: Boolean) = if (v) 1 else 0
            // Bit i of the word, in the placement ISO 18004 figure 25 gives.
            for (i in 0 until 15) {
                val a = when {
                    i <= 5 -> m[8, i]
                    i == 6 -> m[8, 7]
                    i == 7 -> m[8, 8]
                    i == 8 -> m[7, 8]
                    else -> m[14 - i, 8]
                }
                val b = if (i < 8) m[n - 1 - i, 8] else m[8, n - 15 + i]
                first = first or (bit(a) shl i)
                second = second or (bit(b) shl i)
            }
            assertEquals(first, second, "the two format copies disagree")
            val unmasked = first xor 0x5412
            var rem = unmasked
            for (shift in 14 downTo 10) {
                if ((rem ushr shift) and 1 == 1) rem = rem xor (0x537 shl (shift - 10))
            }
            assertEquals(0, rem, "format word ${first.toString(2)} is not a BCH codeword")
            assertEquals(0, unmasked ushr 13, "format word does not say level M")
            assertTrue(m[8, n - 8], "the dark module is light")
        }
    }

    @Test
    fun default_level_is_m() {
        assertEquals("M", scan(QrEncoder.encode("CIRIS")).ecLevel)
    }

    // ─── Every version, every level, through the exact grid ──────────────────

    @Test
    fun every_version_and_level_decodes_when_filled_to_capacity() {
        for (ecc in QrEncoder.Ecc.entries) {
            for (version in QrEncoder.MIN_VERSION..QrEncoder.MAX_VERSION) {
                // Byte mode, filled to the last whole byte this version holds:
                // exercises the block split and interleave with no padding to hide in.
                val countBits = if (version <= 9) 8 else 16
                val n = (QrEncoder.dataCodewords(version, ecc) * 8 - 4 - countBits) / 8
                val text = String(CharArray(n) { 'a' + (it * 7 + version) % 26 })
                val m = QrEncoder.encode(text, ecc, minVersion = version)
                assertEquals(version, m.version, "v$version $ecc chose a different version")
                assertEquals(version * 4 + 17, m.size)
                assertEquals(text, decodeModules(m), "v$version $ecc")
            }
        }
    }

    @Test
    fun the_smallest_version_that_holds_the_text_is_chosen() {
        // ISO 18004 table 7, version 1-M: 14 bytes, 20 alphanumerics, 34 digits.
        assertEquals(1, QrEncoder.encode("a".repeat(14)).version)
        assertEquals(2, QrEncoder.encode("a".repeat(15)).version)
        assertEquals(1, QrEncoder.encode("A".repeat(20)).version)
        assertEquals(2, QrEncoder.encode("A".repeat(21)).version)
        assertEquals(1, QrEncoder.encode("7".repeat(34)).version)
        assertEquals(2, QrEncoder.encode("7".repeat(35)).version)
    }

    @Test
    fun numeric_and_alphanumeric_modes_decode() {
        for (t in listOf("0", "12", "123", "0123456789012", "HELLO WORLD", "A", "\$%*+-./: 09AZ")) {
            assertEquals(t, scan(QrEncoder.encode(t)).text, t)
        }
    }

    @Test
    fun too_long_is_refused_not_truncated() {
        // Version 40-M holds 2,331 bytes.
        val fits = "x".repeat(2331)
        assertEquals(40, QrEncoder.encode(fits).version)
        assertEquals(fits, decodeModules(QrEncoder.encode(fits)))
        assertFailsWith<QrTooLongException> { QrEncoder.encode("x".repeat(2332)) }
        assertNull(QrEncoder.encodeOrNull("x".repeat(2332)))
    }

    // ─── zxing plumbing ──────────────────────────────────────────────────────

    private data class Scanned(val text: String, val ecLevel: String?, val symbology: String?)

    private fun decodeModules(m: QrMatrix): String {
        val bits = BitMatrix(m.size)
        for (y in 0 until m.size) for (x in 0 until m.size) if (m[x, y]) bits.set(x, y)
        return Decoder().decode(bits).text
    }

    /** Render at [scale] px per module with the four-module quiet zone, then detect and decode. */
    private fun scan(m: QrMatrix, scale: Int = 4): Scanned {
        val quiet = 4
        val side = (m.size + quiet * 2) * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (!m[x, y]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + quiet) * scale + dy) * side + (x + quiet) * scale + dx] = 0xFF000000.toInt()
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        val result = QRCodeReader().decode(
            bitmap,
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
        )
        val meta = result.resultMetadata
        return Scanned(
            result.text,
            meta?.get(ResultMetadataType.ERROR_CORRECTION_LEVEL) as String?,
            meta?.get(ResultMetadataType.SYMBOLOGY_IDENTIFIER) as String?,
        )
    }
}
