package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.models.AgentRead
import ai.ciris.mobile.shared.models.ConnectorDto
import ai.ciris.mobile.shared.models.ConnectorTestOutcome
import ai.ciris.mobile.shared.models.ConnectorsList
import ai.ciris.mobile.shared.models.connectorGrantTools
import ai.ciris.mobile.shared.models.connectorStandingKey
import ai.ciris.mobile.shared.platform.testable
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.CardShell
import ai.ciris.mobile.shared.ui.primitives.CirisTextButton
import ai.ciris.mobile.shared.ui.primitives.ConfirmFact
import ai.ciris.mobile.shared.ui.primitives.ConfirmSheet
import ai.ciris.mobile.shared.ui.primitives.FieldRow
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import ai.ciris.mobile.shared.ui.theme.CirisTheme
import ai.ciris.mobile.shared.ui.theme.Tone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The tags this section draws — the contract CSD-020 §7 names. */
object ConnectorTags {
    const val SECTION = "adapters_connectors"
    const val LOADING = "adapters_connectors_loading"
    const val EMPTY = "adapters_connectors_empty"
    const val ADMIN_ONLY = "adapters_connectors_admin_only"
    /** ReadFailureBlock prefix: `adapters_connectors_error` / `adapters_connectors_not_on_this_node`. */
    const val FAILURE_PREFIX = "adapters_connectors"
    fun row(i: Int) = "adapters_connector_$i"
    fun standing(i: Int) = "adapters_connector_standing_$i"
    fun grant(i: Int) = "adapters_connector_grant_$i"
    fun delegation(i: Int) = "adapters_connector_delegation_$i"
    fun testResult(i: Int) = "adapters_connector_test_result_$i"
    fun test(i: Int) = "btn_connector_test_$i"
    fun remove(i: Int) = "btn_connector_remove_$i"
    const val REMOVE_SHEET = "connector_remove"
}

/**
 * CONNECTORS, on the Adapters card (CSD-020 §7). A connector is a database
 * credential handed to the agent's SQL adapter; the card shows what it lets
 * the agent do, never the secret, never "Connected", and a test result as what
 * actually ran.
 */
@Composable
fun AdapterConnectorsSection(
    list: ConnectorsList,
    tests: Map<String, AgentRead<ConnectorTestOutcome>?>,
    removeRefused: Map<String, AgentRead<Nothing>>,
    onTest: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    var confirmRemove by remember { mutableStateOf<ConnectorDto?>(null) }

    Column(modifier = modifier.fillMaxWidth().testable(ConnectorTags.SECTION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizedString("adapters.connectors_title"), style = type.title, color = t.ink)
        Text(localizedString("adapters.connectors_intro"), style = type.body, color = t.dim)
        when (list) {
            ConnectorsList.Loading -> StateBlock(ListState.Loading, tag = ConnectorTags.LOADING, inline = true)
            ConnectorsList.AdminOnly -> StateBlock(
                ListState.Empty(localizedString("adapters.connectors_admin_only"), glyph = GlyphName.LOCK),
                tag = ConnectorTags.ADMIN_ONLY, inline = true,
            )
            is ConnectorsList.Failed -> ReadFailureBlock(
                failure = list.failure,
                tagPrefix = ConnectorTags.FAILURE_PREFIX,
                notOnThisNode = localizedString("adapters.connectors_not_on_this_node"),
                inline = true,
            )
            is ConnectorsList.Ready -> if (list.connectors.isEmpty()) {
                StateBlock(
                    ListState.Empty(localizedString("adapters.connectors_empty"), glyph = GlyphName.KEY),
                    tag = ConnectorTags.EMPTY, inline = true,
                )
            } else {
                list.connectors.forEachIndexed { i, c ->
                    ConnectorCard(
                        i = i, c = c,
                        test = tests[c.connectorId], testing = c.connectorId in tests && tests[c.connectorId] == null,
                        refused = removeRefused[c.connectorId],
                        onTest = { onTest(c.connectorId) },
                        onRemove = { confirmRemove = c },
                    )
                }
            }
        }
    }

    confirmRemove?.let { c ->
        ConfirmSheet(
            title = localizedString("adapters.connector_remove_title", mapOf("name" to c.connectorName)),
            facts = listOf(
                ConfirmFact(localizedString("adapters.connector_remove_fact_who"), "${c.connectorName} · ${c.connectorId}", mono = true),
                ConfirmFact(localizedString("adapters.connector_remove_fact_what"), localizedString("adapters.connector_remove_what")),
                ConfirmFact(localizedString("adapters.connector_remove_fact_signs"), localizedString("adapters.connector_remove_signs")),
            ),
            confirmLabel = localizedString("mobile.common_remove"),
            onConfirm = { confirmRemove = null; onRemove(c.connectorId) },
            onDismiss = { confirmRemove = null },
            destructive = true,
            tagPrefix = ConnectorTags.REMOVE_SHEET,
        )
    }
}

@Composable
private fun ConnectorCard(
    i: Int,
    c: ConnectorDto,
    test: AgentRead<ConnectorTestOutcome>?,
    testing: Boolean,
    refused: AgentRead<Nothing>?,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    val t = CirisTheme.tokens
    val type = CirisTheme.type
    CardShell(tag = ConnectorTags.row(i)) {
        Text(c.connectorName, style = type.body, color = t.ink)
        Text("${c.connectorType} · ${c.connectorId}", style = type.signed, color = t.mute)
        FieldRow(
            label = localizedString("adapters.connector_standing"),
            value = localizedString(
                connectorStandingKey(c),
                mapOf("when" to (c.lastTested ?: c.registeredAt ?: "—")),
            ),
            tag = ConnectorTags.standing(i),
        )
        val tools = connectorGrantTools(c.connectorType)
        FieldRow(
            label = localizedString("adapters.connector_grant"),
            value = if (tools.isEmpty()) localizedString("adapters.connector_grant_unknown") else tools.joinToString(" · "),
            mono = tools.isNotEmpty(),
            gloss = localizedString("adapters.connector_grant_gloss"),
            tag = ConnectorTags.grant(i),
        )
        FieldRow(
            label = localizedString("adapters.connector_delegation"),
            value = localizedString("adapters.connector_delegation_none"),
            tone = Tone.DANGER,
            tag = ConnectorTags.delegation(i),
        )
        when {
            testing -> StateBlock(ListState.Loading, tag = ConnectorTags.testResult(i), inline = true)
            test != null -> ConnectorTestLine(i, test)
        }
        refused?.let { r ->
            when (r) {
                AgentRead.AdminOnly -> Text(localizedString("adapters.connectors_admin_only"), style = type.body, color = t.danger)
                is AgentRead.Failed -> ReadFailureBlock(r.failure, tagPrefix = "adapters_connector_remove_$i", inline = true)
                is AgentRead.Ok -> Unit
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CirisTextButton(localizedString("adapters.connector_test"), tag = ConnectorTags.test(i), onClick = onTest, enabled = !testing)
            CirisTextButton(localizedString("mobile.common_remove"), tag = ConnectorTags.remove(i), onClick = onRemove, danger = true)
        }
    }
}

@Composable
private fun ConnectorTestLine(i: Int, read: AgentRead<ConnectorTestOutcome>) {
    val tag = ConnectorTags.testResult(i)
    when (read) {
        AgentRead.AdminOnly -> StateBlock(
            ListState.Empty(localizedString("adapters.connectors_admin_only"), glyph = GlyphName.LOCK), tag = tag, inline = true,
        )
        is AgentRead.Failed -> ReadFailureBlock(read.failure, tagPrefix = tag, inline = true)
        is AgentRead.Ok -> {
            val (text, tone) = when (val o = read.value) {
                is ConnectorTestOutcome.Passed -> localizedString(
                    "adapters.connector_test_passed",
                    mapOf("ms" to o.latencyMs.toInt().toString(), "when" to (o.testedAt ?: "—")),
                ) to Tone.OK
                is ConnectorTestOutcome.Failed -> localizedString(
                    "adapters.connector_test_failed", mapOf("message" to o.message),
                ) to Tone.DANGER
                is ConnectorTestOutcome.NotRun -> localizedString(
                    "adapters.connector_test_not_run", mapOf("message" to o.message),
                ) to Tone.DIM
            }
            FieldRow(
                label = localizedString("adapters.connector_test_result"),
                value = text, tone = tone, tag = tag, divider = false,
            )
        }
    }
}
