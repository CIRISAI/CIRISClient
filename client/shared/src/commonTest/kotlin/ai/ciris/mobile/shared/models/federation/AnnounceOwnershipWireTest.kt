package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `POST /v1/federation/announce` as CIRISServer `src/claim_remote.rs` answers
 * it. The promoted binding's id is `promoted_owner_binding_attestation_id`; the
 * client read `promoted_attestation_id` and the field was always null (CSD-086).
 */
class AnnounceOwnershipWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun thePromotedBindingAndDiscoverabilityAreRead() {
        val r = json.decodeFromString(
            AnnounceOwnershipResponse.serializer(),
            """{"owner":"fed-1","node_key_id":"n-1","cohort_scope":"FEDERATION",
               "promoted_owner_binding_attestation_id":"att-42","announce_ownership":true,
               "announce_takes_effect":"next_boot","bundle":[],"bundle_expected":3,"federation_discoverable":false}""",
        )
        assertEquals("att-42", r.promotedAttestationId)
        assertEquals(false, r.federationDiscoverable)
    }
}
