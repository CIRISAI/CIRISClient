package ai.ciris.mobile.shared.api

/**
 * The host this client is talking to does not serve [route], and the client
 * knew that before asking (a bare node in [ClientMode] node).
 *
 * A read that has no host to answer it has no answer, so it RAISES. It does not
 * return an empty list, a zero balance or a `hasCredit = true`: those are
 * claims about the person, made in the host's voice, about a question nobody
 * asked the host (CSD/3 §2.2; CSD-056, CSD-071). The same rule the wheels
 * placeholder follows in `AGENTS.md`: the absent thing raises, it never
 * returns something plausible.
 *
 * Screens render this as "this node doesn't have X", which is a fact about
 * the node, never as "you have nothing", which would be a fact about the person.
 */
class RouteNotOnThisHost(val route: String) :
    RuntimeException("$route is not served by this node (it runs without an agent)")

/**
 * A capacity read answered with a payload that carries no composite score and
 * five factors — today, CIRISServer's attestation report served on the same
 * path as the agent's score (CIRISServer#659). Raised instead of scoring the
 * absent fields as 0.00 (CSD-004).
 */
class CapacityPayloadUnrecognised(message: String) : RuntimeException(message)

/**
 * The statuses that mean "this host never had `/v1/wa/deferrals`" and so read
 * as an empty list. A 502 or 503 is NOT in it: that is a host that has the
 * route with a WA service missing behind it, which is a failed read of a
 * frozen agent, never "nothing waiting" (CSD-041; CC 4.3).
 */
val DEFERRALS_UNSERVED: Set<Int> = setOf(404, 405, 501)
