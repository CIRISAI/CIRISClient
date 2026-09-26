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
    /** Remove a contact (CSD-005): opens the ConfirmSheet, never removes on its own. */
    fun receiptActRemove(keyId: String) = "btn_receipt_act_remove_$keyId"

    /** ConfirmSheet prefix: `btn_contacts_remove_confirm` / `btn_contacts_remove_cancel`. */
    const val REMOVE_CONFIRM_PREFIX = "contacts_remove"
    /** The grants still active after a removal — named, with why. Never "removed" while it shows. */
    const val REMOVE_REMAINING = "contacts_remove_remaining"
    const val REMOVE_DONE = "contacts_remove_done"
    const val REMOVE_REFUSAL = "contacts_remove_refusal"
    const val REMOVE_UNSUPPORTED = "contacts_remove_unsupported"
    const val REMOVE_DISMISS = "btn_contacts_remove_dismiss"
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
 * grant this node holds to that peer, and CC 3.3.7 fixes that grant's
 * envelope: `attesting_key_id` = the granting node (this one),
 * `subject_key_ids = [peer]`, `cohort_scope = "federation"` — the grant is a
 * public governance record even though what the two of you send is not.
 *
 * What `/v1/contacts` SENDS is the subject. What the constitution FIXES is
 * the attester, the scope and the dimension. What nobody sent is the rule
 * (the grant's `attestation_prefixes` are payload the list route omits) and
 * the holders. Each is said as what it is.
 */
fun contactReceipt(
    contact: Contact,
    thisNodeLabel: String,
    openChatLabel: String,
    scopeNote: String,
    onOpenChat: () -> Unit,
    /** Remove (withdraw consent); null leaves the act off, e.g. while a removal runs. */
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
): Receipt = Receipt(
    id = contact.keyId,
    subject = Fact.Wire(contact.keyId),
    attester = Fact.ByRule(thisNodeLabel, CONTACT_GRANT_CC),
    scope = Fact.ByRule("federation", CONTACT_GRANT_CC),
    dimension = Dim.consentKind,
    dimensionValue = Fact.ByRule(CONTACT_GRANT_DIMENSION, CONTACT_GRANT_CC),
    rule = Fact.NotSent,
    holders = null,
    acts = buildList {
        add(ReceiptAct(openChatLabel, PeopleTags.receiptActChat(contact.keyId), onOpenChat))
        if (removeLabel != null && onRemove != null) {
            add(ReceiptAct(removeLabel, PeopleTags.receiptActRemove(contact.keyId), onRemove))
        }
    },
    scopeNote = scopeNote,
)
