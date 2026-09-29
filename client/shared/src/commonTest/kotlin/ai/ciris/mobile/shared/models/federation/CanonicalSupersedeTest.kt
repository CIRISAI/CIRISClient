package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Rotating a canonical server (`POST /v1/accord/canonical/supersede`,
 * CIRISServer `src/accord_provision.rs:3570-3640`) takes the successor's
 * completed, anchor-scrubbed `SignedKeyRecord` verbatim plus the 2-of-3
 * proposal digest. The confirm must name the successor BEFORE anything is
 * sent, and it must name it from the record — `record.key_id`, the field the
 * server itself reads for its `successor` answer (`:3603`) — never from a
 * second text box the person could fill in differently.
 */
class CanonicalSupersedeTest {

    private val record = """
        {"record":{"key_id":"ciris-canonical-2-x9","pubkey_ed25519_base64":"aa","pubkey_ml_dsa_65_base64":"bb",
                   "identity_type":"canonical,node"},
         "scrub_key_id":"wa-a1","additional_scrubs":[{"scrub_key_id":"wa-b1"}]}
    """.trimIndent()

    @Test
    fun theSuccessorIsReadFromTheRecordItself() {
        val draft = prepareSupersede(record)
        assertEquals("ciris-canonical-2-x9", draft?.successorKeyId)
        // Verbatim: the element the confirm carries is the parsed record, not a re-typed one.
        assertEquals(Json.parseToJsonElement(record), draft?.record)
    }

    @Test
    fun aRecordWithoutAKeyIdIsStillADraftButNamesNoSuccessor() {
        val draft = prepareSupersede("""{"record":{"pubkey_ed25519_base64":"aa"}}""")
        assertNull(draft?.successorKeyId)
    }

    @Test
    fun notAJsonObjectIsRefusedBeforeAnythingIsSent() {
        assertNull(prepareSupersede("not a record"))
        assertNull(prepareSupersede("[1,2]"))
        assertNull(prepareSupersede(""))
        assertNull(prepareSupersede("   "))
    }

    @Test
    fun aStringFieldIsFoundWhereverTheEnvelopeNestsIt() {
        // The provision response's custody attestation is a signed CEG object; the
        // tier rides inside its envelope (verify-core `custody_tier`). Display-only,
        // so a tolerant walk is right: the first string under that key, or nothing.
        val custody = Json.parseToJsonElement(
            """{"object":{"payload":{"custody_tier":"portable_2fa","holder":"wa-a1"}},"sig":"x"}""",
        )
        assertEquals("portable_2fa", firstStringField(custody, "custody_tier"))
        assertNull(firstStringField(custody, "hardware_class"))
        assertNull(firstStringField(null, "custody_tier"))
        // A non-string under the key is not a tier.
        assertNull(firstStringField(Json.parseToJsonElement("""{"custody_tier":3}"""), "custody_tier"))
    }
}
