package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.AgentRead
import ai.ciris.mobile.shared.models.ConnectorDto
import ai.ciris.mobile.shared.models.ConnectorListDto
import ai.ciris.mobile.shared.models.ConnectorTestDto
import ai.ciris.mobile.shared.models.ConnectorTestOutcome
import ai.ciris.mobile.shared.models.ConnectorsList
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AdapterConnectorsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeBackend(
        var connectors: MutableList<ConnectorDto> = mutableListOf(ConnectorDto("c1", "sql", "Orders")),
        var testAnswer: AgentRead<ConnectorTestDto> = AgentRead.Ok(ConnectorTestDto("c1", true, "SQL connection test successful", 3.0, "t")),
        var removeAnswer: AgentRead<Any>? = null,
    ) : ConnectorsBackend {
        val removed = mutableListOf<String>()
        override suspend fun list(): AgentRead<ConnectorListDto> = AgentRead.Ok(ConnectorListDto(connectors.toList(), connectors.size))
        override suspend fun test(connectorId: String) = testAnswer
        override suspend fun remove(connectorId: String): AgentRead<Any> {
            val answer = removeAnswer ?: AgentRead.Ok(Unit)
            if (answer is AgentRead.Ok) { removed += connectorId; connectors.removeAll { it.connectorId == connectorId } }
            return answer
        }
    }

    /** The agent's "success … (tool bus unavailable, skipped)" is kept as a test that did not run. */
    @Test
    fun aSkippedTestIsRememberedAsNotRun() = runTest {
        val backend = FakeBackend(
            testAnswer = AgentRead.Ok(ConnectorTestDto("c1", true, "SQL connection test successful (tool bus unavailable, skipped)", 0.1, "t")),
        )
        val vm = AdapterConnectorsViewModel(backend)
        vm.load(); advanceUntilIdle()
        vm.test("c1"); advanceUntilIdle()
        val result = vm.tests.value["c1"]
        assertIs<AgentRead.Ok<ConnectorTestOutcome>>(result)
        assertIs<ConnectorTestOutcome.NotRun>(result.value)
    }

    @Test
    fun removingReReadsTheListFromTheAgent() = runTest {
        val backend = FakeBackend()
        val vm = AdapterConnectorsViewModel(backend)
        vm.load(); advanceUntilIdle()
        assertEquals(1, (vm.list.value as ConnectorsList.Ready).connectors.size)
        vm.remove("c1"); advanceUntilIdle()
        assertEquals(listOf("c1"), backend.removed)
        assertTrue((vm.list.value as ConnectorsList.Ready).connectors.isEmpty())
        assertTrue(vm.removeRefused.value.isEmpty())
    }

    /** A refused removal stays on screen as refused; the row does not vanish as if it worked. */
    @Test
    fun aRefusedRemovalIsSaidAndTheRowStays() = runTest {
        val backend = FakeBackend(removeAnswer = AgentRead.Failed(ReadFailure.Failed("HTTP 500: boom")))
        val vm = AdapterConnectorsViewModel(backend)
        vm.load(); advanceUntilIdle()
        vm.remove("c1"); advanceUntilIdle()
        assertIs<AgentRead.Failed>(vm.removeRefused.value["c1"])
        assertEquals(1, (vm.list.value as ConnectorsList.Ready).connectors.size)

        backend.removeAnswer = AgentRead.AdminOnly
        vm.remove("c1"); advanceUntilIdle()
        assertEquals(AgentRead.AdminOnly, vm.removeRefused.value["c1"])
    }
}
