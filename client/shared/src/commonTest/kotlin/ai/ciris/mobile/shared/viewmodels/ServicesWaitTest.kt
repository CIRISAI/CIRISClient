package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.platform.ActiveBackend
import ai.ciris.mobile.shared.platform.AGENT_ENDPOINT
import ai.ciris.mobile.shared.platform.NODE_ONLY_ENDPOINT
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the startup wait should do, and what it must never do (CIRISClient#47).
 *
 * Two defects met here. A node has no 22-service agent roster, so after a
 * run-without-AI hand-off the wait counted to a number that could not arrive.
 * And the timeout was a `throw`, launched bare by one of its two callers —
 * `viewModelScope.launch { waitForServices() }` — which on Dispatchers.Main
 * with no handler ENDED THE PROCESS: Android FATAL EXCEPTION, iOS SIGABRT
 * after ~100s with nothing to relaunch it.
 *
 * `waitForServices` is private and drives a live runtime, so these pin the two
 * decisions it makes rather than the loop around them.
 */
class ServicesWaitTest {

    @AfterTest fun tearDown() = ActiveBackend.reset()

    @Test
    fun a_node_backend_has_no_agent_roster_to_wait_for() {
        // The predicate the wait now branches on. A node's readiness is its
        // endpoint answering, not a count of agent services.
        ActiveBackend.resolveFrom("CIRIS_RUN_WITHOUT_AI=true")
        assertEquals(NODE_ONLY_ENDPOINT, ActiveBackend.endpoint)
        assertEquals(4243, ActiveBackend.endpoint.port)
    }

    @Test
    fun an_agent_backend_still_waits_for_its_services() {
        ActiveBackend.resolveFrom("CIRIS_CONFIGURED=true")
        assertEquals(AGENT_ENDPOINT, ActiveBackend.endpoint)
        assertEquals(8080, ActiveBackend.endpoint.port)
    }

    @Test
    fun the_two_backends_are_distinguishable_at_all() {
        // If these ever collapse, the branch above silently stops protecting
        // anything and the roster wait comes back on the node.
        assertTrue(AGENT_ENDPOINT != NODE_ONLY_ENDPOINT)
    }

    @Test
    fun a_startup_failure_is_reported_as_an_error_state() {
        // The shape the timeout now takes. onErrorDetected is how every other
        // startup failure surfaces; a throw from a bare launch is not a
        // failure mode a UI can render, it is a dead process.
        val vm = StartupViewModel(FakePythonRuntime(), FakeCIRISApiClient())
        assertFalse(vm.hasError.value)
        vm.onErrorDetected("Timeout waiting for services (10/22 online)")
        assertTrue(vm.hasError.value, "a timeout must leave the app in an error state, not kill it")
        assertEquals(StartupPhase.ERROR, vm.phase.value)
        assertTrue((vm.errorMessage.value ?: "").contains("10/22"))
    }

    @Test
    fun the_error_state_is_latched_so_the_first_cause_survives() {
        val vm = StartupViewModel(FakePythonRuntime(), FakeCIRISApiClient())
        vm.onErrorDetected("first")
        vm.onErrorDetected("second")
        assertEquals("first", vm.errorMessage.value, "a later error must not overwrite the original cause")
    }
}
