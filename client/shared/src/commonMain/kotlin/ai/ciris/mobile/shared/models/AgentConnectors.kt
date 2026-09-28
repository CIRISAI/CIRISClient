package ai.ciris.mobile.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * CONNECTORS — database credentials handed to the agent through
 * `/v1/connectors*` (CIRISAgent main `routes/connectors.py`). Shown on the
 * Adapters card, not a card of their own (CSD-020 §7): a SQL connector is a
 * configuration of ONE adapter, `external_data_sql`, whose own wizard already
 * takes the same host, database, user and password
 * (`ciris_adapters/external_data_sql/manifest.json`, step `connection_server`).
 * The route registers the connector by calling that adapter's
 * `initialize_sql_connector` tool (`connectors.py:282-291`).
 *
 * WHAT THIS FILE REFUSES TO SAY.
 *  * "Connected". Registration succeeds even when the adapter never took the
 *    connector — a tool-bus failure is logged and the reply is still
 *    `registered` (`connectors.py:292-297`) — and no delegation or consent
 *    record is written for the credential (CIRISAgent#1211).
 *  * "Healthy" on a test that did not run. With no tool bus the SQL test
 *    returns success with "(tool bus unavailable, skipped)" (`:383-384`), and
 *    every REST test is "(simulated)" (`:399-401`); the route then stores
 *    `healthy` either way (`:451-453`).
 *  * The secret. The list never returns `config` (`ConnectorInfo`,
 *    `:112-122`) and nothing here has a field for it.
 */

@Serializable
data class ConnectorDto(
    @SerialName("connector_id") val connectorId: String,
    @SerialName("connector_type") val connectorType: String,
    @SerialName("connector_name") val connectorName: String,
    val status: String = "registered",
    @SerialName("registered_at") val registeredAt: String? = null,
    @SerialName("last_tested") val lastTested: String? = null,
    @SerialName("last_test_result") val lastTestResult: String? = null,
    @SerialName("total_requests") val totalRequests: Int = 0,
)

@Serializable
data class ConnectorListDto(
    val connectors: List<ConnectorDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ConnectorTestDto(
    @SerialName("connector_id") val connectorId: String,
    val success: Boolean,
    val message: String = "",
    @SerialName("latency_ms") val latencyMs: Double = 0.0,
    @SerialName("tested_at") val testedAt: String? = null,
)

/** The connector list as the card draws it: four states, never two that look alike. */
sealed interface ConnectorsList {
    data object Loading : ConnectorsList

    /** 403 — only the agent's administrators can see connectors (`connectors.py:340`). */
    data object AdminOnly : ConnectorsList

    data class Failed(val failure: ai.ciris.mobile.shared.ui.screens.ReadFailure) : ConnectorsList

    data class Ready(val connectors: List<ConnectorDto>) : ConnectorsList
}

fun connectorsListOf(read: AgentRead<ConnectorListDto>): ConnectorsList = when (read) {
    is AgentRead.Ok -> ConnectorsList.Ready(read.value.connectors.sortedBy { it.connectorName.lowercase() })
    AgentRead.AdminOnly -> ConnectorsList.AdminOnly
    is AgentRead.Failed -> ConnectorsList.Failed(read.failure)
}

/** A connection test, as what actually happened. */
sealed interface ConnectorTestOutcome {
    val testedAt: String?

    data class Passed(val latencyMs: Double, override val testedAt: String?) : ConnectorTestOutcome
    data class Failed(val message: String, override val testedAt: String?) : ConnectorTestOutcome

    /** The agent answered "success" without touching the database. */
    data class NotRun(val message: String, override val testedAt: String?) : ConnectorTestOutcome
}

/**
 * The agent's two ways of reporting success for a test it did not perform
 * (`connectors.py:384`, `:401`). Matched on the agent's own words because it
 * sends no field that says so — the ask is a `performed: bool` (CSD-020 §7).
 */
private val NOT_RUN_MARKERS = listOf("skipped", "(simulated)")

fun connectorTestOutcome(dto: ConnectorTestDto): ConnectorTestOutcome = when {
    !dto.success -> ConnectorTestOutcome.Failed(dto.message, dto.testedAt)
    NOT_RUN_MARKERS.any { dto.message.contains(it, ignoreCase = true) } ->
        ConnectorTestOutcome.NotRun(dto.message, dto.testedAt)
    else -> ConnectorTestOutcome.Passed(dto.latencyMs, dto.testedAt)
}

/**
 * The localization key for a connector's standing, from what the agent stored.
 * Never "connected": every value names what is and is not known.
 *
 *  * `registered` → handed over, never tested — not proof the adapter took it.
 *  * `healthy` / `unhealthy` → the stored result of the last test, which the
 *    agent also stores as `healthy` after a test that did not run.
 *  * `disabled` → a mark on the route's registry that the SQL tools never read
 *    (`connectors.py:517-520`), so it does not stop the agent.
 */
fun connectorStandingKey(dto: ConnectorDto): String = when (dto.status.lowercase()) {
    "registered" -> "adapters.connector_standing_registered"
    "healthy" -> "adapters.connector_standing_last_test_passed"
    "unhealthy" -> "adapters.connector_standing_last_test_failed"
    "disabled" -> "adapters.connector_standing_disabled"
    else -> "adapters.connector_standing_unknown"
}

/**
 * What a connector of [connectorType] lets the agent do, as the tools the
 * agent's SQL adapter exposes for every connector it holds
 * (`ciris_adapters/external_data_sql/service.py:190-200`). Read from the
 * agent's code, not from the wire — no route says what THIS connector grants
 * (CIRISAgent#1211) — so the card labels it as what any SQL connector grants.
 * Empty for a type this agent cannot register (only `POST /connectors/sql`
 * exists), which the card says rather than guessing.
 */
fun connectorGrantTools(connectorType: String): List<String> = when (connectorType.lowercase()) {
    "sql" -> listOf(
        "sql_find_user_data", "sql_export_user", "sql_delete_user", "sql_anonymize_user",
        "sql_verify_deletion", "sql_get_stats", "sql_query",
    )
    else -> emptyList()
}
