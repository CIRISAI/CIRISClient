package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.ceg.formatDate
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.GroupInvite
import ai.ciris.mobile.shared.models.federation.InboxInvite
import ai.ciris.mobile.shared.models.federation.InviteState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.RowFlag
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.viewmodels.GroupInvitesRead
import ai.ciris.mobile.shared.viewmodels.InboxRead
import ai.ciris.mobile.shared.viewmodels.InvitationsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant

/**
 * **Invitations** (CSD-106) — the invitee's inbox, drawn on a hub, and the
 * inviter's pending rows, drawn on a roster.
 *
 * The inbox is on the hub because the person invited is not in the group yet:
 * the roster has nothing to show them, and the hub is where "You're not in a
 * household yet" already stands. [kind] picks which invitations this hub
 * shows (`family` on Family › Rules, `community` on each community hub).
 *
 * Accepted is not joined. The answer's notice says "accepted, waiting for the
 * group", and a quorum group still has to sign the widening that seats them.
 */
@Composable
fun InvitationsInbox(
    viewModel: InvitationsViewModel,
    kind: String,
    /**
     * A community hub's tier (`community` / `affiliations`). Rows that carry
     * `tier` (0.5.219+) are shown on their own hub only; rows without it on
     * every community hub. Null for the household hub.
     */
    hubTier: String? = null,
    /** A key as this owner knows it (a contact's alias), else a short key. */
    nameOf: (String) -> String = { shortKey(it, head = 12, tail = 0) },
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val inbox by viewModel.inbox.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val refusal by viewModel.refusal.collectAsState()
    val answered by viewModel.answered.collectAsState()
    val confirming by viewModel.confirming.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        refusal?.let {
            StateBlock(ListState.Error(title = inviteRefusalText(it)), tag = InviteTags.INBOX_REFUSAL, inline = true)
        }
        answered?.let { a ->
            if (a.invite.groupKind == kind) {
                val text = localizedString(if (a.accepted) "mobile.invites_notice_accepted" else "mobile.invites_notice_declined")
                Text(text, style = type.body, color = t.ok, modifier = Modifier.testable(InviteTags.INBOX_NOTICE, text))
            }
        }
        when (val r = inbox) {
            InboxRead.NotAsked, InboxRead.Loading ->
                StateBlock(ListState.Loading, tag = InviteTags.INBOX_LOADING, inline = true)
            InboxRead.NotOnThisNode -> StateBlock(
                ListState.Empty(localizedString("mobile.invites_not_on_this_node"), glyph = GlyphName.INFO),
                tag = InviteTags.INBOX_NOT_ON_THIS_NODE, inline = true,
            )
            is InboxRead.Failed -> StateBlock(
                ListState.Error(
                    title = localizedString("mobile.invites_read_failed"),
                    detail = r.refusal?.let { inviteRefusalText(it) } ?: r.detail,
                ),
                tag = InviteTags.INBOX_ERROR, inline = true,
            )
            is InboxRead.Loaded -> {
                val mine = inboxFor(r.invites, kind, hubTier)
                if (mine.isEmpty()) {
                    // No invitations is the normal case and says nothing: the
                    // hub's own empty state speaks. The tag is for the flows.
                    Spacer(Modifier.height(0.dp).testable(InviteTags.INBOX_EMPTY))
                } else {
                    Text(localizedString("mobile.invites_inbox_title"), style = type.title, color = t.ink)
                    Column(
                        modifier = Modifier.fillMaxWidth().testable(InviteTags.INBOX_LIST, mine.size.toString()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (inv in mine) {
                            InboxRow(
                                invite = inv,
                                inviter = nameOf(inv.proposerKeyId),
                                busy = busy,
                                onAccept = { viewModel.request(inv, accept = true) },
                                onDecline = { viewModel.request(inv, accept = false) },
                            )
                        }
                    }
                }
            }
        }
    }

    confirming?.let { ask ->
        if (ask.invite.groupKind == kind) {
            AnswerConfirm(
                invite = ask.invite,
                accept = ask.accept,
                inviter = nameOf(ask.invite.proposerKeyId),
                onConfirm = viewModel::confirm,
                onDismiss = viewModel::cancelConfirm,
            )
        }
    }
}

@Composable
private fun InboxRow(
    invite: InboxInvite,
    inviter: String,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val group = groupLabel(invite)
    val role = invite.role?.let { roleWord(it) }
    ItemRow(
        glyph = GlyphName.INVITE,
        glyphTint = CirisTheme.tokens.brand,
        title = group,
        meta = shortKey(invite.groupId, head = 16, tail = 4),
        secondary = if (role != null) {
            localizedString("mobile.invites_row_from", mapOf("who" to inviter, "role" to role))
        } else {
            localizedString("mobile.invites_row_from_norole", "who", inviter)
        },
        flags = listOfNotNull(readableDate(invite.expiresAt)?.let {
            RowFlag(localizedString("mobile.invites_until", "date", it), tag = InviteTags.expires(invite.proposalId), tone = Tone.DIM)
        }),
        tag = InviteTags.row(invite.proposalId),
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CirisTextButton(
                    localizedString("mobile.invites_decline"), tag = InviteTags.decline(invite.proposalId),
                    onClick = onDecline, enabled = !busy, danger = true,
                )
                CirisTextButton(
                    localizedString("mobile.invites_accept"), tag = InviteTags.accept(invite.proposalId),
                    onClick = onAccept, enabled = !busy,
                )
            }
        },
    )
}

/**
 * The three facts an answer confirms: who invites me, into what, and what
 * answering means. Decline says it is final, because it is: inviting again is
 * a new invitation (persist's `membership.declined` is terminal).
 */
@Composable
private fun AnswerConfirm(
    invite: InboxInvite,
    accept: Boolean,
    inviter: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val group = groupLabel(invite)
    val kindWord = localizedString(
        if (invite.groupKind == InboxInvite.KIND_FAMILY) "mobile.invites_kind_family" else "mobile.invites_kind_community",
    )
    val into = localizedString(
        "mobile.invites_into_value",
        mapOf("group" to group, "kind" to kindWord, "role" to (invite.role?.let { roleWord(it) } ?: roleWord(ROLE_MEMBER))),
    )
    val means = when {
        !accept -> localizedString("mobile.invites_decline_means")
        invite.groupKind == InboxInvite.KIND_FAMILY -> localizedString("mobile.invites_accept_means_family")
        else -> localizedString("mobile.invites_accept_means_community")
    }
    ConfirmSheet(
        title = localizedString(if (accept) "mobile.invites_accept_title" else "mobile.invites_decline_title", "group", group),
        facts = listOf(
            ConfirmFact(localizedString("mobile.invites_confirm_who"), inviter),
            ConfirmFact(localizedString("mobile.invites_confirm_into"), into),
            ConfirmFact(
                localizedString(if (accept) "mobile.invites_confirm_means" else "mobile.invites_confirm_means_decline"),
                means,
            ),
        ),
        confirmLabel = localizedString(if (accept) "mobile.invites_accept_go" else "mobile.invites_decline_go"),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = !accept,
        tagPrefix = if (accept) InviteTags.CONFIRM_ACCEPT else InviteTags.CONFIRM_DECLINE,
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// The inviter's side: pending rows on a roster
// ═════════════════════════════════════════════════════════════════════════════

/**
 * The invitations a roster draws under its members, each as the node reports
 * it and none counted as a member. [quorum] says how an accepted invitee is
 * seated: by the members' signatures, or by the founder's node on its own.
 * [onSeat] is offered on an accepted row only when this card can start that
 * signing (a quorum household's envelope); null otherwise.
 */
@Composable
fun PendingInvites(
    read: GroupInvitesRead,
    /**
     * The owner's key as the card read it (`owned-nodes`), or null. The node's
     * own `viewer_key_id` on the list (0.5.219+) is preferred when it sent one
     * ([viewerOf]); with neither, Withdraw falls back to every pending row
     * ([withdrawShown]).
     */
    ownerKeyId: String?,
    quorum: Boolean,
    busy: Boolean,
    nameOf: (String) -> String,
    onWithdraw: (GroupInvite) -> Unit,
    onSeat: ((GroupInvite) -> Unit)?,
    /** Said on an accepted row when nobody here can seat them (a quorum room: CSD-103 §3.1). */
    seatLimit: String? = null,
) {
    when (read) {
        is GroupInvitesRead.Loaded -> {
            val shown = rosterInvites(read.invites)
            val me = viewerOf(read, ownerKeyId)
            for (inv in shown) {
                val withdraw = withdrawShown(inv, me)
                PendingInviteRow(inv, nameOf(inv.inviteeKeyId), withdraw, quorum, busy, onWithdraw, onSeat, seatLimit)
            }
        }
        is GroupInvitesRead.Failed -> StateBlock(
            ListState.Error(
                title = localizedString("mobile.invites_roster_read_failed"),
                detail = read.refusal?.let { inviteRefusalText(it) } ?: read.detail,
            ),
            tag = InviteTags.ROSTER_INVITES_ERROR, inline = true,
        )
        // Loading draws nothing of its own (the roster above is already drawn),
        // and a node without the route has no invitations to show: the add
        // control itself says which door this node has.
        else -> {}
    }
}

@Composable
private fun PendingInviteRow(
    invite: GroupInvite,
    name: String,
    withdraw: Boolean,
    quorum: Boolean,
    busy: Boolean,
    onWithdraw: (GroupInvite) -> Unit,
    onSeat: ((GroupInvite) -> Unit)?,
    seatLimit: String?,
) {
    val accepted = invite.state == InviteState.ACCEPTED
    val status = when (invite.state) {
        InviteState.PENDING -> localizedString("mobile.invites_pending_invited")
        InviteState.ACCEPTED -> localizedString(if (quorum) "mobile.invites_pending_accepted_quorum" else "mobile.invites_pending_accepted")
        InviteState.DECLINED -> localizedString("mobile.invites_pending_declined")
        InviteState.EXPIRED -> localizedString("mobile.invites_pending_expired")
        else -> invite.state
    }
    val flags = buildList {
        if (invite.state == InviteState.PENDING) {
            readableDate(invite.expiresAt)?.let {
                add(RowFlag(localizedString("mobile.invites_expires", "date", it), tag = InviteTags.expires(invite.proposalId), tone = Tone.DIM))
            }
        }
        if (accepted && onSeat == null && quorum && seatLimit != null) add(RowFlag(seatLimit, tone = Tone.DIM))
    }
    val seat = accepted && quorum && onSeat != null
    ItemRow(
        glyph = GlyphName.INVITE,
        title = name,
        meta = shortKey(invite.inviteeKeyId, head = 16, tail = 4),
        secondary = status,
        chips = listOf(ChipSpec(
            status,
            tone = when (invite.state) {
                InviteState.PENDING, InviteState.ACCEPTED -> Tone.BRAND
                InviteState.DECLINED -> Tone.DANGER
                else -> Tone.MUTE
            },
        )),
        flags = flags,
        tag = InviteTags.pending(invite.proposalId),
        trailing = if (!withdraw && !seat) null else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (seat && onSeat != null) {
                        CirisTextButton(
                            localizedString("mobile.invites_seat"), tag = InviteTags.seat(invite.proposalId),
                            onClick = { onSeat(invite) }, enabled = !busy,
                        )
                    }
                    if (withdraw) {
                        CirisTextButton(
                            localizedString("mobile.invites_withdraw"), tag = InviteTags.withdraw(invite.proposalId),
                            onClick = { onWithdraw(invite) }, enabled = !busy, danger = true,
                        )
                    }
                }
            }
        },
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// Small pieces
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun groupLabel(invite: InboxInvite): String =
    invite.groupName?.takeIf { it.isNotBlank() } ?: shortKey(invite.groupId, head = 16, tail = 4)

@Composable
private fun roleWord(role: String): String = when (role) {
    ROLE_FOUNDER -> localizedString("households.role_founder")
    ROLE_MEMBER -> localizedString("households.role_member")
    else -> role
}

private fun readableDate(raw: String?): String? =
    raw?.let { runCatching { formatDate(Instant.parse(it)) }.getOrDefault(it) }

/** A node's `membership.*` refusal in the reader's language, else its English, else the status. Never a raw id. */
@Composable
internal fun inviteRefusalText(r: NodeRefusal): String {
    val id = r.reasonId
    if (id != null) {
        val text = localizedString(id)
        if (text != id && text.isNotBlank()) return text
    }
    return r.detail ?: id ?: localizedString("households.refusal_status", "status", r.statusCode.toString())
}
