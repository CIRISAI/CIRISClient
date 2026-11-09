package ai.ciris.mobile.shared.platform.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * FedCode DECODER for `CIRIS-V1-`, `CIRIS-V2-` and `CIRIS-V3-` (CC 2.6.8).
 *
 * The client decodes and never mints: a node serves its own code, so there is no
 * encoder here. Pinned to CIRISVerify's golden vectors
 * (`ciris-verify-core/tests/vectors/fedcode/fedcode_vectors.json`, v22.1.0), which
 * the agent and server pin too. Where that file's `known_disagreements` record the
 * Rust decoder departing from CC 2.6.8, this follows the CC: it verifies
 * `sha256(key_id)`, bounds the payload at 1,024 bytes on decode, accepts a
 * commitment on any kind, and refuses a v2 payload missing its group hint byte.
 *
 * ```text
 * v1  0x01 | sha256(key_id) | ed25519 | LP(key_id) | LP(transport) | LP(alias)            (kind node)
 * v2  0x02 | kind | sha256(key_id) | ed25519 | LP(key_id) | hint(transport) | hint(alias) | hint(group)
 * v3  v2 fields with 0x03 | node_count | node_count x [LP(node_key_id) | transport_ed25519(32)]
 *     | pqc: 0x00 absent, or 0x01 + sha256(ML-DSA-65 public key)(32); the pqc byte may be missing
 * then CRC-16-CCITT big-endian, base32 without padding.
 * ```
 */
@OptIn(ExperimentalEncodingApi::class)
object FedCodeCodec {
    const val MAX_PAYLOAD_BYTES = 1024
    const val MAX_OWNED_NODES = 16
    private const val HASH_LEN = 32
    private const val PUBKEY_LEN = 32

    /** CC 2.6.8 kind bytes. */
    enum class Kind(val byte: Int) { USER(1), AGENT(2), NODE(3), FAMILY(4), COMMUNITY(5) }

    data class OwnedNode(val keyId: String, val transportPubkeyEd25519Base64: String)

    data class Decoded(
        val version: Int,
        val kind: Kind,
        val keyId: String,
        val pubkeyEd25519Base64: String,
        val transportHint: String? = null,
        val aliasHint: String? = null,
        val groupKeyId: String? = null,
        val ownedNodes: List<OwnedNode> = emptyList(),
        /** 64 lowercase hex: sha256 of the identity's raw ML-DSA-65 public key, when the code carries it. */
        val mlDsa65PubkeySha256: String? = null,
    )

    /**
     * Decode any of the three versions. Tolerates dashes, whitespace, case and the
     * undashed QR form.
     *
     * @throws NodeCodeException.InvalidVersion when the version byte disagrees with the prefix.
     * @throws NodeCodeException.ChecksumMismatch when the CRC does not match.
     * @throws NodeCodeException.Malformed for every other refusal.
     */
    fun decode(code: String): Decoded {
        val (prefixVersion, body) = splitPrefix(code)
        if (body.isEmpty()) throw NodeCodeException.Malformed("no payload after the prefix")
        val raw = base32Decode(body)
        if (raw.size < 3) throw NodeCodeException.Malformed("too short to hold a payload")
        val payload = raw.copyOfRange(0, raw.size - 2)
        val declared = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        val computed = crc16Ccitt(payload)
        if (declared != computed) throw NodeCodeException.ChecksumMismatch(declared, computed)
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw NodeCodeException.Malformed("payload is ${payload.size} bytes; at most $MAX_PAYLOAD_BYTES")
        }
        val r = Reader(payload)
        val version = r.byte()
        if (version != prefixVersion) {
            throw NodeCodeException.InvalidVersion("version byte 0x${version.toString(16)} under CIRIS-V$prefixVersion-")
        }
        val kind = if (version == 1) Kind.NODE else {
            val b = r.byte()
            Kind.entries.firstOrNull { it.byte == b } ?: throw NodeCodeException.Malformed("unknown kind byte $b")
        }
        val keyIdHash = r.bytes(HASH_LEN)
        val pubkey = r.bytes(PUBKEY_LEN)
        val keyId = r.lengthPrefixed()
        if (!Sha256.digest(keyId.encodeToByteArray()).contentEquals(keyIdHash)) {
            throw NodeCodeException.Malformed("sha256(key_id) does not match the key_id")
        }
        val transport: String?
        val alias: String?
        var group: String? = null
        if (version == 1) {
            transport = r.lengthPrefixed().ifEmpty { null }
            alias = r.lengthPrefixed().ifEmpty { null }
        } else {
            transport = r.hint()
            alias = r.hint()
            group = r.hint()
        }
        val nodes = mutableListOf<OwnedNode>()
        var commitment: String? = null
        if (version == 3) {
            val count = r.byte()
            if (count > MAX_OWNED_NODES) throw NodeCodeException.Malformed("$count nodes; at most $MAX_OWNED_NODES")
            if (count > 0 && kind != Kind.USER) throw NodeCodeException.Malformed("only a user code may embed nodes")
            repeat(count) {
                val nodeKeyId = r.lengthPrefixed()
                val transportKey = r.bytes(PUBKEY_LEN)
                if (transportKey.contentEquals(pubkey)) {
                    throw NodeCodeException.Malformed("an embedded node's transport key is the owner's key")
                }
                nodes += OwnedNode(nodeKeyId, Base64.encode(transportKey))
            }
            // Presence-tagged and last; a code minted before the commitment ends here.
            if (!r.atEnd()) {
                when (val tag = r.byte()) {
                    0 -> Unit
                    1 -> commitment = r.bytes(HASH_LEN).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                    else -> throw NodeCodeException.Malformed("unknown commitment tag $tag")
                }
            }
        }
        if (!r.atEnd()) throw NodeCodeException.Malformed("bytes after the last field")
        return Decoded(version, kind, keyId, Base64.encode(pubkey), transport, alias, group, nodes, commitment)
    }

    /** The prefix's version and the base32 body, normalised. */
    private fun splitPrefix(code: String): Pair<Int, String> {
        val cleaned = code.filterNot { it.isWhitespace() }.uppercase()
        val match = Regex("^CIRIS-?V([0-9]+)-?").find(cleaned)
            ?: throw NodeCodeException.Malformed("not a CIRIS code")
        val version = match.groupValues[1].toIntOrNull()
        if (version == null || version !in 1..3) throw NodeCodeException.Malformed("unknown prefix CIRIS-V${match.groupValues[1]}-")
        return version to cleaned.substring(match.range.last + 1).replace("-", "")
    }

    private class Reader(private val buf: ByteArray) {
        private var at = 0
        fun atEnd() = at == buf.size
        fun byte(): Int {
            if (at >= buf.size) throw NodeCodeException.Malformed("truncated")
            return buf[at++].toInt() and 0xFF
        }
        fun bytes(n: Int): ByteArray {
            if (at + n > buf.size) throw NodeCodeException.Malformed("truncated")
            return buf.copyOfRange(at, at + n).also { at += n }
        }
        fun lengthPrefixed(): String {
            val n = byte()
            val b = bytes(n)
            return try {
                b.decodeToString(throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw NodeCodeException.Malformed("a field is not UTF-8")
            }
        }
        fun hint(): String? = if (byte() == 0) null else { at--; lengthPrefixed() }
    }

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Decode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = B32.indexOf(c)
            if (v < 0) throw NodeCodeException.Malformed("'$c' is not base32")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }

    private fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }
}
