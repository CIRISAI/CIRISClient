package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.SetupMode
import ai.ciris.mobile.shared.models.safety.AgeBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wizard's answer has to REACH the request (CIRISAgent#1151).
 *
 * A gate run drove "without an AI assistant" on screen 1, the wizard skipped the
 * LLM screen and reported COMPLETE, and the agent received
 * `run_without_ai: false` — so it took the accidental "no usable provider"
 * branch instead of the deliberate one, kept `:8080`, and the user got a brain
 * with no LLM rather than a node.
 *
 * Everything between the click and the payload is client-side, so this walks
 * exactly that path: choose, advance, build the request. There is no UI here on
 * purpose — if these pass, the model is sound and the loss is in delivery
 * (the click, or the screen); if they fail, it is here.
 */
class RunWithoutAiDeliveryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    /** An agent build: the only configuration where the question is asked. */
    private fun vm() = SetupViewModel(FakeCIRISApiClientForBilling(), hasAgent = true)

    /** Everything screen 1 blocks on, so nextStep() is allowed to advance. */
    private fun SetupViewModel.satisfyScreenOne() {
        setAgeRange(AgeBand.ADULT)
        setUsername("qaadmin")
        setUserPassword("hunter2hunter2")
        setUserPasswordConfirm("hunter2hunter2")
        setFederationLabel("qaadmin")
    }

    @Test
    fun choosing_without_ai_survives_to_the_request() {
        val vm = vm()
        vm.satisfyScreenOne()
        vm.setRunWithoutAi(true)
        assertTrue(vm.state.value.runWithoutAi, "the choice did not land in state")

        // YOU -> JOIN_FEDERATION -> COMPLETE, skipping the LLM screen.
        vm.nextStep()
        vm.nextStep()

        assertEquals(SetupStep.COMPLETE, vm.state.value.currentStep)
        assertTrue(
            vm.state.value.runWithoutAi,
            "the choice was lost between screen 1 and COMPLETE",
        )
        assertTrue(
            vm.buildSetupRequest().run_without_ai,
            "the request carries run_without_ai=false after the user chose to run without AI — " +
                "the agent then takes its ACCIDENTAL no-provider branch, keeps :8080, and the " +
                "user gets a brain with no LLM instead of a node (CIRISAgent#1151)",
        )
    }

    @Test
    fun the_llm_screen_is_skipped_because_of_the_choice_not_by_accident() {
        // hasAgent is true here, so a skipped AI step can only mean the answer
        // was recorded. If this ever passes while the one above fails, the skip
        // is happening for some other reason and the gate's "no AI screen" is
        // not evidence the choice was made.
        val vm = vm()
        vm.satisfyScreenOne()
        vm.setRunWithoutAi(true)
        assertFalse(hasAiStep(hasAgent = true, runWithoutAi = vm.state.value.runWithoutAi))
    }

    @Test
    fun wanting_an_ai_still_reaches_the_llm_screen_and_sends_false() {
        val vm = vm()
        vm.satisfyScreenOne()
        vm.setRunWithoutAi(false)
        vm.nextStep()
        vm.nextStep()
        assertEquals(SetupStep.AI, vm.state.value.currentStep)
        assertFalse(vm.buildSetupRequest().run_without_ai)
    }

    @Test
    fun picking_a_provider_still_clears_the_choice() {
        // The one legitimate reset: the two answers are mutually exclusive, so
        // choosing a provider on the LLM screen un-chooses "without AI". This
        // has to keep working, and it is also the reset most likely to fire
        // somewhere it should not.
        val vm = vm()
        vm.setRunWithoutAi(true)
        vm.setLlmProvider("OpenAI")
        assertFalse(vm.state.value.runWithoutAi)
    }

    @Test
    fun selecting_on_device_inference_also_means_an_ai_was_chosen() {
        // selectLocalOnDeviceProvider() does NOT clear runWithoutAi today, so a
        // user who said "without AI" and then somehow reached the provider
        // dropdown would send run_without_ai=true alongside a configured local
        // provider — two answers to one question.
        val vm = vm()
        vm.setRunWithoutAi(true)
        vm.selectLocalOnDeviceProvider()
        assertEquals(SetupMode.BYOK, vm.state.value.setupMode)
        assertFalse(
            vm.state.value.runWithoutAi,
            "choosing on-device inference is choosing an AI, so it must clear the " +
                "without-AI answer the way setLlmProvider does",
        )
    }
}
