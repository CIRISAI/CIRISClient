package ai.ciris.mobile.shared.platform.util

/**
 * SHA-256, pure Kotlin, no platform deps. One implementation for the two
 * places the client hashes bytes itself: the node code's key-id hash
 * ([NodeCodeCodec]) and the plaintext digest a file's bytes are checked
 * against before any renderer sees them (CC 5.3.2.5, `models/drive`).
 */
object Sha256 {

    /** The 32-byte digest of [message]. */
    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = -0x4498517b // 0xbb67ae85
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6 // 0xa54ff53a
        var h4 = 0x510e527f
        var h5 = -0x64fa9774 // 0x9b05688c
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        // Pre-processing (padding): 0x80, zeros to 56 mod 64, then the 8-byte big-endian bit length.
        val msgLen = message.size
        val bitLen = msgLen.toLong() * 8
        val padLen = ((msgLen + 9 + 63) / 64) * 64
        val data = ByteArray(padLen)
        message.copyInto(data)
        data[msgLen] = 0x80.toByte()
        for (i in 0 until 8) {
            data[padLen - 1 - i] = ((bitLen ushr (i * 8)) and 0xFF).toByte()
        }

        val w = IntArray(64)
        var chunk = 0
        while (chunk < data.size) {
            for (i in 0 until 16) {
                val j = chunk + i * 4
                w[i] = ((data[j].toInt() and 0xFF) shl 24) or
                    ((data[j + 1].toInt() and 0xFF) shl 16) or
                    ((data[j + 2].toInt() and 0xFF) shl 8) or
                    (data[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = (w[i - 15] rotr 7) xor (w[i - 15] rotr 18) xor (w[i - 15] ushr 3)
                val s1 = (w[i - 2] rotr 17) xor (w[i - 2] rotr 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var hh = h7

            for (i in 0 until 64) {
                val s1 = (e rotr 6) xor (e rotr 11) xor (e rotr 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = (a rotr 2) xor (a rotr 13) xor (a rotr 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }

            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += hh
            chunk += 64
        }

        val out = ByteArray(32)
        intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { idx, h ->
            out[idx * 4] = ((h ushr 24) and 0xFF).toByte()
            out[idx * 4 + 1] = ((h ushr 16) and 0xFF).toByte()
            out[idx * 4 + 2] = ((h ushr 8) and 0xFF).toByte()
            out[idx * 4 + 3] = (h and 0xFF).toByte()
        }
        return out
    }

    /** The digest as 64 lowercase hex characters — the form `content_digest` takes on the wire. */
    fun hex(message: ByteArray): String = digest(message).joinToString("") { b ->
        val v = b.toInt() and 0xFF
        HEX[v ushr 4].toString() + HEX[v and 0xF]
    }

    private const val HEX = "0123456789abcdef"

    private infix fun Int.rotr(n: Int): Int = (this ushr n) or (this shl (32 - n))

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
}
