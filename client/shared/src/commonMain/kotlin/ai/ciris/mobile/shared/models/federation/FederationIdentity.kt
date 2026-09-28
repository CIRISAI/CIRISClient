package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Local agent's federation identity card.
 *
 * Backend source of truth: `ciris_engine/schemas/runtime/federation_api.py`
 * (``FederationIdentityResponse``). Returned by ``GET /v1/federation/identity``
 * inside the standard ``{"data": {...}}`` envelope.
 *
 * Does NOT include the global ``agent_mode`` — that ships separately on
 * ``GET /v1/system/agent-mode``.
 */
@Serializable
data class FederationIdentity(
    @SerialName("signer_key_id")
    val signerKeyId: String,
    @SerialName("crate_version")
    val crateVersion: String,
    @SerialName("peer_count_total")
    val peerCountTotal: Int,
    @SerialName("peer_count_canonical")
    val peerCountCanonical: Int,
    /**
     * Federation-surface capability strings advertised by the agent.
     * Mirrors the fixed literal list the route exposes on Edge 1.0.
     */
    val capabilities: List<String> = emptyList(),
    /**
     * Whether the two counts above were MEASURED. CIRISServer
     * `src/federation_surface.rs:124-150` degrades an unreadable peer store
     * (`store_unavailable`) or an unresolvable self (`self_identity_unresolved`)
     * to `0, 0` and says so here — "a zero that cannot say why it is zero is
     * not evidence" (CIRISServer#372). Null on a node that predates it.
     */
    @SerialName("peer_counts_standing")
    val peerCountsStanding: String? = null,
)

/**
 * A peer count as it may be drawn: the number when the node measured it (or
 * an older node that cannot say otherwise), null when the node said the zero
 * is not a reading. The screen draws null as [ai.ciris.mobile.shared.ui.screens.NOT_READ].
 */
fun FederationIdentity.peerCountReading(count: Int): String? =
    if (peerCountsStanding == null || peerCountsStanding == "measured") count.toString() else null
