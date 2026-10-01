package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.CommunityPendingChange
import ai.ciris.mobile.shared.models.federation.CommunityRoom
import ai.ciris.mobile.shared.models.federation.CommunityRoomMember
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.platform.rememberTestableScrollState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.Chip
import ai.ciris.mobile.shared.ui.primitives.ChipKind
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.RowFlag
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.viewmodels.CommunitiesViewModel
import ai.ciris.mobile.shared.viewmodels.CommunityActRefusal
import ai.ciris.mobile.shared.viewmodels.CommunityDetailRead
import ai.ciris.mobile.shared.viewmodels.CommunityListRead
import ai.ciris.mobile.shared.viewmodels.CommunityReadFailure
import ai.ciris.mobile.shared.viewmodels.GroupInvitesRead
import ai.ciris.mobile.shared.viewmodels.InvitationsViewModel
import ai.ciris.mobile.shared.models.federation.InboxInvite
import ai.ciris.mobile.shared.models.federation.InviteState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * **Communities and affiliations** — three cards over one [CommunitiesViewModel]
 * per tier (CSD-102, CSD-103).
 *
 *  - [CommunityGovernanceSection] — the community itself: found one, its rule,
 *    its roles, its moderators, the change its rule is holding, leave and
 *    dissolve. Mounted INSIDE the circle's existing Rules hub
 *    (`LayerHubScreen`, Neighbours and Communities and Businesses) rather than
 *    as a card beside it: the hub is the card that already stands for "this
 *    circle", and a second one would be the same card under another name.
 *  - [CommunityRosterScreen] — who is in each room, in People.
 *  - [CommunityChatsScreen] — the rooms you talk in, in Chats.
 *
 * Tags are the downstream contract; every one is in [CommunityTags].
 */
object CommunityTags {
    fun slug(communityId: String): String = communityId.substringAfterLast(':')

    // ── Rules: the governance section on the hub ──
    const val SECTION = "section_communities"
    const val LIST = "communities_list"
    const val LOADING = "communities_loading"
    const val EMPTY = "communities_empty"
    const val ERROR = "communities_error"
    const val NOT_ON_THIS_NODE = "communities_not_on_this_node"
    fun row(id: String) = "community_row_${slug(id)}"
    const val CREATE_OPEN = "btn_community_create_open"
    const val CREATE_CARD = "card_community_create"
    const val CREATE_NAME = "input_community_name"
    fun createMember(key: String) = "chip_community_create_member_$key"
    fun protocolOption(p: String) = "opt_community_protocol_$p"
    const val CREATE_QUORUM_M = "input_community_quorum_m"
    const val CREATE_SUBMIT = "btn_community_create_submit"
    const val DETAIL = "card_community_detail"
    const val DETAIL_NAME = "txt_community_name"
    const val DETAIL_PROTOCOL = "txt_community_protocol"
    const val DETAIL_MY_ROLE = "txt_community_my_role"
    const val DETAIL_COUNT = "txt_community_member_count"
    const val DETAIL_CLOSE = "btn_community_detail_close"
    const val DETAIL_LOADING = "community_detail_loading"
    const val DETAIL_ERROR = "community_detail_error"
    const val DETAIL_NOT_ON_THIS_NODE = "community_detail_not_on_this_node"
    const val DETAIL_NOT_FOUND = "community_detail_not_found"
    fun roleRow(key: String) = "row_community_role_$key"
    fun roleEdit(key: String) = "btn_community_role_edit_$key"
    const val ROLE_INPUT = "input_community_role"
    const val ROLE_SUBMIT = "btn_community_role_submit"
    fun moderatorRow(key: String) = "row_community_moderator_$key"
    const val MODERATORS_NONE = "txt_community_moderators_none"
    const val MODERATORS_UNREADABLE = "txt_community_moderators_unreadable"
    const val MODERATORS_NOT_SENT = "txt_community_moderators_not_sent"
    const val OPEN_MODERATION = "btn_community_open_moderation"
    const val MODERATION_PICKER = "community_moderation_picker"
    const val MODERATION_PICKER_NONE = "txt_community_moderation_pick_none"
    const val MODERATION_PICKER_UNREADABLE = "txt_community_moderation_pick_unreadable"
    fun moderationPick(id: String) = "chip_community_moderation_$id"
    const val APPOINT_UNAVAILABLE = "txt_community_appoint_unavailable"
    const val TERMS_UNAVAILABLE = "txt_affiliations_terms_unavailable"
    const val LEAVE = "btn_community_leave"
    const val DISSOLVE = "btn_community_dissolve"
    const val PENDING = "card_community_pending"
    const val PENDING_COUNT = "txt_community_pending_count"
    const val PENDING_PROTOCOL = "txt_community_pending_protocol"
    const val PENDING_ELIGIBLE = "txt_community_pending_eligible"
    const val PENDING_ENVELOPE = "txt_community_pending_envelope"
    const val PENDING_COPY = "btn_community_pending_copy"
    const val PENDING_SIGNATURE = "input_community_pending_signature"
    const val PENDING_ADD_SIGNATURE = "btn_community_pending_add_signature"
    const val PENDING_ASSEMBLE = "btn_community_pending_assemble"
    const val PENDING_DISCARD = "btn_community_pending_discard"
    const val COSIGN = "card_community_cosign"
    const val COSIGN_ENVELOPE = "input_community_cosign_envelope"
    const val COSIGN_SUBMIT = "btn_community_cosign_submit"
    const val COSIGNATURE = "txt_community_cosignature"
    const val COSIGNATURE_COPY = "btn_community_cosignature_copy"
    const val REFUSAL = "txt_community_refusal"
    const val APPLIED = "txt_community_applied"

    // ── People: the roster ──
    const val ROSTER = "screen_community_roster"
    const val ROSTER_LOADING = "community_roster_loading"
    const val ROSTER_EMPTY = "community_roster_empty"
    const val ROSTER_ERROR = "community_roster_error"
    const val ROSTER_NOT_ON_THIS_NODE = "community_roster_not_on_this_node"
    /** The populated state: the column of per-room sections, present only when there is at least one. */
    const val ROSTER_LIST = "community_roster_list"
    const val ROSTER_REFRESH = "btn_community_roster_refresh"
    fun rosterSection(id: String) = "community_roster_section_${slug(id)}"
    fun member(id: String, key: String) = "row_community_member_${slug(id)}_$key"
    fun memberLate(id: String, key: String) = "flag_community_member_late_${slug(id)}_$key"
    fun memberRemove(id: String, key: String) = "btn_community_member_remove_${slug(id)}_$key"
    fun addOpen(id: String) = "btn_community_add_member_open_${slug(id)}"
    const val ADD_KEY = "input_community_add_member"
    fun addContact(key: String) = "chip_community_add_contact_$key"
    const val ADD_SUBMIT = "btn_community_add_member_submit"
    const val LIMIT_WIDENED = "txt_community_limit_widened_reads"
    const val GROUP_BOOK = "txt_community_group_book_unavailable"

    // ── Chats: the rooms ──
    const val CHATS = "screen_community_chats"
    const val CHATS_LIST = "community_chats_list"
    const val CHATS_LOADING = "community_chats_loading"
    const val CHATS_EMPTY = "community_chats_empty"
    const val CHATS_ERROR = "community_chats_error"
    const val CHATS_NOT_ON_THIS_NODE = "community_chats_not_on_this_node"
    const val CHATS_REFRESH = "btn_community_chats_refresh"
    fun chatRow(id: String) = "community_chat_row_${slug(id)}"
    fun chatUnopenable(id: String) = "flag_community_chat_room_unopenable_${slug(id)}"
    fun chatNotAContact(id: String) = "flag_community_chat_not_a_contact_${slug(id)}"
}

@Composable
private fun s(key: String): String = localizedString("mobile.$key")

@Composable
private fun s(key: String, name: String, value: String): String = localizedString("mobile.$key", name, value)

@Composable
private fun s(key: String, params: Map<String, String>): String = localizedString("mobile.$key", params)

/** A name for a key: the contact's own alias when this node has one, the short key otherwise. */
private fun nameOf(key: String, contacts: List<Contact>): String =
    contacts.firstOrNull { it.keyId == key }?.aliasOverride ?: shortKey(key, head = 12, tail = 0)

// ═════════════════════════════════════════════════════════════════════════════
// The list read, drawn the same way on all three cards
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Draws the list read's non-populated states and returns the rooms when there
 * are some. Loading, empty, a failed read and a node without the route are
 * four blocks with four tags; none of them is ever the empty sentence for
 * another.
 */
@Composable
private fun roomsOrState(
    read: CommunityListRead,
    rooms: (List<CommunityRoom>) -> List<CommunityRoom>,
    loadingTag: String,
    emptyTag: String,
    errorTag: String,
    notOnThisNodeTag: String,
    emptyMessage: String,
): List<CommunityRoom>? = when (read) {
    CommunityListRead.NotAsked, CommunityListRead.Loading -> {
        StateBlock(ListState.Loading, tag = loadingTag, inline = true)
        null
    }
    is CommunityListRead.Failed -> {
        CommunityFailureBlock(read.failure, errorTag, notOnThisNodeTag)
        null
    }
    is CommunityListRead.Loaded -> {
        val mine = rooms(read.rooms)
        if (mine.isEmpty()) {
            StateBlock(ListState.Empty(emptyMessage, glyph = GlyphName.NEW_GROUP), tag = emptyTag, inline = true)
            null
        } else mine
    }
}

/**
 * [notOnThisNodeTag] for a node without `/v1/communities`; [errorTag] for a refused
 * or failed read. Both LITERAL at every call site: a tag built from a prefix is
 * invisible to `testing/test_csd_state_tags.py`, so a CSD could declare it and
 * nothing would notice when the screen stopped drawing it.
 */
@Composable
private fun CommunityFailureBlock(failure: CommunityReadFailure, errorTag: String, notOnThisNodeTag: String) {
    when (failure) {
        CommunityReadFailure.NotOnThisNode -> StateBlock(
            ListState.Empty(s("community_not_on_this_node"), glyph = GlyphName.INFO),
            tag = notOnThisNodeTag,
            inline = true,
        )
        is CommunityReadFailure.Refused -> StateBlock(
            ListState.Error(
                title = failure.reasonId?.let { localizedString(it) } ?: s("community_read_failed"),
                body = s("community_read_failed_body"),
                detail = failure.detail,
            ),
            tag = errorTag,
            inline = true,
        )
    }
}

/** The node's refusal of an act, by its id, with the node's English under it. */
@Composable
private fun RefusalLine(refusal: CommunityActRefusal?) {
    refusal ?: return
    StateBlock(
        ListState.Error(
            title = refusal.reasonId?.let { localizedString(it) } ?: s("community_act_failed"),
            detail = refusal.detail?.takeIf { it.isNotBlank() },
        ),
        tag = CommunityTags.REFUSAL,
        inline = true,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun AppliedLine(op: String?) {
    op ?: return
    val t = CirisTheme.tokens
    Text(
        s("community_applied", "op", op),
        style = CirisTheme.type.body,
        color = t.ok,
        modifier = Modifier.testable(CommunityTags.APPLIED, op).padding(vertical = 4.dp),
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// Rules — the community itself, on the circle's hub
// ═════════════════════════════════════════════════════════════════════════════

/**
 * The governance half of the community card, mounted in the circle's Rules
 * hub. Not scrollable itself: the hub scrolls, and a second scroller inside it
 * would be a nested scroll that cannot measure.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunityGovernanceSection(
    viewModel: CommunitiesViewModel,
    /**
     * Moderation (CSD-065) — where a room's duty-holders act, and where the
     * named-moderator verdict is asked. Handed the room's id, which Moderation's
     * community picker preselects (its lookup and the quarantine rung's scope).
     */
    onOpenModeration: ((communityId: String) -> Unit)? = null,
    /**
     * The invitee's inbox (CSD-106): community invitations addressed to this
     * owner, on the hub because they are not in the room yet. The inbox row
     * does not say a room's tier, so both community hubs show it (CSD-106 §6).
     */
    invitations: InvitationsViewModel? = null,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val founding = invitations?.support?.collectAsState()?.value ?: InviteSupport.UNKNOWN
    val read by viewModel.rooms.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val refusal by viewModel.refusal.collectAsState()
    val applied by viewModel.applied.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val cosignature by viewModel.cosignature.collectAsState()
    val affiliations = viewModel.tier == CommunityRoom.TIER_AFFILIATIONS

    LaunchedEffect(Unit) { viewModel.refresh() }

    var createOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().testable(CommunityTags.SECTION),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    s(if (affiliations) "community_section_title_affiliations" else "community_section_title"),
                    style = type.title, color = t.ink,
                )
                Text(
                    s(if (affiliations) "community_section_body_affiliations" else "community_section_body"),
                    style = type.body, color = t.dim,
                )
            }
        }
        invitations?.let { inv -> InvitationsInbox(inv, InboxInvite.KIND_COMMUNITY, hubTier = viewModel.tier, nameOf = { nameOf(it, contacts) }) }
        RefusalLine(refusal)
        AppliedLine(applied)

        // ── The list ──
        val rooms = roomsOrState(
            read = read,
            rooms = { CommunitiesViewModel.roomsFor(it, viewModel.tier, includePairs = false) },
            loadingTag = CommunityTags.LOADING,
            emptyTag = CommunityTags.EMPTY,
            errorTag = CommunityTags.ERROR,
            notOnThisNodeTag = CommunityTags.NOT_ON_THIS_NODE,
            emptyMessage = s(if (affiliations) "community_empty_affiliations" else "community_empty"),
        )
        if (rooms != null) {
            Column(
                modifier = Modifier.fillMaxWidth().testable(CommunityTags.LIST),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (room in rooms) {
                    ItemRow(
                        glyph = GlyphName.NEW_GROUP,
                        title = room.name.ifBlank { shortKey(room.communityId) },
                        meta = room.consensusProtocol,
                        secondary = s("community_row_secondary", mapOf(
                            "count" to room.memberCount.toString(),
                            "role" to (room.myRole ?: "—"),
                        )),
                        flags = if (room.communityId in pending) listOf(RowFlag(s("community_row_pending"), tone = Tone.BRAND)) else emptyList(),
                        tag = CommunityTags.row(room.communityId),
                        onClick = { viewModel.select(room.communityId) },
                    )
                }
            }
        }

        // ── One room ──
        selected?.let { id ->
            CommunityDetailCard(
                communityId = id,
                read = detail,
                contacts = contacts,
                affiliations = affiliations,
                busy = busy,
                pending = pending[id],
                onClose = { viewModel.clearSelection() },
                onChangeRole = { key, role -> viewModel.changeRole(id, key, role) },
                onLeave = { viewModel.leave(id) },
                onDissolve = { viewModel.dissolve(id) },
                onOpenModeration = onOpenModeration?.let { open -> { open(id) } },
            )
            pending[id]?.let { p ->
                PendingChangeCard(
                    change = p,
                    contacts = contacts,
                    busy = busy,
                    onAddSignature = { text -> viewModel.addSignature(id, text) },
                    onAssemble = { viewModel.assemble(id) },
                    onDiscard = { viewModel.discardPending(id) },
                )
            }
            if (detail is CommunityDetailRead.Loaded) {
                CosignCard(
                    busy = busy,
                    cosignature = cosignature,
                    onCosign = { text -> viewModel.cosign(id, text) },
                    onDone = { viewModel.clearCosignature() },
                )
            }
        }

        // ── Found one ──
        if (createOpen) {
            CreateCommunityCard(
                affiliations = affiliations,
                contacts = contacts,
                foundAlone = founding == InviteSupport.INVITES,
                busy = busy,
                onCreate = { name, members, protocol ->
                    viewModel.create(name, members, protocol)
                    createOpen = false
                },
                onCancel = { createOpen = false },
            )
        } else {
            CirisTextButton(
                s(if (affiliations) "community_create_open_affiliations" else "community_create_open"),
                tag = CommunityTags.CREATE_OPEN,
                onClick = { viewModel.clearRefusal(); viewModel.consumeApplied(); createOpen = true },
            )
        }
    }
}

/**
 * **The community picker Moderation takes** (`ModerationScreen(communityPicker =
 * …)`, CSD-065): one chip per room the person is in, at every tier they
 * stand in, handing back the room's id — which is the `community_key_id` the
 * named-moderator lookup and the quarantine rung are scoped by. Pair rooms are
 * left out: a two-person chat has no moderation duty to look up.
 *
 * [preselect] is the room whose card opened Moderation; it is handed back once,
 * so the person arrives with that room already filled in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunityModerationPicker(
    tiers: List<CommunitiesViewModel>,
    preselect: String?,
    onPick: (communityId: String) -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var picked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(preselect) {
        if (preselect != null) { picked = preselect; onPick(preselect) }
    }
    val reads = tiers.map { vm -> vm.rooms.collectAsState().value }
    LaunchedEffect(Unit) {
        tiers.forEachIndexed { i, vm -> if (reads[i] is CommunityListRead.NotAsked) vm.refresh() }
    }
    val rooms = tiers.indices.flatMap { i ->
        (reads[i] as? CommunityListRead.Loaded)?.rooms.orEmpty().let { CommunitiesViewModel.roomsFor(it, tiers[i].tier, includePairs = false) }
    }
    Column(modifier = Modifier.fillMaxWidth().testable(CommunityTags.MODERATION_PICKER, rooms.size.toString())) {
        Text(s("community_moderation_pick"), style = type.label, color = t.mute)
        when {
            rooms.isNotEmpty() -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (room in rooms) {
                    Chip(ChipSpec(
                        label = room.name.ifBlank { shortKey(room.communityId) },
                        tag = CommunityTags.moderationPick(room.communityId),
                        kind = ChipKind.CHOICE,
                        selected = picked == room.communityId,
                        onClick = { picked = room.communityId; onPick(room.communityId) },
                    ))
                }
            }
            // A read that failed is not "you are in none": the typed field above stays the way in.
            reads.any { it is CommunityListRead.Failed } -> Text(
                s("community_moderation_pick_unreadable"), style = type.body, color = t.dim,
                modifier = Modifier.testable(CommunityTags.MODERATION_PICKER_UNREADABLE),
            )
            reads.any { it is CommunityListRead.Loading || it is CommunityListRead.NotAsked } -> Unit
            else -> Text(
                s("community_moderation_pick_none"), style = type.body, color = t.dim,
                modifier = Modifier.testable(CommunityTags.MODERATION_PICKER_NONE),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateCommunityCard(
    affiliations: Boolean,
    contacts: List<Contact>,
    /** 0.5.218+: a room is founded by its founder alone, and the others are invited (CSD-106). */
    foundAlone: Boolean,
    busy: Boolean,
    onCreate: (name: String, members: List<String>, protocol: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf(setOf<String>()) }
    var choice by remember { mutableStateOf("founder_only") }
    var quorumM by remember { mutableStateOf("") }
    val protocol = CommunitiesViewModel.protocolFor(choice, quorumM.toIntOrNull(), members.size)
    val quorumIncomplete = choice == "quorum" && protocol == null

    CardShell(tag = CommunityTags.CREATE_CARD, accent = t.brand) {
        Text(s(if (affiliations) "community_create_title_affiliations" else "community_create_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(6.dp))
        FieldRow(
            label = s("community_create_name"),
            divider = false,
            input = { CirisTextField(tag = CommunityTags.CREATE_NAME, value = name, onValueChange = { name = it }, enabled = !busy) },
        )
        Text(s("community_create_members"), style = type.label, color = t.mute)
        if (foundAlone) {
            // persist seats only who signed the founding record, and this node
            // signs it with the founder alone (`membership.founding_member_unsigned`).
            val text = s("invites_found_alone_community")
            Text(text, style = type.body, color = t.dim, modifier = Modifier.testable(InviteTags.FOUND_ALONE_COMMUNITY, text))
        } else if (contacts.isEmpty()) {
            Text(s("community_create_members_none"), style = type.body, color = t.dim)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in contacts) {
                    val on = c.keyId in members
                    Chip(ChipSpec(
                        label = nameOf(c.keyId, contacts),
                        tag = CommunityTags.createMember(c.keyId),
                        kind = ChipKind.FILTER,
                        selected = on,
                        onClick = { members = if (on) members - c.keyId else members + c.keyId },
                    ))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(s("community_create_rule"), style = type.label, color = t.mute)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in listOf("founder_only", "unanimous", "majority", "quorum")) {
                Chip(ChipSpec(
                    label = s("community_protocol_$p"),
                    tag = CommunityTags.protocolOption(p),
                    kind = ChipKind.CHOICE,
                    selected = choice == p,
                    onClick = { choice = p },
                ))
            }
        }
        Text(s("community_protocol_${choice}_body"), style = type.body, color = t.dim)
        if (choice == "quorum") {
            FieldRow(
                label = s("community_create_quorum_m", "n", (members.size + 1).toString()),
                divider = false,
                input = { CirisTextField(tag = CommunityTags.CREATE_QUORUM_M, value = quorumM, onValueChange = { quorumM = it.filter(Char::isDigit) }, enabled = !busy) },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CirisTextButton(localizedString("mobile.confirm_cancel"), tag = "btn_community_create_cancel", onClick = onCancel)
            CirisButton(
                label = s("community_create_submit"),
                tag = CommunityTags.CREATE_SUBMIT,
                enabled = !busy && name.isNotBlank() && !quorumIncomplete,
                onClick = { onCreate(name, if (foundAlone) emptyList() else members.toList(), protocol) },
            )
        }
    }
}

@Composable
private fun CommunityDetailCard(
    communityId: String,
    read: CommunityDetailRead,
    contacts: List<Contact>,
    affiliations: Boolean,
    busy: Boolean,
    pending: CommunityPendingChange?,
    onClose: () -> Unit,
    onChangeRole: (key: String, role: String) -> Unit,
    onLeave: () -> Unit,
    onDissolve: () -> Unit,
    onOpenModeration: (() -> Unit)?,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var roleFor by remember(communityId) { mutableStateOf<String?>(null) }
    var roleText by remember(communityId) { mutableStateOf("") }
    var confirm by remember(communityId) { mutableStateOf<String?>(null) }

    CardShell(tag = CommunityTags.DETAIL) {
        when (read) {
            CommunityDetailRead.NotAsked, CommunityDetailRead.Loading ->
                StateBlock(ListState.Loading, tag = CommunityTags.DETAIL_LOADING, inline = true)
            CommunityDetailRead.NotFound -> StateBlock(
                ListState.Empty(s("community_detail_not_found"), glyph = GlyphName.INFO),
                tag = CommunityTags.DETAIL_NOT_FOUND, inline = true,
            )
            is CommunityDetailRead.Failed -> CommunityFailureBlock(read.failure, CommunityTags.DETAIL_ERROR, CommunityTags.DETAIL_NOT_ON_THIS_NODE)
            is CommunityDetailRead.Loaded -> {
                val room = read.room
                Text(
                    room.name.ifBlank { shortKey(room.communityId) }, style = type.title, color = t.ink,
                    modifier = Modifier.testable(CommunityTags.DETAIL_NAME, room.name),
                )
                FieldRow(label = s("community_detail_rule"), value = room.consensusProtocol, mono = true, tag = CommunityTags.DETAIL_PROTOCOL)
                Text(s("community_protocol_${protocolFamily(room.consensusProtocol)}_body"), style = type.body, color = t.dim)
                FieldRow(label = s("community_detail_my_role"), value = room.myRole ?: "—", tag = CommunityTags.DETAIL_MY_ROLE)
                FieldRow(label = s("community_detail_members"), value = room.memberCount.toString(), tag = CommunityTags.DETAIL_COUNT, divider = false)

                if (affiliations) {
                    Text(
                        s("community_affiliations_terms_unavailable"), style = type.body, color = t.dim,
                        modifier = Modifier.testable(CommunityTags.TERMS_UNAVAILABLE).padding(vertical = 6.dp),
                    )
                }

                // ── Roles: the roster's word. Moderation is a duty, never a role (CC 4.5.5). ──
                Spacer(Modifier.height(8.dp))
                Text(s("community_detail_roles"), style = type.label, color = t.mute)
                for (m in room.members) {
                    ItemRow(
                        glyph = if (m.role == "founder") GlyphName.KEY else GlyphName.PERSON,
                        title = nameOf(m.keyId, contacts),
                        meta = m.role,
                        tag = CommunityTags.roleRow(m.keyId),
                        trailing = {
                            Chip(ChipSpec(
                                label = s("community_role_change"),
                                tag = CommunityTags.roleEdit(m.keyId),
                                kind = ChipKind.CHOICE,
                                onClick = { roleFor = m.keyId; roleText = m.role },
                            ))
                        },
                    )
                }
                roleFor?.let { key ->
                    FieldRow(
                        label = s("community_role_for", "who", nameOf(key, contacts)),
                        divider = false,
                        input = { CirisTextField(tag = CommunityTags.ROLE_INPUT, value = roleText, onValueChange = { roleText = it }, enabled = !busy) },
                    )
                    Text(s("community_role_body"), style = type.body, color = t.dim)
                    CirisButton(
                        label = s("community_role_submit"),
                        tag = CommunityTags.ROLE_SUBMIT,
                        enabled = !busy && roleText.isNotBlank(),
                        onClick = { onChangeRole(key, roleText); roleFor = null },
                    )
                }

                // ── Moderators: shown, and acted through CSD-065, never rebuilt here. ──
                Spacer(Modifier.height(8.dp))
                Text(s("community_detail_moderators"), style = type.label, color = t.mute)
                // Unreadable and none are two facts (CIRISServer#688): the first is
                // said in the danger tone, never as the "nobody" sentence.
                when (val shown = moderatorsShown(room)) {
                    ModeratorsShown.Unreadable -> Text(
                        s("community_moderators_unreadable"), style = type.body, color = t.danger,
                        modifier = Modifier.testable(CommunityTags.MODERATORS_UNREADABLE),
                    )
                    ModeratorsShown.NotSent -> Text(
                        s("community_moderators_not_sent"), style = type.body, color = t.dim,
                        modifier = Modifier.testable(CommunityTags.MODERATORS_NOT_SENT),
                    )
                    ModeratorsShown.None -> Text(s("community_moderators_none"), style = type.body, color = t.dim, modifier = Modifier.testable(CommunityTags.MODERATORS_NONE))
                    is ModeratorsShown.Holders -> for (k in shown.keys) {
                        ItemRow(glyph = GlyphName.SAFETY, title = nameOf(k, contacts), meta = shortKey(k), tag = CommunityTags.moderatorRow(k))
                    }
                }
                Text(
                    s("community_appoint_unavailable"), style = type.body, color = t.mute,
                    modifier = Modifier.testable(CommunityTags.APPOINT_UNAVAILABLE),
                )
                if (onOpenModeration != null) {
                    CirisTextButton(s("community_open_moderation"), tag = CommunityTags.OPEN_MODERATION, onClick = onOpenModeration)
                }

                // ── Leaving and dissolving ──
                Spacer(Modifier.height(8.dp))
                if (pending != null) {
                    Text(s("community_detail_pending_note"), style = type.body, color = t.brand)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CirisTextButton(s("community_leave"), tag = CommunityTags.LEAVE, enabled = !busy, onClick = { confirm = "leave" })
                    CirisTextButton(s("community_dissolve"), tag = CommunityTags.DISSOLVE, enabled = !busy, danger = true, onClick = { confirm = "dissolve" })
                }
                confirm?.let { which ->
                    val whoSigns = s("community_confirm_signs_${if (which == "leave") "leave" else "governed"}", "rule", room.consensusProtocol)
                    ConfirmSheet(
                        title = s("community_confirm_${which}_title", "name", room.name),
                        facts = listOf(
                            ConfirmFact(s("community_confirm_who"), room.name.ifBlank { shortKey(room.communityId) }),
                            ConfirmFact(s("community_confirm_what"), s("community_confirm_${which}_what")),
                            ConfirmFact(s("community_confirm_signs"), whoSigns),
                        ),
                        confirmLabel = s("community_confirm_${which}_go"),
                        destructive = true,
                        tagPrefix = "community_$which",
                        onConfirm = { confirm = null; if (which == "leave") onLeave() else onDissolve() },
                        onDismiss = { confirm = null },
                    )
                }
            }
        }
        CirisTextButton(s("community_detail_close"), tag = CommunityTags.DETAIL_CLOSE, onClick = onClose)
    }
}

/** What the moderators row says. Four facts, four renderings (CSD-102 §3.1). */
sealed interface ModeratorsShown {
    /** The node did not send the list (a list row, or a node that omits it). */
    data object NotSent : ModeratorsShown
    /** The node said it could not read the chain (`moderators_readable: false`, CIRISServer#688). */
    data object Unreadable : ModeratorsShown
    /** Read, and nobody is appointed. */
    data object None : ModeratorsShown
    data class Holders(val keys: List<String>) : ModeratorsShown
}

internal fun moderatorsShown(room: CommunityRoom): ModeratorsShown {
    val mods = room.moderators
    return when {
        room.moderatorsReadable == false -> ModeratorsShown.Unreadable
        mods == null -> ModeratorsShown.NotSent
        mods.isEmpty() -> ModeratorsShown.None
        else -> ModeratorsShown.Holders(mods)
    }
}

/** `founder_only`, `unanimous`, `majority`, `quorum` — the family a declared rule belongs to, for its sentence. */
internal fun protocolFamily(protocol: String): String = when {
    protocol.startsWith("quorum:") -> "quorum"
    protocol in setOf("founder_only", "unanimous", "majority") -> protocol
    else -> "other"
}

/**
 * **The change the room's rule is holding.** The node keeps nothing: the
 * envelope below is the only copy, and it goes to the other signers by
 * whatever channel the people already use. Each signs it on THEIR node
 * (`…/changes/cosign`) and sends back a signature, pasted here; assemble
 * counts them against the rule and applies the change when it is met.
 */
@Composable
private fun PendingChangeCard(
    change: CommunityPendingChange,
    contacts: List<Contact>,
    busy: Boolean,
    onAddSignature: (String) -> Boolean,
    onAssemble: () -> Unit,
    onDiscard: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val clipboard = LocalClipboardManager.current
    var sigText by remember { mutableStateOf("") }
    val share = CommunitiesViewModel.shareText(change)
    CardShell(tag = CommunityTags.PENDING, accent = t.brand) {
        Text(s("community_pending_title"), style = type.title, color = t.ink)
        Text(
            s("community_pending_count", mapOf("valid" to change.signatures.size.toString(), "required" to change.required.toString())),
            style = type.body, color = t.ink,
            modifier = Modifier.testable(CommunityTags.PENDING_COUNT, "${change.signatures.size}/${change.required}"),
        )
        FieldRow(label = s("community_detail_rule"), value = change.consensusProtocol, mono = true, tag = CommunityTags.PENDING_PROTOCOL)
        FieldRow(
            label = s("community_pending_eligible"),
            value = change.eligibleSigners.joinToString(", ") { nameOf(it, contacts) },
            tag = CommunityTags.PENDING_ELIGIBLE,
        )
        Text(s("community_pending_how"), style = type.body, color = t.dim)
        Text(share, style = type.signed, color = t.mute, maxLines = 4, modifier = Modifier.testable(CommunityTags.PENDING_ENVELOPE, share))
        CirisTextButton(s("community_copy"), tag = CommunityTags.PENDING_COPY, onClick = { clipboard.setText(AnnotatedString(share)) })
        FieldRow(
            label = s("community_pending_signature"),
            divider = false,
            input = { CirisTextField(tag = CommunityTags.PENDING_SIGNATURE, value = sigText, onValueChange = { sigText = it }, enabled = !busy, singleLine = false, mono = true) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CirisTextButton(
                s("community_pending_add_signature"), tag = CommunityTags.PENDING_ADD_SIGNATURE,
                enabled = !busy && sigText.isNotBlank(),
                onClick = { if (onAddSignature(sigText)) sigText = "" },
            )
            CirisButton(
                s("community_pending_assemble"), tag = CommunityTags.PENDING_ASSEMBLE,
                enabled = !busy && change.signatures.size >= change.required,
                onClick = onAssemble,
            )
        }
        CirisTextButton(s("community_pending_discard"), tag = CommunityTags.PENDING_DISCARD, danger = true, onClick = onDiscard)
    }
}

/** Sign a change another member built — on this node, with this owner's pen. Nothing is applied here. */
@Composable
private fun CosignCard(
    busy: Boolean,
    cosignature: String?,
    onCosign: (String) -> Unit,
    onDone: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val clipboard = LocalClipboardManager.current
    var envelope by remember { mutableStateOf("") }
    CardShell(tag = CommunityTags.COSIGN) {
        Text(s("community_cosign_title"), style = type.title, color = t.ink)
        Text(s("community_cosign_body"), style = type.body, color = t.dim)
        if (cosignature == null) {
            FieldRow(
                label = s("community_cosign_envelope"),
                divider = false,
                input = { CirisTextField(tag = CommunityTags.COSIGN_ENVELOPE, value = envelope, onValueChange = { envelope = it }, enabled = !busy, singleLine = false, mono = true) },
            )
            CirisButton(
                s("community_cosign_submit"), tag = CommunityTags.COSIGN_SUBMIT,
                enabled = !busy && envelope.isNotBlank(),
                onClick = { onCosign(envelope) },
            )
        } else {
            Text(s("community_cosign_done"), style = type.body, color = t.ok)
            Text(cosignature, style = type.signed, color = t.mute, maxLines = 4, modifier = Modifier.testable(CommunityTags.COSIGNATURE, cosignature))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CirisTextButton(s("community_copy"), tag = CommunityTags.COSIGNATURE_COPY, onClick = { clipboard.setText(AnnotatedString(cosignature)) })
                CirisTextButton(localizedString("common_close"), tag = "btn_community_cosign_close", onClick = { envelope = ""; onDone() })
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// People — who is in each room
// ═════════════════════════════════════════════════════════════════════════════

/**
 * The roster of every room at this tier, as the fold has it NOW. It is not
 * the group book (CIRISServer#650): who admitted each member, and what
 * changed when, are not served, and this card does not reconstruct them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommunityRosterScreen(viewModel: CommunitiesViewModel) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val read by viewModel.rooms.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val refusal by viewModel.refusal.collectAsState()
    val applied by viewModel.applied.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val invites by viewModel.invites.collectAsState()
    val inviteSupport by viewModel.inviteSupport.collectAsState()
    val inviteNotice by viewModel.inviteNotice.collectAsState()
    val myKeyId by viewModel.myKeyId.collectAsState()
    var addFor by remember { mutableStateOf<String?>(null) }
    var addKey by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<Pair<CommunityRoom, CommunityRoomMember>?>(null) }
    var inviting by remember { mutableStateOf<Pair<CommunityRoom, String>?>(null) }
    var withdrawing by remember { mutableStateOf<Pair<CommunityRoom, ai.ciris.mobile.shared.models.federation.GroupInvite>?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier.fillMaxSize().testable(CommunityTags.ROSTER)
            .verticalScroll(rememberTestableScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s("community_roster_title"), style = type.title, color = t.ink, modifier = Modifier.weight(1f))
            CirisTextButton(localizedString("common_refresh"), tag = CommunityTags.ROSTER_REFRESH, onClick = { viewModel.refresh() })
        }
        Text(
            s("community_limit_widened_reads"), style = type.body, color = t.dim,
            modifier = Modifier.testable(CommunityTags.LIMIT_WIDENED),
        )
        Text(
            s("community_group_book_unavailable"), style = type.body, color = t.mute,
            modifier = Modifier.testable(CommunityTags.GROUP_BOOK),
        )
        RefusalLine(refusal)
        AppliedLine(applied)
        inviteNotice?.let { n ->
            val text = s(if (n == CommunitiesViewModel.NOTICE_WITHDRAWN) "invites_notice_withdrawn" else "invites_notice_invited")
            Text(text, style = type.body, color = t.ok, modifier = Modifier.testable(InviteTags.INBOX_NOTICE + "_roster", text))
        }
        if (pending.isNotEmpty()) Text(s("community_roster_pending_note"), style = type.body, color = t.brand)

        val rooms = roomsOrState(
            read = read,
            rooms = { CommunitiesViewModel.roomsFor(it, viewModel.tier, includePairs = false) },
            loadingTag = CommunityTags.ROSTER_LOADING,
            emptyTag = CommunityTags.ROSTER_EMPTY,
            errorTag = CommunityTags.ROSTER_ERROR,
            notOnThisNodeTag = CommunityTags.ROSTER_NOT_ON_THIS_NODE,
            emptyMessage = s("community_roster_empty"),
        ) ?: return@Column

        // The populated state, as one literal tag over the per-room sections.
        Column(Modifier.fillMaxWidth().testable(CommunityTags.ROSTER_LIST), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (room in rooms) {
            CardShell(tag = CommunityTags.rosterSection(room.communityId)) {
                Text(room.name.ifBlank { shortKey(room.communityId) }, style = type.title, color = t.ink)
                Text(
                    s("community_row_secondary", mapOf("count" to room.memberCount.toString(), "role" to (room.myRole ?: "—"))),
                    style = type.body, color = t.dim,
                )
                for (m in room.members) {
                    val late = CommunitiesViewModel.addedAfterFounding(m, room) == true
                    ItemRow(
                        glyph = if (m.role == "founder") GlyphName.KEY else GlyphName.PERSON,
                        title = nameOf(m.keyId, contacts),
                        meta = shortKey(m.keyId, head = 16, tail = 0),
                        chips = listOf(ChipSpec(m.role, tone = if (m.role == "founder") Tone.BRAND else Tone.INK)),
                        flags = if (late) listOf(RowFlag(s("community_member_late"), tag = CommunityTags.memberLate(room.communityId, m.keyId), tone = Tone.DIM)) else emptyList(),
                        tag = CommunityTags.member(room.communityId, m.keyId),
                        trailing = {
                            Chip(ChipSpec(
                                label = s("community_member_remove"),
                                tag = CommunityTags.memberRemove(room.communityId, m.keyId),
                                kind = ChipKind.CHOICE,
                                tone = Tone.DANGER,
                                onClick = { removing = room to m },
                            ))
                        },
                    )
                }
                // Invitations (CSD-106): read per room, drawn under its members
                // and never counted as one of them.
                LaunchedEffect(room.communityId) { viewModel.loadInvites(room.communityId) }
                val roomInvites = invites[room.communityId] ?: GroupInvitesRead.NotAsked
                PendingInvites(
                    read = roomInvites,
                    ownerKeyId = myKeyId,
                    quorum = protocolFamily(room.consensusProtocol) != "founder_only",
                    busy = busy,
                    nameOf = { nameOf(it, contacts) },
                    onWithdraw = { inv -> withdrawing = room to inv },
                    // A room under a quorum seats an accepted invitee through
                    // `…/changes/envelope` `{op: add}`, which this card does not
                    // call yet: the row says so instead of offering it.
                    onSeat = null,
                    seatLimit = s("invites_community_seat_limit"),
                )
                val legacy = inviteSupport == InviteSupport.LEGACY
                if (addFor == room.communityId) {
                    FieldRow(
                        label = s("community_add_key"),
                        divider = false,
                        input = { CirisTextField(tag = CommunityTags.ADD_KEY, value = addKey, onValueChange = { addKey = it }, enabled = !busy, mono = true) },
                    )
                    val asked = (roomInvites as? GroupInvitesRead.Loaded)?.invites.orEmpty()
                        .filter { it.state == InviteState.PENDING || it.state == InviteState.ACCEPTED }
                        .map { it.inviteeKeyId }.toSet()
                    val candidates = contacts.filter { c -> room.members.none { it.keyId == c.keyId } && c.keyId !in asked }
                    if (candidates.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in candidates) {
                                Chip(ChipSpec(
                                    label = nameOf(c.keyId, contacts),
                                    tag = CommunityTags.addContact(c.keyId),
                                    kind = ChipKind.CHOICE,
                                    selected = addKey == c.keyId,
                                    onClick = { addKey = c.keyId },
                                ))
                            }
                        }
                    }
                    Text(s(if (legacy) "community_add_body" else "invites_community_add_body"), style = type.body, color = t.dim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CirisTextButton(localizedString("mobile.confirm_cancel"), tag = "btn_community_add_member_cancel", onClick = { addFor = null; addKey = "" })
                        CirisButton(
                            s(if (legacy) "community_add_submit" else "invites_community_invite_submit"), tag = CommunityTags.ADD_SUBMIT,
                            enabled = !busy && addKey.isNotBlank(),
                            onClick = {
                                // The node decides the door: an invitation (behind
                                // its own confirm) on 0.5.218+, the direct add on
                                // a node without the invites route.
                                if (legacy) viewModel.addMember(room.communityId, addKey) else inviting = room to addKey.trim()
                                addFor = null; addKey = ""
                            },
                        )
                    }
                } else {
                    CirisTextButton(
                        s(if (legacy) "community_add_open" else "invites_invite_open"), tag = CommunityTags.addOpen(room.communityId),
                        onClick = { viewModel.clearRefusal(); viewModel.consumeApplied(); viewModel.consumeInviteNotice(); addFor = room.communityId; addKey = "" },
                    )
                }
            }
        }
        }
    }

    removing?.let { (room, m) ->
        ConfirmSheet(
            title = s("community_confirm_remove_title", "who", nameOf(m.keyId, contacts)),
            facts = listOf(
                ConfirmFact(s("community_confirm_who"), nameOf(m.keyId, contacts) + " · " + room.name),
                ConfirmFact(s("community_confirm_what"), s("community_confirm_remove_what")),
                ConfirmFact(s("community_confirm_signs"), s("community_confirm_signs_governed", "rule", room.consensusProtocol)),
            ),
            confirmLabel = s("community_confirm_remove_go"),
            destructive = true,
            tagPrefix = "community_remove",
            onConfirm = { removing = null; viewModel.removeMember(room.communityId, m.keyId) },
            onDismiss = { removing = null },
        )
    }

    inviting?.let { (room, key) ->
        val who = nameOf(key, contacts)
        val roomName = room.name.ifBlank { shortKey(room.communityId) }
        ConfirmSheet(
            title = s("invites_community_invite_title", mapOf("name" to who, "room" to roomName)),
            facts = listOf(
                ConfirmFact(s("community_confirm_who"), "$who · $roomName"),
                ConfirmFact(s("community_confirm_what"), s("invites_community_invite_what")),
                ConfirmFact(s("community_confirm_signs"), s("invites_community_signs", "rule", room.consensusProtocol)),
            ),
            confirmLabel = s("invites_confirm_send"),
            tagPrefix = InviteTags.COMMUNITY_CONFIRM_INVITE,
            onConfirm = { inviting = null; viewModel.invite(room.communityId, key) },
            onDismiss = { inviting = null },
        )
    }

    withdrawing?.let { (room, inv) ->
        val who = nameOf(inv.inviteeKeyId, contacts)
        ConfirmSheet(
            title = s("invites_withdraw_title", "name", who),
            facts = listOf(
                ConfirmFact(s("community_confirm_who"), who + " · " + room.name.ifBlank { shortKey(room.communityId) }),
                ConfirmFact(s("community_confirm_what"), s("invites_withdraw_what")),
                ConfirmFact(s("community_confirm_signs"), s("invites_signs_proposer")),
            ),
            confirmLabel = s("invites_confirm_withdraw"),
            destructive = true,
            tagPrefix = InviteTags.COMMUNITY_CONFIRM_WITHDRAW,
            onConfirm = { withdrawing = null; viewModel.withdrawInvite(room.communityId, inv.proposalId) },
            onDismiss = { withdrawing = null },
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Chats — the rooms you talk in
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Every room at this tier, pair rooms included: a pair room is `tier:
 * community` on the wire, so it folds to Neighbours with the substrate's
 * backing and not by a client guess. A pair room opens the chat through its
 * contact (CSD-091); a room of more than two opens BY ID — `GET/POST
 * /v1/chat/{id}/messages` serve N-member rooms (CIRISServer#594), and
 * `POST /v1/chat` is never asked for one. A pair room whose contact this node
 * no longer lists cannot be opened through a contact and says so.
 */
@Composable
fun CommunityChatsScreen(
    viewModel: CommunitiesViewModel,
    onOpenPairChat: (room: CommunityRoom, contact: Contact) -> Unit,
    /** A room of more than two, entered by its id (CSD-091). */
    onOpenRoom: (room: CommunityRoom) -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val read by viewModel.rooms.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier.fillMaxSize().testable(CommunityTags.CHATS)
            .verticalScroll(rememberTestableScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s("community_chats_title"), style = type.title, color = t.ink, modifier = Modifier.weight(1f))
            CirisTextButton(localizedString("common_refresh"), tag = CommunityTags.CHATS_REFRESH, onClick = { viewModel.refresh() })
        }
        val rooms = roomsOrState(
            read = read,
            rooms = { CommunitiesViewModel.roomsFor(it, viewModel.tier, includePairs = true) },
            loadingTag = CommunityTags.CHATS_LOADING,
            emptyTag = CommunityTags.CHATS_EMPTY,
            errorTag = CommunityTags.CHATS_ERROR,
            notOnThisNodeTag = CommunityTags.CHATS_NOT_ON_THIS_NODE,
            emptyMessage = s("community_chats_empty"),
        ) ?: return@Column
        Column(Modifier.fillMaxWidth().testable(CommunityTags.CHATS_LIST), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (room in rooms) {
                val contact = CommunitiesViewModel.pairContact(room, contacts)
                val flags = when {
                    !room.isPair -> emptyList()
                    contact == null -> listOf(RowFlag(s("community_chat_not_a_contact"), tag = CommunityTags.chatNotAContact(room.communityId)))
                    else -> emptyList()
                }
                ItemRow(
                    glyph = if (room.isPair) GlyphName.SEND else GlyphName.NEW_GROUP,
                    title = when {
                        contact != null -> nameOf(contact.keyId, contacts)
                        room.name.isNotBlank() -> room.name
                        else -> shortKey(room.communityId)
                    },
                    meta = if (room.isPair) s("community_chat_pair") else s("community_chat_room", "count", room.memberCount.toString()),
                    metaMono = false,
                    flags = flags,
                    tag = CommunityTags.chatRow(room.communityId),
                    onClick = when {
                        !room.isPair -> ({ onOpenRoom(room) })
                        contact != null -> ({ onOpenPairChat(room, contact) })
                        else -> null
                    },
                )
            }
        }
    }
}
