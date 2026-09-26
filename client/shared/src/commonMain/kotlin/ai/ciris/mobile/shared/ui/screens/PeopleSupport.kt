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
    /** `freshly_emitted: false` — the person was already a contact (CSD-005). */
    const val ADD_ALREADY = "contacts_add_already"
    /** QrScanAction beside [ADD_KEY]; it fills the field and never submits (CSD-005). */
    const val SCAN = "btn_scan_contact_code"

    // ── Share my contact code (CSD-092; the tags are that CSD's contract) ──
    const val CODE_OPEN = "btn_contact_code_open"
    const val CODE_CARD = "card_contact_code"
    const val CODE_TEXT = "text_contact_code"
    const val CODE_QR = "qr_contact_code"
    const val CODE_COPY = "btn_contact_code_copy"
    const val CODE_NODES_ALL = "opt_contact_code_nodes_all"
    const val CODE_NODES_LIST = "opt_contact_code_nodes_list"
    const val CODE_NODES_NONE = "opt_contact_code_nodes_none"
    const val CODE_PRIVATE_NOTE = "text_contact_code_private_note"
    const val CODE_INCLUDED = "text_contact_code_included"
    const val CODE_REFUSAL = "contact_code_refusal"
    const val CODE_UNREACHABLE = "contact_code_unreachable"
    const val CODE_MAKE_REACHABLE = "btn_contact_code_make_reachable"
    const val CODE_LOADING = "contact_code_loading"
    const val CODE_ERROR = "contact_code_error"
    /** Not in CSD-092 (it names no close or status tag); needed to drive the card shut and read the announce. */
    const val CODE_CLOSE = "btn_contact_code_close"
    const val CODE_REACHABLE_STATUS = "text_contact_code_reachable_status"
    fun codeNode(nodeKeyId: String) = "row_contact_code_node_$nodeKeyId"

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
): Receipt = Receipt(
    id = contact.keyId,
    subject = Fact.Wire(contact.keyId),
    attester = Fact.ByRule(thisNodeLabel, CONTACT_GRANT_CC),
    scope = Fact.ByRule("federation", CONTACT_GRANT_CC),
    dimension = Dim.consentKind,
    dimensionValue = Fact.ByRule(CONTACT_GRANT_DIMENSION, CONTACT_GRANT_CC),
    rule = Fact.NotSent,
    holders = null,
    acts = listOf(ReceiptAct(openChatLabel, PeopleTags.receiptActChat(contact.keyId), onOpenChat)),
    scopeNote = scopeNote,
)
