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
 * ## Severity is `warning`, and the card is red anyway
 *
 * It arrives as `warning`, not `error`, and that is deliberate on the node's
 * side: `is_degrading()` maps `error|critical` to `degraded_mode = true`, and
 * this condition is PERMANENT until somebody repairs it. Raised as an error it
 * would pin every node holding a stale row as degraded forever, over something
 * that reduces no node service at all — the degradation module's own doctrine
 * names that failure exactly: *"collapsing them would make the flag useless the
 * first time an advisory fired."*
 *
 * So severity answers "is this node degrading?" and the answer is no. It does
 * NOT answer "how loudly should this be shown to the person whose identity is
 * broken", and nothing here reads it. Prominence is keyed on the code, which is
 * why the card renders in the error container from a warning-severity signal.
 * A card that waited for a severity bump would be waiting for a bug.
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


/**
 * Where the repair button actually goes: [SubjectBlindKey.actionUrl] resolved
 * against the node.
 *
 * `action_url` is documented as *"where to go to act on it"* and the node
 * naturally states it as its own route — `/v1/federation/adopt-scrubbed`. A
 * relative path handed to a URI handler opens nothing, so it is joined to the
 * node the client is attached to. An absolute URL is passed through untouched:
 * a node that points somewhere else (a crypto-ops box, where the holder keys
 * live) is naming a place this client has no business rewriting.
 *
 * Returns null for anything that cannot be made openable, and the caller renders
 * no button rather than a dead one.
 */
fun repairUrl(nodeBaseUrl: String, actionUrl: String?): String? {
    val route = actionUrl?.trim().orEmpty()
    if (route.isEmpty()) return null
    if (route.startsWith("http://") || route.startsWith("https://")) return route
    // Anything that is neither absolute nor a rooted path is not a route this
    // can resolve — refuse rather than guess at a base it was not given.
    if (!route.startsWith("/")) return null
    val base = nodeBaseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return null
    return base + route
}
