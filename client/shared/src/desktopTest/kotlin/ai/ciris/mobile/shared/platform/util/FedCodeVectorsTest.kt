package ai.ciris.mobile.shared.platform.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The client's FedCode decoder against CIRISVerify's golden vectors (v22.2.0,
 * CIRISVerify#329 and #331), the file the agent and server pin too. Every valid code,
 * every normalised spelling and every decode refusal. Since #331 the Rust follows
 * CC 2.6.8 too, so the file records no disagreements, and that is asserted.
 */
class FedCodeVectorsTest {
    private val file: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/vectors/fedcode_vectors.json")) { "vectors missing" }.readText(),
    ).jsonObject

    private fun JsonElement?.str(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content

    /** The decoded value in the file's own field names, for a one-line comparison. */
    private fun FedCodeCodec.Decoded.asFile(): Map<String, Any?> = mapOf(
        "alias_hint" to aliasHint,
        "group_key_id" to groupKeyId,
        "key_id" to keyId,
        "kind" to kind.name.lowercase(),
        "ml_dsa_65_pubkey_sha256" to mlDsa65PubkeySha256,
        "owned_nodes" to ownedNodes.map { mapOf("key_id" to it.keyId, "transport_pubkey_ed25519_base64" to it.transportPubkeyEd25519Base64) },
        "pubkey_ed25519_base64" to pubkeyEd25519Base64,
        "transport_hint" to transportHint,
    )

    private fun JsonObject.asFile(): Map<String, Any?> = mapOf(
        "alias_hint" to this["alias_hint"].str(),
        "group_key_id" to this["group_key_id"].str(),
        "key_id" to this["key_id"].str(),
        "kind" to this["kind"].str(),
        "ml_dsa_65_pubkey_sha256" to this["ml_dsa_65_pubkey_sha256"].str(),
        "owned_nodes" to (this["owned_nodes"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            mapOf("key_id" to o["key_id"].str(), "transport_pubkey_ed25519_base64" to o["transport_pubkey_ed25519_base64"].str())
        },
        "pubkey_ed25519_base64" to this["pubkey_ed25519_base64"].str(),
        "transport_hint" to this["transport_hint"].str(),
    )

    private val valid get() = file["valid"]!!.jsonArray.map { it.jsonObject }

    @Test fun theFileIsThere() {
        // A parser that finds nothing must fail loudly.
        assertTrue(valid.size >= 24)
        assertTrue(file["refusals"]!!.jsonArray.size >= 41)
    }

    @Test fun everyValidCodeDecodesInBothForms() {
        for (v in valid) {
            val want = v["decoded"]!!.jsonObject.asFile()
            for (form in listOf("code", "code_qr")) {
                val code = v[form].str() ?: continue
                val got = try { FedCodeCodec.decode(code) } catch (e: Exception) { fail("${v["id"].str()} $form: $e") }
                assertEquals(want, got.asFile(), "${v["id"].str()} $form")
            }
        }
    }

    @Test fun everyNormalisedSpellingDecodesTheSame() {
        val byId = valid.associateBy { it["id"].str() }
        for (n in file["normalisation"]!!.jsonArray.map { it.jsonObject }) {
            val want = byId.getValue(n["decodes_as"].str())["decoded"]!!.jsonObject.asFile()
            assertEquals(want, FedCodeCodec.decode(n["input"].str()!!).asFile(), n["id"].str())
        }
    }

    @Test fun everyDecodeRefusalIsRefusedWithItsError() {
        for (r in file["refusals"]!!.jsonArray.map { it.jsonObject }.filter { it["stage"].str() == "decode" }) {
            val id = r["id"].str()
            val error = r["error"]!!
            val thrown = assertFailsWith<NodeCodeException>(id) { FedCodeCodec.decode(r["code"].str()!!) }
            when {
                error is JsonObject && "ChecksumMismatch" in error -> {
                    val m = error["ChecksumMismatch"]!!.jsonObject
                    val e = thrown as? NodeCodeException.ChecksumMismatch ?: fail("$id: $thrown")
                    assertEquals(m["declared_hex"].str(), e.declared.toString(16).padStart(4, '0'), id)
                    assertEquals(m["computed_hex"].str(), e.computed.toString(16).padStart(4, '0'), id)
                }
                error.str() == "InvalidVersion" -> assertTrue(thrown is NodeCodeException.InvalidVersion, "$id: $thrown")
                error.str() == "Malformed" -> assertTrue(thrown is NodeCodeException.Malformed, "$id: $thrown")
                else -> fail("$id: unexpected error spec $error")
            }
        }
    }

    /**
     * v22.1.0 recorded four places the Rust departed from CC 2.6.8; #331 fixed them and
     * moved their inputs into `valid` and `refusals`, where the tests above run them.
     * A new departure must be read before it is pinned, so a non-empty list fails.
     */
    @Test fun verifyRecordsNoDisagreementWithTheConstitution() {
        val cases = file["known_disagreements"]!!.jsonObject["cases"]!!.jsonArray
        assertTrue(cases.isEmpty(), "Verify records a departure from CC 2.6.8: ${cases.map { it.jsonObject["id"].str() }}")
    }

    /** New in v22.2.0: a key_id that does not hash to the code's sha256 is refused. */
    @Test fun dec_key_id_swappedIsRefused() {
        val r = file["refusals"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["id"].str() == "dec_key_id_swapped" }
            ?: fail("dec_key_id_swapped missing from refusals")
        assertFailsWith<NodeCodeException.Malformed> { FedCodeCodec.decode(r["code"].str()!!) }
    }

    /** The node screen's decoder: a v3 node code now decodes, with its commitment. */
    @Test fun aV1NodeCodeStillDecodesAsANode() {
        val v1 = valid.first { it["id"].str() == "v1_node_bare" }
        assertEquals(FedCodeCodec.Kind.NODE, FedCodeCodec.decode(v1["code"].str()!!).kind)
    }
}
