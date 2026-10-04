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
 * A refused node read is "not up yet", not an answer (CIRISClient#149).
 *
 * CIRISAgent run 37010300145, iOS leg: the client asked `:4243/v1/setup/status`
 * at 13:32:45.100, was refused, stopped its timer, and never asked again. The
 * node bound 0.6 s later, at 13:32:45.688. The app stayed on the splash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupNodeWaitTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val nodeUrl = "http://127.0.0.1:4243"

    /** A node that refuses its first [refusals] reads and answers after that. */
    private class FakeNode(private val refusals: Int, private val throws: Boolean = false) {
        var reads = 0
            private set
        suspend fun read(): Boolean {
            reads++
            if (reads <= refusals) {
                if (throws) throw RuntimeException("NSURLErrorDomain -1004 Could not connect to the server.")
                return false
            }
            return true
        }
    }

    private fun vm() = StartupViewModel(FakePythonRuntime(), FakeCIRISApiClient())

    private fun TestScope.await(vm: StartupViewModel, node: FakeNode, deadlineSeconds: Int = 90) =
        async { vm.awaitNodeBound(nodeUrl, node::read, deadlineSeconds) { currentTime } }

    @Test
    fun a_node_that_refuses_then_binds_is_waited_for_not_given_up_on() = runTest(dispatcher) {
        val vm = vm()
        // The failing run's shape: refused while the node was still binding.
        val node = FakeNode(refusals = 3, throws = true)
        val result = await(vm, node)
        runCurrent()

        // While refused: the splash says it is waiting for the node, and the timer keeps counting.
        assertIs<NodeWait.Waiting>(vm.nodeWait.value, "a refused read must put the splash into a WAITING state")
        assertTrue(vm.keepTimerAlive.value, "the timer must keep counting while the node binds")

        advanceUntilIdle()
        assertTrue(result.await(), "the node answered on read 4; the wait must report it bound")
        assertEquals(4, node.reads, "asked again after each refusal, and not after the answer")
        assertEquals(NodeWait.Idle, vm.nodeWait.value)
        // 250 + 500 + 1000 ms of backoff: the 0.6 s miss is recovered well inside two seconds.
        assertEquals(1_750L, currentTime, "backoff must be 250 ms doubling, not a fixed long sleep")
    }

    @Test
    fun a_node_that_answers_first_time_costs_no_wait_and_no_status_change() = runTest(dispatcher) {
        val vm = vm()
        val before = vm.statusMessage.value
        val node = FakeNode(refusals = 0)
        val result = await(vm, node)
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(1, node.reads)
        assertEquals(0L, currentTime)
        assertEquals(before, vm.statusMessage.value, "an answering node must not flash a waiting message")
        assertEquals(NodeWait.Idle, vm.nodeWait.value)
    }

    @Test
    fun a_node_that_never_answers_ends_in_an_error_with_retry_never_a_silent_splash() = runTest(dispatcher) {
        val vm = vm()
        val node = FakeNode(refusals = Int.MAX_VALUE)
        val result = await(vm, node, deadlineSeconds = 90)

        advanceTimeBy(89_000)
        runCurrent()
        assertIs<NodeWait.Waiting>(vm.nodeWait.value, "still inside the deadline: still waiting")

        advanceUntilIdle()
        assertFalse(result.await(), "past the deadline the wait must report failure")
        assertEquals(90_000L, currentTime, "the last read lands ON the deadline, not a backoff step past it")
        val timedOut = assertIs<NodeWait.TimedOut>(vm.nodeWait.value, "past the deadline: an error state, not Idle")
        assertEquals(nodeUrl, timedOut.nodeUrl)
        assertEquals(90, timedOut.waitedSeconds)
        assertFalse(vm.keepTimerAlive.value, "the timer stops on the error, which carries Retry")
        // ~4 reads in the first 1.75 s, then one every 2 s.
        assertTrue(node.reads in 45..50, "a cold boot costs one cheap read every 2 s at most, got ${node.reads}")

        // Retry clears the error and asks the first-run check to run again.
        val retriesBefore = vm.nodeWaitRetries.value
        vm.retryNodeWait()
        assertEquals(NodeWait.Idle, vm.nodeWait.value)
        assertEquals(retriesBefore + 1, vm.nodeWaitRetries.value)
        assertTrue(vm.keepTimerAlive.value, "Retry resumes the timer")

        // Let the resumed timer stop the way it does in the app (READY, not kept
        // alive), or virtual time runs it forever.
        vm.setPhase(StartupPhase.READY)
        vm.setKeepTimerAlive(false)
    }

    @Test
    fun the_backoff_and_deadline_are_the_documented_numbers() {
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 2000L, 2000L), (0..5).map(NodeBindWait::delayFor))
        assertEquals(2000L, NodeBindWait.delayFor(100))
        assertTrue(NodeBindWait.deadlineSeconds() >= 90, "never shorter than 90 s")
    }
}
