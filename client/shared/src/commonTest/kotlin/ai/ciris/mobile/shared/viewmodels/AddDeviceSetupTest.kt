package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.AddDeviceSetupApi
import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.AddDeviceProgress
import ai.ciris.mobile.shared.models.federation.ClaimedSessionGrant
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Add a device, on the NEW device** (CSD-093; CIRISServer v0.5.231
 * `FSD/ADD_DEVICE.md` §1, §2.1, `src/add_device.rs`).
 *
 * The wizard against a scripted node: the first question, the request and
 * each refusal by id, polling to an answer, the end of first run (apply, then
 * collect the session, then the agent's setup-complete) and the restart-resume.
 * The wire half is `AddDeviceWireTest` (desktopTest, a real socket).
 */
class AddDeviceSetupTest {

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    /** Every wizard a test made: its poll is stopped before Main is reset, or it leaks into the next test. */
    private val made = mutableListOf<SetupViewModel>()
    private fun track(vm: SetupViewModel) = vm.also { made += it }

    @AfterTest fun tearDown() {
        made.forEach { it.resetState() }
        Dispatchers.resetMain()
    }

    /** A node that answers from a script and records every call, in order. */
    private class ScriptedNode : AddDeviceSetupApi {
        val calls = mutableListOf<String>()
        var startAnswer: () -> AddDeviceProgress = { progress("requested", code = "482913") }
        val statusAnswers = ArrayDeque<() -> AddDeviceProgress>()
        var statusFallback: () -> AddDeviceProgress = { progress("requested", code = "482913") }
        var completeAnswer: () -> AddDeviceProgress = { progress("applied", owner = "eric-moore-v1") }
        var sessionAnswer: (String) -> ClaimedSessionGrant = { grant() }
        var lastStart: Pair<String, String?>? = null
        var lastPin: String? = null
        var inFlight = 0
        var maxInFlight = 0

        override suspend fun start(contact: String, label: String?): AddDeviceProgress {
            calls += "start"
            lastStart = contact to label
            return startAnswer()
        }

        override suspend fun status(): AddDeviceProgress {
            calls += "status"
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                kotlinx.coroutines.delay(2)
                return (statusAnswers.removeFirstOrNull() ?: statusFallback)()
            } finally {
                inFlight--
            }
        }

        override suspend fun complete(): AddDeviceProgress {
            calls += "complete"
            return completeAnswer()
        }

        override suspend fun collectSession(claimPin: String): ClaimedSessionGrant {
            calls += "claimed-session"
            lastPin = claimPin
            return sessionAnswer(claimPin)
        }
    }

    private companion object {
        fun progress(state: String, code: String? = null, owner: String? = null, detail: String? = null) =
            AddDeviceProgress(state = state, matchCode = code, ownerKeyId = owner, detail = detail)

        fun grant() = ClaimedSessionGrant(
            accessToken = "tok-owner", tokenType = "Bearer", expiresIn = 86_400, role = "SYSTEM_ADMIN", userId = "wa-root",
        )

        fun refusal(status: Int, id: String?) = NodeRefusal(id, id?.let { "english for $it" }, status)
    }

    private fun vm(node: ScriptedNode, hasAgent: Boolean = false) =
        track(SetupViewModel(FakeCIRISApiClientForBilling(), hasAgent = hasAgent, addDeviceApiOverride = node, addDevicePollMs = 5))

    private suspend fun SetupViewModel.until(what: String, pred: (SetupFormState) -> Boolean): SetupFormState =
        withContext(Dispatchers.Default) {
            try {
                withTimeout(10_000) { state.first(pred) }
            } catch (e: Exception) {
                throw AssertionError("never reached: $what (state: ${state.value.addDevice} ${state.value.ownershipClaim})", e)
            }
        }

    private fun SetupViewModel.asked(contact: String = "  CIRIS-V3-EXAMPLE-LONG-FORM  ", name: String = "S23 Phone") {
        chooseIdentityPath(IdentityPath.EXISTING)
        setDeviceName(name)
        setAddDeviceContact(contact)
        sendAddDeviceRequest()
    }

    // ── The first question ───────────────────────────────────────────────────

    @Test
    fun theFirstStepIsAChoiceAndNeitherAnswerIsPreselected() {
        val vm = vm(ScriptedNode())
        val fresh = vm.state.value
        assertNull(fresh.identityPath, "no default: a default would mint a second identity for someone who has one")
        assertFalse(fresh.canProceedFromCurrentStep())
        assertNotNull(fresh.getStepValidationError())

        vm.chooseIdentityPath(IdentityPath.EXISTING)
        assertEquals(IdentityPath.EXISTING, vm.state.value.identityPath)
        assertFalse(vm.state.value.canProceedFromCurrentStep(), "the add-device path needs its request out first")

        vm.chooseIdentityPath(IdentityPath.NEW)
        assertEquals(IdentityPath.NEW, vm.state.value.identityPath)
    }

    @Test
    fun theAddDevicePathAsksForNoFedIdNameAndNoAge() = runBlocking {
        val node = ScriptedNode()
        val vm = vm(node)
        vm.asked()
        val s = vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }
        assertEquals("", s.federationIdentity.label)
        assertNull(s.ageRange.selectedBandToken)
        assertTrue(s.canProceedFromCurrentStep(), "a request in flight is all the YOU step needs on this path")
        assertNull(s.getStepValidationError())
    }

    // ── The request ──────────────────────────────────────────────────────────

    @Test
    fun theRequestCarriesTheContactAndTheDeviceNameAndShowsTheMatchCode() = runBlocking {
        val node = ScriptedNode()
        val vm = vm(node)
        vm.asked()
        val s = vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }
        assertEquals("CIRIS-V3-EXAMPLE-LONG-FORM" to "S23 Phone", node.lastStart, "contact trimmed; the name is the label")
        assertEquals("482913", s.addDevice.matchCode)
    }

    @Test
    fun eachRefusalOfTheRequestLandsByIdAndOffersTryAgain() = runBlocking {
        val cases = listOf(
            409 to "add_device.not_accepting",
            404 to "add_device.unknown_identity",
            404 to "add_device.no_device",
            502 to "add_device.unreachable",
            409 to "add_device.already_owned",
            503 to "add_device.no_transport",
        )
        for ((status, id) in cases) {
            val node = ScriptedNode().apply { startAnswer = { throw refusal(status, id) } }
            val vm = vm(node)
            vm.asked()
            val s = vm.until("refused $id") { it.addDevice.phase == AddDevicePhase.REFUSED }
            assertEquals(id, s.addDevice.refusalReasonId)
            assertEquals(status, s.addDevice.refusalStatus)
            assertEquals("english for $id", s.addDevice.detail, "the node's English rides beside the id")
            assertFalse(s.canProceedFromCurrentStep(), "$id: nothing is in flight, so Next stays shut")
        }
    }

    @Test
    fun notAcceptingThenRetryAfterTheOtherDeviceOpensSucceeds() = runBlocking {
        var first = true
        val node = ScriptedNode().apply {
            startAnswer = {
                if (first) { first = false; throw refusal(409, "add_device.not_accepting") }
                progress("requested", code = "111222")
            }
        }
        val vm = vm(node)
        vm.asked()
        vm.until("refused") { it.addDevice.phase == AddDevicePhase.REFUSED }
        vm.sendAddDeviceRequest()
        val s = vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }
        assertEquals("111222", s.addDevice.matchCode)
        assertNull(s.addDevice.refusalReasonId)
    }

    // ── Polling to an answer ─────────────────────────────────────────────────

    @Test
    fun pollingReachesApprovedAndShowsTheIdentityBeingJoined() = runBlocking {
        val node = ScriptedNode()
        node.statusAnswers += { progress("requested", code = "482913") }
        node.statusAnswers += { progress("requested", code = "482913") }
        node.statusAnswers += { progress("approved", code = "482913", owner = "eric-moore-v1") }
        val vm = vm(node)
        vm.asked()
        val s = vm.until("approved") { it.addDevice.phase == AddDevicePhase.APPROVED }
        assertEquals("eric-moore-v1", s.addDevice.ownerKeyId)
        assertEquals(1, node.maxInFlight, "polls are awaited and never overlap")
        val polls = node.calls.count { it == "status" }
        kotlinx.coroutines.delay(60)
        assertEquals(polls, node.calls.count { it == "status" }, "polling stops once the request is answered")
    }

    @Test
    fun deniedExpiredAndFailedEachEndTheRequestAndOfferANewOne() = runBlocking {
        for ((state, phase) in listOf("denied" to AddDevicePhase.DENIED, "expired" to AddDevicePhase.EXPIRED, "failed" to AddDevicePhase.FAILED)) {
            val node = ScriptedNode()
            node.statusAnswers += { progress(state, detail = if (state == "failed") "the owner-binding does not verify" else null) }
            val vm = vm(node)
            vm.asked()
            val s = vm.until(state) { it.addDevice.phase == phase }
            assertTrue(s.addDevice.phase.needsNewRequest, "$state needs a new request")
            assertFalse(s.canProceedFromCurrentStep(), "$state: nothing in flight")
            if (state == "failed") assertEquals("the owner-binding does not verify", s.addDevice.detail)
        }
    }

    @Test
    fun expiredThenSendANewRequestStartsAgainWithFreshKeysOnTheNode() = runBlocking {
        val node = ScriptedNode()
        node.statusAnswers += { progress("expired") }
        val vm = vm(node)
        vm.asked()
        vm.until("expired") { it.addDevice.phase == AddDevicePhase.EXPIRED }
        node.startAnswer = { progress("requested", code = "777888") }
        node.statusFallback = { progress("requested", code = "777888") }

        vm.sendAddDeviceRequest()

        val s = vm.until("requested again") { it.addDevice.phase == AddDevicePhase.REQUESTED && it.addDevice.matchCode == "777888" }
        assertEquals(2, node.calls.count { it == "start" }, "a NEW request: the node mints fresh device keys for each")
        assertTrue(s.canProceedFromCurrentStep())
    }

    @Test
    fun theOtherDeviceNoLongerHoldingTheRequestIsExpiredWithItsOwnSentence() = runBlocking {
        // fix/client-copy-0.5.232 (b3e05e27) src/add_device.rs:649-655: the
        // approving device answered Unknown (its 15 minutes, or an approval not
        // collected within 15), and B says `expired` with this detail.
        val gone = "your other device no longer holds this request — send a new one"
        val node = ScriptedNode()
        node.statusAnswers += { progress("expired", detail = gone) }
        val vm = vm(node)
        vm.asked()
        val s = vm.until("expired") { it.addDevice.phase == AddDevicePhase.EXPIRED }
        assertEquals(gone, s.addDevice.detail, "the detail picks the 'no longer holds it' sentence on screen")
        assertTrue(s.addDevice.phase.needsNewRequest)

        node.startAnswer = { progress("requested", code = "135790") }
        node.statusFallback = { progress("requested", code = "135790") }
        vm.sendAddDeviceRequest()
        val again = vm.until("requested again") { it.addDevice.phase == AddDevicePhase.REQUESTED && it.addDevice.matchCode == "135790" }
        assertNull(again.addDevice.detail, "a new request clears the old answer")
    }

    @Test
    fun anOlderNodesFailedUnknownReadsAsExpired() = runBlocking {
        // v0.5.231 said the same thing as `failed` with detail "Unknown" (:639-645).
        val node = ScriptedNode()
        node.statusAnswers += { progress("failed", detail = "Unknown") }
        val vm = vm(node)
        vm.asked()
        val s = vm.until("expired") { it.addDevice.phase == AddDevicePhase.EXPIRED }
        assertEquals("Unknown", s.addDevice.detail)
    }

    @Test
    fun aRealFailureStaysAFailure() = runBlocking {
        val node = ScriptedNode()
        node.statusAnswers += { progress("failed", detail = "the owner-binding does not verify") }
        val vm = vm(node)
        vm.asked()
        vm.until("failed") { it.addDevice.phase == AddDevicePhase.FAILED }
        Unit
    }

    @Test
    fun anIdleAnswerForASentRequestIsALostRequestNotAFreshStart() = runBlocking {
        val node = ScriptedNode()
        node.statusAnswers += { progress("idle") }
        val vm = vm(node)
        vm.asked()
        val s = vm.until("interrupted") { it.addDevice.phase == AddDevicePhase.INTERRUPTED }
        assertTrue(s.addDevice.phase.needsNewRequest)
    }

    // ── The end of first run ─────────────────────────────────────────────────

    @Test
    fun completeBeforeApprovalWaitsInsteadOfFailing() = runBlocking {
        val node = ScriptedNode().apply { completeAnswer = { throw refusal(409, "add_device.not_approved") } }
        val vm = vm(node)
        vm.asked()
        vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }

        vm.claimLocalNodeOwnership(claimPinProvider = { "7F3K-Q9MZ" })

        val s = vm.until("waiting") { it.ownershipClaim.awaitingApproval }
        assertFalse(s.ownershipClaim.claimed)
        assertFalse(s.ownershipClaim.inProgress)
        assertNull(s.ownershipClaim.error, "waiting is not an error")
        assertFalse("claimed-session" in node.calls, "no session is collected for a claim that did not land")
    }

    @Test
    fun completeThenClaimedSessionInThatOrderAndTheSessionIsApplied() = runBlocking {
        // A real client, so the session is really applied. Port 1: nothing
        // listens, and the node's own options (announce off here) go nowhere.
        val saved = CIRISApiClient.LOCAL_NODE_URL
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:1")
        try {
            val api = CIRISApiClient("http://127.0.0.1:1", null)
            val node = ScriptedNode()
            node.statusAnswers += { progress("approved", owner = "eric-moore-v1") }
            val vm = track(SetupViewModel(api, hasAgent = false, addDeviceApiOverride = node, addDevicePollMs = 5))
            vm.setAnnounceOwnership(false)
            vm.asked()
            vm.until("approved") { it.addDevice.phase == AddDevicePhase.APPROVED }

            vm.claimLocalNodeOwnership(claimPinProvider = { " 7F3K-Q9MZ " })

            val s = vm.until("settled") { it.ownershipClaim.claimed && !it.ownershipClaim.inProgress }
            assertEquals(listOf("complete", "claimed-session"), node.calls.filter { it == "complete" || it == "claimed-session" })
            assertEquals("7F3K-Q9MZ", node.lastPin)
            assertEquals("SYSTEM_ADMIN", s.ownershipClaim.role)
            assertEquals(AddDevicePhase.APPLIED, s.addDevice.phase)
            assertEquals(ClaimedSessionState.Collected("SYSTEM_ADMIN"), vm.claimedSession.value)
            assertEquals("tok-owner", api.getAccessToken(), "the session is applied as the self-claim applies its own")
        } finally {
            CIRISApiClient.setLocalNodeUrl(saved)
        }
    }

    @Test
    fun applyFailedIsARecoverableFailureById() = runBlocking {
        val node = ScriptedNode().apply { completeAnswer = { throw refusal(502, "add_device.apply_failed") } }
        val vm = vm(node)
        vm.asked()
        vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }

        vm.claimLocalNodeOwnership(claimPinProvider = { "7F3K-Q9MZ" })

        val s = vm.until("failed") { it.ownershipClaim.error != null }
        assertEquals("add_device.apply_failed", s.ownershipClaim.errorReasonId)
        assertTrue(s.ownershipClaim.errorRecoverable, "the node keeps the held approval, so Retry can apply it")
        assertFalse(s.ownershipClaim.claimed)
    }

    @Test
    fun aRetryAfterTheClaimLandedDoesNotApplyTwice() = runBlocking {
        val node = ScriptedNode()
        var sessionTries = 0
        node.sessionAnswer = { sessionTries++; if (sessionTries == 1) throw RuntimeException("connection reset") else grant() }
        node.statusAnswers += { progress("approved", owner = "eric-moore-v1") }
        val vm = vm(node)
        vm.asked()
        vm.until("approved") { it.addDevice.phase == AddDevicePhase.APPROVED }
        vm.claimLocalNodeOwnership(claimPinProvider = { "7F3K-Q9MZ" })
        vm.until("settled once") { it.ownershipClaim.claimed && !it.ownershipClaim.inProgress }

        vm.claimLocalNodeOwnership(claimPinProvider = { "7F3K-Q9MZ" })
        vm.until("settled twice") { !it.ownershipClaim.inProgress && vm.claimedSession.value is ClaimedSessionState.Collected }

        assertEquals(1, node.calls.count { it == "complete" }, "applied once: a second complete would answer not_approved")
        assertEquals(2, node.calls.count { it == "claimed-session" })
    }

    @Test
    fun theAddDevicePathNeverMintsOrSelfClaims() = runBlocking {
        val api = FakeCIRISApiClientForBilling()
        val node = ScriptedNode()
        node.statusAnswers += { progress("approved", owner = "eric-moore-v1") }
        val vm = track(SetupViewModel(api, addDeviceApiOverride = node, addDevicePollMs = 5))
        vm.asked()
        vm.until("approved") { it.addDevice.phase == AddDevicePhase.APPROVED }
        vm.claimLocalNodeOwnership(claimPinProvider = { "7F3K-Q9MZ" })
        val s = vm.until("settled") { it.ownershipClaim.claimed && !it.ownershipClaim.inProgress }
        assertFalse(s.federationIdentity.minted, "joining an identity mints none")
    }

    // ── With an agent: the claim settles before the agent's setup-complete ──

    @Test
    fun withAnAgentCompleteAndClaimedSessionRunBeforeTheAgentsSetupComplete() = runBlocking {
        val node = ScriptedNode()
        node.statusAnswers += { progress("approved", owner = "eric-moore-v1") }
        val vm = vm(node, hasAgent = true)
        vm.setRunWithoutAi(true)
        vm.asked()
        vm.setUsername("qaadmin")
        vm.setUserPassword("hunter2hunter2")
        vm.setUserPasswordConfirm("hunter2hunter2")
        vm.until("approved") { it.addDevice.phase == AddDevicePhase.APPROVED }
        assertTrue(vm.nextStep(), "YOU → JOIN_FEDERATION on the add-device path")
        vm.setAccordMetricsConsent(false)

        val started = vm.finishAgentSetup(
            claimPinProvider = { "7F3K-Q9MZ" },
            submit = {
                node.calls += "agent-setup-complete"
                SetupCompletionResult(success = true, message = "ok")
            },
        )

        assertTrue(started)
        vm.until("completed") { it.currentStep == SetupStep.COMPLETE && !it.isSubmitting && "agent-setup-complete" in node.calls }
        assertEquals(
            listOf("complete", "claimed-session", "agent-setup-complete"),
            node.calls.filter { it != "start" && it != "status" },
            "add-device/complete → claimed-session → THEN the agent's setup-complete (which reloads the runtime)",
        )
    }

    @Test
    fun withAnAgentNotApprovedHoldsAndTheAgentIsNotCompleted() = runBlocking {
        val node = ScriptedNode().apply { completeAnswer = { throw refusal(409, "add_device.not_approved") } }
        val vm = vm(node, hasAgent = true)
        vm.setRunWithoutAi(true)
        vm.asked()
        vm.setUsername("qaadmin")
        vm.setUserPassword("hunter2hunter2")
        vm.setUserPasswordConfirm("hunter2hunter2")
        vm.until("requested") { it.addDevice.phase == AddDevicePhase.REQUESTED }
        vm.nextStep()
        vm.setAccordMetricsConsent(false)
        var submitted = 0

        vm.finishAgentSetup(claimPinProvider = { "7F3K-Q9MZ" }, submit = { submitted++; SetupCompletionResult(success = true, message = "ok") })

        val s = vm.until("waiting on COMPLETE") { it.ownershipClaim.awaitingApproval && it.currentStep == SetupStep.COMPLETE && !it.isSubmitting }
        assertEquals(0, submitted, "the agent is not completed over an unowned node")

        // The approval arrives; the screen's retry finishes the whole thing.
        node.completeAnswer = { progress("applied", owner = "eric-moore-v1") }
        node.statusAnswers += { progress("approved", owner = "eric-moore-v1") }
        vm.until("approved") { it.addDevice.phase == AddDevicePhase.APPROVED }
        assertTrue(vm.retryAddDeviceFinish(claimPinProvider = { "7F3K-Q9MZ" }, submit = { submitted++; SetupCompletionResult(success = true, message = "ok") }))
        vm.until("finished") { it.ownershipClaim.claimed && !it.isSubmitting && submitted == 1 }
        assertFalse(vm.state.value.ownershipClaim.awaitingApproval)
        assertTrue(s.ownershipClaim.awaitingApproval)
    }

    // ── Restart-resume and old nodes ─────────────────────────────────────────

    @Test
    fun aRestartWithAnApprovalHeldResumesTheAddDevicePath() = runBlocking {
        val node = ScriptedNode().apply { statusFallback = { progress("approved", owner = "eric-moore-v1") } }
        val vm = vm(node)

        vm.probeAddDevice()

        val s = vm.until("resumed") { it.addDevice.phase == AddDevicePhase.APPROVED }
        assertEquals(IdentityPath.EXISTING, s.identityPath, "resume the add-device path, do not start over")
        assertEquals("eric-moore-v1", s.addDevice.ownerKeyId)
        assertEquals(AddDeviceAvailability.AVAILABLE, s.addDevice.availability)
    }

    @Test
    fun aRestartWhileRequestedResumesAndKeepsPolling() = runBlocking {
        val node = ScriptedNode()
        node.statusAnswers += { progress("requested", code = "482913") }
        node.statusAnswers += { progress("requested", code = "482913") }
        node.statusFallback = { progress("approved", owner = "eric-moore-v1") }
        val vm = vm(node)

        vm.probeAddDevice()

        val s = vm.until("approved after resume") { it.addDevice.phase == AddDevicePhase.APPROVED }
        assertEquals(IdentityPath.EXISTING, s.identityPath)
        assertEquals("482913", s.addDevice.matchCode)
    }

    @Test
    fun anIdleNodeLeavesTheFirstQuestionOpen() = runBlocking {
        val node = ScriptedNode().apply { statusFallback = { progress("idle") } }
        val vm = vm(node)
        vm.probeAddDevice()
        val s = vm.until("available") { it.addDevice.availability == AddDeviceAvailability.AVAILABLE }
        assertNull(s.identityPath)
        assertEquals(AddDevicePhase.IDLE, s.addDevice.phase)
    }

    @Test
    fun aNodeOlderThan0_5_231DisablesAddThisDeviceAndLeavesTheNewPath() = runBlocking {
        val node = ScriptedNode().apply { statusFallback = { throw refusal(404, null) } }
        val vm = vm(node)

        vm.probeAddDevice()

        vm.until("too old") { it.addDevice.availability == AddDeviceAvailability.NODE_TOO_OLD }
        vm.chooseIdentityPath(IdentityPath.EXISTING)
        assertNull(vm.state.value.identityPath, "refused: the option is disabled with the node-version reason")
        vm.chooseIdentityPath(IdentityPath.NEW)
        assertEquals(IdentityPath.NEW, vm.state.value.identityPath, "the new-ID path is unchanged")
    }

    @Test
    fun aBare404OnTheRequestItselfIsTheVersionFactNotARefusalId() = runBlocking {
        val node = ScriptedNode().apply { startAnswer = { throw refusal(404, null) } }
        val vm = vm(node)
        vm.asked()
        val s = vm.until("refused") { it.addDevice.phase == AddDevicePhase.REFUSED }
        assertEquals(AddDeviceAvailability.NODE_TOO_OLD, s.addDevice.availability)
        assertNull(s.addDevice.refusalReasonId)
    }
}
