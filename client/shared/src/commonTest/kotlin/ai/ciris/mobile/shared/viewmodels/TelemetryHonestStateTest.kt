package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.TelemetryApi
import ai.ciris.mobile.shared.models.ExportDestination
import ai.ciris.mobile.shared.models.TelemetryResponse
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The client's own wording for a served 404, which `ReadFailure.of` reads as "not on this node". */
private const val SERVED_404 = "API error: HTTP 404 Not Found"

/** A node whose two telemetry reads answer what the test says — each may wait at a gate first. */
private class FakeTelemetry(
    var answerOverview: suspend () -> TelemetryResponse = { throw RuntimeException(SERVED_404) },
    var answerDestinations: suspend () -> List<ExportDestination> = { throw RuntimeException(SERVED_404) },
    var overviewGate: CompletableDeferred<Unit>? = null,
    var destinationsGate: CompletableDeferred<Unit>? = null,
) : TelemetryApi {
    override suspend fun overview(): TelemetryResponse {
        overviewGate?.await()
        return answerOverview()
    }
    override suspend fun exportDestinations(): List<ExportDestination> {
        destinationsGate?.await()
        return answerDestinations()
    }
}

/**
 * **A failed telemetry read draws no metrics** (CSD-030), asserted on the
 * state the view model PUBLISHES after the reads answer — not awaited against
 * a stub server on a clock. `HonestStatesTest.telemetry_a_failed_read_draws_no_metrics`
 * did the latter and timed out on CI (#98, #122): the overview and the
 * destinations reads complete on two threads and each wrote the shared value
 * by read-copy-write, so one answer could publish over the other's and the
 * card never settled. The mapping of a served 404 through the real client is
 * still held next door, in `HonestStatesTest`, sequentially.
 */
class TelemetryHonestStateTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(fake: FakeTelemetry) = TelemetryViewModel(CIRISApiClient(baseUrl = "http://127.0.0.1:9"), reads = fake)

    @Test
    fun a_failed_read_draws_no_metrics() {
        val vm = vm(FakeTelemetry())
        vm.refresh()
        vm.loadExportDestinations()
        val data = vm.telemetryData.value
        assertFalse(data.hasReading, "no reading, so the metric cards do not draw")
        assertNull(data.cognitiveState, "never a default WORK")
        assertIs<ReadFailure.NotOnThisNode>(data.readFailure)
        assertIs<ReadFailure.NotOnThisNode>(data.destinationsFailure, "no destinations read is not 'none configured'")
        assertFalse(vm.isLoading.value, "the read has settled")
    }

    @Test
    fun the_two_reads_keep_each_others_answer_whichever_lands_second() {
        // Destinations answers first while the overview is still out, then the
        // overview fails: the overview's failure must not publish over the
        // destinations' — and the other way round.
        val overviewGate = CompletableDeferred<Unit>()
        val slowOverview = vm(FakeTelemetry(overviewGate = overviewGate))
        slowOverview.refresh()
        slowOverview.loadExportDestinations()
        assertIs<ReadFailure.NotOnThisNode>(slowOverview.telemetryData.value.destinationsFailure)
        assertNull(slowOverview.telemetryData.value.readFailure, "the overview has not answered yet")
        overviewGate.complete(Unit)
        assertIs<ReadFailure.NotOnThisNode>(slowOverview.telemetryData.value.readFailure)
        assertIs<ReadFailure.NotOnThisNode>(slowOverview.telemetryData.value.destinationsFailure, "lost when the overview published")

        val destinationsGate = CompletableDeferred<Unit>()
        val slowDestinations = vm(FakeTelemetry(destinationsGate = destinationsGate))
        slowDestinations.refresh()
        slowDestinations.loadExportDestinations()
        assertIs<ReadFailure.NotOnThisNode>(slowDestinations.telemetryData.value.readFailure)
        destinationsGate.complete(Unit)
        assertIs<ReadFailure.NotOnThisNode>(slowDestinations.telemetryData.value.readFailure, "lost when the destinations published")
        assertIs<ReadFailure.NotOnThisNode>(slowDestinations.telemetryData.value.destinationsFailure)
    }

    @Test
    fun a_failed_read_after_a_success_drops_the_old_reading() {
        val fake = FakeTelemetry(answerOverview = {
            TelemetryResponse(
                data = ai.ciris.mobile.shared.models.TelemetryData(cognitive_state = "work", services_online = 3, services_total = 3),
            )
        })
        val vm = vm(fake)
        vm.refresh()
        assertTrue(vm.telemetryData.value.hasReading)
        assertEquals("WORK", vm.telemetryData.value.cognitiveState)

        fake.answerOverview = { throw RuntimeException("boom") }
        vm.refresh()
        val data = vm.telemetryData.value
        assertFalse(data.hasReading, "the last success must not stand as if current")
        assertNull(data.cognitiveState)
        assertIs<ReadFailure.Failed>(data.readFailure)
    }
}
