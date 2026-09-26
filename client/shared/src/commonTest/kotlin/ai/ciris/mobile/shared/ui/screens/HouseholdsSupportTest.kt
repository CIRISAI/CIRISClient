package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.models.federation.FamilyChangeCarry
import ai.ciris.mobile.shared.models.federation.FamilyDto
import ai.ciris.mobile.shared.models.federation.FamilyListResponse
import ai.ciris.mobile.shared.models.federation.FamilySignatureDto
import ai.ciris.mobile.shared.ui.primitives.Fact
import ai.ciris.mobile.shared.ui.primitives.SignerState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The household rules that decide what the cards OFFER (CSD-100 / CSD-101).
 * The node enforces the protocol either way; these pin that the client never
 * offers a single call the node would refuse after the person confirmed.
 */
class HouseholdsSupportTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── The wire ─────────────────────────────────────────────────────────

    /** The shape `view()` builds (`family_api.rs:541-571`), verbatim keys. */
    @Test
    fun theNodesFamilyViewDecodes() {
        val raw = """{"families":[{"family_id":"family:v1:9f2c","name":"The Okafors",
            "consensus_protocol":"quorum:2/3","founded_at":"2026-09-20T10:00:00Z",
            "members":[{"key_id":"ada","role":"founder","joined_at":"2026-09-20T10:00:00Z"},
                       {"key_id":"bo","role":"member","joined_at":"2026-09-20T10:00:00Z"}],
            "my_role":"founder",
            "envelope":{"subject":"family:v1:9f2c","attester":"ada","cohort_scope":"family",
                        "dimension":"family","persist_row_hash":"abc"}}],"resume":null}"""
        val page = json.decodeFromString(FamilyListResponse.serializer(), raw)
        val f = page.families.single()
        assertEquals("quorum:2/3", f.consensusProtocol)
        assertEquals(listOf("founder", "member"), f.members.map { it.role })
        assertEquals("ada", f.envelope?.attester)
        assertNull(page.resume)
    }

    // ── Governance: read the way the node reads it ───────────────────────

    @Test
    fun protocolsParseTheWayTheNodeParsesThem() {
        assertEquals(Governance.FounderOnly(true), governanceOf("founder_only", "founder"))
        assertEquals(Governance.FounderOnly(false), governanceOf("founder_only", "member"))
        assertEquals(Governance.FounderOnly(false), governanceOf("founder_only", null))
        assertEquals(Governance.Quorum(2, 3), governanceOf("quorum:2/3", "member"))
        assertEquals(Governance.Quorum(3, 3), governanceOf("quorum:3/3", "founder"))
        // `Protocol::of` refuses anything that is not a strict majority over N.
        assertIs<Governance.Ungovernable>(governanceOf("quorum:1/2", "founder"))
        assertIs<Governance.Ungovernable>(governanceOf("quorum:0/1", "founder"))
        assertIs<Governance.Ungovernable>(governanceOf("weighted:rubric", "founder"))
        assertIs<Governance.Ungovernable>(governanceOf("majority", "founder"), "stored as quorum:M/N, never as the alias")
    }

    @Test
    fun aQuorumHouseholdIsNeverOfferedASingleCall() {
        val q = Governance.Quorum(2, 3)
        for (act in listOf(
            HouseholdAct.Add("k", "K"), HouseholdAct.Remove("k", "K"),
            HouseholdAct.Role("k", "K", ROLE_FOUNDER), HouseholdAct.Dissolve,
        )) {
            assertEquals(ActRoute.PROPOSE, routeOf(act, q), "$act on a quorum household")
        }
    }

    @Test
    fun aFounderActsInOneCallAndNobodyElseActsAtAll() {
        val founder = Governance.FounderOnly(true)
        val member = Governance.FounderOnly(false)
        assertEquals(ActRoute.DIRECT, routeOf(HouseholdAct.Remove("k", "K"), founder))
        assertEquals(ActRoute.DIRECT, routeOf(HouseholdAct.Dissolve, founder))
        assertEquals(ActRoute.NOT_ALLOWED, routeOf(HouseholdAct.Remove("k", "K"), member))
        assertEquals(ActRoute.NOT_ALLOWED, routeOf(HouseholdAct.Dissolve, member))
        assertEquals(ActRoute.NOT_ALLOWED, routeOf(HouseholdAct.Add("k", "K"), Governance.Ungovernable("custom:x")))
    }

    @Test
    fun leavingIsAlwaysYourOwnAct() {
        for (g in listOf(Governance.FounderOnly(false), Governance.FounderOnly(true), Governance.Quorum(2, 3), Governance.Ungovernable("x"))) {
            assertEquals(ActRoute.DIRECT, routeOf(HouseholdAct.Leave, g), "leave under $g")
        }
        assertNull(HouseholdAct.Leave.envelopeAction, "leave is never proposed")
    }

    // ── The ceremony ─────────────────────────────────────────────────────

    @Test
    fun signerStatesSayWhoHasSigned() {
        val sig = FamilySignatureDto("bo", "e", "m")
        val states = signerStates(listOf("ada", "bo", "cy"), listOf(sig), proposer = "ada")
        assertEquals(
            listOf("ada" to SignerState.PROPOSED, "bo" to SignerState.SIGNED, "cy" to SignerState.NOT_YET),
            states,
        )
        // Once the proposer signs, they are signed, not merely proposing.
        val after = signerStates(listOf("ada", "bo"), listOf(sig, FamilySignatureDto("ada", "e")), proposer = "ada")
        assertEquals(SignerState.SIGNED, after.first().second)
    }

    @Test
    fun aChangeSurvivesTheTripBetweenMembers() {
        val env = buildJsonObject {
            put("family_key_id", JsonPrimitive("family:v1:9f2c"))
            put("action", JsonPrimitive("remove"))
            put("target_key_id", JsonPrimitive("cy"))
            put("prior_persist_row_hash", JsonPrimitive("abc"))
        }
        val carry = FamilyChangeCarry(env, listOf(FamilySignatureDto("ada", "e", "m")))
        val back = decodeCarry(encodeCarry(carry))
        assertNotNull(back)
        assertEquals(carry, back)
        assertEquals("family:v1:9f2c", back.familyId())
        assertEquals("remove", back.action())
        assertEquals("cy", back.targetKeyId())
        assertNull(decodeCarry("not a change"))
        assertNull(decodeCarry("{\"signatures\":[]}"), "no envelope, no change")
    }

    // ── The receipt ──────────────────────────────────────────────────────

    @Test
    fun theReceiptSaysWhatTheNodeSentAndWhatItDidNot() {
        val f = json.decodeFromString(
            FamilyDto.serializer(),
            """{"family_id":"family:v1:9f2c","name":"x","envelope":{"subject":"family:v1:9f2c",
               "attester":null,"cohort_scope":"family","dimension":"family"}}""",
        )
        val r = householdReceipt(f)
        assertEquals(Fact.Wire("family:v1:9f2c"), r.subject)
        assertEquals(Fact.NotSent, r.attester, "a row with no signed read has no attester; never guess the founder")
        assertEquals(Fact.Wire("family"), r.scope)
        assertEquals(Fact.Wire("family"), r.dimensionValue)
        assertEquals(Fact.NotSent, r.rule)
        assertEquals("family_v1_9f2c", r.id)
        assertEquals(3, r.wireFacts)
        // An older row with no envelope at all: five NotSent, never blank.
        assertEquals(0, householdReceipt(f.copy(envelope = null)).wireFacts)
    }

    @Test
    fun tagsAreSlugs() {
        assertEquals("chip_household_family_v1_9f2c", HouseholdTags.chip("family:v1:9f2c"))
        assertEquals("btn_household_member_remove_wa_peer_4a19", HouseholdTags.remove("wa-peer-4A19"))
        assertEquals("opt_household_protocol_majority", HouseholdTags.protocolOption(ProtocolChoice.MAJORITY))
    }
}
