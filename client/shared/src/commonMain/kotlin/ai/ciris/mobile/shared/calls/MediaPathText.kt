package ai.ciris.mobile.shared.calls

/**
 * CSD-134 §2.1: the sentences for a member this device could not reach directly,
 * as localization keys and their parameters, so the panel is assembled from
 * translated parts and never from the node's English.
 *
 * Every key is written out literally (no string building), so the string checks
 * see each one. A token this client does not know falls back to the generic key;
 * the raw token is still shown under Details, so nothing is dropped silently.
 *
 * Edge cannot tell a person's choice from their node's policy, so no sentence
 * here says "{person} isn't accepting direct calls".
 */
object MediaPathText {
    /** One line: a key and the values for its placeholders. */
    data class Line(val key: String, val params: Map<String, String> = emptyMap())

    /** Edge 45.2.0's frozen causes. Later 45.x only adds; an unknown one reads as `other`. */
    val CAUSES = listOf(
        "both_nats_random", "punch_no_answer", "punch_one_way", "nat_undetermined", "no_facilitator",
        "refused_by_peer_policy", "peer_unsupported", "budget_refused", "not_trusted",
        "no_member_could_relay", "anonymous_no_direct", "other",
    )

    /** The headline: what went wrong, naming the person. */
    fun headline(f: MediaPathFailure, person: String): Line {
        val p = mapOf("person" to person)
        return when (f.cause) {
            "both_nats_random" -> Line("mobile.call_path_both_nats_random", p)
            "punch_no_answer" -> Line("mobile.call_path_punch_no_answer", p)
            "punch_one_way" -> Line("mobile.call_path_punch_one_way", p)
            "nat_undetermined" -> Line("mobile.call_path_nat_undetermined", p)
            "no_facilitator" -> Line("mobile.call_path_no_facilitator", p)
            "refused_by_peer_policy" -> Line("mobile.call_path_refused_by_peer_policy", p)
            "peer_unsupported" -> Line("mobile.call_path_peer_unsupported", p)
            "budget_refused" -> f.retryAfterS?.let { Line("mobile.call_path_budget_refused", mapOf("seconds" to it.toString())) }
                ?: Line("mobile.call_path_budget_refused_shortly")
            "not_trusted" -> Line("mobile.call_path_not_trusted", p)
            "no_member_could_relay" -> Line("mobile.call_path_no_member_could_relay", p)
            "anonymous_no_direct" -> Line("mobile.call_path_anonymous_no_direct", p)
            else -> Line("mobile.call_path_other", p)
        }
    }

    /**
     * The secondary line: why no one relayed it. `peer_relay_in_use` is reserved
     * and never on a failure, and an unknown standing says nothing rather than guess.
     */
    fun relay(f: MediaPathFailure): Line? = when (f.relay) {
        "refused_by_policy" -> Line("mobile.call_relay_refused_by_policy")
        "none_available" -> Line("mobile.call_relay_none_available")
        else -> null
    }

    /** The network type as words; an unknown class reads as "unknown", never as a guess. */
    fun natKey(legacyClass: String?): String = when (legacyClass) {
        "open" -> "mobile.call_nat_open"
        "full_cone" -> "mobile.call_nat_full_cone"
        "restricted" -> "mobile.call_nat_restricted"
        "port_restricted" -> "mobile.call_nat_port_restricted"
        "symmetric" -> "mobile.call_nat_symmetric"
        else -> "mobile.call_nat_unknown"
    }

    /**
     * Both networks in one line. The peer's is its own claim, so it is worded "as
     * they report it"; when there is no claim, it says the type isn't known.
     * [mine] and [theirs] are [natKey] already rendered in the current language.
     */
    fun networks(f: MediaPathFailure, person: String, mine: String, theirs: String?): Line =
        if (f.peerNat != null && theirs != null) {
            Line("mobile.call_path_networks", mapOf("nat" to mine, "person" to person, "peer_nat" to theirs))
        } else Line("mobile.call_path_networks_peer_unknown", mapOf("nat" to mine, "person" to person))

    /** Said only when this device holds a port mapping on its router. Always absent on the LTS. */
    fun portOpen(f: MediaPathFailure): Line? = if (f.localNat?.portHeld == true) Line("mobile.call_path_port_open") else null

    private val BLOCKING = setOf("symmetric", "port_restricted")

    /**
     * What the person can do. Our side blocking: try another network (the
     * open-a-port wizard is 0.6 work). Only their side: say so, with nothing to
     * press, because nothing here can change their network.
     */
    fun advice(f: MediaPathFailure, person: String): Line? {
        // Anonymous mode is why, not the network: another network would not help.
        if (f.cause == "anonymous_no_direct") return null
        val mine = f.localNat?.legacyClass in BLOCKING
        val theirs = f.peerNat?.legacyClass in BLOCKING
        return when {
            mine -> Line("mobile.call_path_action_local")
            theirs -> Line("mobile.call_path_action_peer", mapOf("person" to person))
            else -> null
        }
    }

    /** "Try again" is offered only where edge says it could help. */
    fun offersRetry(f: MediaPathFailure): Boolean = f.retryable

    /**
     * The restored line, briefly, in place of the panel. Through another member
     * names them ([relayer], "another member" when the node could not say who);
     * every other path kind reads "directly", the local network as such.
     */
    fun restored(r: PathRestored, person: String, relayer: String): Line = when {
        !r.direct -> Line("mobile.call_path_restored_relay", mapOf("person" to person, "relayer" to relayer))
        r.via == "direct_lan" -> Line("mobile.call_path_restored_lan", mapOf("person" to person))
        else -> Line("mobile.call_path_restored", mapOf("person" to person))
    }

    /**
     * Details: the node's tokens verbatim, for the person who wants them and for
     * a bug report. Not translated, on purpose: they are identifiers, not prose.
     */
    fun details(f: MediaPathFailure): List<String> = buildList {
        add("cause=${f.cause}" + (f.detail?.let { " detail=$it" } ?: "") + " retryable=${f.retryable}" +
            (f.retryAfterS?.let { " retry_after_s=$it" } ?: ""))
        f.relay?.let { add("relay=$it") }
        f.localNat?.let { add("local: " + axes(it)) }
        add("peer (self-reported): " + (f.peerNat?.let(::axes) ?: "none"))
        f.tried.forEach { t ->
            add(
                "tried ${t.kind}: ${t.outcome}" +
                    listOfNotNull(
                        t.attempts?.let { "attempts=$it" }, t.elapsedMs?.let { "elapsed_ms=$it" },
                        t.knocksSent?.let { "knocks=$it" }, t.sockets?.let { "sockets=$it" },
                    ).joinToString(" ", prefix = " ").trimEnd()
            )
        }
    }

    private fun axes(n: NatReport) = listOfNotNull(
        "class=${n.legacyClass}", n.mapping?.let { "mapping=$it" }, n.allocation?.let { "allocation=$it" },
        n.filtering?.let { "filtering=$it" }, n.portPreserving?.let { "port_preserving=$it" },
        n.routerMapping?.let { "router=$it" }, n.family?.let { "family=$it" }, n.evidence?.let { "evidence=$it" },
    ).joinToString(" ")
}
