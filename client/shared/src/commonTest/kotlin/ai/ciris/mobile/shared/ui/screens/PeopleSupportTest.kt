package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.Dim
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.ContactGrant
import ai.ciris.mobile.shared.models.federation.ContactListResponse
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.Fact
import ai.ciris.mobile.shared.ui.theme.Tone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The tags are a downstream contract (CIRISAgent's gate drives them); the receipt must be honest. */
class PeopleSupportTest {

    @Test
    fun theContactsTagsAreUnchanged() {
        assertEquals("contacts_list", PeopleTags.LIST)
        assertEquals("input_contacts_search", PeopleTags.SEARCH)
        assertEquals("card_contacts_add", PeopleTags.ADD_CARD)
        assertEquals("input_contacts_add_key", PeopleTags.ADD_KEY)
        assertEquals("btn_contacts_add_submit", PeopleTags.ADD_SUBMIT)
        assertEquals("btn_contacts_add_open", PeopleTags.ADD_OPEN)
        assertEquals("contacts_add_refusal", PeopleTags.ADD_REFUSAL)
        assertEquals("btn_contacts_add_open_chat", PeopleTags.ADD_OPEN_CHAT)
        assertEquals("btn_contacts_add_dismiss", PeopleTags.ADD_DISMISS)
        assertEquals("btn_contacts_refresh", PeopleTags.REFRESH)
        assertEquals("btn_contacts_back", PeopleTags.BACK)
        assertEquals("contacts_unsupported", PeopleTags.UNSUPPORTED)
        assertEquals("contacts_row_k1", PeopleTags.row("k1"))
        assertEquals("btn_contacts_chat_k1", PeopleTags.chat("k1"))
        assertEquals("btn_contacts_pick_k1", PeopleTags.pick("k1"))
        assertEquals("contacts_chat_ineligible_k1", PeopleTags.ineligible("k1"))
        // new in wave 0
        assertEquals("btn_receipt_k1", PeopleTags.receipt("k1"))
        assertEquals("contacts_empty", PeopleTags.EMPTY)
        assertEquals("contacts_loading", PeopleTags.LOADING)
        assertEquals("contacts_error", PeopleTags.ERROR)
    }

    @Test
    fun aContactReceiptWithoutAGrantSaysWhatItKnowsAndWhatItDoesNot() {
        // An older node (< 0.5.213) sends no `grant`.
        val c = Contact(keyId = "peer-1")
        val r = contactReceipt(c, attesterGloss = PERSON, openChatLabel = "Open chat", scopeNote = "note", onOpenChat = {})
        assertEquals("peer-1", r.id)
        assertEquals(Fact.Wire("peer-1"), r.subject)
        // Consent moved from the node to the person at 0.5.211: without the grant
        // nobody can say who signed, so it is not-sent — never "this node" by rule.
        assertEquals(Fact.NotSent, r.attester)
        assertEquals(Fact.ByRule("federation", CONTACT_GRANT_CC), r.scope)
        assertSame(Dim.consentKind, r.dimension)
        assertEquals(Fact.ByRule("consent:replication:v1", CONTACT_GRANT_CC), r.dimensionValue)
        assertEquals(Fact.NotSent, r.rule)
        assertNull(r.forAgent, "no grant, no for-agent row")
        assertEquals(null, r.holders)
        assertEquals(1, r.wireFacts, "only the subject came off the wire")
        assertEquals("btn_receipt_act_chat_peer-1", r.acts.single().tag)
        assertEquals("note", r.scopeNote)
    }

    @Test
    fun aContactReceiptWithTheGrantEnvelopeFillsAllFiveFactsFromTheWire() {
        val c = json.decodeFromString<ContactListResponse>(ROW_WITH_GRANT).contacts.single()
        val r = contactReceipt(c, attesterGloss = PERSON, openChatLabel = "Open chat", scopeNote = "note", onOpenChat = {})
        assertEquals(5, r.wireFacts, "every one of the five facts came off the wire")
        assertEquals(Fact.Wire("peer-1"), r.subject)
        assertEquals(Fact.Wire("owner-fed-id", PERSON), r.attester)
        assertEquals(Fact.Wire("federation"), r.scope)
        assertEquals(Fact.Wire("consent:replication:v1"), r.dimensionValue)
        assertEquals(Fact.Wire("chat:, memory:"), r.rule)
        assertEquals(Fact.Wire("agent-7"), r.forAgent)
    }

    @Test
    fun theGrantAttesterIsLabelledAsThePersonNotTheNode() {
        val c = Contact(keyId = "peer-1", grant = ContactGrant(attestingKeyId = "owner-fed-id"))
        val r = contactReceipt(c, attesterGloss = PERSON, openChatLabel = "Open chat", scopeNote = "note", onOpenChat = {})
        val attester = r.attester
        assertTrue(attester is Fact.Wire, "the attester is sent, not fixed by rule")
        assertEquals("owner-fed-id", attester.value)
        assertEquals(PERSON, attester.gloss, "labelled as the person who consented")
    }

    @Test
    fun aGrantMemberTheNodeLeftEmptyIsNotSentNeverGuessed() {
        // `grant: {}` — a node that could not compute any member reports it absent.
        val c = Contact(keyId = "peer-1", grant = ContactGrant())
        val r = contactReceipt(c, attesterGloss = PERSON, openChatLabel = "Open chat", scopeNote = "note", onOpenChat = {})
        assertEquals(0, r.wireFacts)
        listOf(r.subject, r.attester, r.scope, r.dimensionValue, r.rule, r.forAgent).forEach {
            assertEquals(Fact.NotSent, it)
        }
    }

    @Test
    fun aNullGrantOnTheWireDecodesAsNoGrant() {
        val row = """{"contacts":[{"key_id":"peer-1","canonical":false,"trust":"unknown","contact":true,"grant":null}],"total":1}"""
        assertNull(json.decodeFromString<ContactListResponse>(row).contacts.single().grant)
    }

    private companion object {
        const val PERSON = "The person who consented"
        val json = Json { ignoreUnknownKeys = true }

        /** The shape `contacts_chat.rs::list_contacts` + `peer.rs::grant_receipt` serve (0.5.213+). */
        val ROW_WITH_GRANT = """
            {"contacts":[{
              "key_id":"peer-1","canonical":false,"trust":"trusted","contact":true,
              "grant":{
                "attestation_id":"att-1","attesting_key_id":"owner-fed-id",
                "dimension":"consent:replication:v1","subject_key_ids":["peer-1"],
                "cohort_scope":"federation","for_key_id":"agent-7",
                "consent_prefixes":["chat:","memory:"],
                "asserted_at":"2026-09-01T00:00:00+00:00","valid_until":null,"row_expires_at":null
              },
              "chat_community_id":"c-1","chat_started":false,"occurrence_key_ids":[]
            }],"total":1}
        """.trimIndent()
    }

    @Test
    fun trustReadsAsGlyphAndToneNeverAColour() {
        assertEquals(GlyphName.CHECK to Tone.OK, PeerTrustState.TRUSTED.reading())
        assertEquals(GlyphName.STOP to Tone.DANGER, PeerTrustState.BLOCKED.reading())
        assertNotEquals(Tone.OK, PeerTrustState.UNTRUSTED.reading().second)
        assertNotEquals(Tone.OK, PeerTrustState.UNKNOWN.reading().second)
    }
}
