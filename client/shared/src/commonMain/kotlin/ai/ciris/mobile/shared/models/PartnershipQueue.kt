package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * THE PARTNERSHIP REQUESTS WAITING ON THIS AGENT — the Consent card's view of
 * the `/v1/partnership` routes (CSD-054 §7). Wire shapes are CIRISAgent main's
 * `schemas/consent/core.py:240-302` and `routes/partnership.py:380-427`.
 *
 * WHOSE ANSWER EACH REQUEST IS. A partnership is a bilateral pair: the person's
 * half is `consent:partnership_grant`, the agent's is
 * `consent:partnership_accept`, emitted by the producer (CC 3.3.1; CC 3.4.7
 * makes it normative per leaf). A request a person made is the AGENT's to
 * answer, in its own reasoning (`partnership_utils.py:67-73`). The route that
 * would let someone else answer it (`POST /v1/partnership/decide`) admits the
 * requester and any administrator (`routes/partnership.py:583`) — the two
 * parties CC does not give that half to. So [deciderOf] has one answer, and the
 * card offers no button: see CSD-054 §7.
 */

@Serializable
data class PartnershipRequestDto(
    @SerialName("user_id") val userId: String,
    @SerialName("task_id") val taskId: String,
    val categories: List<String> = emptyList(),
    val reason: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("age_hours") val ageHours: Double = 0.0,
    @SerialName("aging_status") val agingStatus: String = "normal",
    val priority: String = "normal",
    val notes: String? = null,
)

@Serializable
data class PartnershipPendingDto(
    val requests: List<PartnershipRequestDto> = emptyList(),
    val total: Int = 0,
    @SerialName("by_status") val byStatus: Map<String, Int> = emptyMap(),
)

@Serializable
data class PartnershipMetricsDto(
    @SerialName("total_requests") val totalRequests: Int = 0,
    @SerialName("total_approvals") val totalApprovals: Int = 0,
    @SerialName("total_rejections") val totalRejections: Int = 0,
    @SerialName("total_deferrals") val totalDeferrals: Int = 0,
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("avg_pending_hours") val avgPendingHours: Double = 0.0,
    @SerialName("oldest_pending_hours") val oldestPendingHours: Double = 0.0,
    @SerialName("critical_count") val criticalCount: Int = 0,
)

@Serializable
data class PartnershipOutcomeDto(
    @SerialName("user_id") val userId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("outcome_type") val outcomeType: String,
    @SerialName("decided_by") val decidedBy: String,
    @SerialName("decided_at") val decidedAt: String,
    val reason: String? = null,
)

@Serializable
data class PartnershipHistoryDto(
    @SerialName("user_id") val userId: String,
    @SerialName("total_requests") val totalRequests: Int = 0,
    val outcomes: List<PartnershipOutcomeDto> = emptyList(),
    @SerialName("current_status") val currentStatus: String = "none",
)

@Serializable
data class PartnershipOptionsDto(
    @SerialName("required_categories") val requiredCategories: List<String> = emptyList(),
    @SerialName("optional_categories") val optionalCategories: List<String> = emptyList(),
    @SerialName("approval_process") val approvalProcess: String? = null,
    val benefits: List<String> = emptyList(),
    val responsibilities: List<String> = emptyList(),
    val revocation: String? = null,
)

/** Who gives the answer a request is waiting for. One value, on purpose. */
enum class PartnershipDecider { AGENT }

/**
 * The decider of a pending request. Always the agent: every request on the
 * queue was filed by a person asking for the `partnered` stream
 * (`service.py:288-290`), which makes the answer the producer half (CC 3.3.1).
 * The agent's own ask files no request at all (`service.py:1262` writes the
 * stream directly), so no row on this queue is ever the person's to answer.
 * If upstream ever files agent-initiated requests, this is where a second
 * value — and the first button — would go, and only with an `initiated_by`
 * field to tell them apart (CSD-054 §7, ask 2).
 */
@Suppress("UNUSED_PARAMETER")
fun deciderOf(request: PartnershipRequestDto): PartnershipDecider = PartnershipDecider.AGENT

/** How long a request has waited, in whole units: hours under two days, then days. */
fun partnershipWaitHours(hours: Double): Pair<Int, Boolean> {
    val h = if (hours.isNaN() || hours < 0) 0.0 else hours
    return if (h < 48.0) h.toInt() to false else (h / 24.0).toInt() to true
}

/** The queue as the card draws it: four states, never two that look alike. */
sealed interface PartnershipQueue {
    data object Loading : PartnershipQueue

    /** 403: only the agent's administrators see who is waiting on it. */
    data object AdminOnly : PartnershipQueue

    data class Failed(val failure: ReadFailure) : PartnershipQueue

    /** Read. [metrics] is null when that second read failed — the list still stands. */
    data class Ready(
        val requests: List<PartnershipRequestDto>,
        val metrics: PartnershipMetricsDto?,
    ) : PartnershipQueue
}

/**
 * Fold the two reads into what the card draws. The pending list decides the
 * state; metrics only decorate a list that was read. Pure.
 */
fun partnershipQueueOf(
    pending: AgentRead<PartnershipPendingDto>,
    metrics: AgentRead<PartnershipMetricsDto>?,
): PartnershipQueue = when (pending) {
    is AgentRead.Ok -> PartnershipQueue.Ready(
        requests = pending.value.requests.sortedByDescending { it.ageHours },
        metrics = (metrics as? AgentRead.Ok)?.value,
    )
    AgentRead.AdminOnly -> PartnershipQueue.AdminOnly
    is AgentRead.Failed -> PartnershipQueue.Failed(pending.failure)
}
