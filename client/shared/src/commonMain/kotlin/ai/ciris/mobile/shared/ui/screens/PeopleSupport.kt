package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.Dim
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.Fact
import ai.ciris.mobile.shared.ui.primitives.Receipt
import ai.ciris.mobile.shared.ui.primitives.ReceiptAct
import ai.ciris.mobile.shared.ui.theme.Tone

/**
 * The pure half of the People screen: tag builders, the trust reading, and the
 * receipt a contact row carries. No Compose, so it is tested directly.
 */
object PeopleTags {
    const val LIST = "contacts_list"
    const val SEARCH = "input_contacts_search"
    const val ADD_CARD = "card_contacts_add"
    const val ADD_KEY = "input_contacts_add_key"
    const val ADD_SUBMIT = "btn_contacts_add_submit"
    const val ADD_OPEN = "btn_contacts_add_open"
    const val ADD_REFUSAL = "contacts_add_refusal"
    const val ADD_OPEN_CHAT = "btn_contacts_add_open_chat"
    const val ADD_DISMISS = "btn_contacts_add_dismiss"
    const val REFRESH = "btn_contacts_refresh"
    const val BACK = "btn_contacts_back"
    const val UNSUPPORTED = "contacts_unsupported"
    const val ERROR = "contacts_error"
    const val LOADING = "contacts_loading"
    const val EMPTY = "contacts_empty"

    fun row(keyId: String) = "contacts_row_$keyId"
    fun chat(keyId: String) = "btn_contacts_chat_$keyId"
    fun pick(keyId: String) = "btn_contacts_pick_$keyId"
    fun ineligible(keyId: String) = "contacts_chat_ineligible_$keyId"
    /** The hamburger; `ItemRow` derives it from the receipt id, which is the key id. */
    fun receipt(keyId: String) = "btn_receipt_$keyId"
    fun receiptActChat(keyId: String) = "btn_receipt_act_chat_$keyId"
}

/** How a trust state reads: a glyph and a tone. Never a colour. */
fun PeerTrustState.reading(): Pair<GlyphName, Tone> = when (this) {
    PeerTrustState.TRUSTED -> GlyphName.CHECK to Tone.OK
    PeerTrustState.UNKNOWN -> GlyphName.QUESTION to Tone.MUTE
    PeerTrustState.UNTRUSTED -> GlyphName.WARNING to Tone.DIM
    PeerTrustState.BLOCKED -> GlyphName.STOP to Tone.DANGER
}

/** `consent:replication:v1` — the one dimension a contact row is (CIRISApiClient.listContacts). */
const val CONTACT_GRANT_DIMENSION = "consent:replication:v1"
const val CONTACT_GRANT_CC = "CC 3.3.7"

/**
 * The receipt a contact row carries. A contact IS a `consent:replication:v1`
 * grant, and since ciris-server 0.5.213 each `/v1/contacts` row carries that
 * grant's envelope as `grant` (CIRISServer#616). Every fact is read off it:
 *
 * - who it is about — `subject_key_ids`
 * - who sent it — `attesting_key_id`, which since 0.5.211 is the PERSON who
 *   consented (the owner's federation identity), NOT this node; [attesterGloss]
 *   says so under the key, because "who sent it" alone reads as "the node"
 * - who can see it — `cohort_scope`, folded to a circle by the sheet
 * - what it is — `dimension`
 * - the rule it follows — `consent_prefixes`
 * - which of my agents it is for — `for_key_id`
 *
 * An older node sends no `grant`. Then only the subject (the row's key) is
 * wire; the dimension and scope stay [Fact.ByRule] because the route and
 * CC 3.3.7 fix them; and the attester is [Fact.NotSent] — it USED to be fixed
 * as "this node", but consent moved to the person at 0.5.211, so a grant-less
 * row cannot say who signed and the receipt must not guess. A member the
 * grant does carry but leaves empty is likewise [Fact.NotSent].
 */
fun contactReceipt(
    contact: Contact,
    attesterGloss: String,
    openChatLabel: String,
    scopeNote: String,
    onOpenChat: () -> Unit,
): Receipt {
    val g = contact.grant
    fun wire(v: String?, gloss: String? = null): Fact =
        v?.takeIf { it.isNotBlank() }?.let { Fact.Wire(it, gloss) } ?: Fact.NotSent
    return Receipt(
        id = contact.keyId,
        subject = if (g == null) Fact.Wire(contact.keyId)
        else wire(g.subjectKeyIds.filter { it.isNotBlank() }.joinToString(", ")),
        attester = if (g == null) Fact.NotSent else wire(g.attestingKeyId, attesterGloss),
        scope = if (g == null) Fact.ByRule("federation", CONTACT_GRANT_CC) else wire(g.cohortScope),
        dimension = Dim.consentKind,
        dimensionValue = if (g == null) Fact.ByRule(CONTACT_GRANT_DIMENSION, CONTACT_GRANT_CC) else wire(g.dimension),
        rule = if (g == null) Fact.NotSent else wire(g.consentPrefixes.filter { it.isNotBlank() }.joinToString(", ")),
        forAgent = if (g == null) null else wire(g.forKeyId),
        holders = null,
        acts = listOf(ReceiptAct(openChatLabel, PeopleTags.receiptActChat(contact.keyId), onOpenChat)),
        scopeNote = scopeNote,
    )
}
