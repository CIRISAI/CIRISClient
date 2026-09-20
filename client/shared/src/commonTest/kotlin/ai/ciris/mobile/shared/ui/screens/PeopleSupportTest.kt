package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.Dim
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.Fact
import ai.ciris.mobile.shared.ui.theme.Tone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun aContactReceiptSaysWhatItKnowsAndWhatItDoesNot() {
        val c = Contact(keyId = "peer-1")
        val r = contactReceipt(c, thisNodeLabel = "This node", openChatLabel = "Open chat", scopeNote = "note", onOpenChat = {})
        assertEquals("peer-1", r.id)
        assertEquals(Fact.Wire("peer-1"), r.subject)
        assertTrue(r.attester is Fact.ByRule)
        assertEquals(Fact.ByRule("federation", CONTACT_GRANT_CC), r.scope)
        assertSame(Dim.consentKind, r.dimension)
        assertEquals(Fact.ByRule("consent:replication:v1", CONTACT_GRANT_CC), r.dimensionValue)
        assertEquals(Fact.NotSent, r.rule)
        assertEquals(null, r.holders)
        assertEquals(1, r.wireFacts, "only the subject came off the wire")
        assertEquals("btn_receipt_act_chat_peer-1", r.acts.single().tag)
        assertEquals("note", r.scopeNote)
    }

    @Test
    fun trustReadsAsGlyphAndToneNeverAColour() {
        assertEquals(GlyphName.CHECK to Tone.OK, PeerTrustState.TRUSTED.reading())
        assertEquals(GlyphName.STOP to Tone.DANGER, PeerTrustState.BLOCKED.reading())
        assertNotEquals(Tone.OK, PeerTrustState.UNTRUSTED.reading().second)
        assertNotEquals(Tone.OK, PeerTrustState.UNKNOWN.reading().second)
    }
}
