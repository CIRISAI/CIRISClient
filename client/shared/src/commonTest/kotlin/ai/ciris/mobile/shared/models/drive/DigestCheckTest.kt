package ai.ciris.mobile.shared.models.drive

import ai.ciris.mobile.shared.platform.util.Sha256
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CC 5.3.2.5: the full SHA-256 is verified before any renderer sees the
 * bytes. 0.5.217 states the plaintext digest on `GET /v1/files/{id}`
 * (CIRISServer `src/drive.rs:2088-2089`) as lowercase hex, `content_digest_alg:
 * "sha-256"`; this is the check, and the hash under it.
 */
class DigestCheckTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sha256MatchesTheKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", Sha256.hex("hello".encodeToByteArray()))
        // Two blocks, with the length field crossing the padding boundary.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }))
    }

    @Test
    fun theWireShapeDecodesAndVerifies() {
        // As 0.5.217 answers the read, digest and all.
        val file = json.decodeFromString(
            OpenedFile.serializer(),
            """{"attestation_id":"a","media_type":"text/plain","filename":"a.txt","size":5,
               |"content_digest":"2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824","content_digest_alg":"sha-256",
               |"bytes_base64":"aGVsbG8="}""".trimMargin(),
        )
        assertEquals(5L, file.size)
        // Case-insensitive on the node's hex: the digest is a number, not a string.
        assertEquals(DigestCheck.Verified("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"), DigestCheck.of(file, "hello".encodeToByteArray()))
        val mismatch = assertIs<DigestCheck.Mismatch>(DigestCheck.of(file, "hellp".encodeToByteArray()))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", mismatch.expected)
    }

    @Test
    fun noDigestAndAnUnknownAlgorithmAreSaidNotGuessed() {
        val none = OpenedFile("a", bytesBase64 = "aGVsbG8=")
        assertEquals(DigestCheck.NotSent, DigestCheck.of(none, "hello".encodeToByteArray()))
        val blank = OpenedFile("a", contentDigest = "  ", bytesBase64 = "aGVsbG8=")
        assertEquals(DigestCheck.NotSent, DigestCheck.of(blank, "hello".encodeToByteArray()))
        val other = OpenedFile("a", contentDigest = "abcd", contentDigestAlg = "sha-512", bytesBase64 = "aGVsbG8=")
        assertEquals(DigestCheck.UnknownAlgorithm("sha-512"), DigestCheck.of(other, "hello".encodeToByteArray()))
    }

    @Test
    fun theNodesPolicyBodyParses() {
        // The body `src/media_gate.rs:294` sends, trimmed to what matters here.
        val body = json.parseToJsonElement(
            """{"policy_version":1,"source":"CC 5.3.2.6",
               |"tier_a":{"image/png":{"max_bytes":16000000,"max_pixels":33000000},
               |          "text/plain":{"max_bytes":1000000,"utf8_only":true},
               |          "audio/mpeg":{"conditional":"ID3v2 ignored; APIC never decoded"}},
               |"tier_c_download":["application/pdf"],"inline_max_bytes":1048576,"whole_read_max_bytes":268435456,"renditions":false}""".trimMargin(),
        )
        val node = MediaPolicy.fromWire(body as kotlinx.serialization.json.JsonObject)
        assertEquals(1, node.policyVersion)
        assertEquals(16_000_000L, node.tierA["image/png"])
        assertEquals(1_000_000L, node.tierA["text/plain"])
        assertEquals(Long.MAX_VALUE, node.tierA["audio/mpeg"], "listed with no cap of its own")
        assertEquals(1_048_576L, node.inlineMaxBytes)
        assertEquals(268_435_456L, node.wholeReadMaxBytes)
        assertEquals(268_435_456L, node.uploadCap, "the write door's gate is the whole-read cap")
        assertEquals(1_048_576L, MediaPolicy.RECOMMENDED.uploadCap, "a node that publishes nothing keeps the compiled-in cap")
        assertEquals(false, node.renditions)
        val inForce = MediaPolicy.RECOMMENDED.narrowedBy(node)
        assertEquals(16L * 1_048_576, inForce.tierA["audio/mpeg"], "the recommended cap stands where the node set none")
        assertEquals(setOf("image/png", "text/plain", "audio/mpeg"), inForce.tierA.keys)
    }
}
