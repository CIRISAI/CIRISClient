package ai.ciris.mobile.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **What the node says it offers**: the two ungated catalogue reads.
 *
 * ```text
 * GET /v1/vocabulary   every enumerable wire vocabulary   (CIRISServer src/vocabulary_surface.rs:50)
 * GET /v1/operations   the graded-act ladder, as rows     (CIRISServer src/operations_catalogue.rs:50)
 * ```
 *
 * Both exist so a client stops spelling what the substrate already defines. A
 * hand-copied member compiles and skews: the duty picker offered five duties
 * while persist admitted seven (CIRISClient#108), and `AdminLadderOp` re-derived
 * every column of the ladder with nothing failing when the two disagreed
 * (CIRISClient#109). So pickers and the ladder are built from these reads, and
 * the compiled lists survive only as DISPLAY ORDER, as the labels and message
 * ids the node does not send, and as the fallback on a node that serves
 * neither route, which the screen then says it is using.
 */

/**
 * `GET /v1/vocabulary`: `{ "<name>": { "all": [...], "<axis>": [...] } }`.
 *
 * Kept as the map it is on the wire, so a vocabulary or axis added upstream
 * arrives without a client change.
 */
data class NodeVocabulary(val sets: Map<String, Map<String, List<String>>>) {
    /** One axis of one vocabulary, or null when the node does not serve it. */
    fun axis(vocabulary: String, axis: String): List<String>? = sets[vocabulary]?.get(axis)

    /** The scopes a moderation duty may carry (`delegation_scope.moderation`). */
    val moderationScopes: List<String>? get() = axis("delegation_scope", "moderation")
}

/** One row of `GET /v1/operations` (`operations_catalogue.rs::get_operations`). */
@Serializable
data class OperationRow(
    /** Stable op token, the same string the ledger records as `admin_action:{op}`. */
    val op: String = "",
    /** The route to POST to. The client never builds it from [op]. */
    val route: String = "",
    /** Ladder position. Null for the read-only preview, which is not graded. */
    val tier: Int? = null,
    /** The delegation scope the named delegation must itself carry. */
    val scope: String? = null,
    /** Independent delegation chains required. */
    val quorum: Int = 1,
    /** The op that undoes this one, or null. */
    val reverses: String? = null,
    /** Whether the act changes what the SUBSTRATE accepts, not just what this node records. */
    @SerialName("reaches_substrate") val reachesSubstrate: Boolean = false,
)

/** `GET /v1/operations`. */
@Serializable
data class OperationsCatalogue(
    @SerialName("selection_cardinality") val selectionCardinality: String? = null,
    @SerialName("commit_fields") val commitFields: List<String> = emptyList(),
    val operations: List<OperationRow> = emptyList(),
)

/**
 * Where a catalogue-driven control got its members. A screen names the
 * fallback out loud: a compiled list shown as if the node had served it is the
 * silent drift these routes exist to end.
 */
sealed class CatalogueSource {
    /** The node served it. */
    data object Node : CatalogueSource()

    /** The node has no such route (an older node); the compiled list stands in. */
    data class NotOnThisNode(val detail: String? = null) : CatalogueSource()

    /** The node was asked and the read failed; the compiled list stands in. */
    data class Unreadable(val detail: String?) : CatalogueSource()

    /** Not asked yet. */
    data object NotLoaded : CatalogueSource()
}

/**
 * **One rung of the ladder, as the node states it**, joined to the labels and
 * message ids this app ships for it ([local]).
 *
 * Tier, scope, quorum, route and the reversal pair come from the served row;
 * [local] contributes only what the node does not send. A served op with no
 * local entry is still a rung: it renders with its own token, and its limits
 * say this app cannot state them.
 */
data class LadderRung(
    val op: String,
    val route: String,
    val tier: Int,
    val scope: String?,
    val quorum: Int,
    /** The op that undoes this one, as served. Null in the fallback: the compiled table never had it. */
    val reverses: String?,
    /** The op this one undoes (a served row whose `reverses` names this op). */
    val reversedBy: String?,
    /** Null when the node did not state it (the fallback). */
    val reachesSubstrate: Boolean?,
    val local: AdminLadderOp?,
) {
    /**
     * The test-tag stem: the compiled entry's name where there is one, so the
     * tags flows already drive (`chip_ladder_quarantine`) survive; the served
     * token otherwise.
     */
    val tagStem: String get() = local?.name?.lowercase() ?: op

    val requiresQuorum: Boolean get() = quorum > 1
    val requiresCommunityId: Boolean get() = local?.requiresCommunityId ?: false
    val acceptsRevokedAfter: Boolean get() = local?.acceptsRevokedAfter ?: false

    /**
     * The compiled entry decides where there is one (descend). For an op this
     * app has never seen, no inverse on either side reads as irreversible: the
     * heavier gate is the safe mistake.
     */
    val irreversible: Boolean get() = local?.irreversible ?: (reverses == null && reversedBy == null)

    companion object {
        /** The compiled ladder, for a node that serves no catalogue. */
        fun fallback(): List<LadderRung> = AdminLadderOp.entries.map { local ->
            LadderRung(
                op = local.wireOp,
                route = local.route,
                tier = local.tier,
                scope = local.requiredScope,
                quorum = if (local.requiresQuorum) AdminLadderOp.DESCEND_QUORUM_MIN else 1,
                reverses = null,
                reversedBy = null,
                reachesSubstrate = null,
                local = local,
            )
        }

        /**
         * The served ladder: every graded row (the untiered preview is not a
         * rung), in the node's order, joined to its compiled entry by op token
         * and then by route.
         */
        fun fromCatalogue(rows: List<OperationRow>): List<LadderRung> = rows
            .filter { it.tier != null && it.op.isNotBlank() && it.route.isNotBlank() }
            .map { row ->
                LadderRung(
                    op = row.op,
                    route = row.route,
                    tier = row.tier ?: 0,
                    scope = row.scope,
                    quorum = row.quorum,
                    reverses = row.reverses,
                    reversedBy = rows.firstOrNull { it.reverses == row.op }?.op,
                    reachesSubstrate = row.reachesSubstrate,
                    local = AdminLadderOp.entries.firstOrNull { it.wireOp == row.op }
                        ?: AdminLadderOp.entries.firstOrNull { it.route == row.route },
                )
            }

        /** The distinct tiers a duty set unlocks: the rungs whose scope it carries. */
        fun tiersUnlockedBy(rungs: List<LadderRung>, duties: Set<String>): List<Int> =
            rungs.filter { it.scope != null && it.scope in duties }.map { it.tier }.distinct().sorted()
    }
}

/**
 * **The duty-conferral menu**, built from what the node serves.
 *
 * [served] is `delegation_scope.moderation`. The compiled [displayOrder] only
 * orders what was served; a served member it does not know goes after, in the
 * node's order, and is offered with its wire token. Null [served] (no read)
 * falls back to [displayOrder], and the screen says so.
 */
fun dutyMenu(served: List<String>?, displayOrder: List<String>): List<String> {
    if (served == null) return displayOrder
    val known = displayOrder.filter { it in served }
    return known + served.filter { it !in displayOrder }.distinct()
}
