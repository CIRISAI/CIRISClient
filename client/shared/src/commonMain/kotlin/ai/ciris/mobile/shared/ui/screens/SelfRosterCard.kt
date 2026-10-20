package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.RosterProposal
import ai.ciris.mobile.shared.models.federation.RosterStanding
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisShape
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.viewmodels.RosterSettled
import ai.ciris.mobile.shared.viewmodels.RosterSettledHow
import ai.ciris.mobile.shared.viewmodels.SelfRosterUiState
import ai.ciris.mobile.shared.viewmodels.SelfRosterViewModel
import ai.ciris.mobile.shared.viewmodels.signaturesNeeded
import ai.ciris.mobile.shared.viewmodels.signaturesSoFar
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Test tags for the self-key roster card (CSD-095). One place, so the CSD, the flows and the card cannot drift. */
object RosterTags {
    const val CARD = "card_self_roster"
    const val EMPTY = "self_roster_empty"
    const val TOO_OLD = "self_roster_too_old"
    const val REFUSAL = "self_roster_refusal"
    const val ANSWER_OUTCOME = "text_self_roster_answer_outcome"
    const val ANSWER_RECEIPT = "text_self_roster_receipt"
    const val ANSWER_REFUSAL = "self_roster_answer_refusal"
    const val HISTORY = "self_roster_history"
    const val CONFIRM_PREFIX = "confirm_roster_answer"
    fun row(id: String) = "self_roster_row_$id"
    fun kind(id: String) = "text_self_roster_kind_$id"
    fun signatures(id: String) = "text_self_roster_signatures_$id"
    fun path(id: String) = "text_self_roster_path_$id"
    fun objectors(id: String) = "text_self_roster_objectors_$id"
    fun own(id: String) = "text_self_roster_own_$id"
    fun answered(id: String) = "text_self_roster_answered_$id"
    fun assent(id: String) = "btn_self_roster_assent_$id"
    fun objectTo(id: String) = "btn_self_roster_object_$id"
    fun settled(id: String) = "text_self_roster_settled_$id"
}

/** `2026-10-21 14:05` in this device's zone, from an RFC 3339 instant; the wire text when it does not parse. */
fun rosterTimeText(iso: String): String = try {
    val d = Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = d.hour.toString().padStart(2, '0')
    val mm = d.minute.toString().padStart(2, '0')
    "${d.date} $hh:$mm"
} catch (e: Exception) {
    iso
}

@Composable
private fun refusedReasonText(token: String?): String = when (token) {
    "proposer_not_on_roster" -> localizedString("mobile.roster_refused_proposer_not_on_roster")
    "target_already_on_roster" -> localizedString("mobile.roster_refused_target_already_on_roster")
    "target_not_on_roster" -> localizedString("mobile.roster_refused_target_not_on_roster")
    "proposer_is_target" -> localizedString("mobile.roster_refused_proposer_is_target")
    "proposer_has_open_proposal" -> localizedString("mobile.roster_refused_proposer_has_open_proposal")
    "target_has_open_proposal" -> localizedString("mobile.roster_refused_target_has_open_proposal")
    else -> token.orEmpty()
}

@Composable
private fun cancelledReasonText(token: String?): String = when (token) {
    "cancel_reached" -> localizedString("mobile.roster_cancelled_cancel_reached")
    "below_floor" -> localizedString("mobile.roster_cancelled_below_floor")
    "no_reverse_path" -> localizedString("mobile.roster_cancelled_no_reverse_path")
    "target_spoke" -> localizedString("mobile.roster_cancelled_target_spoke")
    else -> token.orEmpty()
}

/** A device's name: its label on the roster, else the one this device gave it, else the short key. */
private fun deviceName(key: String, labels: Map<String, String>, state: SelfRosterUiState): String =
    labels[key]?.takeIf { it.isNotBlank() } ?: state.proposedLabels[key]?.takeIf { it.isNotBlank() } ?: shortKey(key)

@Composable
private fun changeText(p: RosterProposal, labels: Map<String, String>, state: SelfRosterUiState): String {
    val known = labels[p.target] ?: state.proposedLabels[p.target]
    return if (p.kind == "evict") {
        localizedString("mobile.roster_kind_evict", "device", deviceName(p.target, labels, state))
    } else {
        localizedString("mobile.roster_kind_add", "device", known?.takeIf { it.isNotBlank() } ?: localizedString("mobile.roster_new_device"))
    }
}

/**
 * **Changes to your devices** (CSD-095, CIRISServer#794, CC 3.3.6.3): the
 * changes to the person's own device keys their roster holds, each with who
 * proposed it, how many signatures it has of how many, how it can still land,
 * who objected, and Agree / Object for a device that did not propose it.
 *
 * Polls while a change is open (every 5 s), else every 30 s; never during an
 * answer. [labels] names roster keys (occurrence key id → device name).
 */
@Composable
fun SelfRosterCard(viewModel: SelfRosterViewModel, labels: Map<String, String>, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.refresh().join()
            val s = viewModel.state.value
            if (s.nodeTooOld) break
            delay(if (s.open.isNotEmpty()) 5_000 else 30_000)
        }
    }

    CardShell(modifier = modifier, tag = RosterTags.CARD, accent = t.brand) {
        Text(localizedString("mobile.roster_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(4.dp))
        Text(localizedString("mobile.roster_desc"), style = type.body, color = t.dim)
        Spacer(Modifier.height(10.dp))

        if (state.nodeTooOld) {
            val why = localizedString("mobile.roster_too_old")
            Text(why, style = type.body, color = t.dim, modifier = Modifier.testable(RosterTags.TOO_OLD, why))
            return@CardShell
        }
        state.refusal?.let { r ->
            RosterRefusalBlock(r.reasonId, r.detail, localizedString("mobile.roster_refused"), RosterTags.REFUSAL)
        }
        if (state.loaded && state.proposals.isEmpty() && state.refusal == null) {
            StateBlock(ListState.Empty(localizedString("mobile.roster_empty")), tag = RosterTags.EMPTY, inline = true)
        }
        state.proposals.forEach { s -> ProposalRow(s, state, labels, viewModel) }

        state.answerRefusal?.let { r ->
            RosterRefusalBlock(r.reasonId, r.detail, localizedString("mobile.roster_answer_refused"), RosterTags.ANSWER_REFUSAL)
        }
        state.lastAnswer?.let { a ->
            Spacer(Modifier.height(8.dp))
            val line = when {
                a.settled -> localizedString(if (a.assent) "mobile.roster_answered_assent" else "mobile.roster_answered_object") +
                    " " + localizedString("mobile.roster_answered_settled")
                else -> localizedString(if (a.assent) "mobile.roster_answered_assent" else "mobile.roster_answered_object")
            }
            Text(
                line, style = type.body, color = t.ok,
                modifier = Modifier.testable(RosterTags.ANSWER_OUTCOME, (if (a.assent) "assent" else "object") + if (a.settled) ":settled" else ""),
            )
            a.attestationId?.let { id ->
                Text(
                    localizedString("mobile.roster_receipt", "id", shortKey(id, head = 12, tail = 6)),
                    style = type.signed, color = t.dim,
                    modifier = Modifier.testable(RosterTags.ANSWER_RECEIPT, id),
                )
            }
        }
        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(modifier = Modifier.fillMaxWidth().testable(RosterTags.HISTORY)) {
                Text(localizedString("mobile.roster_history_title"), style = type.label, color = t.mute)
                state.history.forEach { h -> SettledLine(h, labels, state) }
            }
        }
    }

    state.confirming?.let { c ->
        val p = c.standing.proposal
        ConfirmSheet(
            title = localizedString(if (c.assent) "mobile.roster_confirm_assent_title" else "mobile.roster_confirm_object_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.roster_fact_change"), changeText(p, labels, state)),
                ConfirmFact(localizedString("mobile.roster_fact_proposer"), deviceName(p.proposer, labels, state)),
                ConfirmFact(
                    localizedString("mobile.roster_fact_effect"),
                    localizedString(if (c.assent) "mobile.roster_effect_assent" else "mobile.roster_effect_object"),
                ),
            ),
            confirmLabel = localizedString(if (c.assent) "mobile.roster_assent" else "mobile.roster_object"),
            onConfirm = { viewModel.confirm() },
            onDismiss = { viewModel.dismissConfirm() },
            destructive = !c.assent,
            tagPrefix = RosterTags.CONFIRM_PREFIX,
        )
    }
}

/** A refusal by id: the bundle's words first, the node's English beneath; the English alone when the id is unknown. */
@Composable
private fun RosterRefusalBlock(reasonId: String?, detail: String?, fallbackTitle: String, tag: String) {
    val resolved = reasonId?.let { localizedString(it).takeIf { s -> s != reasonId && s.isNotBlank() } }
    val title = resolved ?: detail?.takeIf { it.isNotBlank() } ?: fallbackTitle
    StateBlock(
        ListState.Error(title = title, detail = detail?.takeIf { it.isNotBlank() && it != title && resolved == null }),
        tag = tag,
        inline = true,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ProposalRow(s: RosterStanding, state: SelfRosterUiState, labels: Map<String, String>, viewModel: SelfRosterViewModel) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val id = s.proposal.id
    val d = s.decision
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(CirisShape.card)
            .border(CirisShape.hairlineWidth, t.hairline, CirisShape.card)
            .padding(12.dp)
            .testable(RosterTags.row(id), s.outcome.ifBlank { d.outcome }),
    ) {
        Text(
            changeText(s.proposal, labels, state),
            style = type.body.copy(fontWeight = FontWeight.SemiBold), color = t.ink,
            modifier = Modifier.testable(RosterTags.kind(id), s.proposal.kind),
        )
        Text(
            localizedString("mobile.roster_proposed_by", "device", deviceName(s.proposal.proposer, labels, state)),
            style = type.label, color = t.dim,
        )
        Spacer(Modifier.height(4.dp))
        val have = s.signaturesSoFar()
        val need = s.signaturesNeeded(state.roster.size)
        Text(
            localizedString("mobile.roster_signatures", mapOf("have" to have.toString(), "need" to need.toString())),
            style = type.body, color = t.ink,
            modifier = Modifier.testable(RosterTags.signatures(id), "$have/$need"),
        )
        val (pathLine, pathValue) = when (d.outcome) {
            "pending" -> {
                val parts = mutableListOf<String>()
                d.needs?.takeIf { it > 0 }?.let { parts += localizedString("mobile.roster_needs_more", "count", it.toString()) }
                parts += d.earliestLoneAt?.let { localizedString("mobile.roster_lone_at", "time", rosterTimeText(it)) }
                    ?: localizedString("mobile.roster_lone_closed")
                parts.joinToString(" ") to (if (d.earliestLoneAt == null) "pending:lone_closed" else "pending:lone_open")
            }
            "lands" -> localizedString(if (d.path == "lone") "mobile.roster_lands_lone" else "mobile.roster_lands_quorum") to "lands:${d.path ?: "quorum"}"
            "refused" -> localizedString("mobile.roster_status_refused", "reason", refusedReasonText(d.reason)) to "refused:${d.reason}"
            "cancelled" -> localizedString("mobile.roster_status_cancelled", "reason", cancelledReasonText(d.reason)) to "cancelled:${d.reason}"
            else -> d.outcome to d.outcome
        }
        Text(
            pathLine, style = type.label,
            color = when (d.outcome) { "lands" -> t.ok; "refused", "cancelled" -> t.danger; else -> t.dim },
            modifier = Modifier.testable(RosterTags.path(id), pathValue),
        )
        if (s.objectors.isNotEmpty()) {
            Text(
                localizedString("mobile.roster_objected_by", "devices", s.objectors.joinToString(", ") { deviceName(it, labels, state) }),
                style = type.label, color = t.danger,
                modifier = Modifier.testable(RosterTags.objectors(id), s.objectors.size.toString()),
            )
        }
        if (d.outcome != "pending") return@Column
        Spacer(Modifier.height(8.dp))
        val mine = id in state.ownProposals
        val answered = state.answered[id]
        when {
            mine -> {
                val why = localizedString("mobile.roster_own")
                Text(why, style = type.label, color = t.dim, modifier = Modifier.testable(RosterTags.own(id), why))
            }
            answered != null -> {
                Text(
                    localizedString(if (answered) "mobile.roster_you_assented" else "mobile.roster_you_objected"),
                    style = type.label, color = t.dim,
                    modifier = Modifier.testable(RosterTags.answered(id), if (answered) "assent" else "object"),
                )
            }
        }
        if (!mine) {
            val enabled = state.canAnswer(s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CirisButton(
                    label = localizedString("mobile.roster_assent"),
                    tag = RosterTags.assent(id),
                    enabled = enabled,
                    onClick = { viewModel.askAssent(s) },
                )
                Spacer(Modifier.width(8.dp))
                CirisTextButton(
                    label = localizedString("mobile.roster_object"),
                    tag = RosterTags.objectTo(id),
                    enabled = enabled,
                    danger = true,
                    onClick = { viewModel.askObject(s) },
                )
            }
        }
    }
}

@Composable
private fun SettledLine(h: RosterSettled, labels: Map<String, String>, state: SelfRosterUiState) {
    val t = CirisTheme.tokens
    val change = changeText(h.proposal, labels, state)
    val line = when (h.how) {
        RosterSettledHow.LANDED -> localizedString("mobile.roster_settled_landed", "change", change)
        RosterSettledHow.CLOSED -> localizedString("mobile.roster_settled_closed", "change", change)
        RosterSettledHow.CANCELLED -> localizedString("mobile.roster_settled_cancelled", mapOf("change" to change, "reason" to cancelledReasonText(h.reason)))
        RosterSettledHow.REFUSED -> localizedString("mobile.roster_settled_refused", mapOf("change" to change, "reason" to refusedReasonText(h.reason)))
    }
    Text(
        line, style = CirisTheme.type.label,
        color = if (h.how == RosterSettledHow.LANDED) t.ok else t.dim,
        modifier = Modifier.padding(top = 2.dp).testable(RosterTags.settled(h.proposal.id), h.how.name.lowercase()),
    )
}
