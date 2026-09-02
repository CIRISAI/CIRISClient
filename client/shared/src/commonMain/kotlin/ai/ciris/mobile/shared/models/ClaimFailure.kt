package ai.ciris.mobile.shared.models

/**
 * WHY A CLAIM FAILED — as a fact, not a sentence.
 *
 * Claiming a remote node is the act that binds a responsible party to it, and
 * it fails in ways that need DIFFERENT NEXT ACTIONS from the operator:
 *
 *   - the PIN was wrong                 -> re-read it from the node's console
 *   - the node is already claimed       -> nothing to do; it has an owner
 *   - the node could not be reached     -> a network/address problem, not a secret
 *   - the NodeCode itself is malformed  -> re-scan or re-paste the code
 *
 * Before this, all four rendered as one string ("Claim failed: …" with the
 * node's raw message appended, or a PIN message matched on English prose), so
 * an operator claiming a fleet of research agents could not tell "I mistyped
 * eight characters" from "this one already has an owner" without reading a
 * substrate error. Those are opposite situations: one is a retry, the other is
 * a success that already happened.
 *
 * MATCHED ON THE SERVER'S STABLE CODES, NOT ITS PROSE. CIRISServer emits
 * `auth.claim.pin_invalid`, `auth.claim.pin_missing` and `auth.claim.not_armed`
 * (`src/auth/bootstrap.rs`), and those are wire-stable in a way the English
 * sentences beside them are not — the previous prose match (`"claim pin"`,
 * `"invalid pin"`) breaks the moment the server rewords, and rewording an error
 * message is not a breaking change anybody would announce. The prose patterns
 * are kept BELOW the codes as a fallback for older nodes, which is the only
 * thing they are fit for.
 */
enum class ClaimFailure {
    /** The one-time PIN was wrong, or was not supplied. */
    PIN_REJECTED,

    /**
     * The node is not armed for a first-run claim — it has no one-time PIN
     * because ownership is already established. Not a retryable error.
     */
    ALREADY_CLAIMED,

    /** The target could not be reached: no transport hint, or the connection failed. */
    UNREACHABLE,

    /** The NodeCode could not be decoded. */
    BAD_NODE_CODE,

    /** Anything else — surfaced verbatim rather than guessed at. */
    UNKNOWN;

    /** Re-entering the PIN or retrying can plausibly succeed. */
    val isRetryable: Boolean
        get() = this == PIN_REJECTED || this == UNREACHABLE || this == BAD_NODE_CODE
}

/**
 * Classify a claim failure from whatever the node said.
 *
 * Order matters: the codes are checked first and win outright. A message can
 * satisfy more than one prose pattern (a target rejection body carries both the
 * word "claim" and an HTTP status), so the fallbacks are ordered most- to
 * least-specific and the first match is taken.
 */
fun classifyClaimFailure(message: String?): ClaimFailure {
    val m = message?.lowercase().orEmpty()
    if (m.isBlank()) return ClaimFailure.UNKNOWN

    // 1) The server's own codes. Wire-stable; these are the answer when present.
    if (m.contains("auth.claim.pin_invalid") || m.contains("auth.claim.pin_missing")) {
        return ClaimFailure.PIN_REJECTED
    }
    if (m.contains("auth.claim.not_armed")) return ClaimFailure.ALREADY_CLAIMED

    // 2) Prose, for nodes older than those codes. Never the primary signal.
    // "not armed for a first-run claim" is the distinguishing phrase, and it
    // must be tested BEFORE the PIN patterns: the sentence carrying it also
    // contains the words "one-time PIN", so a PIN-first order sends the operator
    // to the console to re-read a PIN that was never minted, on a node that
    // already has an owner. Caught by this class's own test.
    if (m.contains("not armed") || m.contains("already claimed") ||
        m.contains("ownership may already be claimed")
    ) {
        return ClaimFailure.ALREADY_CLAIMED
    }
    if (m.contains("claim_pin") || m.contains("claim pin") || m.contains("invalid pin")) {
        return ClaimFailure.PIN_REJECTED
    }
    if (m.contains("no transport_hint") || m.contains("cannot reach the node") ||
        m.contains("reach target node")
    ) {
        return ClaimFailure.UNREACHABLE
    }
    if (m.contains("decode target nodecode") || m.contains("bad node code")) {
        return ClaimFailure.BAD_NODE_CODE
    }
    return ClaimFailure.UNKNOWN
}
