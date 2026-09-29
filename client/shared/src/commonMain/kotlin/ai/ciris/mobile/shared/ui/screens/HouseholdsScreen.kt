package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.ceg.formatDate
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.FamilyDto
import ai.ciris.mobile.shared.models.federation.FamilyMemberDto
import ai.ciris.mobile.shared.platform.rememberTestableScrollState
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CeremonyBlock
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
import ai.ciris.mobile.shared.ui.primitives.Receipt
import ai.ciris.mobile.shared.ui.primitives.ReceiptSheet
import ai.ciris.mobile.shared.ui.primitives.Signer
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.viewmodels.HouseholdNotice
import ai.ciris.mobile.shared.viewmodels.HouseholdsLoad
import ai.ciris.mobile.shared.viewmodels.HouseholdsViewModel
import ai.ciris.mobile.shared.viewmodels.PendingChange
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.datetime.Instant

/**
 * **The household** (CSD-100) — the Family hub's own content on Family › Rules.
 *
 * A household is a CC 3.3.4 `family` this node's owner is in. This panel says
 * which one you are looking at (and lets you switch, since one person can be
 * in several — CC 3.3.4 "one identity MAY belong to multiple families"), how
 * it decides (its `consensus_protocol`, read the way the node reads it), who
 * its founders are, and the acts that change it: form one, leave, dissolve,
 * and — for a quorum household — the change waiting on signatures, as a
 * [CeremonyBlock].
 *
 * The roster itself is NOT here. Who is in the household is a People fact and
 * lives on Family › People ([HouseholdMembersScreen], CSD-101); this card
 * names only the founders, because they are the rule. Two lists of the same
 * members would be one list under two names.
 *
 * Rendered inside [ai.ciris.mobile.shared.ui.screens.commons.LayerHubScreen]
 * for the Family scope, which already scrolls, so this is a plain Column.
 */
@Composable
fun HouseholdPanel(
    viewModel: HouseholdsViewModel,
    onOpenMembers: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val load by viewModel.load.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val refusal by viewModel.refusal.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val confirming by viewModel.confirming.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val me by viewModel.myKeyId.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }
    var creating by remember { mutableStateOf(false) }
    var receiptFor by remember { mutableStateOf<Receipt?>(null) }
    val names = rememberNames(contacts, me)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        when (val l = load) {
            HouseholdsLoad.Loading -> StateBlock(ListState.Loading, tag = HouseholdTags.LOADING, inline = true)
            is HouseholdsLoad.Failed -> HouseholdsFailure(l, HouseholdTags.ERROR, HouseholdTags.NOT_ON_THIS_NODE)
            is HouseholdsLoad.Loaded -> {
                val family = l.families.firstOrNull { it.familyId == selectedId } ?: l.families.firstOrNull()
                HouseholdSwitcher(
                    families = l.families,
                    selectedId = family?.familyId,
                    onSelect = viewModel::select,
                    onCreate = { creating = !creating; viewModel.clearMessages() },
                    onRefresh = viewModel::load,
                )
                if (creating || l.families.isEmpty()) {
                    CreateHouseholdCard(
                        contacts = contacts,
                        busy = busy,
                        showCancel = l.families.isNotEmpty(),
                        onCancel = { creating = false },
                        onCreate = { name, protocol, founding ->
                            viewModel.create(name, protocol, founding)
                            creating = false
                        },
                    )
                }
                if (l.families.isEmpty()) {
                    StateBlock(
                        ListState.Empty(localizedString("households.empty"), glyph = GlyphName.HOME),
                        tag = HouseholdTags.EMPTY, inline = true,
                    )
                }
                HouseholdMessages(refusal, notice)
                if (family != null) {
                    HouseholdCard(
                        family = family,
                        governance = viewModel.governance(family),
                        names = names,
                        busy = busy,
                        onOpenMembers = onOpenMembers,
                        onOpenReceipt = { receiptFor = it },
                        onLeave = { viewModel.request(HouseholdAct.Leave) },
                        onDissolve = { viewModel.request(HouseholdAct.Dissolve) },
                    )
                    PendingChangeBlock(viewModel, family, pending, names, me, busy)
                }
            }
        }
    }

    val family = viewModel.selected()
    confirming?.let { act ->
        if (family != null) HouseholdConfirm(act, family, viewModel.governance(family), viewModel::confirmHouseholdAct, viewModel::cancelConfirm)
    }
    receiptFor?.let { ReceiptSheet(receipt = it, onDismiss = { receiptFor = null }) }
}

/**
 * **The roster** (CSD-101) — who is in the household, on Family › People.
 *
 * Each member is a row: their name as your contacts know them (else a short
 * key), their role, and — when this household's rule lets you — make
 * founder / make member and remove. Adding someone picks from your contacts:
 * a member must be an identity this node knows, and People is where you come
 * to know them.
 */
@Composable
fun HouseholdMembersScreen(
    viewModel: HouseholdsViewModel,
    onOpenHousehold: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val load by viewModel.load.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val refusal by viewModel.refusal.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val confirming by viewModel.confirming.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val contactsFailed by viewModel.contactsFailed.collectAsState()
    val me by viewModel.myKeyId.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }
    var adding by remember { mutableStateOf(false) }
    val names = rememberNames(contacts, me)

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberTestableScrollState(name = "household_members"))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val l = load) {
            HouseholdsLoad.Loading -> StateBlock(ListState.Loading, tag = HouseholdTags.MEMBERS_LOADING)
            is HouseholdsLoad.Failed -> HouseholdsFailure(l, HouseholdTags.MEMBERS_ERROR, HouseholdTags.MEMBERS_NOT_ON_THIS_NODE)
            is HouseholdsLoad.Loaded -> {
                val family = l.families.firstOrNull { it.familyId == selectedId } ?: l.families.firstOrNull()
                if (family == null) {
                    StateBlock(
                        ListState.Empty(localizedString("households.no_household"), glyph = GlyphName.HOME),
                        tag = HouseholdTags.MEMBERS_NO_HOUSEHOLD,
                        action = {
                            CirisTextButton(localizedString("households.go_rules"), tag = HouseholdTags.MEMBERS_GO_RULES, onClick = onOpenHousehold)
                        },
                    )
                    return@Column
                }
                HouseholdSwitcher(
                    families = l.families,
                    selectedId = family.familyId,
                    onSelect = viewModel::select,
                    onCreate = null,
                    onRefresh = viewModel::load,
                )
                val governance = viewModel.governance(family)
                val canChange = governance is Governance.Quorum ||
                    (governance is Governance.FounderOnly && governance.iAmFounder)
                HouseholdMessages(refusal, notice)
                if (!canChange) GovernanceNote(governance)

                Column(
                    modifier = Modifier.fillMaxWidth().testable(HouseholdTags.MEMBERS_LIST, family.members.size.toString()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (m in family.members) {
                        MemberRow(
                            member = m,
                            name = names(m.keyId),
                            isMe = m.keyId == me,
                            // Removing yourself is leaving, which lives with the household
                            // (Rules), not here — and with no known "me" the row cannot be
                            // told apart from yours, so nothing is offered.
                            canChange = canChange && me != null && m.keyId != me,
                            busy = busy,
                            onRole = { role -> viewModel.request(HouseholdAct.Role(m.keyId, names(m.keyId), role)) },
                            onRemove = { viewModel.request(HouseholdAct.Remove(m.keyId, names(m.keyId))) },
                        )
                    }
                }

                if (canChange) {
                    if (!adding) {
                        CirisButton(localizedString("households.add_member"), tag = HouseholdTags.ADD_OPEN, onClick = { adding = true; viewModel.clearMessages() }, enabled = !busy)
                    } else {
                        AddMemberCard(
                            contacts = contacts.filter { c -> family.members.none { it.keyId == c.keyId } },
                            contactsFailed = contactsFailed,
                            names = names,
                            onPick = { c -> adding = false; viewModel.request(HouseholdAct.Add(c.keyId, names(c.keyId))) },
                            onCancel = { adding = false },
                        )
                    }
                }
                Text(localizedString("households.limits"), style = type.body, color = t.dim, modifier = Modifier.testable(HouseholdTags.LIMITS))
            }
        }
    }

    val family = viewModel.selected()
    confirming?.let { act ->
        if (family != null) HouseholdConfirm(act, family, viewModel.governance(family), viewModel::confirmMemberAct, viewModel::cancelConfirm)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Pieces
// ═════════════════════════════════════════════════════════════════════════════

/** Which household you are looking at. One chip per household; the selected one is marked. */
@Composable
private fun HouseholdSwitcher(
    families: List<FamilyDto>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCreate: (() -> Unit)?,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testable(HouseholdTags.SWITCHER, selectedId),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (f in families) {
            Chip(ChipSpec(
                label = f.name.ifBlank { shortKey(f.familyId) },
                tag = HouseholdTags.chip(f.familyId),
                kind = ChipKind.CHOICE,
                selected = f.familyId == selectedId,
                glyph = GlyphName.HOME,
                onClick = { onSelect(f.familyId) },
            ))
        }
        if (onCreate != null && families.isNotEmpty()) {
            Chip(ChipSpec(localizedString("households.create_open"), tag = HouseholdTags.CREATE_OPEN, kind = ChipKind.CHOICE, glyph = GlyphName.NEW_GROUP, onClick = onCreate))
        }
        Chip(ChipSpec(localizedString("common_refresh"), tag = HouseholdTags.REFRESH, kind = ChipKind.CHOICE, glyph = GlyphName.REFRESH, onClick = onRefresh))
    }
}

@Composable
private fun HouseholdsFailure(l: HouseholdsLoad.Failed, errorTag: String, notOnThisNodeTag: String) {
    val refusal = l.refusal
    // Two literal tags rather than ReadFailureBlock's `${prefix}_error`: the
    // CSD declares them by name and the state-tag test greps for the literal.
    val state = if (refusal != null) {
        ListState.Error(title = localizedString("households.read_refused"), body = householdRefusalText(refusal))
    } else {
        l.failure.listState(
            notOnThisNode = localizedString("households.not_on_this_node"),
            failedTitle = localizedString("mobile.state_read_failed"),
            failedBody = localizedString("mobile.state_read_failed_body"),
        )
    }
    val tag = if (refusal == null && l.failure is ReadFailure.NotOnThisNode) notOnThisNodeTag else errorTag
    StateBlock(state, tag = tag)
}

@Composable
private fun HouseholdMessages(refusal: NodeRefusal?, notice: HouseholdNotice?) {
    refusal?.let {
        StateBlock(ListState.Error(title = householdRefusalText(it)), tag = HouseholdTags.REFUSAL, inline = true)
    }
    notice?.let {
        Text(
            localizedString("households.notice_${it.name.lowercase()}"),
            style = CirisTheme.type.body, color = CirisTheme.tokens.ok,
            modifier = Modifier.testable(HouseholdTags.NOTICE, it.name.lowercase()),
        )
    }
}

@Composable
private fun GovernanceNote(governance: Governance) {
    val text = when (governance) {
        is Governance.FounderOnly -> localizedString("households.governance_not_founder")
        is Governance.Quorum -> localizedString("households.governance_quorum", mapOf("m" to governance.m.toString(), "n" to governance.n.toString()))
        is Governance.Ungovernable -> protocolText(governance)
    }
    Text(text, style = CirisTheme.type.body, color = CirisTheme.tokens.dim, modifier = Modifier.testable(HouseholdTags.GOVERNANCE_NOTE, text))
}

@Composable
private fun protocolText(g: Governance): String = when (g) {
    is Governance.FounderOnly -> localizedString("households.protocol_founder_only")
    is Governance.Quorum -> localizedString("households.protocol_quorum", mapOf("m" to g.m.toString(), "n" to g.n.toString()))
    is Governance.Ungovernable -> localizedString("households.protocol_ungovernable", "protocol", g.protocol)
}

@Composable
private fun roleText(role: String): String = when (role) {
    ROLE_FOUNDER -> localizedString("households.role_founder")
    ROLE_MEMBER -> localizedString("households.role_member")
    else -> role
}

private fun readableDate(raw: String?): String? =
    raw?.let { runCatching { formatDate(Instant.parse(it)) }.getOrDefault(it) }

@Composable
private fun HouseholdCard(
    family: FamilyDto,
    governance: Governance,
    names: (String) -> String,
    busy: Boolean,
    onOpenMembers: () -> Unit,
    onOpenReceipt: (Receipt) -> Unit,
    onLeave: () -> Unit,
    onDissolve: () -> Unit,
) {
    val t = CirisTheme.tokens
    val receipt = householdReceipt(family)
    ItemRow(
        glyph = GlyphName.HOME,
        glyphTint = t.circle(ai.ciris.mobile.shared.ui.nav.CohortScope.FAMILY),
        title = family.name.ifBlank { shortKey(family.familyId) },
        meta = shortKey(family.familyId, head = 18, tail = 4),
        secondary = localizedString("households.members_count", "count", family.members.size.toString()),
        tag = HouseholdTags.RECORD,
        receipt = receipt,
        onClick = onOpenMembers,
        onOpenReceipt = onOpenReceipt,
    )
    CardShell(tag = HouseholdTags.CARD, accent = t.circle(ai.ciris.mobile.shared.ui.nav.CohortScope.FAMILY)) {
        FieldRow(localizedString("households.field_name"), value = family.name, tag = HouseholdTags.NAME)
        FieldRow(
            localizedString("households.field_protocol"), value = protocolText(governance),
            protocol = "consensus_protocol: ${family.consensusProtocol}", tag = HouseholdTags.PROTOCOL,
        )
        FieldRow(
            localizedString("households.field_founders"),
            value = family.members.filter { it.role == ROLE_FOUNDER }.joinToString(", ") { names(it.keyId) }
                .ifBlank { localizedString("households.founders_none") },
            tag = HouseholdTags.FOUNDERS,
        )
        FieldRow(
            localizedString("households.field_my_role"),
            value = family.myRole?.let { roleText(it) } ?: localizedString("ceg.envelope.not_sent"),
            tone = if (family.myRole == null) Tone.DANGER else Tone.INK,
            tag = HouseholdTags.MY_ROLE,
        )
        FieldRow(
            localizedString("households.field_members"),
            value = localizedString("households.members_count", "count", family.members.size.toString()),
            tag = HouseholdTags.MEMBER_COUNT,
        )
        FieldRow(
            localizedString("households.field_founded"),
            value = readableDate(family.foundedAt) ?: localizedString("ceg.envelope.not_sent"),
            tone = if (family.foundedAt == null) Tone.DANGER else Tone.INK,
            tag = HouseholdTags.FOUNDED, divider = false,
        )
        Spacer(Modifier.height(8.dp))
        GovernanceNote(governance)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CirisTextButton(localizedString("households.leave"), tag = HouseholdTags.LEAVE, onClick = onLeave, enabled = !busy, danger = true)
            if (routeOf(HouseholdAct.Dissolve, governance) != ActRoute.NOT_ALLOWED) {
                CirisTextButton(localizedString("households.dissolve"), tag = HouseholdTags.DISSOLVE, onClick = onDissolve, enabled = !busy, danger = true)
            }
        }
    }
    Text(
        localizedString("households.limits"), style = CirisTheme.type.body, color = t.dim,
        modifier = Modifier.testable(HouseholdTags.LIMITS),
    )
}

/** The change waiting on signatures, and the way it moves between members. */
@Composable
private fun PendingChangeBlock(
    viewModel: HouseholdsViewModel,
    family: FamilyDto,
    pending: PendingChange?,
    names: (String) -> String,
    me: String?,
    busy: Boolean,
) {
    val t = CirisTheme.tokens
    val clipboard = LocalClipboardManager.current
    var paste by remember { mutableStateOf("") }
    var pasteBad by remember { mutableStateOf(false) }
    val quorum = viewModel.governance(family) as? Governance.Quorum ?: return

    if (pending != null && pending.familyId == family.familyId) {
        val target = pending.targetKeyId?.let(names).orEmpty()
        val proposal = when (pending.action) {
            "add" -> localizedString("households.change_add", "name", target)
            "remove" -> localizedString("households.change_remove", "name", target)
            "role" -> localizedString("households.change_role", "name", target)
            else -> localizedString("households.change_dissolve")
        }
        val states = signerStates(pending.signers, pending.signatures, pending.proposedBy)
        CeremonyBlock(
            proposal = proposal,
            proposedBy = pending.proposedBy?.let(names) ?: localizedString("households.change_someone"),
            whenText = localizedString("households.change_waiting"),
            signers = states.map { (k, s) -> Signer(names(k), s) },
            needed = pending.required,
            tagPrefix = HouseholdTags.CHANGE,
            note = localizedString("households.change_note"),
            onSign = if (!pending.signedBy(me) && !busy) ({ viewModel.sign() }) else null,
            onRefuse = { viewModel.discardChange() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CirisTextButton(localizedString("households.change_copy"), tag = HouseholdTags.CHANGE_COPY, onClick = {
                clipboard.setText(AnnotatedString(encodeCarry(pending.carry)))
            })
            CirisButton(
                localizedString("households.change_apply"), tag = HouseholdTags.CHANGE_APPLY,
                onClick = { viewModel.apply() },
                enabled = !busy && (pending.quorumMet || pending.signatures.size >= pending.required),
            )
        }
    }
    // Someone else's change arrives as text, because the node has no inbox for it.
    CardShell(tag = "card_${HouseholdTags.CHANGE}_paste") {
        Text(
            localizedString("households.governance_quorum", mapOf("m" to quorum.m.toString(), "n" to quorum.n.toString())),
            style = CirisTheme.type.body, color = t.dim,
        )
        Spacer(Modifier.height(8.dp))
        CirisTextField(
            tag = HouseholdTags.CHANGE_PASTE, value = paste,
            onValueChange = { paste = it; pasteBad = false },
            placeholder = localizedString("households.change_paste_hint"), singleLine = false, mono = true,
        )
        if (pasteBad) {
            Text(localizedString("households.change_import_bad"), style = CirisTheme.type.body, color = t.danger,
                modifier = Modifier.testable(HouseholdTags.CHANGE_NOTE))
        }
        Spacer(Modifier.height(8.dp))
        CirisTextButton(localizedString("households.change_import"), tag = HouseholdTags.CHANGE_IMPORT, enabled = paste.isNotBlank(), onClick = {
            if (viewModel.importChange(decodeCarry(paste))) paste = "" else pasteBad = true
        })
    }
}

@Composable
private fun CreateHouseholdCard(
    contacts: List<Contact>,
    busy: Boolean,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onCreate: (String, ProtocolChoice, List<String>) -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var name by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(ProtocolChoice.FOUNDER_ONLY) }
    var founding by remember { mutableStateOf(setOf<String>()) }
    CardShell(tag = HouseholdTags.CREATE_CARD) {
        Text(localizedString("households.create_title"), style = type.title, color = t.ink)
        Spacer(Modifier.height(8.dp))
        CirisTextField(tag = HouseholdTags.CREATE_NAME, value = name, onValueChange = { name = it.take(200) },
            placeholder = localizedString("households.create_name_hint"))
        Spacer(Modifier.height(10.dp))
        Text(localizedString("households.create_protocol_label").uppercase(), style = type.label, color = t.mute)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in ProtocolChoice.entries) {
                Chip(ChipSpec(
                    label = localizedString("households.protocol_choice_${c.wire}"),
                    tag = HouseholdTags.protocolOption(c), kind = ChipKind.CHOICE,
                    selected = protocol == c, onClick = { protocol = c },
                ))
            }
        }
        if (protocol != ProtocolChoice.FOUNDER_ONLY) {
            Text(localizedString("households.create_quorum_note"), style = type.body, color = t.dim)
        }
        Spacer(Modifier.height(10.dp))
        Text(localizedString("households.create_founding_label").uppercase(), style = type.label, color = t.mute)
        Text(localizedString("households.create_founding_hint"), style = type.body, color = t.dim)
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in contacts) {
                val on = c.keyId in founding
                Chip(ChipSpec(
                    label = c.aliasOverride ?: shortKey(c.keyId, head = 12, tail = 0),
                    tag = HouseholdTags.founding(c.keyId), kind = ChipKind.FILTER, selected = on,
                    glyph = if (on) GlyphName.CHECK else GlyphName.PERSON,
                    onClick = { founding = if (on) founding - c.keyId else founding + c.keyId },
                ))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showCancel) CirisTextButton(localizedString("households.create_cancel"), tag = HouseholdTags.CREATE_CANCEL, onClick = onCancel)
            CirisButton(
                localizedString("households.create_submit"), tag = HouseholdTags.CREATE_SUBMIT,
                enabled = !busy && name.isNotBlank(),
                onClick = { onCreate(name, protocol, founding.toList()) },
            )
        }
    }
}

@Composable
private fun MemberRow(
    member: FamilyMemberDto,
    name: String,
    isMe: Boolean,
    canChange: Boolean,
    busy: Boolean,
    onRole: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val t = CirisTheme.tokens
    val founder = member.role == ROLE_FOUNDER
    val chips = buildList {
        add(ChipSpec(roleText(member.role), tone = if (founder) Tone.BRAND else Tone.MUTE))
        if (isMe) add(ChipSpec(localizedString("households.you"), tone = Tone.OK))
    }
    val makeLabel = localizedString(if (founder) "households.make_member" else "households.make_founder")
    val removeLabel = localizedString("households.remove")
    val actions: (@Composable () -> Unit)? = if (!canChange) null else {
        @Composable {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(ChipSpec(
                    label = makeLabel,
                    tag = HouseholdTags.role(member.keyId), kind = ChipKind.CHOICE,
                    onClick = { if (!busy) onRole(if (founder) ROLE_MEMBER else ROLE_FOUNDER) },
                ))
                Chip(ChipSpec(
                    label = removeLabel,
                    tag = HouseholdTags.remove(member.keyId), kind = ChipKind.CHOICE, tone = Tone.DANGER,
                    onClick = { if (!busy) onRemove() },
                ))
            }
        }
    }
    // No hamburger: the node sends one envelope per household, not per member,
    // and a membership is a fold over rows rather than one signed claim. The
    // household's receipt is on the household (CSD-100).
    ItemRow(
        glyph = GlyphName.PERSON,
        glyphTint = t.circle(ai.ciris.mobile.shared.ui.nav.CohortScope.FAMILY),
        title = name,
        meta = shortKey(member.keyId, head = 16, tail = 4),
        secondary = readableDate(member.joinedAt)?.let { localizedString("households.member_joined", "when", it) },
        chips = chips,
        tag = HouseholdTags.member(member.keyId),
        trailing = actions,
    )
}

@Composable
private fun AddMemberCard(
    contacts: List<Contact>,
    contactsFailed: Boolean,
    names: (String) -> String,
    onPick: (Contact) -> Unit,
    onCancel: () -> Unit,
) {
    val t = CirisTheme.tokens
    CardShell(tag = HouseholdTags.ADD_CARD) {
        Text(localizedString("households.add_title_card"), style = CirisTheme.type.title, color = t.ink)
        Spacer(Modifier.height(8.dp))
        when {
            contactsFailed -> StateBlock(
                ListState.Error(title = localizedString("households.add_contacts_failed")),
                tag = HouseholdTags.ADD_NO_CONTACTS, inline = true,
            )
            contacts.isEmpty() -> StateBlock(
                ListState.Empty(localizedString("households.add_no_contacts"), glyph = GlyphName.PERSON),
                tag = HouseholdTags.ADD_NO_CONTACTS, inline = true,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in contacts) {
                    Chip(ChipSpec(
                        label = names(c.keyId), tag = HouseholdTags.pick(c.keyId),
                        kind = ChipKind.CHOICE, glyph = GlyphName.INVITE, onClick = { onPick(c) },
                    ))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        CirisTextButton(localizedString("households.create_cancel"), tag = "btn_household_member_add_cancel", onClick = onCancel)
    }
}

/**
 * The three facts every household act confirms: which household, what
 * changes, and who signs. Who signs is where the protocol shows: you as a
 * founder, you proposing for M of N, or you alone because leaving is yours.
 */
@Composable
private fun HouseholdConfirm(
    act: HouseholdAct,
    family: FamilyDto,
    governance: Governance,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val household = family.name.ifBlank { shortKey(family.familyId) }
    val route = routeOf(act, governance)
    val (title, what) = when (act) {
        is HouseholdAct.Add -> localizedString("households.add_title", "name", act.label) to
            localizedString("households.add_what", "name", act.label)
        is HouseholdAct.Remove -> localizedString("households.remove_title", "name", act.label) to
            localizedString("households.remove_what", "name", act.label)
        is HouseholdAct.Role -> if (act.role == ROLE_FOUNDER)
            localizedString("households.role_title_founder", "name", act.label) to localizedString("households.role_what_founder", "name", act.label)
        else localizedString("households.role_title_member", "name", act.label) to localizedString("households.role_what_member", "name", act.label)
        HouseholdAct.Dissolve -> localizedString("households.dissolve_title", "household", household) to
            localizedString("households.dissolve_what")
        HouseholdAct.Leave -> localizedString("households.leave_title", "household", household) to
            localizedString("households.leave_what")
    }
    val who = when {
        act is HouseholdAct.Leave -> localizedString("households.signs_you_only")
        governance is Governance.Quorum -> localizedString("households.signs_quorum", mapOf("m" to governance.m.toString(), "n" to governance.n.toString()))
        else -> localizedString("households.signs_you_founder")
    }
    ConfirmSheet(
        title = title,
        facts = listOf(
            ConfirmFact(localizedString("households.confirm_household"), household),
            ConfirmFact(localizedString("households.confirm_what"), what),
            ConfirmFact(localizedString("households.confirm_who_signs"), who),
        ),
        confirmLabel = localizedString(if (route == ActRoute.PROPOSE) "households.confirm_propose" else "households.confirm_do"),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = act is HouseholdAct.Remove || act is HouseholdAct.Dissolve || act is HouseholdAct.Leave,
        tagPrefix = HouseholdTags.CONFIRM,
    )
}

/** A person's name as this owner knows them: "You", a contact's alias, else a short key. */
@Composable
private fun rememberNames(contacts: List<Contact>, me: String?): (String) -> String {
    val you = localizedString("households.you")
    return remember(contacts, me, you) {
        val byKey = contacts.associateBy { it.keyId }
        return@remember { key: String ->
            when {
                key == me -> you
                else -> byKey[key]?.aliasOverride ?: shortKey(key, head = 12, tail = 0)
            }
        }
    }
}

/**
 * A node refusal in the reader's language: the bundle's text for the node's
 * `family.*` id, else the node's own English, else the status. Never a raw id.
 */
@Composable
internal fun householdRefusalText(r: NodeRefusal): String {
    val id = r.reasonId
    if (id != null) {
        val text = localizedString(id)
        if (text != id && text.isNotBlank()) return text
    }
    return r.detail ?: id ?: localizedString("households.refusal_status", "status", r.statusCode.toString())
}
