package ai.ciris.mobile.shared.models

/**
 * The **three** ownership states of the local node (FSD FIRST_RUN_WIZARD_2.9.14 §5).
 *
 * The 2.9.13 first-run loop exists because the startup router knew only *fresh* vs
 * *claimed*. There is a third, named state — a node owned the LEGACY way (a ROOT
 * WaCert with a password/OAuth login but NO fed-ID `delegates_to` owner-binding).
 * A router that collapses it into *fresh* sends it to Setup, where `setup/root`
 * 409s, and the wizard loops.
 *
 * The two signals, and why each one:
 *
 * * **owner-binding** — `GET {node}/v1/setup/owned-nodes` (`owner`). Reads the CEG
 *   graph via `admission::owner_of` and is the SAME predicate `require_owner_bound`
 *   consults at every owner-gated operation (config writes, peering, commons, mesh
 *   config, peers). This is the only signal that GATES anything.
 * * **ROOT WaCert** — `GET {node}/v1/setup/status` (`setup_required`). The NODE's
 *   own first-run predicate: `false` ⇒ an active ROOT WA exists. Note this is the
 *   *node's* answer, not the brain's — the brain's `/v1/setup/status` asks whether
 *   `.env` exists and the two can legitimately disagree.
 *
 * `GET /v1/auth/owner-hint` is deliberately NOT used here. It reads the WaCert auth
 * store to render a GDPR-masked "welcome back" string on the login screen. It gates
 * nothing, and routing on it is what produced the loop.
 */
enum class NodeOwnership {
    /** No ROOT WaCert and no owner-binding — a genuinely fresh node. Route: Setup. */
    FRESH,

    /**
     * ROOT WaCert present, owner-binding ABSENT. The node is owned, just not rooted
     * on a fed-ID. Route: Login (the existing password/OAuth login still works),
     * then `POST /v1/self/upgrade-owner` — never Setup.
     */
    LEGACY_OWNED,

    /** Owner-binding present. Route: Login. */
    CLAIMED;

    /**
     * True when the node already has an owner in EITHER form, i.e. it is configured
     * and first-run must not be re-entered.
     */
    val isOwned: Boolean get() = this != FRESH

    /** True when the node needs `POST /v1/self/upgrade-owner` to be re-rooted on a fed-ID. */
    val needsOwnerUpgrade: Boolean get() = this == LEGACY_OWNED
}

/**
 * Derive the [NodeOwnership] state from the two node-side signals.
 *
 * @param ownerBinding the `owner` field of `GET /v1/setup/owned-nodes` — the bound
 *        owner's fed-ID key_id, or null/blank when the node carries no owner-binding.
 * @param nodeSetupRequired the `setup_required` field of the NODE's
 *        `GET /v1/setup/status` — false ⇒ an active ROOT WaCert exists. Pass null
 *        when the probe failed; an unknown WaCert state degrades to [FRESH] only
 *        when there is also no owner-binding, which is the pre-2.9.14 behaviour.
 */
fun nodeOwnershipFrom(ownerBinding: String?, nodeSetupRequired: Boolean?): NodeOwnership = when {
    !ownerBinding.isNullOrBlank() -> NodeOwnership.CLAIMED
    nodeSetupRequired == false -> NodeOwnership.LEGACY_OWNED
    else -> NodeOwnership.FRESH
}

/**
 * What the node said when asked which [NodeOwnership] state it is in, for a
 * claim that could not go ahead (CIRISClient#184 item 3). Read from the same
 * two loopback routes as the startup router, and kept three-valued: a route
 * the node does not have is a version fact ([RouteAbsent]), and a probe that
 * got no answer ([Unknown]) is not evidence that the node is fresh.
 */
sealed interface NodeOwnershipProbe {
    /** Both reads answered (or the owner-binding read alone was decisive). */
    data class Known(val state: NodeOwnership) : NodeOwnershipProbe

    /** `GET /v1/setup/owned-nodes` answered a bare 404: a node older than the route. */
    object RouteAbsent : NodeOwnershipProbe

    /** No usable answer (unreachable, a non-404 refusal, an unreadable body). */
    data class Unknown(val reason: String) : NodeOwnershipProbe
}

/**
 * The named outcomes of a self-claim on a node that turned out NOT to be
 * claimable by PIN (CIRISClient#184 item 3). An owned node has no claim PIN to
 * read — the node consumes it on the first claim (ciris-server 0.5.231
 * `src/auth/bootstrap.rs:1653` `setup_status`: `claim_pin_file` is `None` once
 * claimed) — so the remedy is signing in as the owner, never "read a console".
 */
object OwnedNodeClaim {
    /** Owner-binding present: sign in as the owner (`mobile.setup_claim_already_claimed`). */
    const val ALREADY_CLAIMED = "claim.node_already_claimed"

    /**
     * ROOT WaCert present, owner-binding absent: sign in as the owner, then the
     * Add Federation ID catch-up mints (`POST /v1/self/identity`) and re-roots
     * (`POST /v1/self/upgrade-owner`) — `mobile.setup_claim_owner_upgrade`.
     */
    const val OWNER_UPGRADE = "claim.node_owner_upgrade"

    /** `owned-nodes` is a bare 404: the node cannot say who owns it (`mobile.setup_claim_node_too_old`). */
    const val NODE_TOO_OLD = "claim.node_too_old"

    /**
     * The named outcome for [probe], or null when the node is FRESH or did not
     * answer — the caller then keeps the failure it already had.
     */
    fun outcomeFor(probe: NodeOwnershipProbe): String? = when (probe) {
        is NodeOwnershipProbe.Known -> when (probe.state) {
            NodeOwnership.CLAIMED -> ALREADY_CLAIMED
            NodeOwnership.LEGACY_OWNED -> OWNER_UPGRADE
            NodeOwnership.FRESH -> null
        }
        NodeOwnershipProbe.RouteAbsent -> NODE_TOO_OLD
        is NodeOwnershipProbe.Unknown -> null
    }

    /** The en.json key whose text explains [outcomeId], or null for an id this object does not own. */
    fun messageKey(outcomeId: String?): String? = when (outcomeId) {
        ALREADY_CLAIMED -> "mobile.setup_claim_already_claimed"
        OWNER_UPGRADE -> "mobile.setup_claim_owner_upgrade"
        NODE_TOO_OLD -> "mobile.setup_claim_node_too_old"
        else -> null
    }

    /** True when [outcomeId] is entered by signing in as the node's existing owner. */
    fun needsOwnerSignIn(outcomeId: String?): Boolean =
        outcomeId == ALREADY_CLAIMED || outcomeId == OWNER_UPGRADE
}
