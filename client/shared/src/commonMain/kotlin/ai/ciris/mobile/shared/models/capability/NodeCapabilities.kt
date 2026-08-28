package ai.ciris.mobile.shared.models.capability

/**
 * WHAT THIS NODE CAN ACTUALLY DO, as the node declares it.
 *
 * The client is shipping UI ahead of the API: the CIRISPortal surfaces land here
 * while CIRISRegistry is still folding into CIRISServer, so on any node released
 * today most of them cannot be served. A screen that calls an endpoint the node
 * does not have is a broken screen; a screen that hides itself for the wrong
 * reason is worse, because the operator cannot tell a missing feature from a
 * missing permission.
 *
 * THREE STATES, NOT TWO. This is the same lesson as [ai.ciris.mobile.shared.models.ModeProbe]:
 * "I could not ask" is not "the answer is no". A node that declares nothing is
 * UNDECLARED, and the UI says the node has not told us — it does not silently
 * hide, and it does not optimistically show.
 *
 * NOT A SECURITY BOUNDARY, and nothing here may be load-bearing for
 * authorization. CIRISServer's TRUST_ROOT_CAPABILITY_GATE.md §5 puts it exactly:
 * "the server enforces the reality whether or not the client showed it (the
 * warning informs; the gate binds)." This is the informing half. If the client
 * is wrong in the permissive direction the server still refuses; if it is wrong
 * in the restrictive direction the operator sees a feature marked unavailable
 * that would have worked, which is why UNDECLARED is not the same as ABSENT.
 *
 * The declaration itself is CIRISServer#499.
 */
enum class CapabilityState {
    /** The node declared it holds this capability. Render the feature. */
    PRESENT,

    /** The node declared its capabilities and this was not among them. */
    ABSENT,

    /**
     * The node declared nothing at all — it predates the declaration, or the
     * probe failed. Distinct from [ABSENT] on purpose: every node released
     * today is here, and reading that as "the feature does not exist" would
     * hide surfaces that a newer node will serve.
     */
    UNDECLARED;

    /** Only a positive declaration earns the full UI. */
    val isUsable: Boolean get() = this == PRESENT
}

/**
 * Capability ids the client asks about.
 *
 * Strings rather than an enum on the wire: the node is the authority on what it
 * confers, and a client that could not represent an unknown capability would
 * have to drop it. These constants are the ones this UI gates on.
 */
object Capability {
    /** Registry: look an agent build up by hash — registered, deprecated, revoked. */
    const val REGISTRY_LOOKUP = "registry:lookup"

    /** The two verbs a canonical node holds that registry work rides on. */
    const val INFRA_ATTEST = "infra:attest"
    const val INFRA_SERVE = "infra:serve"
}

/**
 * A node's declared capabilities, or the absence of a declaration.
 *
 * @param declared null when the node said nothing — see [CapabilityState.UNDECLARED].
 */
data class NodeCapabilities(val declared: Set<String>?) {

    fun state(id: String): CapabilityState = when {
        declared == null -> CapabilityState.UNDECLARED
        id in declared -> CapabilityState.PRESENT
        else -> CapabilityState.ABSENT
    }

    fun has(id: String): Boolean = state(id).isUsable

    companion object {
        /** A node that has told us nothing. Every node released today. */
        val UNDECLARED = NodeCapabilities(null)
    }
}
