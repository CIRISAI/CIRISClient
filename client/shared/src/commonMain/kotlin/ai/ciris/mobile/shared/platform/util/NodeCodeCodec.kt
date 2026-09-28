package ai.ciris.mobile.shared.platform.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * NodeCode binary codec — pure-Kotlin port of the agent's authoritative codec
 * (`ciris_engine/logic/utils/node_code_codec.py`) and the Rust port
 * (`CIRISServer/src/nodecode.rs`). Byte-identical with both: a `CIRIS-V1-...`
 * code shared from any one of agent / node / this client decodes to the same
 * identity here, and a code encoded here decodes there.
 *
 * A NodeCode is the QR-able federation-key bootstrap handle (CEG §0.10): a
 * compact, user-shareable encoding of a node's identity (`key_id` + Ed25519
 * pubkey + optional transport/alias hints) plus a checksum. Decoding it locally
 * is the secure "find the right node" primitive: the receiver can derive a
 * transport hint, connect, then **identity-pin** by confirming the node serves
 * back a code with the same `key_id` + pubkey.
 *
 * ## Wire format — byte-identical with the agent / Rust
 *
 * ```text
 *   offset  size  field
 *   ------  ----  -----
 *      0      1   version (currently 0x01)
 *      1     32   key_id_hash  = SHA-256(key_id utf-8)
 *     33     32   pubkey_ed25519 (raw 32 bytes)
 *     65      1   key_id_len (0-255)
 *     66      N   key_id (UTF-8)
 *    66+N     1   transport_hint_len
 *    ...      M   transport_hint (UTF-8)
 *    ...      1   alias_hint_len
 *    ...      K   alias_hint (UTF-8)
 *      …      2   CRC-16-CCITT (big-endian, over all preceding bytes)
 * ```
 *
 * Encoding: RFC 4648 base32 (`A-Z2-7`) WITHOUT padding, uppercased, prefixed
 * `CIRIS-V1-`, grouped into 4-char chunks separated by dashes for display
 * ([encode]) or ungrouped for the QR form ([encodeQr]). [decode] tolerates
 * dashes, whitespace, case, and the undashed `CIRISV1...` QR form. CRC-16-CCITT
 * params: poly `0x1021`, init `0xFFFF`, no final xor, bytes consumed MSB-first.
 *
 * Pure Kotlin — no platform deps. SHA-256, CRC-16, and base32 are all
 * implemented inline so the codec compiles identically on every KMP target.
 */
object NodeCodeCodec {

    /** Current NodeCode binary-format version. */
    const val VERSION: Int = 0x01

    private const val PREFIX = "CIRIS-V1-"
    private const val GROUP_SIZE = 4
    private const val MAX_HINT_BYTES = 255
    private const val PUBKEY_RAW_LEN = 32
    private const val KEY_ID_HASH_LEN = 32
    private const val CRC_POLY = 0x1021
    private const val CRC_INIT = 0xFFFF

    private const val B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Encode a [DecodedNodeCode] as a `CIRIS-V1-...` dashed display string.
     *
     * @throws NodeCodeException.Malformed if a field is over-length or the
     *   pubkey is not valid base64 / wrong size.
     */
    fun encode(nc: DecodedNodeCode): String = PREFIX + group(encodeBody(nc))

    /**
     * Encode as the QR-friendly (no-dashes) form. Same content as [encode] but
     * with separator dashes omitted; the `CIRIS-V1-` marker stays.
     */
    fun encodeQr(nc: DecodedNodeCode): String = PREFIX + encodeBody(nc)

    /**
     * Decode a `CIRIS-V1-...` string back into a [DecodedNodeCode].
     *
     * Accepts dashed (display) or undashed (QR) forms; whitespace, dashes, and
     * case are tolerated. Validates the version + CRC-16-CCITT and the
     * `key_id`/hash consistency.
     *
     * @throws NodeCodeException.InvalidVersion on a version mismatch.
     * @throws NodeCodeException.ChecksumMismatch on a CRC mismatch.
     * @throws NodeCodeException.Malformed on any structural problem.
     */
    fun decode(code: String): DecodedNodeCode {
        // Normalize: drop all whitespace, uppercase.
        val cleaned = buildString {
            for (c in code) if (!c.isWhitespace()) append(c)
        }.uppercase()

        val body = stripDashedPrefix(cleaned)
            ?: stripUndashedPrefix(cleaned)
            ?: throw NodeCodeException.Malformed(
                "NodeCode does not start with \"$PREFIX\" (or its undashed equivalent)"
            )

        val stripped = body.filter { it != '-' }
        if (stripped.isEmpty()) {
            throw NodeCodeException.Malformed("NodeCode has no payload after prefix")
        }

        val raw = b32NoPadDecode(stripped)

        val minSize = 1 + KEY_ID_HASH_LEN + PUBKEY_RAW_LEN + 1 + 1 + 1 + 2
        if (raw.size < minSize) {
            throw NodeCodeException.Malformed(
                "NodeCode payload too short (${raw.size} bytes; need at least $minSize)"
            )
        }

        val payload = raw.copyOfRange(0, raw.size - 2)
        val declared = ((raw[raw.size - 2].toInt() and 0xFF) shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        val computed = crc16Ccitt(payload)
        if (declared != computed) {
            throw NodeCodeException.ChecksumMismatch(declared, computed)
        }

        val version = payload[0].toInt() and 0xFF
        if (version != VERSION) {
            throw NodeCodeException.InvalidVersion(
                "binary version 0x${version.toString(16)}; this build supports 0x${VERSION.toString(16)}"
            )
        }

        var offset = 1
        val keyIdHash = payload.copyOfRange(offset, offset + KEY_ID_HASH_LEN)
        offset += KEY_ID_HASH_LEN

        val pubkeyRaw = payload.copyOfRange(offset, offset + PUBKEY_RAW_LEN)
        offset += PUBKEY_RAW_LEN

        val (keyId, o1) = readLengthPrefixed(payload, offset); offset = o1
        val (transportHint, o2) = readLengthPrefixed(payload, offset); offset = o2
        val (aliasHint, o3) = readLengthPrefixed(payload, offset); offset = o3

        if (offset != payload.size) {
            throw NodeCodeException.Malformed(
                "NodeCode has trailing garbage after parsed fields (${payload.size - offset} extra bytes)"
            )
        }
        if (keyId.isEmpty()) {
            throw NodeCodeException.Malformed("Decoded key_id is empty — NodeCode is malformed")
        }
        if (!sha256(keyId.encodeToByteArray()).contentEquals(keyIdHash)) {
            throw NodeCodeException.Malformed(
                "Decoded key_id hash does not match key_id string — NodeCode is corrupt"
            )
        }

        return DecodedNodeCode(
            keyId = keyId,
            pubkeyEd25519Base64 = b64Encode(pubkeyRaw),
            transportHint = transportHint.ifEmpty { null },
            aliasHint = aliasHint.ifEmpty { null },
        )
    }

    // ─── Encode internals ────────────────────────────────────────────────────

    private fun encodeBody(nc: DecodedNodeCode): String {
        val payload = buildPayload(nc)
        val crc = crc16Ccitt(payload)
        val full = payload + byteArrayOf(((crc ushr 8) and 0xFF).toByte(), (crc and 0xFF).toByte())
        return b32NoPadEncode(full)
    }

    private fun buildPayload(nc: DecodedNodeCode): ByteArray {
        val keyIdBytes = nc.keyId.encodeToByteArray()
        if (keyIdBytes.size > MAX_HINT_BYTES) {
            throw NodeCodeException.Malformed(
                "key_id exceeds $MAX_HINT_BYTES-byte limit (${keyIdBytes.size} bytes)"
            )
        }
        val pubkeyRaw = try {
            b64Decode(nc.pubkeyEd25519Base64)
        } catch (e: Exception) {
            throw NodeCodeException.Malformed("pubkey_ed25519_base64 is not valid base64: ${e.message}")
        }
        if (pubkeyRaw.size != PUBKEY_RAW_LEN) {
            throw NodeCodeException.Malformed(
                "pubkey_ed25519 must be $PUBKEY_RAW_LEN raw bytes, got ${pubkeyRaw.size}"
            )
        }
        val out = ArrayList<Byte>()
        out.add(VERSION.toByte())
        sha256(keyIdBytes).forEach { out.add(it) }
        pubkeyRaw.forEach { out.add(it) }
        out.add(keyIdBytes.size.toByte())
        keyIdBytes.forEach { out.add(it) }
        encodeHint(nc.transportHint).forEach { out.add(it) }
        encodeHint(nc.aliasHint).forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun encodeHint(value: String?): ByteArray {
        if (value.isNullOrEmpty()) return byteArrayOf(0)
        val raw = value.encodeToByteArray()
        if (raw.size > MAX_HINT_BYTES) {
            throw NodeCodeException.Malformed(
                "Hint field exceeds $MAX_HINT_BYTES-byte limit (${raw.size} bytes)"
            )
        }
        return byteArrayOf(raw.size.toByte()) + raw
    }

    private fun readLengthPrefixed(buf: ByteArray, offset: Int): Pair<String, Int> {
        if (offset >= buf.size) {
            throw NodeCodeException.Malformed("Truncated NodeCode: missing length byte")
        }
        val length = buf[offset].toInt() and 0xFF
        val start = offset + 1
        val end = start + length
        if (end > buf.size) {
            throw NodeCodeException.Malformed(
                "Truncated NodeCode: declared field length $length exceeds remaining buffer"
            )
        }
        val value = buf.copyOfRange(start, end).decodeToString()
        return value to end
    }

    private fun group(text: String): String {
        if (text.isEmpty()) return text
        return text.chunked(GROUP_SIZE).joinToString("-")
    }

    // ─── Prefix handling ─────────────────────────────────────────────────────

    /** Strip a dashed `CIRIS-VN-` prefix; null if absent. Throws on version mismatch. */
    private fun stripDashedPrefix(cleaned: String): String? {
        val after = cleaned.removePrefix("CIRIS-V").takeIf { it != cleaned } ?: return null
        val dash = after.indexOf('-')
        if (dash < 0) return null
        val verStr = after.substring(0, dash)
        val declared = verStr.toIntOrNull()
            ?: throw NodeCodeException.Malformed("malformed CIRIS-V<version>- prefix")
        if (declared != VERSION) {
            throw NodeCodeException.InvalidVersion("textual prefix V$declared; this build supports V$VERSION")
        }
        return after.substring(dash + 1)
    }

    /** Strip an undashed `CIRISVN` (QR) prefix; null if absent. Throws on version mismatch. */
    private fun stripUndashedPrefix(cleaned: String): String? {
        val after = cleaned.removePrefix("CIRISV").takeIf { it != cleaned } ?: return null
        val digits = after.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        val declared = digits.toIntOrNull()
            ?: throw NodeCodeException.Malformed("malformed CIRISV<version> prefix")
        if (declared != VERSION) {
            throw NodeCodeException.InvalidVersion("textual prefix V$declared; this build supports V$VERSION")
        }
        return after.substring(digits.length)
    }

    // ─── Base32 (RFC 4648, no padding) ───────────────────────────────────────

    private fun b32NoPadEncode(data: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(B32_ALPHABET[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            out.append(B32_ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return out.toString()
    }

    private fun b32NoPadDecode(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length * 5 / 8 + 1)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in '2'..'7' -> ch - '2' + 26
                else -> throw NodeCodeException.Malformed("invalid base32 character: '$ch'")
            }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    // ─── CRC-16-CCITT (poly 0x1021, init 0xFFFF, no xor-out, MSB-first) ───────

    private fun crc16Ccitt(data: ByteArray): Int {
        var crc = CRC_INIT
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor CRC_POLY) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    // ─── Base64 (standard, padded — matches Python b64 / Rust STANDARD) ───────

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64Encode(data: ByteArray): String = Base64.encode(data)

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64Decode(text: String): ByteArray = Base64.decode(text)

    /** SHA-256 lives in [Sha256], shared with the drive's digest check; one implementation, one set of test vectors. */
    private fun sha256(message: ByteArray): ByteArray = Sha256.digest(message)
}

/**
 * The decoded NodeCode payload — a node's shareable identity (CEG §0.10).
 *
 * Mirrors the agent's `NodeCode` pydantic model and the Rust `NodeCode` struct:
 *  - [keyId] is the human-readable Ed25519 `signer_key_id` of the node.
 *  - [pubkeyEd25519Base64] is the raw 32-byte Ed25519 public key, standard
 *    base64-encoded (the `LocalPeerState.pubkey_ed25519_base64` form).
 *  - the hints are optional UTF-8 strings (max 255 bytes each); [transportHint]
 *    is typically a reachable base URL.
 */
data class DecodedNodeCode(
    val keyId: String,
    val pubkeyEd25519Base64: String,
    val transportHint: String? = null,
    val aliasHint: String? = null,
)

/** NodeCode encode/decode failures — mirrors the agent/Rust exception hierarchy. */
sealed class NodeCodeException(message: String) : Exception(message) {
    /** The decoded version byte (or textual `CIRIS-VN-` prefix) is unsupported. */
    class InvalidVersion(message: String) : NodeCodeException("unsupported NodeCode version: $message")

    /** The trailing CRC-16-CCITT did not match the recomputed checksum. */
    class ChecksumMismatch(val declared: Int, val computed: Int) : NodeCodeException(
        "NodeCode CRC mismatch (declared 0x${declared.toString(16)}, computed 0x${computed.toString(16)})"
    )

    /** Structurally invalid: bad prefix/base32, truncation, over-long fields, etc. */
    class Malformed(message: String) : NodeCodeException("malformed NodeCode: $message")
}
