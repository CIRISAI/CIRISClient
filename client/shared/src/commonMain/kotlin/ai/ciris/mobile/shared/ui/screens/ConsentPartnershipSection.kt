package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.AgentRead
import ai.ciris.mobile.shared.models.PartnershipDecider
import ai.ciris.mobile.shared.models.PartnershipHistoryDto
import ai.ciris.mobile.shared.models.PartnershipOptionsDto
import ai.ciris.mobile.shared.models.PartnershipQueue
import ai.ciris.mobile.shared.models.PartnershipRequestDto
import ai.ciris.mobile.shared.models.deciderOf
import ai.ciris.mobile.shared.models.partnershipWaitHours
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.RowFlag
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The tags this section draws — the contract CSD-054 §7 names. */
object PartnershipTags {
    const val SECTION = "partnership_section"
    const val OPTIONS = "partnership_options"
    const val OPTIONS_REVOCATION = "partnership_options_revocation"
    const val WHO_DECIDES = "partnership_who_decides"
    const val UNSIGNED = "partnership_unsigned"
    const val LIST = "partnership_pending_list"
    const val EMPTY = "partnership_pending_empty"
    const val LOADING = "partnership_loading"
    const val ADMIN_ONLY = "partnership_admin_only"
    const val METRICS = "partnership_metrics"
    /** ReadFailureBlock prefix: `partnership_error` / `partnership_not_on_this_node`. */
    const val FAILURE_PREFIX = "partnership"
    fun request(i: Int) = "partnership_request_$i"
    fun age(i: Int) = "partnership_request_age_$i"
    fun decider(i: Int) = "partnership_request_decider_$i"
    fun history(i: Int) = "partnership_history_$i"
}

/**
 * THE REQUESTS WAITING ON THIS AGENT, on the Consent card (CSD-054 §7).
 *
 * Read-only by design. Every request on the queue is one a person made, so its
 * answer is the agent's producer half (`consent:partnership_accept`, CC 3.3.1,
 * normative per leaf at CC 3.4.7). The route that would let the requester or an
 * administrator answer instead (`routes/partnership.py:583`) is the bypass the
 * route's own header forbids, so there is no accept, decline or defer here —
 * and every row says whose answer it is.
 *
 * No row carries a receipt: nothing here is signed (CIRISServer#423), and the
 * hamburger is only for a signed claim.
 */
@Composable
fun ConsentPartnershipSection(
    options: AgentRead<PartnershipOptionsDto>?,
    queue: PartnershipQueue,
    histories: Map<String, AgentRead<PartnershipHistoryDto>?>,
    onToggleHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    Column(modifier = modifier.fillMaxWidth().testable(PartnershipTags.SECTION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardShell(tag = PartnershipTags.OPTIONS) {
            Text(localizedString("partnership_queue.title"), style = type.title, color = t.ink)
            when (options) {
                null -> StateBlock(ListState.Loading, tag = "partnership_options_loading", inline = true)
                is AgentRead.Ok -> {
                    val o = options.value
                    FieldRow(
                        label = localizedString("partnership_queue.always_covers"),
                        value = o.requiredCategories.joinToString(" · ").ifBlank { localizedString("partnership_queue.categories_none") },
                    )
                    FieldRow(
                        label = localizedString("partnership_queue.may_add"),
                        value = o.optionalCategories.joinToString(" · ").ifBlank { localizedString("partnership_queue.categories_none") },
                    )
                    o.revocation?.let {
                        FieldRow(
                            label = localizedString("partnership_queue.ending"),
                            value = it,
                            gloss = localizedString("partnership_queue.revocable_not_undoable"),
                            tag = PartnershipTags.OPTIONS_REVOCATION,
                        )
                    }
                }
                AgentRead.AdminOnly -> Unit // /options is open to everyone signed in; a 403 here says nothing to draw
                is AgentRead.Failed -> ReadFailureBlock(
                    failure = options.failure,
                    tagPrefix = "partnership_options",
                    notOnThisNode = localizedString("partnership_queue.not_on_this_node"),
                    inline = true,
                )
            }
            FieldRow(
                label = localizedString("partnership_queue.who_decides_label"),
                value = localizedString("partnership_queue.who_decides"),
                tag = PartnershipTags.WHO_DECIDES,
            )
            FieldRow(
                label = localizedString("partnership_queue.signed_by_label"),
                value = localizedString("partnership_queue.signed_by_nobody"),
                tone = Tone.DIM,
                tag = PartnershipTags.UNSIGNED,
                divider = false,
            )
        }

        Text(
            localizedString("partnership_queue.waiting_title"),
            style = type.label, color = t.mute,
            modifier = Modifier.padding(top = 8.dp),
        )
        when (queue) {
            PartnershipQueue.Loading -> StateBlock(ListState.Loading, tag = PartnershipTags.LOADING, inline = true)
            PartnershipQueue.AdminOnly -> StateBlock(
                ListState.Empty(localizedString("partnership_queue.admin_only"), glyph = GlyphName.LOCK),
                tag = PartnershipTags.ADMIN_ONLY, inline = true,
            )
            is PartnershipQueue.Failed -> ReadFailureBlock(
                failure = queue.failure,
                tagPrefix = PartnershipTags.FAILURE_PREFIX,
                notOnThisNode = localizedString("partnership_queue.not_on_this_node"),
                inline = true,
            )
            is PartnershipQueue.Ready -> if (queue.requests.isEmpty()) {
                StateBlock(
                    ListState.Empty(localizedString("partnership_queue.empty"), glyph = GlyphName.CHECK),
                    tag = PartnershipTags.EMPTY, inline = true,
                )
                Text(localizedString("partnership_queue.memory_note"), style = type.body, color = t.mute)
            } else {
                Column(Modifier.fillMaxWidth().testable(PartnershipTags.LIST), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    queue.metrics?.let { m ->
                        Text(
                            localizedString(
                                "partnership_queue.metrics",
                                mapOf(
                                    "pending" to m.pendingCount.toString(),
                                    "accepted" to m.totalApprovals.toString(),
                                    "declined" to m.totalRejections.toString(),
                                    "deferred" to m.totalDeferrals.toString(),
                                ),
                            ),
                            style = type.signed, color = t.mute,
                            modifier = Modifier.testable(PartnershipTags.METRICS),
                        )
                    }
                    queue.requests.forEachIndexed { i, r ->
                        PartnershipRequestRow(i, r, histories, onToggleHistory)
                    }
                    Text(localizedString("partnership_queue.memory_note"), style = type.body, color = t.mute)
                }
            }
        }
    }
}

@Composable
private fun PartnershipRequestRow(
    i: Int,
    r: PartnershipRequestDto,
    histories: Map<String, AgentRead<PartnershipHistoryDto>?>,
    onToggleHistory: (String) -> Unit,
) {
    val (n, days) = partnershipWaitHours(r.ageHours)
    val waited = localizedString(
        if (days) "partnership_queue.waited_days" else "partnership_queue.waited_hours",
        mapOf("n" to n.toString()),
    )
    val tone = when (r.agingStatus.lowercase()) {
        "critical" -> Tone.DANGER
        "warning" -> Tone.BRAND
        else -> Tone.MUTE
    }
    val decider = when (deciderOf(r)) {
        PartnershipDecider.AGENT -> localizedString("partnership_queue.waiting_on_agent")
    }
    ItemRow(
        glyph = GlyphName.PERSON,
        title = r.userId,
        tag = PartnershipTags.request(i),
        meta = r.categories.joinToString(" · ").ifBlank { localizedString("partnership_queue.categories_none") },
        secondary = r.reason?.takeIf { it.isNotBlank() } ?: localizedString("partnership_queue.reason_none"),
        chips = listOf(ChipSpec(label = waited, tag = PartnershipTags.age(i), tone = tone)),
        flags = listOf(RowFlag(decider, tag = PartnershipTags.decider(i), tone = Tone.DIM)),
        onClick = { onToggleHistory(r.userId) },
    )
    if (r.userId in histories) {
        PartnershipHistoryBlock(i, histories[r.userId])
    }
}

@Composable
private fun PartnershipHistoryBlock(i: Int, read: AgentRead<PartnershipHistoryDto>?) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val tag = PartnershipTags.history(i)
    when (read) {
        null -> StateBlock(ListState.Loading, tag = tag, inline = true)
        AgentRead.AdminOnly -> StateBlock(
            ListState.Empty(localizedString("partnership_queue.admin_only"), glyph = GlyphName.LOCK), tag = tag, inline = true,
        )
        is AgentRead.Failed -> ReadFailureBlock(read.failure, tagPrefix = tag, inline = true)
        is AgentRead.Ok -> Column(
            Modifier.fillMaxWidth().padding(start = 48.dp).testable(tag),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (read.value.outcomes.isEmpty()) {
                Text(localizedString("partnership_queue.history_empty"), style = type.body, color = t.mute)
            }
            for (o in read.value.outcomes) {
                val outcome = when (o.outcomeType.lowercase()) {
                    "approved" -> localizedString("partnership_queue.outcome_approved")
                    "rejected" -> localizedString("partnership_queue.outcome_rejected")
                    "deferred" -> localizedString("partnership_queue.outcome_deferred")
                    "expired" -> localizedString("partnership_queue.outcome_expired")
                    else -> o.outcomeType
                }
                Text(
                    localizedString(
                        "partnership_queue.history_row",
                        mapOf("outcome" to outcome, "who" to o.decidedBy, "when" to o.decidedAt),
                    ),
                    style = type.signed, color = t.dim,
                )
                o.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = type.body, color = t.mute) }
            }
        }
    }
}
