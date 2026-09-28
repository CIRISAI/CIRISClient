package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.safety.AgeBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ONE PRESS, ONE RUN (CIRISClient#69).
 *
 * The wizard's final step waits up to ~100s (claim PIN, then claim settle)
 * before it posts `setup/complete`. A second press during that wait ran the
 * whole step again: two completes 64ms apart, two ROOT owners with one name,
 * and every login after refused as ambiguous.
 */
class FinalStepOnceTest {

    @Test
    fun aSecondPressWhileTheStepRunsIsRefused() {
        val vm = SetupViewModel(FakeCIRISApiClientForBilling(), hasAgent = true)
        assertTrue(vm.beginFinalStep(), "the first press must start the step")
        assertTrue(vm.state.value.isSubmitting, "Next must be disabled from the first press, not from completeSetup")
        assertFalse(vm.beginFinalStep(), "a second press during the claim wait ran the whole step again")
    }

    @Test
    fun theStepCanRunAgainOnceItHasEnded() {
        // A failed claim must not strand the wizard: after the step ends, the
        // person can press Next again.
        val vm = SetupViewModel(FakeCIRISApiClientForBilling(), hasAgent = true)
        assertTrue(vm.beginFinalStep())
        vm.endFinalStep()
        assertFalse(vm.state.value.isSubmitting)
        assertTrue(vm.beginFinalStep())
    }

    // ── The node client's final step had no guard at all ──────────────────
    //
    // `isFinalStep && !hasAgent` called claimLocalNodeOwnership + nextStep
    // straight from the click. Two presses in one frame both read the same
    // composed step and each started a claim.

    private fun nodeClientAtFinalStep(): SetupViewModel {
        val vm = SetupViewModel(FakeCIRISApiClientForBilling(), hasAgent = false)
        vm.setAgeRange(AgeBand.ADULT)
        vm.setUsername("qaadmin")
        vm.setUserPassword("hunter2hunter2")
        vm.setUserPasswordConfirm("hunter2hunter2")
        vm.setFederationLabel("qaadmin")
        vm.setAccordMetricsConsent(true)
        vm.nextStep()
        assertEquals(SetupStep.JOIN_FEDERATION, vm.state.value.currentStep, "fixture must reach the node's final step")
        return vm
    }

    @Test
    fun theNodeClientFinalStepRunsOncePerPress() {
        val vm = nodeClientAtFinalStep()
        var pinAsks = 0
        assertTrue(vm.finishNodeClientSetup(claimPinProvider = { pinAsks++; null }), "the first press must run")
        assertEquals(SetupStep.COMPLETE, vm.state.value.currentStep)
        // The second press of a double click: the screen still shows the final
        // step, the ViewModel does not.
        assertFalse(vm.finishNodeClientSetup(claimPinProvider = { pinAsks++; null }),
            "a second press after the step advanced started a second claim")
        assertEquals(SetupStep.COMPLETE, vm.state.value.currentStep)
    }

    @Test
    fun theNodeClientFinalStepIsRefusedWhileAFinalStepRuns() {
        val vm = nodeClientAtFinalStep()
        assertTrue(vm.beginFinalStep())
        assertFalse(vm.finishNodeClientSetup(claimPinProvider = { null }),
            "a press while a final step is in flight must be refused")
        assertEquals(SetupStep.JOIN_FEDERATION, vm.state.value.currentStep, "a refused press must not advance")
    }
}
