package ai.ciris.mobile.shared.ui.primitives

import ai.ciris.mobile.shared.ceg.Dimension
import androidx.compose.runtime.Immutable

/**
 * THE RECEIPT — the five facts that travel with every CEG item.
 *
 * | plain label            | envelope member     |
 * |------------------------|---------------------|
 * | Who it is about        | `subject_key_ids`   |
 * | Who sent it            | `attesting_key_id`  |
 * | Who can see it         | `cohort_scope`      |
 * | What it is             | `dimension`         |
 * | The rule it follows    | `consent:scope`     |
 *
 * plus where the bytes are (holders), notes others left, and the acts.
 *
 * A fact is one of three things, and the sheet says which: the node SENT it
 * ([Fact.Wire]); the constitution FIXES it for this kind of record so it is
 * true without being sent ([Fact.ByRule], with the section); or the node did
 * not send it ([Fact.NotSent]). Never a guess, never blank, never reduced —
 * all five rows render every time.
 */
sealed interface Fact {
    /**
     * Sent by the node. [gloss] says what the value IS when the plain label
     * alone would mislead (a contact grant's attester is the person who
     * consented, not the node that relays it).
     */
    data class Wire(val value: String, val gloss: String? = null) : Fact
    data class ByRule(val value: String, val ccRef: String) : Fact
    data object NotSent : Fact
}

@Immutable
data class ReceiptNote(val by: String, val text: String)

@Immutable
data class ReceiptAct(val label: String, val tag: String, val onAct: () -> Unit)

@Immutable
data class Receipt(
    /** What the tags hang off: `btn_receipt_<id>`, `btn_receipt_act_<id>`. */
    val id: String,
    val subject: Fact,
    val attester: Fact,
    /** A wire `cohort_scope`; rendered as a ScopePill when it folds to a circle. */
    val scope: Fact,
    val dimension: Dimension,
    /** The bound wire dimension, e.g. `consent:replication:v1`. */
    val dimensionValue: Fact,
    val rule: Fact,
    /**
     * Which of MY agents the claim is for (`for_key_id`). Not one of the five —
     * most records have no such member, and for them this is null and the row
     * is not drawn. When non-null it renders like any other fact.
     */
    val forAgent: Fact? = null,
    val holders: Int? = null,
    val notes: List<ReceiptNote> = emptyList(),
    val acts: List<ReceiptAct> = emptyList(),
    /** One sentence the sheet adds under the scope row when the honest answer surprises. */
    val scopeNote: String? = null,
) {
    /** How many of the five facts came off the wire. */
    val wireFacts: Int get() = listOf(subject, attester, scope, dimensionValue, rule).count { it is Fact.Wire }
}
