package ai.ciris.mobile.shared.models.capability

/**
 * The registry's answer about one agent build.
 *
 * Ported from CIRISPortal's `/verify` (`AgentRecord`/`LookupResponse`), which is
 * the surface a canonical node serves once CIRISRegistry folds in. Registry work
 * is attestation-shaped — identity, license, revocation, build provenance — and
 * this is its smallest complete question: is this build registered, and is it
 * still good?
 */
enum class AgentStatus {
    REGISTERED,
    DEPRECATED,
    REVOKED,

    /**
     * The registry returned a status this client does not know.
     *
     * NOT folded into REVOKED or REGISTERED. A status we cannot read is not a
     * verdict we may invent, and guessing in either direction is a lie about a
     * revocation check: guess REGISTERED and a revoked build looks fine, guess
     * REVOKED and a good one is condemned. The UI shows the raw string.
     */
    UNKNOWN;

    /** Worth a warning banner — the build is registered but should not be used. */
    val isDiscouraged: Boolean get() = this == DEPRECATED || this == REVOKED

    companion object {
        /** Wire form is `AGENT_STATUS_REGISTERED` etc. */
        fun fromWire(raw: String?): AgentStatus = when (raw?.removePrefix("AGENT_STATUS_")?.uppercase()) {
            "REGISTERED" -> REGISTERED
            "DEPRECATED" -> DEPRECATED
            "REVOKED" -> REVOKED
            else -> UNKNOWN
        }
    }
}

/** One registry record, as the node reports it. */
data class AgentRecord(
    val agentHash: String,
    val agentType: String = "",
    val version: String = "",
    val status: AgentStatus = AgentStatus.UNKNOWN,
    val rawStatus: String = "",
    val capabilities: List<String> = emptyList(),
    val registeredAt: String = "",
    val hasAttestation: Boolean = false,
)

/**
 * The outcome of a lookup.
 *
 * FOUND-BUT-ABSENT AND COULD-NOT-ASK ARE DIFFERENT, and this is the third time
 * that distinction has earned its place in this codebase (see [CapabilityState]
 * and `ModeProbe`). "The registry has no record of this hash" is a real answer
 * an operator can act on. "We could not reach the registry" is not, and showing
 * the first when the second happened tells someone an unregistered build is
 * confirmed-unregistered.
 */
sealed interface LookupResult {
    /** The registry has a record. */
    data class Found(val record: AgentRecord) : LookupResult

    /** The registry answered and holds no record for this hash. */
    data object NotFound : LookupResult

    /** We could not get an answer. Never rendered as NotFound. */
    data class Unavailable(val reason: String) : LookupResult
}
