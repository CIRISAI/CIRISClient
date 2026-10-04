package ai.ciris.mobile.shared.viewmodels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A node that ANSWERS but whose gate probe fails must not leave Setup holding
 * forever (CIRISClient#149, second form).
 *
 * Setup will not compose the wizard until the gate probe (`/v1/system/health`
 * on the node) has told it whether there is an agent. That probe used to be
 * tried once inline: a throw left the gate unset with a log line and nothing
 * else, and an undetermined answer was retried for 60 s in the background and
 * then dropped silently. Either way Setup composed StartupScreen indefinitely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupBrainProbeWaitTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val target = "http://127.0.0.1:4243/v1/system/health"

    /** A gate probe that fails its first [failures] attempts, naming why, then resolves. */
    private class FakeGateProbe(private val failures: Int) {
        var attempts = 0
            private set
        suspend fun probe() {
            attempts++
            if (attempts <= failures) throw IllegalStateException("HTTP 500 from /v1/system/health")
        }
    }

    private fun vm() = StartupViewModel(FakePythonRuntime(), FakeCIRISApiClient())

    private fun TestScope.await(vm: StartupViewModel, gate: FakeGateProbe) =
        async { vm.awaitBrainProbe(target, gate::probe, deadlineSeconds = 90) { currentTime } }

    @Test
    fun a_probe_that_fails_then_resolves_is_retried_on_the_backoff() = runTest(dispatcher) {
        val vm = vm()
        val gate = FakeGateProbe(failures = 2)
        val result = await(vm, gate)
        runCurrent()
        assertIs<NodeWait.Waiting>(vm.brainWait.value, "a failed probe must put the splash into a WAITING state")

        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(3, gate.attempts)
        assertEquals(750L, currentTime, "250 + 500 ms: the same backoff as the node bind")
        assertEquals(NodeWait.Idle, vm.brainWait.value)
    }

    @Test
    fun a_probe_that_never_succeeds_ends_in_an_error_naming_what_failed_and_retry_works() = runTest(dispatcher) {
        val vm = vm()
        val gate = FakeGateProbe(failures = Int.MAX_VALUE)
        val result = await(vm, gate)

        advanceTimeBy(89_000)
        runCurrent()
        assertIs<NodeWait.Waiting>(vm.brainWait.value, "inside the deadline: still waiting, and saying so")

        advanceUntilIdle()
        assertFalse(result.await())
        assertEquals(90_000L, currentTime, "the error lands ON the deadline")
        val timedOut = assertIs<NodeWait.TimedOut>(vm.brainWait.value, "past the deadline: an error, never a silent hold")
        assertEquals(target, timedOut.nodeUrl)
        assertEquals(90, timedOut.waitedSeconds)
        assertEquals("HTTP 500 from /v1/system/health", timedOut.detail, "the error names what failed")
        assertEquals(NodeWait.Idle, vm.nodeWait.value, "the node wait is a different question and stays untouched")

        val before = vm.brainProbeRetries.value
        vm.retryBrainProbe()
        assertEquals(NodeWait.Idle, vm.brainWait.value, "Retry clears the error")
        assertEquals(before + 1, vm.brainProbeRetries.value, "and relaunches the probe")
    }
}
