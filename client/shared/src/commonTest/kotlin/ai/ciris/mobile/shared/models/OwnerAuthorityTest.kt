package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.models.selfreader.AdminRefusalDto
import ai.ciris.mobile.shared.models.selfreader.OwnerAuthority
import ai.ciris.mobile.shared.models.selfreader.SelfStandingOutcome
import ai.ciris.mobile.shared.models.selfreader.SelfStandingResponse
import ai.ciris.mobile.shared.models.selfreader.delegationForAct
import ai.ciris.mobile.shared.models.selfreader.ownerAuthority
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-045 / CIRISServer#676: the delegation a tier S or tier R act is taken
 * under comes from the node when the node can name it, and the typed id
 * survives only where it cannot. Bodies copied from `self_standing` on
 * `integ/0.5.218` (`src/admin_ops.rs:3348-3360`) and from the 0.5.217 builder,
 * which has no such key.
 */
class OwnerAuthorityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun body(delegations: String): SelfStandingResponse = json.decodeFromString(
        SelfStandingResponse.serializer(),
        """{"source_locale":"en","tier":"S","node_key_id":"key-node-1",$delegations"standings":{}}""",
    )

    @Test
    fun a0518NodeNamesTheDelegationSoNothingIsTyped() {
        val r = body(
            """"owner_delegations":[{"delegation_id":"att-owner-serve-1","issuer_key_id":"key-owner",
               "subject_key_id":"key-node-1","scope":"infra:serve","owner_binding":true,
               "cohort_scope":"self","asserted_at":"2026-09-20T10:00:00+00:00"}],
               "owner_delegations_error":null,""",
        )
        val a = r.ownerAuthority()
        assertIs<OwnerAuthority.Supplied>(a)
        assertFalse(a.needsTypedId)
        assertEquals("att-owner-serve-1", a.delegationForAct(0, typed = ""))
        assertEquals("key-owner", a.delegations.single().issuerKeyId)
        assertTrue(a.delegations.single().ownerBinding)
    }

    @Test
    fun severalDelegationsArePickedNotTyped() {
        val r = body(
            """"owner_delegations":[{"delegation_id":"d-1"},{"delegation_id":"d-2"}],""",
        )
        val a = r.ownerAuthority()
        assertEquals("d-2", a.delegationForAct(1, typed = "ignored"))
        assertEquals("d-1", a.delegationForAct(7, typed = "ignored"), "an out-of-range pick falls back to the first")
    }

    @Test
    fun anOlderNodeCannotSaySoTheTypedIdIsTheFallback() {
        val a = body("").ownerAuthority()
        assertIs<OwnerAuthority.NotSupplied>(a)
        assertTrue(a.needsTypedId)
        assertNull(a.delegationForAct(0, typed = "  "))
        assertEquals("typed-id", a.delegationForAct(0, typed = " typed-id "))
    }

    @Test
    fun anEmptyListIsNoneHeldAndTypingCannotHelp() {
        val a = body(""""owner_delegations":[],"owner_delegations_error":null,""").ownerAuthority()
        assertEquals(OwnerAuthority.NoneHeld, a)
        assertFalse(a.needsTypedId)
        assertNull(a.delegationForAct(0, typed = "anything"))
    }

    @Test
    fun aFailedDelegationReadIsNotNoneHeld() {
        val a = body(""""owner_delegations":null,"owner_delegations_error":"list_attestations_for(k): io",""")
            .ownerAuthority()
        assertIs<OwnerAuthority.Unreadable>(a)
        assertTrue(a.needsTypedId)
    }

    @Test
    fun aRefusedOrUnreachableReadSaysNothingAboutDelegations() {
        assertIs<OwnerAuthority.NotSupplied>(
            SelfStandingOutcome.Refused(AdminRefusalDto(refusal = "not_owner"), 403).ownerAuthority(),
        )
        assertIs<OwnerAuthority.NotSupplied>(SelfStandingOutcome.Unreachable("refused").ownerAuthority())
    }
}
