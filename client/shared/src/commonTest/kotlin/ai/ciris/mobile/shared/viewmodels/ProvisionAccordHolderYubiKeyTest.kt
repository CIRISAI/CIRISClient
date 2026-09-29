package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.models.federation.YubiKeyProbe
import ai.ciris.mobile.shared.models.federation.YubiKeyStatus
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The YubiKey banner's three answers (Codex, PR #126): a probe that failed was
 * stored as null and drawn as "CHECKING YUBIKEY…" forever.
 */
class ProvisionAccordHolderYubiKeyTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm(read: suspend () -> YubiKeyStatus) =
        ProvisionAccordHolderViewModel(CIRISApiClient(baseUrl = "http://127.0.0.1:9"), readYubiKeyStatus = read)

    @Test
    fun aFailedYubiKeyProbeIsAFailureWithItsReasonNotCheckingForever() {
        val vm = vm { throw RuntimeException("HTTP 500: pcscd not running") }
        assertEquals(YubiKeyProbe.Loading, vm.yubiKeyStatus.value, "asking, before the first probe")
        vm.refreshYubiKeyStatus()
        val failed = assertIs<YubiKeyProbe.Failed>(vm.yubiKeyStatus.value)
        val f = assertIs<ReadFailure.Failed>(failed.failure)
        assertEquals("HTTP 500: pcscd not running", f.detail)
    }

    @Test
    fun aNodeWithoutTheProbeRouteSaysSo() {
        val vm = vm { throw RouteNotOnThisHost("/v1/accord/yubikey-status") }
        vm.refreshYubiKeyStatus()
        val failed = assertIs<YubiKeyProbe.Failed>(vm.yubiKeyStatus.value)
        assertIs<ReadFailure.NotOnThisNode>(failed.failure)
    }

    @Test
    fun anAnsweredProbeIsTheNodesAnswer() {
        val vm = vm { YubiKeyStatus(detected = false, hint = "no token") }
        vm.refreshYubiKeyStatus()
        val loaded = assertIs<YubiKeyProbe.Loaded>(vm.yubiKeyStatus.value)
        assertEquals(false, loaded.status.detected)
    }
}
