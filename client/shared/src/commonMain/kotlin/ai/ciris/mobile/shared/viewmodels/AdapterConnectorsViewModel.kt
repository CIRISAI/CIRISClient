package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.AgentRead
import ai.ciris.mobile.shared.models.ConnectorListDto
import ai.ciris.mobile.shared.models.ConnectorTestDto
import ai.ciris.mobile.shared.models.ConnectorTestOutcome
import ai.ciris.mobile.shared.models.ConnectorsList
import ai.ciris.mobile.shared.models.connectorTestOutcome
import ai.ciris.mobile.shared.models.connectorsListOf
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The three connector routes the Adapters card calls — an interface so the view model is testable. */
interface ConnectorsBackend {
    suspend fun list(): AgentRead<ConnectorListDto>
    suspend fun test(connectorId: String): AgentRead<ConnectorTestDto>
    suspend fun remove(connectorId: String): AgentRead<Any>
}

fun connectorsBackendOf(api: CIRISApiClient): ConnectorsBackend = object : ConnectorsBackend {
    override suspend fun list() = api.listConnectors()
    override suspend fun test(connectorId: String) = api.testConnector(connectorId)
    override suspend fun remove(connectorId: String): AgentRead<Any> = api.deleteConnector(connectorId)
}

/**
 * Drives the connectors section of the Adapters card (CSD-020 §7).
 *
 * Two acts, and what each one does NOT do is part of the contract:
 *  * **Test** asks the agent to run `SELECT 1` through the connector. A
 *    "success" the agent reports without running anything is kept as
 *    [ConnectorTestOutcome.NotRun], never as a pass.
 *  * **Remove** deletes the route's record. The agent's SQL tools keep their
 *    own copy until the agent restarts (`connectors.py:582-584`), so the
 *    screen confirms with that fact, not with "disconnected".
 *
 * There is no add here: the SQL adapter's wizard already takes the same
 * credential, and a second door onto one object is the duplication the card
 * rules forbid. There is no enable/disable either: the flag it sets is read by
 * nothing that uses the connection (`connectors.py:517-520`).
 */
class AdapterConnectorsViewModel(private val backend: ConnectorsBackend) : ViewModel() {

    private val _list = MutableStateFlow<ConnectorsList>(ConnectorsList.Loading)
    val list: StateFlow<ConnectorsList> = _list.asStateFlow()

    private val _tests = MutableStateFlow<Map<String, AgentRead<ConnectorTestOutcome>?>>(emptyMap())
    /** The last test this session ran, by connector id; a null value is a test in flight. */
    val tests: StateFlow<Map<String, AgentRead<ConnectorTestOutcome>?>> = _tests.asStateFlow()

    private val _removeRefused = MutableStateFlow<Map<String, AgentRead<Nothing>>>(emptyMap())
    /** A removal the agent refused or that failed, by connector id. */
    val removeRefused: StateFlow<Map<String, AgentRead<Nothing>>> = _removeRefused.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _list.value = ConnectorsList.Loading
            _list.value = connectorsListOf(backend.list())
        }
    }

    fun test(connectorId: String) {
        _tests.value = _tests.value + (connectorId to null)
        viewModelScope.launch {
            val outcome: AgentRead<ConnectorTestOutcome> = when (val r = backend.test(connectorId)) {
                is AgentRead.Ok -> AgentRead.Ok(connectorTestOutcome(r.value))
                AgentRead.AdminOnly -> AgentRead.AdminOnly
                is AgentRead.Failed -> r
            }
            _tests.value = _tests.value + (connectorId to outcome)
            // The agent stores the result on its record; re-read so the
            // standing line and the test line come from the same answer.
            _list.value = connectorsListOf(backend.list())
        }
    }

    fun remove(connectorId: String) {
        viewModelScope.launch {
            when (val r = backend.remove(connectorId)) {
                is AgentRead.Ok -> {
                    _removeRefused.value = _removeRefused.value - connectorId
                    _tests.value = _tests.value - connectorId
                }
                AgentRead.AdminOnly -> _removeRefused.value = _removeRefused.value + (connectorId to AgentRead.AdminOnly)
                is AgentRead.Failed -> {
                    PlatformLogger.w("AdapterConnectorsVM", "[remove] $connectorId: ${r.failure.detail}")
                    _removeRefused.value = _removeRefused.value + (connectorId to r)
                }
            }
            _list.value = connectorsListOf(backend.list())
        }
    }
}
