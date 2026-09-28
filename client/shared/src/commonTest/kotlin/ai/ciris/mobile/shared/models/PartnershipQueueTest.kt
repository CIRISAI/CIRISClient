package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-054 §7 — the partnership queue on the Consent card. The bodies are the
 * shapes CIRISAgent main's `routes/partnership.py` builds (`StandardResponse`
 * with the payload under `data`).
 */
class PartnershipQueueTest {

    private val pendingBody = """
        {"success": true, "message": "Found 2 pending partnership requests",
         "data": {"requests": [
            {"user_id": "discord:4471", "task_id": "partnership_discord:4471_ab12cd34",
             "categories": ["interaction", "preference"], "reason": "I talk to it daily",
             "channel_id": "api_discord:4471", "created_at": "2026-09-16T10:00:00Z",
             "age_hours": 220.5, "aging_status": "warning", "priority": "normal", "notes": null},
            {"user_id": "alice", "task_id": "partnership_alice_0011aabb",
             "categories": [], "reason": null, "channel_id": "api_alice",
             "created_at": "2026-09-25T08:00:00Z", "age_hours": 3.2,
             "aging_status": "normal", "priority": "low"}],
          "total": 2, "by_status": {"normal": 1, "warning": 1, "critical": 0}},
         "metadata": {"timestamp": "2026-09-25T11:00:00Z", "critical_count": 0}}
    """.trimIndent()

    @Test
    fun aServedQueueDecodesOldestFirst() {
        val read = agentReadOf(200, pendingBody, PartnershipPendingDto.serializer())
        val queue = partnershipQueueOf(read, null)
        assertIs<PartnershipQueue.Ready>(queue)
        assertEquals(listOf("discord:4471", "alice"), queue.requests.map { it.userId })
        assertEquals(listOf("interaction", "preference"), queue.requests.first().categories)
        assertEquals("warning", queue.requests.first().agingStatus)
        assertNull(queue.metrics, "no metrics read, so none drawn — not zeros")
    }

    /**
     * A 403 is a fact about who is looking. Drawing it as the empty queue
     * would tell a non-administrator "nobody is waiting" about a list they
     * were never shown (CSD/3 §2.2).
     */
    @Test
    fun aForbiddenQueueIsNeverTheEmptyQueue() {
        val read = agentReadOf(403, """{"detail":"Only administrators can view pending partnerships"}""", PartnershipPendingDto.serializer())
        assertEquals(PartnershipQueue.AdminOnly, partnershipQueueOf(read, null))
    }

    @Test
    fun aMissingRouteAndAFailedReadAreTwoFailuresAndNeitherIsEmpty() {
        val absent = partnershipQueueOf(agentReadOf(404, "", PartnershipPendingDto.serializer()), null)
        assertIs<PartnershipQueue.Failed>(absent)
        assertIs<ReadFailure.NotOnThisNode>(absent.failure)

        val broken = partnershipQueueOf(agentReadOf(500, "boom", PartnershipPendingDto.serializer()), null)
        assertIs<PartnershipQueue.Failed>(broken)
        assertIs<ReadFailure.Failed>(broken.failure)

        // A 200 whose body is not the queue is a failed read, not "nobody waiting".
        val garbled = partnershipQueueOf(agentReadOf(200, "not json", PartnershipPendingDto.serializer()), null)
        assertIs<PartnershipQueue.Failed>(garbled)
    }

    @Test
    fun metricsDecorateAReadQueueAndNothingElse() {
        val metricsBody = """{"success":true,"data":{"total_requests":20,"total_approvals":12,
            "total_rejections":4,"total_deferrals":2,"pending_count":2,"approval_rate_percent":60.0,
            "rejection_rate_percent":20.0,"deferral_rate_percent":10.0,"avg_pending_hours":40.0,
            "oldest_pending_hours":220.5,"critical_count":0}}"""
        val metrics = agentReadOf(200, metricsBody, PartnershipMetricsDto.serializer())
        val queue = partnershipQueueOf(agentReadOf(200, pendingBody, PartnershipPendingDto.serializer()), metrics)
        assertIs<PartnershipQueue.Ready>(queue)
        assertEquals(12, queue.metrics?.totalApprovals)
    }

    /**
     * WHOSE ANSWER. Every request on the queue was filed by a person asking
     * for the partnered stream, so the answer is the agent's producer half
     * (CC 3.3.1 `consent:partnership_accept`). No request is ever the reader's
     * to answer — the card's refusal to draw accept/decline rests on this.
     */
    @Test
    fun everyRequestIsTheAgentsToAnswer() {
        val queue = partnershipQueueOf(agentReadOf(200, pendingBody, PartnershipPendingDto.serializer()), null)
        assertIs<PartnershipQueue.Ready>(queue)
        assertTrue(queue.requests.isNotEmpty())
        for (r in queue.requests) assertEquals(PartnershipDecider.AGENT, deciderOf(r), r.userId)
    }

    @Test
    fun historyAndOptionsDecodeTheAgentsShapes() {
        val history = agentReadOf(
            200,
            """{"success":true,"data":{"user_id":"alice","total_requests":1,"current_status":"none",
              "outcomes":[{"user_id":"alice","task_id":"partnership_alice_1","outcome_type":"deferred",
              "decided_by":"agent","decided_at":"2026-09-20T09:00:00Z","reason":"More information needed before deciding","notes":null}]}}""",
            PartnershipHistoryDto.serializer(),
        )
        assertIs<AgentRead.Ok<PartnershipHistoryDto>>(history)
        assertEquals("deferred", history.value.outcomes.single().outcomeType)

        val options = agentReadOf(
            200,
            """{"success":true,"data":{"required_categories":["interaction","preference","improvement"],
              "optional_categories":["research","sharing"],"approval_process":"…","benefits":[],"responsibilities":[],
              "revocation":"Either party can revoke partnership at any time. Data handling follows consent decay protocols."}}""",
            PartnershipOptionsDto.serializer(),
        )
        assertIs<AgentRead.Ok<PartnershipOptionsDto>>(options)
        assertEquals(listOf("research", "sharing"), options.value.optionalCategories)
    }

    @Test
    fun waitReadsInHoursThenDays() {
        assertEquals(3 to false, partnershipWaitHours(3.2))
        assertEquals(47 to false, partnershipWaitHours(47.9))
        assertEquals(9 to true, partnershipWaitHours(220.5))
        assertEquals(0 to false, partnershipWaitHours(-1.0))
    }
}
