package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Edge metrics snapshot returned by ``GET /v1/federation/metrics``.
 *
 * Wraps the Rust-side ``Edge.metrics_snapshot()`` result. Each family
 * is a string-keyed map; the family keys themselves are stable across
 * Edge releases (typed below), but the sub-keys (envelope kinds,
 * transport mediums, failure reasons) are open-ended — new Edge
 * releases add new sub-keys without bumping the API contract.
 *
 * Backend source of truth: ``FederationMetricsResponse`` in
 * ``ciris_engine/schemas/runtime/federation_api.py``. The pydantic
 * model exposes one field per family (flat shape); we mirror that
 * flat shape exactly so kotlinx-serialization round-trips against the
 * backend's ``model_dump_json()`` output.
 *
 * For consumers that prefer a generic family-keyed view (e.g. a
 * "renderer that knows family names from config"), call [families].
 * Internal counters are typed as [Long] so 32-bit accumulators don't
 * truncate at the multi-day mark; the reachability ratio family uses
 * [Double].
 */
@Serializable
data class FederationMetricsResponse(
    @SerialName("envelopes_sent_total")
    val envelopesSentTotal: Map<String, Long> = emptyMap(),
    @SerialName("envelopes_received_total")
    val envelopesReceivedTotal: Map<String, Long> = emptyMap(),
    @SerialName("send_failures_total")
    val sendFailuresTotal: Map<String, Long> = emptyMap(),
    @SerialName("verify_failures_total")
    val verifyFailuresTotal: Map<String, Long> = emptyMap(),
    /**
     * The durable outbound queue's depth. Two wire shapes, and the client must
     * read both: through CIRISServer 0.5.222 a per-class MAP of counters edge
     * kept beside the queue; since 0.5.223 (CIRISEdge#845, persist v54
     * `outbound_counts`) ONE number — the rows actually RESIDENT in the queue
     * (pending / sending / awaiting_ack) — or `null` when the queue could not
     * be counted ("never 0, which would read as drained"). Typed as a
     * [JsonElement] because a `Map<String, Long>` failed to decode the number
     * and took the whole metrics read down with it. Read it through
     * [getQueueDepth].
     */
    @SerialName("durable_queue_depth")
    val durableQueueDepth: JsonElement? = null,
    /** Cumulative enqueues per delivery class (0.5.223+; the old map's successor). */
    @SerialName("durable_enqueued_total")
    val durableEnqueuedTotal: Map<String, Long>? = null,
    @SerialName("transport_bytes_in_total")
    val transportBytesInTotal: Map<String, Long> = emptyMap(),
    @SerialName("transport_bytes_out_total")
    val transportBytesOutTotal: Map<String, Long> = emptyMap(),
    @SerialName("peer_reachability_ratio")
    val peerReachabilityRatio: Map<String, Double> = emptyMap(),
    // The WIRE key is still `inline_text_subscriber_count`: the node serves the
    // verified-feed receiver count UNDER that name (CIRISServer
    // src/federation_surface.rs:317). The client read
    // `verified_feed_subscriber_count`, a key no node sends, so it was always 0.
    @SerialName("inline_text_subscriber_count")
    val verifiedFeedSubscriberCount: Long = 0L,
    // ── The REPLICATION plane (CIRISServer#377, CIRISEdge#434/#457) ─────────
    // The four counters above are the APPLICATION plane only; anti-entropy
    // replication (the plane that carries trace:*) increments none of them, so
    // their zeros "say nothing about carriage" (the node's own `plane_note`,
    // federation_surface.rs:332). These are the fields that do. All nullable:
    // a node that predates them has not said, which is not zero.
    @SerialName("replication_envelopes_served_total")
    val replicationEnvelopesServedTotal: Map<String, Long>? = null,
    @SerialName("replication_applied_total")
    val replicationAppliedTotal: Map<String, Long>? = null,
    /** `unreadable|not_exercised|idle|moving|withholding` (operator_surface.rs:391-421). */
    @SerialName("carriage_standing")
    val carriageStanding: String? = null,
    /** `unreadable|not_exercised|idle|converged|applying|refusing` (operator_surface.rs:486-525). */
    @SerialName("receive_standing")
    val receiveStanding: String? = null,
    @SerialName("receive_decided_total")
    val receiveDecidedTotal: Long? = null,
    @SerialName("plane_note")
    val planeNote: String? = null,
) {

    /** Rows served to peers by replication, or null when the node did not say. */
    fun replicationServed(): Long? = replicationEnvelopesServedTotal?.values?.sum()

    /** Rows applied from peers by replication, or null when the node did not say. */
    fun replicationApplied(): Long? = replicationAppliedTotal?.values?.sum()

    /** Total envelopes sent across all envelope kinds. */
    fun getEnvelopesSent(): Long = envelopesSentTotal.values.sum()

    /** Total envelopes received across all envelope kinds. */
    fun getEnvelopesReceived(): Long = envelopesReceivedTotal.values.sum()

    /** Total send failures across all transport/reason combos. */
    fun getSendFailures(): Long = sendFailuresTotal.values.sum()

    /** Total verify failures across all reasons. */
    fun getVerifyFailures(): Long = verifyFailuresTotal.values.sum()

    /**
     * Rows in the durable queue, or null when the node did not say or could
     * not count them — a depth nobody read is not 0 (CSD-049).
     */
    fun getQueueDepth(): Long? = when (val d = durableQueueDepth) {
        null, JsonNull -> null
        is JsonPrimitive -> d.longOrNull
        is JsonObject -> d.values.sumOf { (it as? JsonPrimitive)?.longOrNull ?: 0L }
        else -> null
    }

    /** [durableQueueDepth] as a family map: the per-class map as served, or `resident` → n. */
    private fun queueDepthFamily(): Map<String, Double> = when (val d = durableQueueDepth) {
        is JsonObject -> d.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap()
        is JsonPrimitive -> d.doubleOrNull?.let { mapOf("resident" to it) } ?: emptyMap()
        else -> emptyMap()
    }

    /** Total inbound transport bytes across all transports. */
    fun getBytesIn(): Long = transportBytesInTotal.values.sum()

    /** Total outbound transport bytes across all transports. */
    fun getBytesOut(): Long = transportBytesOutTotal.values.sum()

    /**
     * Mean reachability ratio across all observed (peer, medium) pairs
     * in ``[0.0, 1.0]``, or ``null`` when nothing has been measured
     * yet. Distinct from the empty-map "unknown" case which becomes
     * ``null`` here — same load-bearing distinction as
     * [EdgePeerReachability.byMedium].
     */
    fun meanReachabilityRatio(): Double? {
        if (peerReachabilityRatio.isEmpty()) return null
        return peerReachabilityRatio.values.average()
    }

    /**
     * Family-keyed view of the metrics for renderers that don't want
     * to switch on each typed field. Counter families are coerced to
     * Double so all families share a value type; consumers that need
     * integer accuracy should reach for the typed [Map] field
     * directly (the typed fields preserve [Long] precision).
     */
    fun families(): Map<String, Map<String, Double>> = mapOf(
        FAMILY_ENVELOPES_SENT to envelopesSentTotal.mapValues { it.value.toDouble() },
        FAMILY_ENVELOPES_RECEIVED to envelopesReceivedTotal.mapValues { it.value.toDouble() },
        FAMILY_SEND_FAILURES to sendFailuresTotal.mapValues { it.value.toDouble() },
        FAMILY_VERIFY_FAILURES to verifyFailuresTotal.mapValues { it.value.toDouble() },
        FAMILY_DURABLE_QUEUE_DEPTH to queueDepthFamily(),
        FAMILY_TRANSPORT_BYTES_IN to transportBytesInTotal.mapValues { it.value.toDouble() },
        FAMILY_TRANSPORT_BYTES_OUT to transportBytesOutTotal.mapValues { it.value.toDouble() },
        FAMILY_PEER_REACHABILITY_RATIO to peerReachabilityRatio,
    )

    companion object {
        const val FAMILY_ENVELOPES_SENT = "envelopes_sent_total"
        const val FAMILY_ENVELOPES_RECEIVED = "envelopes_received_total"
        const val FAMILY_SEND_FAILURES = "send_failures_total"
        const val FAMILY_VERIFY_FAILURES = "verify_failures_total"
        const val FAMILY_DURABLE_QUEUE_DEPTH = "durable_queue_depth"
        const val FAMILY_TRANSPORT_BYTES_IN = "transport_bytes_in_total"
        const val FAMILY_TRANSPORT_BYTES_OUT = "transport_bytes_out_total"
        const val FAMILY_PEER_REACHABILITY_RATIO = "peer_reachability_ratio"
    }
}
