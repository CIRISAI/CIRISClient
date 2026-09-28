package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.CommunicationAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CSD-082: the optional features offered follow the service mode. Switching
 * CIRIS Proxy ↔ BYOK re-filters the adapter list; what the new list no longer
 * offers must leave the enabled set too, or `buildSetupRequest()` submits an
 * adapter the wizard is no longer showing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupServiceModeAdaptersTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val hostedTools = CommunicationAdapter(
        id = "ciris_hosted_tools", name = "Hosted tools", description = "",
        requires_ciris_services = true, enabled_by_default = true,
    )
    private val discord = CommunicationAdapter(id = "discord", name = "Discord", description = "")

    @Test
    fun switchingToByokDropsTheServiceOnlyAdapterFromTheSelection() = runTest {
        val vm = SetupViewModel(FakeCIRISApiClientForBilling())

        // Proxy mode: the platform filter offers the service-only adapter, and it auto-enables.
        vm.loadAvailableAdapters { listOf(hostedTools, discord) }
        vm.toggleAdapter("discord", true)
        assertTrue("ciris_hosted_tools" in vm.state.value.enabledAdapterIds)

        // BYOK: the filter no longer offers it.
        vm.loadAvailableAdapters { listOf(discord) }
        val enabled = vm.state.value.enabledAdapterIds
        assertFalse("ciris_hosted_tools" in enabled, "an adapter the new list does not offer stays selected: $enabled")
        assertTrue("discord" in enabled, "a choice the new list still offers survives the switch")
        assertTrue("api" in enabled)
        assertFalse(
            "ciris_hosted_tools" in vm.buildSetupRequest().enabled_adapters,
            "the request would carry an adapter the person cannot see",
        )

        // And back: it is offered again, and re-enables by default.
        vm.loadAvailableAdapters { listOf(hostedTools, discord) }
        assertTrue("ciris_hosted_tools" in vm.state.value.enabledAdapterIds)
    }
}
