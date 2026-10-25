package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.formatDate
import ai.ciris.mobile.shared.ceg.shortKey
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.federation.ContactRequest
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.Chip
import ai.ciris.mobile.shared.ui.primitives.ChipKind
import ai.ciris.mobile.shared.ui.primitives.ChipSpec
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.ItemRow
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import ai.ciris.mobile.shared.ui.theme.tone
import ai.ciris.mobile.shared.viewmodels.ContactRequestNotice
import ai.ciris.mobile.shared.viewmodels.ContactRequestsRead
import ai.ciris.mobile.shared.viewmodels.ContactRequestsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant

/** CSD-109's tags. Pure, so a test can build them. */
object ContactRequestTags {
    const val LIST = "contact_requests_list"
    const val EMPTY = "contact_requests_empty"
    const val LOADING = "contact_requests_loading"
    const val ERROR = "contact_requests_error"
    const val UNSUPPORTED = "contact_requests_unsupported"
    const val NOTICE = "contact_requests_notice"
    const val REFUSAL = "contact_requests_refusal"
    const val IGNORED_TOGGLE = "contact_requests_ignored_toggle"
    const val IGNORED_LIST = "contact_requests_ignored_list"
    const val ACCEPT_CONFIRM_PREFIX = "contact_request_accept"
    fun row(id: String) = "row_contact_request_$id"
    fun accept(id: String) = "btn_contact_request_accept_$id"
    fun ignore(id: String) = "btn_contact_request_ignore_$id"
    fun ignoredRow(id: String) = "row_contact_request_ignored_$id"
    fun unignore(id: String) = "btn_contact_request_unignore_$id"
}

/**
 * Who a request row names: the requester's own label, marked as theirs, or
 * "Someone" — never a name this client invented (CSD-109 §2).
 */
@Composable
internal fun requestTitle(r: ContactRequest): String {
    val label = r.requesterLabel?.takeIf { it.isNotBlank() }
    return if (label != null) localizedString("mobile.contact_requests_title_named", "label", label)
    else localizedString("mobile.contact_requests_title_someone")
}

private fun dateOf(iso: String?): String? = iso?.let { runCatching { formatDate(Instant.parse(it)) }.getOrNull() }

/** The node's sentence for a refusal, by id; its English when the bundle has none. */
@Composable
private fun refusalText(reasonId: String?, detail: String?): String =
    reasonId?.let { rid -> localizedString(rid).takeIf { it != rid } } ?: detail ?: reasonId ?: ""

/**
 * The requests above People's contact list (CSD-109). Polls while composed,
 * because the node has no push for requests. Draws nothing on a node without
 * the route, and nothing when there are none: the list below speaks.
 */
@Composable
fun ContactRequestsSection(viewModel: ContactRequestsViewModel) {
    DisposableEffect(viewModel) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    val read by viewModel.read.collectAsState()
    val confirming by viewModel.confirming.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val refusal by viewModel.refusal.collectAsState()
    var showIgnored by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notice?.let { n ->
            val text = when (n) {
                ContactRequestNotice.ACCEPTED -> localizedString("mobile.contact_requests_accepted")
                ContactRequestNotice.ACCEPT_PENDING -> localizedString("mobile.contact_requests_accept_pending")
                ContactRequestNotice.IGNORED -> localizedString("mobile.contact_requests_ignored_notice")
                ContactRequestNotice.UNIGNORED -> localizedString("mobile.contact_requests_unignored_notice")
            }
            Text(text, style = type.body, color = t.tone(Tone.DIM), modifier = Modifier.testable(ContactRequestTags.NOTICE, text))
        }
        refusal?.let { r ->
            val text = refusalText(r.reasonId, r.detail)
            Text(text, style = type.body, color = t.danger, modifier = Modifier.testable(ContactRequestTags.REFUSAL, text))
        }

        when (val r = read) {
            ContactRequestsRead.NotAsked, ContactRequestsRead.Loading ->
                Box(Modifier.height(0.dp).testable(ContactRequestTags.LOADING))
            ContactRequestsRead.NotOnThisNode ->
                Box(Modifier.height(0.dp).testable(ContactRequestTags.UNSUPPORTED))
            is ContactRequestsRead.Failed -> {
                val text = localizedString("mobile.contact_requests_error", "why", refusalText(r.refusal?.reasonId, r.detail))
                Text(text, style = type.body, color = t.danger, modifier = Modifier.testable(ContactRequestTags.ERROR, text))
            }
            is ContactRequestsRead.Loaded -> {
                if (r.requests.isEmpty()) {
                    Box(Modifier.height(0.dp).testable(ContactRequestTags.EMPTY))
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().testable(ContactRequestTags.LIST),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val heading = localizedString("mobile.contact_requests_heading")
                        Text(heading, style = type.label, color = t.tone(Tone.DIM))
                        r.requests.forEach { req -> RequestRow(req, busy, viewModel) }
                    }
                }
                if (r.ignored.isNotEmpty()) {
                    CirisTextButton(
                        if (showIgnored) localizedString("mobile.contact_requests_ignored_hide")
                        else localizedString("mobile.contact_requests_ignored_show", "count", r.ignored.size.toString()),
                        tag = ContactRequestTags.IGNORED_TOGGLE,
                        onClick = { showIgnored = !showIgnored },
                    )
                    if (showIgnored) {
                        Column(
                            modifier = Modifier.fillMaxWidth().testable(ContactRequestTags.IGNORED_LIST),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            r.ignored.forEach { req -> IgnoredRow(req, busy, viewModel) }
                        }
                    }
                }
            }
        }
    }

    confirming?.let { req ->
        val who = requestTitle(req)
        ConfirmSheet(
            title = localizedString("mobile.contact_requests_accept_title"),
            facts = listOf(
                ConfirmFact(localizedString("mobile.contact_requests_fact_who"), "$who · ${req.requesterKeyId}", mono = false),
                ConfirmFact(
                    localizedString("mobile.contact_requests_fact_what"),
                    localizedString("mobile.contact_requests_fact_what_value"),
                ),
                ConfirmFact(
                    localizedString("mobile.contact_requests_fact_means"),
                    localizedString("mobile.contact_requests_fact_means_value"),
                ),
            ),
            confirmLabel = localizedString("mobile.contact_requests_accept"),
            onConfirm = { viewModel.confirmAccept() },
            onDismiss = { viewModel.cancelConfirm() },
            tagPrefix = ContactRequestTags.ACCEPT_CONFIRM_PREFIX,
        )
    }
}

@Composable
private fun RequestRow(req: ContactRequest, busy: Boolean, viewModel: ContactRequestsViewModel) {
    val id = req.grantAttestationId
    val asked = dateOf(req.requestedAt)
    ItemRow(
        glyph = GlyphName.INVITE,
        title = requestTitle(req),
        meta = shortKey(req.requesterKeyId, head = 8, tail = 0),
        secondary = asked?.let { localizedString("mobile.contact_requests_asked", "date", it) } ?: "",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(ChipSpec(
                    label = localizedString("mobile.contact_requests_accept"),
                    tag = ContactRequestTags.accept(id),
                    kind = ChipKind.CHOICE,
                    tone = Tone.BRAND,
                    onClick = { if (!busy) viewModel.requestAccept(req) },
                ))
                Chip(ChipSpec(
                    label = localizedString("mobile.contact_requests_ignore"),
                    tag = ContactRequestTags.ignore(id),
                    kind = ChipKind.CHOICE,
                    tone = Tone.DIM,
                    onClick = { if (!busy) viewModel.ignore(req) },
                ))
            }
        },
        tag = ContactRequestTags.row(id),
        onClick = null,
    )
}

@Composable
private fun IgnoredRow(req: ContactRequest, busy: Boolean, viewModel: ContactRequestsViewModel) {
    val id = req.grantAttestationId
    ItemRow(
        glyph = GlyphName.PERSON,
        title = requestTitle(req),
        meta = shortKey(req.requesterKeyId, head = 8, tail = 0),
        secondary = localizedString("mobile.contact_requests_ignored_secondary"),
        trailing = {
            Chip(ChipSpec(
                label = localizedString("mobile.contact_requests_unignore"),
                tag = ContactRequestTags.unignore(id),
                kind = ChipKind.CHOICE,
                tone = Tone.BRAND,
                onClick = { if (!busy) viewModel.unignore(req) },
            ))
        },
        tag = ContactRequestTags.ignoredRow(id),
        onClick = null,
    )
}
