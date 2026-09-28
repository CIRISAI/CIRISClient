package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DeviceAuthRefused
import ai.ciris.mobile.shared.models.ConnectNodeResult
import ai.ciris.mobile.shared.models.NodeAuthPollResult
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
import kotlin.test.assertTrue

/**
 * The Portal "connect to node" grant on the setup wizard (CSD-082): what the
 * session does when the node's answer is not one the flow can go on from, and
 * when the network merely hiccups while the person is still in the browser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortalConnectTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SetupViewModel(FakeCIRISApiClientForBilling()).also { it.updateNodeUrl("portal.ciris.ai") }

    private val grant = ConnectNodeResult(
        verificationUriComplete = "https://portal.ciris.ai/device?c=AB12",
        deviceCode = "dc-1",
        userCode = "AB12",
        portalUrl = "https://portal.ciris.ai",
        expiresIn = 600,
        interval = 5,
    )

    // ── a connect answer without the codes never becomes WAITING ──

    @Test
    fun aConnectAnswerWithoutTheCodesDoesNotEnterWaiting() = runTest {
        val vm = viewModel()
        vm.startNodeConnection(nowMs = 0) { grant.copy(deviceCode = "", userCode = "") }
        val auth = vm.state.value.deviceAuth
        assertEquals(DeviceAuthStatus.ERROR, auth.status, "a blank code cannot be shown or polled")
        assertTrue(auth.error?.contains("device_code") == true, "says what the node did not send: ${auth.error}")
    }

    // ── a transport failure while WAITING is a retry, not the end ──

    @Test
    fun aTransportFailureWhileWaitingKeepsWaitingUntilTheGrantExpires() = runTest {
        val vm = viewModel()
        vm.startNodeConnection(nowMs = 0) { grant }
        assertEquals(DeviceAuthStatus.WAITING, vm.state.value.deviceAuth.status)

        vm.pollNodeAuthStatus(nowMs = 1_000) { _, _ -> throw RuntimeException("Request timeout has expired") }
        assertEquals(
            DeviceAuthStatus.WAITING, vm.state.value.deviceAuth.status,
            "one timed-out poll must not end a session the person may be approving right now",
        )

        vm.pollNodeAuthStatus(nowMs = 2_000) { _, _ -> NodeAuthPollResult(status = "pending") }
        assertEquals(DeviceAuthStatus.WAITING, vm.state.value.deviceAuth.status)

        vm.pollNodeAuthStatus(nowMs = 600_001) { _, _ -> throw RuntimeException("connect timed out") }
        assertEquals(DeviceAuthStatus.ERROR, vm.state.value.deviceAuth.status, "past the advertised expiry it is over")
    }

    @Test
    fun aRefusalWhileWaitingIsTerminal() = runTest {
        val vm = viewModel()
        vm.startNodeConnection(nowMs = 0) { grant }
        vm.pollNodeAuthStatus(nowMs = 1_000) { _, _ -> throw DeviceAuthRefused("Invalid device code") }
        val auth = vm.state.value.deviceAuth
        assertEquals(DeviceAuthStatus.ERROR, auth.status, "the node answered, and the answer ends the session")
        assertEquals("Invalid device code", auth.error)
    }

    @Test
    fun anErrorStatusFromTheNodeIsTerminal() = runTest {
        val vm = viewModel()
        vm.startNodeConnection(nowMs = 0) { grant }
        vm.pollNodeAuthStatus(nowMs = 1_000) { _, _ -> NodeAuthPollResult(status = "error", error = "denied") }
        assertEquals(DeviceAuthStatus.ERROR, vm.state.value.deviceAuth.status)
        assertEquals("denied", vm.state.value.deviceAuth.error)
    }
}
