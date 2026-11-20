package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.DeferralData
import ai.ciris.mobile.shared.api.IdentityResolveData
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.ResolveDeferralData
import ai.ciris.mobile.shared.api.TicketData
import ai.ciris.mobile.shared.api.WAStatusData
import ai.ciris.mobile.shared.approvals.ApprovalNotifier
import ai.ciris.mobile.shared.approvals.ApprovalNotificationSink
import ai.ciris.mobile.shared.approvals.ApprovalsApi
import ai.ciris.mobile.shared.approvals.BudgetGrantOutcome
import ai.ciris.mobile.shared.approvals.DeferralList
import ai.ciris.mobile.shared.approvals.DeferralListMeta
import ai.ciris.mobile.shared.approvals.IdentityCapabilities
import ai.ciris.mobile.shared.approvals.IdentityProfile
import ai.ciris.mobile.shared.approvals.IdentityProfileStatus
import ai.ciris.mobile.shared.approvals.InMemoryNotifiedApprovalStore
import ai.ciris.mobile.shared.approvals.ResolveSubject
import ai.ciris.mobile.shared.approvals.TicketBudgetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The WA view model's identity path: the subject reaches the API unchanged, and every outcome is typed. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityChangeResolveTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    // lint: test-scheduler-main -- Main is a StandardTestDispatcher: a cancelled view model's resume queues on its scheduler and never runs, so nothing leaks, and stopViewModels() would wait forever.
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private class Api(val meta: DeferralListMeta) : ApprovalsApi {
        val identityCalls = mutableListOf<List<Any?>>()
        var answer: () -> IdentityResolveData = { IdentityResolveData("d1", true, null, "req-1", "res-1") }
        override suspend fun fetchWAStatus() = WAStatusData(true, 1, 0, 0, 0.0, null)
        override suspend fun fetchDeferrals(): List<DeferralData> = emptyList()
        override suspend fun fetchDeferralList() = DeferralList(emptyList(), meta)
        override suspend fun resolveDeferral(deferralId: String, resolution: String, guidance: String): ResolveDeferralData =
            error("an identity deferral never goes through the plain resolve")
        override suspend fun resolveIdentityDeferral(deferralId: String, resolution: String, guidance: String, subject: ResolveSubject?): IdentityResolveData {
            identityCalls += listOf(deferralId, resolution, guidance, subject)
            return answer()
        }
        override suspend fun fetchProposals(): List<TicketData> = emptyList()
        override suspend fun fetchTicketBudget(ticketId: String): TicketBudgetState? = null
        override suspend fun grantBudget(ticketId: String, amount: String, currency: String, purpose: String, expiresInHours: Int): BudgetGrantOutcome =
            error("unused")
        override suspend fun updateTicketStatus(ticketId: String, status: String, notes: String?, expectedStatus: String?) =
            error("unused")
        override suspend fun readTicketStatus(ticketId: String): String = error("unused")
    }

    private object Sink : ApprovalNotificationSink {
        override fun hasPermission() = true
        override suspend fun requestPermission() = true
        override fun show(id: String, title: String, body: String) {}
    }

    private val meta = DeferralListMeta(
        IdentityProfile(IdentityProfileStatus.UNANCHORED, "d-base"),
        IdentityCapabilities(true, true, true),
    )

    private fun vm(api: Api) = WiseAuthorityViewModel(api, ApprovalNotifier(Sink, InMemoryNotifiedApprovalStore()))

    @Test
    fun theListsMetaReachesTheScreen() = runTest {
        val v = vm(Api(meta))
        v.refresh(); advanceUntilIdle()
        assertEquals(meta, v.deferralMeta.value)
    }

    @Test
    fun approveSendsTheExactTextAndSaysApprovedWithItsReceipts() = runTest {
        val api = Api(meta)
        val v = vm(api)
        val text = "I serve.\n\n  With care,  always."
        v.resolveIdentityDeferral("d1", "approve", "", ResolveSubject.AmendmentText(text))
        advanceUntilIdle()
        assertEquals(listOf<Any?>("d1", "approve", "", ResolveSubject.AmendmentText(text)), api.identityCalls.single())
        assertEquals(IdentityResolveOutcome.Approved("req-1", "res-1"), v.identityOutcome.value)
    }

    @Test
    fun aRejectCarriesNoSubject() = runTest {
        val api = Api(meta)
        val v = vm(api)
        v.resolveIdentityDeferral("d1", "reject", "Say what changes, and why.", null)
        advanceUntilIdle()
        assertEquals(listOf<Any?>("d1", "reject", "Say what changes, and why.", null), api.identityCalls.single())
        assertEquals(IdentityResolveOutcome.Rejected, v.identityOutcome.value)
    }

    @Test
    fun aRefusalKeepsItsReasonId() = runTest {
        val api = Api(meta).apply { answer = { throw NodeRefusal("wa.base_link_digest_mismatch", "mismatch", 400) } }
        val v = vm(api)
        v.resolveIdentityDeferral("d1", "approve", "", ResolveSubject.BaseLinkDigest("a".repeat(64)))
        advanceUntilIdle()
        val o = v.identityOutcome.value
        assertIs<IdentityResolveOutcome.Refused>(o)
        assertEquals("wa.base_link_digest_mismatch", o.refusal.reasonId)
    }

    @Test
    fun aDecisionThatDidNotCommitIsNotApproved() = runTest {
        val api = Api(meta).apply { answer = { IdentityResolveData("d1", false, null, null, null) } }
        val v = vm(api)
        v.resolveIdentityDeferral("d1", "approve", "", ResolveSubject.AmendmentText("x"))
        advanceUntilIdle()
        assertEquals(IdentityResolveOutcome.NotResolved, v.identityOutcome.value)
    }
}
