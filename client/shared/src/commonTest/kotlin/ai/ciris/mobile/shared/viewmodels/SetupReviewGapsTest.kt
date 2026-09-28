package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.safety.AgeBand
import ai.ciris.mobile.shared.models.CommunicationAdapter
import ai.ciris.mobile.shared.models.optionalFeatureAdapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The setup-group review (CSD-082/083): the gaps between the wizard, the agent's
 * setup routes and the CC that the review closed. Each test names the state the
 * screen would otherwise have made indistinguishable from another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupReviewGapsTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SetupViewModel(FakeCIRISApiClientForBilling())

    // ── CSD-082 §2 `empty`: a disclosure without a trace grant must not strand the step

    @Test
    fun aDisclosureWithNoTraceGrantDoesNotStrandScreenTwo() {
        val vm = viewModel()
        vm.noteTraceQuestionAbsent()
        val s = vm.state.value.copy(currentStep = SetupStep.JOIN_FEDERATION)
        assertFalse(s.traceQuestionOffered, "the step must know there was no question")
        assertTrue(s.canProceedFromCurrentStep(), "nothing to answer, so Next is live")
        assertFalse(s.accordMetricsConsent, "no grant was offered, so nothing is sent")
    }

    @Test
    fun anOfferedTraceQuestionStillHasToBeAnswered() {
        val s = SetupFormState(currentStep = SetupStep.JOIN_FEDERATION)
        assertFalse(s.canProceedFromCurrentStep())
    }

    // ── 0.5.218: announce is per device, and not offered to an under-18 account

    @Test
    fun anAdultAnnouncesThisDeviceWhenTheSwitchIsOn() {
        val vm = viewModel()
        vm.setAgeRange(AgeBand.ADULT)
        assertTrue(vm.state.value.announcesThisDevice())
    }

    @Test
    fun aMinorNeverAnnouncesWhateverTheSwitchHeld() {
        val vm = viewModel()
        vm.setAnnounceOwnership(true)
        vm.setAgeRange(AgeBand.MINOR)
        assertFalse(vm.state.value.announcesThisDevice())
    }

    @Test
    fun decliningTheAgeQuestionIsTreatedLikeAMinorForAnnounce() {
        val vm = viewModel()
        vm.setAnnounceOwnership(true)
        vm.declineAgeRange()
        assertFalse(vm.state.value.announcesThisDevice())
    }

    // ── the optional features: a failed read is not an empty list

    @Test
    fun aFailedTemplateReadIsNotAnEmptyTemplateList() = runTest {
        val vm = viewModel()
        vm.loadAvailableTemplates { throw RuntimeException("503") }
        assertTrue(vm.state.value.templatesError)
        vm.loadAvailableTemplates { emptyList() }
        assertFalse(vm.state.value.templatesError, "a later empty answer clears the error")
    }

    @Test
    fun aFailedAdapterReadIsNotAnEmptyAdapterList() = runTest {
        val vm = viewModel()
        vm.loadAvailableAdapters { throw RuntimeException("503") }
        assertTrue(vm.state.value.adaptersError)
        assertEquals(setOf("api"), vm.state.value.enabledAdapterIds, "a failed read enables nothing")
    }

    @Test
    fun aFailedDisclosureReadIsNotAnUnrunOne() = runTest {
        val vm = viewModel()
        assertFalse(vm.state.value.toolDisclosureError, "not run yet is not failed")
        vm.loadToolDisclosure { throw RuntimeException("503") }
        assertTrue(vm.state.value.toolDisclosureError)
    }

    @Test
    fun theOptionalFeatureListLeavesOutTheAlwaysOnApiAndWhatThisDeviceCannotRun() {
        val adapters = listOf(
            CommunicationAdapter(id = "api", name = "Web API", description = "", enabled_by_default = true),
            CommunicationAdapter(id = "discord", name = "Discord", description = ""),
            CommunicationAdapter(id = "gone", name = "Gone", description = "", platform_available = false),
        )
        assertEquals(listOf("discord"), optionalFeatureAdapters(adapters).map { it.id })
    }
}
