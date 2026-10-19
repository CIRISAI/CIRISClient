package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DeviceRequestsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.ApproveDeviceResponse
import ai.ciris.mobile.shared.models.federation.DenyDeviceResponse
import ai.ciris.mobile.shared.models.federation.DeviceJoinRequest
import ai.ciris.mobile.shared.models.federation.DeviceJoinRequests
import ai.ciris.mobile.shared.models.federation.OpenDeviceRequestsResponse
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Add a device, on the device the person already has** (CSD-094; CIRISServer
 * v0.5.231 `src/add_device.rs:939-1100`).
 *
 * The window gates NEW requests only: a request already held stays listed and
 * approvable after the window closes, until its own REQUEST_TTL (15 min from
 * arrival) runs out. Approve and deny each go through a confirm first.
 */
class ApproveDeviceViewModelTest {

    /** Every view model a test makes: stopped AND joined before Main is reset (see [stopViewModels]). */
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private class OwnerNode : DeviceRequestsApi {
        val calls = mutableListOf<String>()
        var listing = DeviceJoinRequests()
        var approveAnswer: (String) -> ApproveDeviceResponse = { ApproveDeviceResponse(approved = true, matchCode = "482913") }
        var listAnswer: () -> DeviceJoinRequests = { listing }

        override suspend fun open(seconds: Long?): OpenDeviceRequestsResponse {
            calls += "open:${seconds ?: "default"}"
            listing = listing.copy(windowSecsLeft = seconds ?: 600)
            return OpenDeviceRequestsResponse(open = true, seconds = seconds ?: 600)
        }

        override suspend fun list(): DeviceJoinRequests {
            calls += "list"
            return listAnswer()
        }

        override suspend fun approve(requestId: String): ApproveDeviceResponse {
            calls += "approve:$requestId"
            listing = listing.copy(requests = listing.requests.filterNot { it.requestId == requestId })
            return approveAnswer(requestId)
        }

        override suspend fun deny(requestId: String): DenyDeviceResponse {
            calls += "deny:$requestId"
            listing = listing.copy(requests = listing.requests.filterNot { it.requestId == requestId })
            return DenyDeviceResponse(denied = true)
        }
    }

    private val phone = DeviceJoinRequest(
        requestId = "req-1", label = "S23 Phone", deviceClass = "laptop", matchCode = "482913",
        nodeKeyId = "ciris-node-b", requestedSecsAgo = 120,
    )

    private var now = 1_000_000L

    private suspend fun ApproveDeviceViewModel.until(what: String, pred: (ApproveDeviceUiState) -> Boolean) =
        withContext(Dispatchers.Default) {
            try {
                withTimeout(10_000) { state.first(pred) }
            } catch (e: Exception) {
                throw AssertionError("never reached: $what (${state.value})", e)
            }
        }

    @Test
    fun openingTheWindowAsksTheNodeAndReReadsTheList() = runBlocking {
        val node = OwnerNode()
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }

        vm.open()

        val s = vm.until("open") { it.windowSecsLeft == 600L }
        assertEquals(listOf("open:default", "list"), node.calls)
        assertTrue(s.windowWasOpen)
        assertEquals(600L, s.windowSecsLeftAt(now))
    }

    @Test
    fun theListShowsEachRequestWithItsMatchCode() = runBlocking {
        val node = OwnerNode().apply { listing = DeviceJoinRequests(listOf(phone), windowSecsLeft = 300) }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }

        vm.refreshNow()

        val s = vm.state.value
        assertEquals(listOf(phone), s.requests)
        assertEquals("482913", s.requests.single().matchCode)
        assertEquals(300L, s.windowSecsLeftAt(now))
    }

    @Test
    fun afterTheWindowClosesAHeldRequestIsStillListedAndApprovable() = runBlocking {
        // The node clears window_secs_left once it closes (`sweep`), and keeps
        // the request until REQUEST_TTL: the window gates NEW joins only.
        val node = OwnerNode().apply { listing = DeviceJoinRequests(listOf(phone), windowSecsLeft = 5) }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()
        now += 10_000
        assertNull(vm.state.value.windowSecsLeftAt(now), "the local countdown closes the window")
        node.listing = DeviceJoinRequests(listOf(phone.copy(requestedSecsAgo = 130)), windowSecsLeft = null)
        vm.refreshNow()

        val s = vm.state.value
        assertNull(s.windowSecsLeft)
        assertTrue(s.windowWasOpen, "a closed window offers Open again")
        assertTrue(s.requestExpiresInSecs(s.requests.single(), now) > 0, "still inside its own 15 minutes")

        vm.askApprove(s.requests.single())
        vm.confirm()

        vm.until("approved") { it.outcome != null }
        assertTrue("approve:req-1" in node.calls, "approvable with the window closed")
    }

    @Test
    fun eachRequestCountsDownItsOwnFifteenMinutes() = runBlocking {
        val old = phone.copy(requestId = "req-old", requestedSecsAgo = 890)
        val node = OwnerNode().apply { listing = DeviceJoinRequests(listOf(phone, old), windowSecsLeft = null) }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()
        val s = vm.state.value

        assertEquals(15 * 60L - 120, s.requestExpiresInSecs(phone, now))
        assertEquals(10L, s.requestExpiresInSecs(old, now))
        // Ageing between polls, locally:
        assertEquals(15 * 60L - 120 - 30, s.requestExpiresInSecs(phone, now + 30_000))
        assertEquals(0L, s.requestExpiresInSecs(old, now + 30_000), "expired; never negative")
        assertEquals("14:00", formatCountdown(840))
        assertEquals("0:09", formatCountdown(9))
    }

    @Test
    fun approveGoesThroughTheConfirmAndNothingIsSentBeforeIt() = runBlocking {
        val node = OwnerNode().apply { listing = DeviceJoinRequests(listOf(phone), windowSecsLeft = 300) }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()

        vm.askApprove(phone)
        assertEquals(DeviceRequestConfirm(phone, approve = true), vm.state.value.confirming)
        assertFalse(node.calls.any { it.startsWith("approve") }, "asking is not approving")

        vm.confirm()

        val s = vm.until("approved") { it.outcome != null && it.working == null }
        val outcome = assertIs<DeviceRequestOutcome.Approved>(s.outcome)
        assertEquals("S23 Phone", outcome.label)
        assertEquals("482913", outcome.matchCode)
        assertNull(s.confirming)
        assertTrue(s.requests.isEmpty(), "a decided request leaves the list (the node frees its slot)")
    }

    @Test
    fun denyGoesThroughTheConfirmToo() = runBlocking {
        val node = OwnerNode().apply { listing = DeviceJoinRequests(listOf(phone), windowSecsLeft = 300) }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()

        vm.askDeny(phone)
        assertEquals(DeviceRequestConfirm(phone, approve = false), vm.state.value.confirming)
        vm.confirm()

        val s = vm.until("denied") { it.outcome != null }
        assertIs<DeviceRequestOutcome.Denied>(s.outcome)
        assertTrue("deny:req-1" in node.calls)
        assertFalse(node.calls.any { it.startsWith("approve") })
    }

    @Test
    fun dismissingTheConfirmSendsNothing() = runBlocking {
        val node = OwnerNode().apply { listing = DeviceJoinRequests(listOf(phone), windowSecsLeft = 300) }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()
        vm.askApprove(phone)
        vm.dismissConfirm()
        vm.confirm()
        assertNull(vm.state.value.confirming)
        assertEquals(listOf("list"), node.calls)
    }

    @Test
    fun aRefusalLandsById() = runBlocking {
        val node = OwnerNode().apply {
            listing = DeviceJoinRequests(listOf(phone), windowSecsLeft = 300)
            approveAnswer = { throw NodeRefusal("add_device.request_unknown", "no such pending request", 404) }
        }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()
        vm.askApprove(phone)
        vm.confirm()
        val s = vm.until("refused") { it.refusal != null && it.working == null }
        assertEquals(DeviceRequestRefusal("add_device.request_unknown", "no such pending request", 404), s.refusal)
        assertNull(s.outcome)
    }

    @Test
    fun theRowShowsTheDeviceClassTheListSends() = runBlocking {
        // fix/client-copy-0.5.232 (b3e05e27) sends the requesting HOST's class
        // (`host_device_class()`): phone on mobile. v0.5.231 always sent laptop.
        val fromPhone = phone.copy(deviceClass = "phone")
        val node = OwnerNode().apply { listing = DeviceJoinRequests(listOf(fromPhone), windowSecsLeft = 300) }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()
        val req = vm.state.value.requests.single()
        assertEquals("phone", req.deviceClass, "carried as sent, not assumed")
        val words = mapOf(
            "mobile.device_class_phone" to "Phone",
            "mobile.device_class_laptop" to "Laptop",
            "mobile.device_class_server" to "Server",
        )
        assertEquals("Phone", deviceClassDisplay(req.deviceClass) { words.getValue(it) })
    }

    @Test
    fun deviceClassWordsAndFallbacks() {
        assertEquals("mobile.device_class_phone", deviceClassKey("phone"))
        assertEquals("mobile.device_class_laptop", deviceClassKey(" Laptop "))
        assertEquals("mobile.device_class_server", deviceClassKey("server"))
        assertNull(deviceClassKey("tablet"), "no word for it: the screen shows the token, never a guess")
        assertEquals("tablet", deviceClassDisplay("tablet") { error("no key for tablet") })
        assertNull(deviceClassDisplay("  ") { error("blank asks for nothing") }, "a blank class shows no line")
    }

    @Test
    fun aNodeOlderThan0_5_231SaysSoAndOffersNothing() = runBlocking {
        val node = OwnerNode().apply { listAnswer = { throw NodeRefusal(null, null, 404) } }
        val vm = ApproveDeviceViewModel(node, clock = { now }).also { made += it }
        vm.refreshNow()
        val s = vm.state.value
        assertTrue(s.nodeTooOld)
        assertNull(s.refusal, "a version fact, not a refusal")
    }
}
