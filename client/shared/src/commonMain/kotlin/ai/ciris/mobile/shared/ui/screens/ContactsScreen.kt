package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.formatDate
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.LocalPeerState
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.platform.testableWithHandler
import ai.ciris.mobile.shared.ui.components.CIRISIcons
import ai.ciris.mobile.shared.ui.glyphs.Glyph
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.nav.LocalIsCompactWindow
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.Chip
import ai.ciris.mobile.shared.ui.primitives.ChipKind
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.CirisButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.CirisTextField
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.Receipt
import ai.ciris.mobile.shared.ui.primitives.ReceiptSheet
import ai.ciris.mobile.shared.ui.primitives.RowFlag
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import ai.ciris.mobile.shared.viewmodels.ContactRemoval
import ai.ciris.mobile.shared.viewmodels.ContactsViewModel
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.shell.ScreenTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * **People** — the node client's home surface, and wave 0's proof that the
 * nine primitives can express a real screen: a list, an empty state, and the
 * one honoured "node fact" error (a node too old to serve the route).
 *
 * Two modes, and they read two different sets on purpose:
 *
 *  - **Browse mode** (default): the owner's CONTACTS (``GET /v1/contacts``) —
 *    the people a `consent:replication:v1` grant stands with, each carrying the
 *    derived `chat_community_id` for their two-person room. Tapping one opens
 *    the chat; long-press (or the hamburger) opens its receipt. When the list
 *    is EMPTY the screen lands on the add-a-contact card as the primary action,
 *    because an empty list has exactly one useful next move.
 *  - **Picker mode** ([onPeerPicked] != null): the node's KNOWN PEERS
 *    (``GET /v1/federation/peers``), each row offering a "Choose" chip. A peer
 *    listing is furniture, not a claim, so those rows carry no receipt.
 *
 * Every element is built from `ui/primitives`; the file names no colour and
 * reads no Material scheme. Test tags are the downstream contract and are
 * unchanged from the Contacts screen this replaces — see [PeopleTags].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onBack: () -> Unit,
    /** Open the two-person chat with this contact. Browse mode only. */
    onOpenChat: (Contact) -> Unit = {},
    /** When non-null the screen is in picker mode — each row shows a "Choose" button. */
    onPeerPicked: ((LocalPeerState) -> Unit)? = null,
    /** The node's reported version, so the too-old state can name it. */
    nodeVersion: String? = null,
) {
    val pickerMode = onPeerPicked != null
    val t = CirisTheme.tokens
    val type = CirisTheme.type

    val contacts by viewModel.contacts.collectAsState()
    val contactsLoaded by viewModel.contactsLoaded.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val addBusy by viewModel.addBusy.collectAsState()
    val addRefusalReasonId by viewModel.addRefusalReasonId.collectAsState()
    val addError by viewModel.addError.collectAsState()
    val justAdded by viewModel.justAdded.collectAsState()
    val routeUnsupported by viewModel.routeUnsupported.collectAsState()
    val chatIneligible by viewModel.chatIneligible.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    // The add card is ALWAYS presented over an empty contact list — that is the
    // "land on Add a Contact" rule. Over a non-empty list it is opt-in so the
    // conversations stay the first thing on screen.
    var addExpanded by remember { mutableStateOf(false) }
    var addKeyId by remember { mutableStateOf("") }
    val listIsEmpty = contacts.isEmpty() && searchQuery.isBlank()
    val showAddCard = !pickerMode && !routeUnsupported &&
        (addExpanded || (contactsLoaded && listIsEmpty))

    // The receipt sheet: one at a time, opened from a row's hamburger or long-press.
    var receiptFor by remember { mutableStateOf<Receipt?>(null) }
    val thisNodeLabel = localizedString("mobile.receipt_this_node")
    val openChatLabel = localizedString("mobile.contacts_open_chat")
    val scopeNote = localizedString("mobile.receipt_contact_scope_note")

    // Remove a contact (CSD-005, CIRISServer#657): receipt act → ConfirmSheet → DELETE.
    val removing by viewModel.removing.collectAsState()
    val removal by viewModel.removal.collectAsState()
    var confirmRemove by remember { mutableStateOf<Contact?>(null) }
    val removeLabel = localizedString("mobile.contacts_remove")

    Scaffold(
        containerColor = t.ground,
        topBar = {
            ScreenTopBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = t.ground,
                    scrolledContainerColor = t.ground,
                    titleContentColor = t.ink,
                    navigationIconContentColor = t.dim,
                    actionIconContentColor = t.dim,
                ),
                title = {
                    Text(
                        if (pickerMode) localizedString("mobile.contacts_title_picker")
                        else localizedString("mobile.people_title"),
                        style = type.title,
                    )
                },
                navigationIcon = {
                    if (!LocalIsCompactWindow.current) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testableWithHandler(PeopleTags.BACK) { onBack() },
                        ) {
                            Icon(CIRISIcons.arrowBack, contentDescription = localizedString("common_back"), tint = t.dim)
                        }
                    } else {
                        Spacer(Modifier.width(56.dp))
                    }
                },
                actions = {
                    if (!pickerMode) {
                        IconButton(
                            onClick = { addExpanded = !addExpanded },
                            modifier = Modifier.testableWithHandler(PeopleTags.ADD_OPEN) { addExpanded = !addExpanded },
                        ) {
                            Glyph(GlyphName.INVITE, tint = t.dim, contentDescription = localizedString("mobile.contacts_add_title"))
                        }
                    }
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !loading,
                        modifier = Modifier.testableWithHandler(PeopleTags.REFRESH) { if (!loading) viewModel.refresh() },
                    ) {
                        Glyph(GlyphName.REFRESH, tint = if (loading) t.mute else t.dim, contentDescription = localizedString("common_refresh"))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // ── Search ────────────────────────────────────────────────────────
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                CirisTextField(
                    tag = PeopleTags.SEARCH,
                    value = searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    placeholder = if (pickerMode) localizedString("mobile.contacts_search_peers_hint")
                    else localizedString("mobile.contacts_search_hint"),
                )
            }

            // ── Error (list-level) — an error, never mistaken for an empty list ──
            error?.let { msg ->
                StateBlock(
                    ListState.Error(title = msg), tag = PeopleTags.ERROR, inline = true,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // ── What the last removal did ─────────────────────────────────────
            removal?.let { r -> RemovalOutcome(r, onDismiss = viewModel::clearRemoval) }

            // ── Add someone ───────────────────────────────────────────────────
            if (showAddCard) {
                AddContactCard(
                    keyId = addKeyId,
                    onKeyIdChange = { addKeyId = it; viewModel.clearAddError() },
                    busy = addBusy,
                    emptyState = listIsEmpty,
                    refusalReasonId = addRefusalReasonId,
                    refusalDetail = addError,
                    justAdded = justAdded,
                    onSubmit = { viewModel.addContact(addKeyId) },
                    onOpenChat = {
                        justAdded?.let { c ->
                            viewModel.consumeJustAdded()
                            addKeyId = ""
                            addExpanded = false
                            onOpenChat(c)
                        }
                    },
                    onDismissAdded = { viewModel.consumeJustAdded(); addKeyId = "" },
                )
            }

            // ── The node predates this surface ────────────────────────────────
            // Checked BEFORE loading/empty, because both of those would be a
            // wrong answer to a question this node cannot answer at all. It is
            // the error treatment (never the empty one), with the copy that says
            // it is a version fact and everything else works — the one honoured
            // "node fact" state in the no-gating rule.
            if (routeUnsupported && !pickerMode) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    StateBlock(
                        ListState.Error(
                            title = localizedString("mobile.contacts_node_too_old_title"),
                            body = if (nodeVersion != null)
                                localizedString("mobile.contacts_node_too_old_body_versioned", "version", nodeVersion)
                            else localizedString("mobile.contacts_node_too_old_body"),
                        ),
                        tag = PeopleTags.UNSUPPORTED,
                    )
                }
                return@Column
            }

            // ── Loading ───────────────────────────────────────────────────────
            val listEmptyNow = if (pickerMode) peers.isEmpty() else contacts.isEmpty()
            if (loading && listEmptyNow) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StateBlock(ListState.Loading, tag = PeopleTags.LOADING)
                }
                return@Column
            }

            // ── Empty ─────────────────────────────────────────────────────────
            if (listEmptyNow) {
                // With the add card already on screen the empty list needs no
                // second "nothing here" block — it would repeat the card's own
                // copy directly beneath it.
                if (!showAddCard) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        StateBlock(
                            ListState.Empty(
                                message = when {
                                    searchQuery.isNotBlank() && pickerMode ->
                                        localizedString("mobile.contacts_peers_no_match", "query", searchQuery)
                                    searchQuery.isNotBlank() ->
                                        localizedString("mobile.contacts_no_match", "query", searchQuery)
                                    pickerMode -> localizedString("mobile.contacts_peers_empty")
                                    else -> localizedString("mobile.contacts_empty_title")
                                },
                                glyph = GlyphName.PERSON,
                            ),
                            tag = PeopleTags.EMPTY,
                        )
                    }
                }
                return@Column
            }

            // ── The list ──────────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize().testable(PeopleTags.LIST),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pickerMode) {
                    items(peers, key = { it.keyId }) { peer ->
                        PeerRow(peer = peer, onPick = { onPeerPicked?.invoke(peer) })
                    }
                } else {
                    items(contacts, key = { it.keyId }) { contact ->
                        ContactRow(
                            contact = contact,
                            chatIneligible = contact.keyId in chatIneligible,
                            receipt = contactReceipt(
                                contact, thisNodeLabel, openChatLabel, scopeNote,
                                onOpenChat = { receiptFor = null; onOpenChat(contact) },
                                removeLabel = removeLabel.takeIf { removing == null },
                                onRemove = { receiptFor = null; confirmRemove = contact },
                            ),
                            onOpenChat = { onOpenChat(contact) },
                            onOpenReceipt = { receiptFor = it },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    receiptFor?.let { r -> ReceiptSheet(receipt = r, onDismiss = { receiptFor = null }) }

    confirmRemove?.let { c ->
        val who = c.aliasOverride ?: shortKey(c.keyId, head = 12, tail = 0)
        ConfirmSheet(
            title = localizedString("mobile.contacts_remove_confirm_title", "who", who),
            facts = listOf(
                ConfirmFact(localizedString("mobile.consent_withdraw_fact_who"), c.keyId, mono = true),
                ConfirmFact(
                    localizedString("mobile.consent_withdraw_fact_stops"),
                    localizedString("mobile.contacts_remove_fact_stops_value"),
                ),
                ConfirmFact(
                    localizedString("mobile.consent_withdraw_fact_signs"),
                    localizedString("mobile.consent_withdraw_signs_value"),
                ),
            ),
            confirmLabel = localizedString("mobile.contacts_remove"),
            onConfirm = { confirmRemove = null; viewModel.removeContact(c.keyId) },
            onDismiss = { confirmRemove = null },
            destructive = true,
            tagPrefix = PeopleTags.REMOVE_CONFIRM_PREFIX,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Remove someone
// ═════════════════════════════════════════════════════════════════════════════

/**
 * What a removal DID, as the node reported it ([ContactRemoval]).
 *
 * The still-active arm is the one this exists for: grants this node wrote
 * before the person signed their contacts cannot be withdrawn by them, stay
 * live, and are listed by id with that reason — in the ordinary tone, never
 * struck through, and never under a "Removed" line.
 */
@Composable
private fun RemovalOutcome(removal: ContactRemoval, onDismiss: () -> Unit) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val who = shortKey(removal.keyId, head = 12, tail = 0)
    val pad = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    when (removal) {
        is ContactRemoval.Removed -> Row(pad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                localizedString("mobile.contacts_removed", "who", who),
                style = type.body, color = t.ok,
                modifier = Modifier.weight(1f).testable(PeopleTags.REMOVE_DONE),
            )
            CirisTextButton(localizedString("common_close"), tag = PeopleTags.REMOVE_DISMISS, onClick = onDismiss)
        }
        is ContactRemoval.StillActive -> CardShell(modifier = pad, tag = PeopleTags.REMOVE_REMAINING) {
            Text(localizedString("mobile.contacts_remove_remaining_title", "who", who), style = type.title, color = t.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                localizedString("mobile.contacts_remove_remaining_body", "count", removal.withdrawn.toString()),
                style = type.body, color = t.dim,
            )
            removal.remainingGrants.forEach { grant ->
                FieldRow(
                    label = localizedString("mobile.contacts_remove_remaining_row"),
                    value = grant,
                    mono = true,
                )
            }
            CirisTextButton(localizedString("common_close"), tag = PeopleTags.REMOVE_DISMISS, onClick = onDismiss)
        }
        is ContactRemoval.Refused -> StateBlock(
            ListState.Error(
                title = removal.reasonId?.let { localizedString(it) } ?: removal.detail.orEmpty(),
                detail = removal.detail?.takeIf { it.isNotBlank() && removal.reasonId != null },
            ),
            tag = PeopleTags.REMOVE_REFUSAL, inline = true, modifier = pad,
        )
        is ContactRemoval.Unsupported -> StateBlock(
            ListState.Error(title = localizedString("mobile.contacts_remove_unsupported")),
            tag = PeopleTags.REMOVE_UNSUPPORTED, inline = true, modifier = pad,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Add someone
// ═════════════════════════════════════════════════════════════════════════════

/**
 * The add-by-code flow, and the primary action when the contact list is empty.
 * (Scanning in person and showing your own code arrive with the contacts,
 * groups and rosters work — not stubbed here.)
 *
 * Refusals are rendered from the node's typed `reason_id` — not from the English
 * sentence — because two of them have remedies that point in opposite
 * directions: `contacts.unknown_fed_id` means the key must be ADMITTED first
 * (peering), and `contacts.self_contact` means the key is this node's own. The
 * server's English is kept as the detail line for any id the bundle does not
 * carry, which is the designed degradation rather than an error.
 */
@Composable
private fun AddContactCard(
    keyId: String,
    onKeyIdChange: (String) -> Unit,
    busy: Boolean,
    emptyState: Boolean,
    refusalReasonId: String?,
    refusalDetail: String?,
    justAdded: Contact?,
    onSubmit: () -> Unit,
    onOpenChat: () -> Unit,
    onDismissAdded: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    CardShell(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        tag = PeopleTags.ADD_CARD,
        accent = t.brand,
    ) {
        Text(localizedString("mobile.contacts_add_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(4.dp))
        Text(
            if (emptyState) localizedString("mobile.contacts_empty_body")
            else localizedString("mobile.contacts_add_hint"),
            style = type.body, color = t.dim,
        )
        Spacer(Modifier.height(6.dp))
        FieldRow(
            label = localizedString("mobile.contacts_add_field_label"),
            divider = false,
            input = {
                CirisTextField(
                    tag = PeopleTags.ADD_KEY,
                    value = keyId,
                    onValueChange = onKeyIdChange,
                    enabled = !busy,
                    mono = true,
                )
            },
        )

        // ── The typed refusal ─────────────────────────────────────────────────
        if (refusalReasonId != null || refusalDetail != null) {
            // The localized answer for the id the node returned. An id the
            // bundle does not carry resolves to itself, which is why the
            // server's English follows it rather than replacing it.
            StateBlock(
                ListState.Error(
                    title = refusalReasonId?.let { localizedString(it) } ?: refusalDetail.orEmpty(),
                    detail = refusalDetail?.takeIf { it.isNotBlank() && refusalReasonId != null },
                ),
                tag = PeopleTags.ADD_REFUSAL,
                inline = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ── Success ───────────────────────────────────────────────────────────
        justAdded?.let { added ->
            Spacer(Modifier.height(8.dp))
            Text(
                localizedString("mobile.contacts_added", "who", shortKey(added.keyId)),
                style = type.body, color = t.ok,
            )
            Row {
                CirisTextButton(localizedString("mobile.contacts_open_chat"), tag = PeopleTags.ADD_OPEN_CHAT, onClick = onOpenChat)
                CirisTextButton(localizedString("common_close"), tag = PeopleTags.ADD_DISMISS, onClick = onDismissAdded)
            }
        }

        Spacer(Modifier.height(12.dp))
        CirisButton(
            label = if (busy) localizedString("mobile.contacts_add_submit_busy") else localizedString("mobile.contacts_add_submit"),
            tag = PeopleTags.ADD_SUBMIT,
            enabled = !busy && keyId.isNotBlank(),
            onClick = onSubmit,
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Rows
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun ContactRow(
    contact: Contact,
    /**
     * The live consent grant does not cover `chat:` — messages to this contact
     * cannot replicate (CIRISServer#458). Said out loud on the row, because a
     * contact that silently cannot receive anything is the failure mode that
     * defect hid behind.
     */
    chatIneligible: Boolean,
    receipt: Receipt,
    onOpenChat: () -> Unit,
    onOpenReceipt: (Receipt) -> Unit,
) {
    val t = CirisTheme.tokens
    val (glyph, tone) = contact.trust.reading()
    val flags = buildList {
        // The de-admitted-but-still-consented arm. Said out loud rather than
        // rendered as a normal row, because the grant is real and only the
        // human can retract it.
        if (contact.projectionMissing) add(RowFlag(localizedString("mobile.contacts_projection_missing")))
        if (chatIneligible) add(RowFlag(localizedString("mobile.contacts_chat_not_covered"), tag = PeopleTags.ineligible(contact.keyId)))
    }
    val secondary = buildList {
        add(if (contact.chatStarted) localizedString("mobile.contacts_chat_started") else localizedString("mobile.contacts_chat_not_started"))
        if (contact.occurrenceKeyIds.isNotEmpty()) {
            add(localizedString("mobile.contacts_occurrences", "count", contact.occurrenceKeyIds.size.toString()))
        }
    }.joinToString(" · ")
    ItemRow(
        glyph = glyph,
        glyphTint = t.tone(tone),
        title = contact.aliasOverride ?: shortKey(contact.keyId, head = 12, tail = 0),
        meta = shortKey(contact.keyId, head = 16, tail = 0),
        secondary = secondary,
        chips = trustChips(contact.canonical, contact.trust),
        flags = flags,
        receipt = receipt,
        trailing = {
            Chip(ChipSpec(
                label = localizedString("mobile.contacts_open_chat"),
                tag = PeopleTags.chat(contact.keyId),
                kind = ChipKind.CHOICE,
                tone = Tone.BRAND,
                onClick = onOpenChat,
            ))
        },
        tag = PeopleTags.row(contact.keyId),
        onClick = onOpenChat,
        onOpenReceipt = onOpenReceipt,
    )
}

@Composable
private fun PeerRow(peer: LocalPeerState, onPick: () -> Unit) {
    val t = CirisTheme.tokens
    val (glyph, tone) = peer.trust.reading()
    ItemRow(
        glyph = glyph,
        glyphTint = t.tone(tone),
        title = peer.aliasOverride ?: shortKey(peer.keyId, head = 12, tail = 0),
        meta = shortKey(peer.keyId, head = 16, tail = 0),
        secondary = localizedString("mobile.contacts_pubkey", "short", peer.pubkeyEd25519Base64.take(10) + "…") +
            " · " + localizedString("mobile.contacts_first_seen", "when", formatDate(peer.firstSeen)),
        chips = trustChips(peer.canonical, peer.trust),
        trailing = {
            Chip(ChipSpec(
                label = localizedString("mobile.contacts_choose"),
                tag = PeopleTags.pick(peer.keyId),
                kind = ChipKind.CHOICE,
                tone = Tone.BRAND,
                onClick = onPick,
            ))
        },
        tag = PeopleTags.row(peer.keyId),
        onClick = null,
    )
}

/** The canonical badge and the trust state as READING MATTER, not the wire token. */
@Composable
private fun trustChips(canonical: Boolean, trust: PeerTrustState): List<ChipSpec> = buildList {
    if (canonical) add(ChipSpec(localizedString("mobile.contacts_badge_canonical"), tone = Tone.BRAND))
    add(ChipSpec(trustLabel(trust), tone = trust.reading().second))
}

@Composable
private fun trustLabel(trust: PeerTrustState): String = when (trust) {
    PeerTrustState.TRUSTED -> localizedString("mobile.contacts_trust_trusted")
    PeerTrustState.UNTRUSTED -> localizedString("mobile.contacts_trust_untrusted")
    PeerTrustState.BLOCKED -> localizedString("mobile.contacts_trust_blocked")
    PeerTrustState.UNKNOWN -> localizedString("mobile.contacts_trust_unknown")
}
