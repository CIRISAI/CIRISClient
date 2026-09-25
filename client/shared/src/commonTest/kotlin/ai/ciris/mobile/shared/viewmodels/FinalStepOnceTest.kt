package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
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
}
