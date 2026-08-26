package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.api.SystemWarning

/**
 * The node's warning code for a **subject-blind federation key row**
 * (CIRISServer#490).
 *
 * A row minted before persist v31.0.0 (CIRISPersist#659) carries a registration
 * envelope naming only `key_id` and binding neither `identity_type` nor either
 * pubkey. Every signature over it is verified over those bytes ONLY, so persist
 * refuses it — *"an envelope that does not name its subject stands for ANY
 * record it is pasted onto"* — at every peer it replicates to, forever.
 *
 * KEY ON THIS CONSTANT, NEVER ON [SystemWarning.message]. The substrate's own
 * rule for refusal tokens is *"consumers key on the token constant, never on
 * message prose"*, and `degradation::Warning.message` is documented as arriving
 * already composed and **never localized**. Rendering the server's sentence
 * would ship one English string to 29 audiences; matching on it would break the
 * first time someone improved the wording.
 */
const val WARNING_KEY_SUBJECT_BLIND: String = "federation.key_subject_blind"

/**
 * A damaged identity this operator actually owns, and where to act on it.
 *
 * @property keyId the `federation_keys.key_id` the node reported as subject-blind.
 * @property actionUrl the repair route, when the node named one.
 */
data class SubjectBlindKey(
    val keyId: String,
    val actionUrl: String? = null,
)

/**
 * The subject-blind warning for an identity on THIS operator's roster, or null.
 *
 * ## Why this is scoped, and scoped this way
 *
 * A node holds federation keys for strangers. A malformed row belonging to
 * someone else is not this operator's problem and must not appear on their
 * identity card — the card's claim is *"your identity is unusable"*, and it has
 * to be true. So a warning is rendered only when its subject is a key this
 * screen is already managing: the bound owner fed-ID, or one of its active
 * occurrences.
 *
 * ## Why it is not scoped by lineage
 *
 * The right scope is "fedIDs descended from the operator's portable ID", and
 * that is **not expressible today**: [MintedIdentity] carries no lineage field,
 * and the live damaged row has no `delegates_to` owner-binding to walk
 * (CIRISServer#490, open question). The roster relation is a real binding and
 * is narrower than nothing, so it is what this uses until lineage exists.
 *
 * What it deliberately does NOT do is read lineage out of the key_id string.
 * `-portable-` in a name is a naming convention, not a binding; treating one as
 * the other is the person/node axis mistake in a new place, and it would put a
 * stranger's row on this operator's card the first time someone else's key
 * happened to be named that way.
 *
 * ## Why an unattributed warning renders nothing
 *
 * If the node reports the code without naming a subject, this returns null and
 * the card stays hidden. That is a deliberate fail-closed: with no subject there
 * is no way to know whose identity is broken, and a card that says "your
 * identity is unusable" about an unknown row is worse than no card. The subject
 * must arrive as a STRUCTURED field — see [SystemWarning.subjectKeyId].
 *
 * @param warnings the node's `/v1/system/health` warnings.
 * @param ownedKeyIds every key_id this screen manages — the self fed-ID and its
 *        occurrence roster.
 */
fun subjectBlindKeyFor(
    warnings: List<SystemWarning>,
    ownedKeyIds: Set<String>,
): SubjectBlindKey? {
    if (ownedKeyIds.isEmpty()) return null
    return warnings.asSequence()
        .filter { it.code == WARNING_KEY_SUBJECT_BLIND }
        .mapNotNull { w -> w.subjectKeyId?.takeIf { it.isNotBlank() }?.let { w to it } }
        .firstOrNull { (_, subject) -> subject in ownedKeyIds }
        ?.let { (w, subject) -> SubjectBlindKey(keyId = subject, actionUrl = w.actionUrl) }
}
