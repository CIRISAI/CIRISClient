package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.AgentRateGovernor
import ai.ciris.mobile.shared.api.AgentRateLimit
import ai.ciris.mobile.shared.api.AttachmentPolicyRead
import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.MessageType
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The chat screen inside the agent's rate limit** (gate run 39755201812,
 * linux-ai, ciris-agent 2.16.2: the attachment policy read 429'd three times
 * and the user's `POST /v1/agent/interact` was answered 429, so the reply
 * never came — csd_010_interact_chat).
 *
 * The client's REAL pollers ([InteractViewModel.startPolling]) against
 * [FakeRateLimitedAgent] — a port of the agent's 60/min token bucket — on
 * virtual time, so a minute of polling runs in milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRateBudgetTest {

    // lint: test-scheduler-main -- Main is a StandardTestDispatcher on runTest's scheduler: each test cancels its view model and drains the scheduler ([stop]) before asserting, so nothing outlives it.
    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        AgentRateGovernor.clock = { kotlinx.datetime.Clock.System.now().toEpochMilliseconds() }
        // Governors are process-wide; state built on virtual time must not reach the next class.
        kotlinx.coroutines.runBlocking { AgentRateGovernor.resetForTests() }
    }

    private fun TestScope.chat(initialTokens: Double = 60.0): Pair<FakeRateLimitedAgent, InteractViewModel> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        AgentRateGovernor.clock = { testScheduler.currentTime }
        val agent = FakeRateLimitedAgent(dispatcher, { testScheduler.currentTime }, initialTokens)
        val vm = InteractViewModel(CIRISApiClient(agent.url, "tok", agent.engine))
        return agent to vm
    }

    private fun TestScope.stop(vm: InteractViewModel) {
        vm.viewModelScope.coroutineContext.job.cancel()
        advanceUntilIdle()
    }

    private fun describe(agent: FakeRateLimitedAgent, from: Long = 0, to: Long = Long.MAX_VALUE) =
        agent.counted().filter { it.atMs in from..to }.groupingBy { it.path }.eachCount().entries
            .sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }

    /**
     * Background traffic stays at or under half the agent's limit in EVERY
     * minute of chat — opening included — so the other half is the user's.
     */
    @Test
    fun theChatScreensBackgroundTrafficStaysInsideHalfTheAgentsLimit() = runTest {
        AgentRateGovernor.resetForTests()
        val (agent, vm) = chat()
        vm.startPolling()
        advanceTimeBy(180_000)
        val worst = agent.maxInAnyWindow(60_000)
        val firstMinute = describe(agent, 0, 60_000)
        val steadyMinute = describe(agent, 120_000, 180_000)
        println("AGENT-BUDGET worst60s=$worst | first minute: $firstMinute | minute 3: $steadyMinute")
        stop(vm)
        assertTrue(
            worst <= AgentRateGovernor.BACKGROUND_BUDGET_PER_MINUTE,
            "worst 60 s window sent $worst agent requests (budget ${AgentRateGovernor.BACKGROUND_BUDGET_PER_MINUTE}); first minute: $firstMinute",
        )
        assertTrue(agent.counted().none { it.status == 429 }, "no request should ever be refused: ${agent.counted().filter { it.status == 429 }}")
    }

    /**
     * THE GATE'S FAILURE: chat opens on a bucket startup already emptied, the
     * pollers keep it empty, and the user's send is refused. It must instead
     * be admitted — the background steps aside until the user's reserve has
     * refilled, and a refused send waits out `Retry-After` and goes again.
     */
    @Test
    fun aUserSendDuringSaturationIsAnsweredAfterTheBackoff() = runTest {
        AgentRateGovernor.resetForTests()
        val (agent, vm) = chat(initialTokens = 0.0)
        vm.startPolling()
        // The gate's timing: the send lands in chat-open's burst, on an empty bucket.
        advanceTimeBy(500)
        vm.onInputTextChanged("In one short sentence: what are you?")
        vm.sendMessage()
        advanceTimeBy(30_000)
        val interacts = agent.hits.filter { it.path == "/v1/agent/interact" }
        val users = vm.messages.value.filter { it.type == MessageType.USER }
        val errors = vm.messages.value.filter { it.type == MessageType.SYSTEM || it.type == MessageType.ERROR }
        val shed = vm.shedState.value
        stop(vm)
        assertTrue(interacts.any { it.status == 200 }, "the user's send was never admitted: ${interacts.map { "${it.atMs}:${it.status}" }}")
        assertEquals("srv-1", users.singleOrNull()?.id, "one user bubble, reconciled to the agent's id: ${vm.messages.value}")
        assertTrue(errors.isEmpty(), "a rate limit is not an error bubble: $errors")
        assertEquals(ChatShedState.None, shed)
    }

    /**
     * A refused send is SAID ("The agent is busy — retrying in Ns", the shed
     * notice's wording) and resent unchanged; the limiter refuses before any
     * handler runs, so the resend cannot duplicate the message.
     */
    @Test
    fun aRefusedSendShowsBusyRetryingAndIsResentUnchanged() = runTest {
        AgentRateGovernor.resetForTests()
        val (agent, vm) = chat()
        var refusals = 0
        agent.override = { method, path ->
            if (method == "POST" && path == "/v1/agent/interact" && refusals < 2) {
                refusals++
                FakeRateLimitedAgent.Reply(
                    429,
                    """{"detail":"Too many requests. Please wait 2 seconds before trying again.","error":"rate_limit_exceeded","retry_after":2}""",
                    mapOf("Retry-After" to "2", "X-RateLimit-Remaining" to "0"),
                )
            } else null
        }
        val seen = mutableListOf<ChatShedState>()
        backgroundScope.launch { vm.shedState.collect { seen += it } }
        vm.onInputTextChanged("are you there?")
        vm.sendMessage()
        advanceTimeBy(30_000)
        val interacts = agent.hits.filter { it.path == "/v1/agent/interact" }
        val users = vm.messages.value.filter { it.type == MessageType.USER }
        val errors = vm.messages.value.filter { it.type == MessageType.SYSTEM || it.type == MessageType.ERROR }
        stop(vm)
        assertTrue(seen.any { it is ChatShedState.Waiting }, "the wait must be visible: $seen")
        assertEquals(listOf(429, 429, 200), interacts.map { it.status })
        assertEquals(1, interacts.map { it.body }.distinct().size, "the same request is resent")
        assertEquals("srv-1", users.singleOrNull()?.id)
        assertTrue(errors.isEmpty(), "no error bubble: $errors")
    }

    /** A 429 on ONE poller's route pauses every background caller for its Retry-After. */
    @Test
    fun a429OnOnePollerPausesTheOthers() = runTest {
        AgentRateGovernor.resetForTests()
        val (agent, vm) = chat()
        var refusedAt: Long? = null
        agent.override = { _, path ->
            if (path == "/v1/agent/history" && refusedAt == null && testScheduler.currentTime >= 20_000) {
                refusedAt = testScheduler.currentTime
                FakeRateLimitedAgent.Reply(429, """{"error":"rate_limit_exceeded","retry_after":10}""", mapOf("Retry-After" to "10"))
            } else null
        }
        vm.startPolling()
        advanceTimeBy(60_000)
        stop(vm)
        val t = refusedAt ?: error("history was never polled after 20 s")
        val during = agent.counted().filter { it.atMs > t && it.atMs < t + 10_000 }
        assertTrue(during.isEmpty(), "background requests inside the 10 s pause: ${during.map { "${it.atMs - t}ms ${it.path}" }}")
    }

    /**
     * THE SECOND GATE'S FAILURE (run 39764738615, linux-ai): traffic that never
     * declared who was asking — a hundred unmarked reads from screens opening
     * at once — emptied the bucket 36 s after launch, and the user's policy
     * read was refused on every retry. Unmarked traffic now waits on the
     * mirror of the agent's bucket, so the user's read still finds a token.
     */
    @Test
    fun anUnmarkedBurstLeavesTheUserARequest() = runTest {
        AgentRateGovernor.resetForTests()
        val (agent, _) = chat()
        val screens = HttpClient(agent.engine) { install(AgentRateLimit) }
        // The gate's burst came long after the client had first heard from the
        // agent, so the governor already knew this host has a limiter.
        screens.get("${agent.url}/v1/agent/status")
        val burst = (1..100).map { i -> async { screens.get("${agent.url}/v1/screen/$i") } }
        advanceTimeBy(2_000)
        val policy = CIRISApiClient(agent.url, "tok", agent.engine).getAttachmentPolicy()
        burst.awaitAll()
        val refused = agent.counted().filter { it.status == 429 }
        assertTrue(policy !is AttachmentPolicyRead.Failed, "the user's read was refused behind the burst: $policy")
        assertTrue(refused.isEmpty(), "the burst ran the agent dry: ${refused.size} refused, first ${refused.firstOrNull()}")
    }

    /**
     * THE THIRD GATE'S FAILURE (run 39774629469): the node on :4243 proxies
     * brain routes onto the agent's :8080 from loopback, so both ports spend
     * ONE bucket. Paced per origin, each port's mirror believed it had the
     * whole bucket, and together they ran it dry.
     */
    /**
     * A host that never says it has a limiter (a node with no agent behind it,
     * or every in-process test server) is not held back by an imaginary one:
     * a hundred unmarked reads all go at once.
     */
    @Test
    fun aHostWithNoLimiterIsNotPaced() = runTest {
        AgentRateGovernor.resetForTests()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        AgentRateGovernor.clock = { testScheduler.currentTime }
        val agent = FakeRateLimitedAgent(dispatcher, { testScheduler.currentTime }, rate = 100_000, sendsHeaders = false)
        val screens = HttpClient(agent.engine) { install(AgentRateLimit) }
        val burst = (1..100).map { i -> async { screens.get("${agent.url}/v1/contacts/$i") } }
        burst.awaitAll()
        assertEquals(100, agent.hits.count { it.atMs == 0L }, "a node with no limiter was paced: ${agent.hits.map { it.atMs }.distinct()}")
    }

    @Test
    fun theNodePortAndTheAgentPortShareOneBudget() = runTest {
        AgentRateGovernor.resetForTests()
        val (agent, _) = chat()
        val screens = HttpClient(agent.engine) { install(AgentRateLimit) }
        // The gate's burst came long after the client had first heard from the
        // agent, so the governor already knew this host has a limiter.
        screens.get("${agent.url}/v1/agent/status")
        val node = agent.url.replace(":8080", ":4243")
        val burst = (1..100).map { i ->
            async { screens.get("${if (i % 2 == 0) node else agent.url}/v1/telemetry/$i") }
        }
        advanceTimeBy(2_000)
        val policy = CIRISApiClient(agent.url, "tok", agent.engine).getAttachmentPolicy()
        burst.awaitAll()
        val refused = agent.counted().filter { it.status == 429 }
        assertTrue(policy !is AttachmentPolicyRead.Failed, "the user's read was refused behind the two ports' burst: $policy")
        assertTrue(refused.isEmpty(), "the two ports ran the agent dry: ${refused.size} refused")
    }
}

