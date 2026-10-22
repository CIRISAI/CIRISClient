package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.AttachmentPolicy
import ai.ciris.mobile.shared.models.AttachmentPolicySource
import ai.ciris.mobile.shared.models.MessageType
import ai.ciris.mobile.shared.platform.AttachmentRefusal
import ai.ciris.mobile.shared.platform.PickTooLarge
import ai.ciris.mobile.shared.platform.PickedFile
import androidx.lifecycle.ViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent's attachment policy drives the paperclip, and its per-file
 * verdicts land on the SENT message (CIRISClient#217; CIRISAgent
 * `fix/interact-attachments` @ 380455879). [InteractViewModel] against a real
 * socket that serves `GET /v1/agent/attachments/policy` and `POST /v1/agent/interact`.
 */
class InteractAttachmentPolicyTest {

    /** The agent's own policy today (api_attachments.py:45-57). */
    private val agentPolicy = """{"data":{"image_types":["image/jpeg","image/png","image/gif","image/webp"],
        "image_max_bytes":20971520,"max_images":5,
        "document_types":["application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/plain","text/markdown","text/csv"],
        "document_max_bytes":10485760,"max_documents":5,"max_extracted_chars":50000}}"""

    /**
     * [policy] answers the policy route, called with how many policy reads came
     * before this one, so a test can script "429, then 200".
     */
    private class Agent(
        private val policy: (Int) -> Pair<Int, String>,
        private val interact: String = "",
        /** What `/v1/system/health` reports as `version`; null answers 404, as the fakes always did. */
        private val version: String? = null,
    ) {
        constructor(policy: Pair<Int, String>, interact: String = "", version: String? = null) : this({ _: Int -> policy }, interact, version)
        val bodies: MutableList<String> = CopyOnWriteArrayList()
        val policyReads = java.util.concurrent.atomic.AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.use { it.readBytes().decodeToString() }
                val (status, out) = when (ex.requestURI.path) {
                    "/v1/agent/attachments/policy" -> policy(policyReads.getAndIncrement())
                    "/v1/agent/interact" -> { bodies += body; 200 to interact }
                    "/v1/system/health" -> if (version == null) 404 to """{"detail":"Not Found"}"""
                        else 200 to """{"data":{"status":"healthy","version":"$version","cognitive_state":"WORK"}}"""
                    else -> 404 to """{"detail":"Not Found"}"""
                }
                val bytes = out.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private val made = mutableListOf<ViewModel>()
    private val agents = mutableListOf<Agent>()
    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    // The 429s here mark 127.0.0.1 as a limited host with an empty bucket, and
    // the governor is process-wide: left in place, every later test that talks
    // to 127.0.0.1 waits on it (WipeForgetsNodeTest stalled the whole suite).
    @AfterTest fun tearDown() {
        stopViewModels(made); Dispatchers.resetMain(); agents.forEach { it.server.stop(0) }
        kotlinx.coroutines.runBlocking { ai.ciris.mobile.shared.api.AgentRateGovernor.resetForTests() }
    }

    private fun agent(policy: Pair<Int, String>, interact: String = "") = Agent(policy, interact).also { agents += it }
    private fun agent(policy: (Int) -> Pair<Int, String>) = Agent(policy).also { agents += it }
    private fun vm(a: Agent) = InteractViewModel(CIRISApiClient(a.url, "tok")).also { made += it }
    private fun file(name: String, type: String, size: Long = 3) = PickedFile(name, type, "AAAA", size)

    private suspend fun InteractViewModel.settled() = withContext(Dispatchers.Default) {
        withTimeout(15_000) { isSending.first { !it } }
    }

    // --- the policy read ---

    @Test
    fun thePolicyIsReadFromTheAgent() = runBlocking {
        val vm = vm(agent(200 to agentPolicy))
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.AGENT, vm.attachmentPolicySource.value)
        assertEquals(20971520L, vm.attachmentPolicy.value.imageMaxBytes)
        assertTrue("text/markdown" in vm.attachmentPolicy.value.documentTypes)
    }

    @Test
    fun aBare404FallsBackToTheClientsLimitsAndNeverBlocksAttaching() = runBlocking {
        val vm = vm(agent(404 to """{"detail":"Not Found"}"""))
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.FALLBACK_AGENT_TOO_OLD, vm.attachmentPolicySource.value)
        assertEquals(AttachmentPolicy.CLIENT_FALLBACK, vm.attachmentPolicy.value)
        vm.addAttachment(file("a.png", "image/png"))
        vm.addAttachment(file("b.pdf", "application/pdf"))
        assertEquals(2, vm.attachedFiles.value.size)
        // An old agent does not read text documents; the fallback does not offer them.
        vm.addAttachment(file("n.txt", "text/plain"))
        assertEquals(AttachmentRefusal.UnsupportedType("n.txt", "text/plain"), vm.attachmentRefused.value)
    }

    /**
     * WHAT ciris-agent 2.16.2 ANSWERS FOR A ROUTE IT DOES NOT HAVE: FastAPI's
     * `{"detail":"Not Found"}`, not an empty body. Pinned by name so a later
     * "bare 404 means no body" tightening cannot make the released agent read
     * as a failure.
     */
    @Test
    fun fastApisNotFoundBodyIsAnAgentOlderThanTheRoute() = runBlocking {
        val vm = vm(agent(404 to """{"detail":"Not Found"}"""))
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.FALLBACK_AGENT_TOO_OLD, vm.attachmentPolicySource.value)
    }

    /**
     * THE AGENT'S RATE LIMITER, VERBATIM (ciris_engine api/middleware/rate_limiter.py,
     * keyed on `auth_127.0.0.1`). Release gate 39706713027, linux-ai and
     * windows-ai: `[loadAttachmentPolicy] attachment policy unavailable (HTTP 429)`.
     * The read fired in chat-open's burst, was told to wait one second, and
     * was filed as a failure instead.
     */
    private val rateLimited = 429 to """{"detail":"Too many requests. Please wait 1 seconds before trying again.","error":"rate_limit_exceeded","retry_after":1,"message":"You're sending requests too quickly. The system will be ready again shortly."}"""

    @Test
    fun a429IsWaitedOutAndReadAgain() = runBlocking {
        val a = agent { n: Int -> if (n == 0) rateLimited else 200 to agentPolicy }
        val vm = vm(a)
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.AGENT, vm.attachmentPolicySource.value, "a 429 means wait, not unavailable")
        assertEquals(2, a.policyReads.get())
    }

    @Test
    fun retriesThatStayRateLimitedAreUnavailableButNotForTheSession() = runBlocking {
        val limited = java.util.concurrent.atomic.AtomicBoolean(true)
        val a = agent { _: Int -> if (limited.get()) rateLimited else 200 to agentPolicy }
        val vm = vm(a)
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.FALLBACK_UNAVAILABLE, vm.attachmentPolicySource.value)
        assertTrue(a.policyReads.get() in 2..4, "retries must be bounded; got ${a.policyReads.get()}")
        // The burst passes; the next paperclip tap reads again.
        limited.set(false)
        vm.onAttachTapped()
        withContext(Dispatchers.Default) {
            withTimeout(15_000) { vm.attachmentPolicySource.first { it == AttachmentPolicySource.AGENT } }
        }
        assertEquals(20971520L, vm.attachmentPolicy.value.imageMaxBytes)
    }

    /**
     * Gate run 39789325955: the bucket stayed empty for ~14 s after chat
     * opened, longer than the read's own retries, and nothing tapped the
     * paperclip before the files flow looked. Chat opening alone must get to
     * the live policy once the agent answers again.
     */
    @Test
    fun aPolicyLeftUnavailableByChatOpenIsReadAgainWithoutATap() = runBlocking {
        val limited = java.util.concurrent.atomic.AtomicBoolean(true)
        val a = agent { _: Int -> if (limited.get()) rateLimited else 200 to agentPolicy }
        val vm = vm(a).apply { policyRetryDelaysMs = listOf(200L, 200L, 200L, 200L, 200L, 200L, 200L, 200L) }
        vm.refreshAttachmentPolicy()
        withContext(Dispatchers.Default) {
            withTimeout(15_000) { vm.attachmentPolicySource.first { it == AttachmentPolicySource.FALLBACK_UNAVAILABLE } }
        }
        limited.set(false)
        withContext(Dispatchers.Default) {
            withTimeout(15_000) { vm.attachmentPolicySource.first { it == AttachmentPolicySource.AGENT } }
        }
        assertEquals(20971520L, vm.attachmentPolicy.value.imageMaxBytes)
    }

    @Test
    fun theBackgroundRereadsAreBounded() = runBlocking {
        val a = agent { _: Int -> rateLimited }
        val vm = vm(a).apply { policyRetryDelaysMs = listOf(10L, 10L) }
        vm.refreshAttachmentPolicy()
        withContext(Dispatchers.Default) { withTimeout(30_000) { while (true) { kotlinx.coroutines.delay(100); if (a.policyReads.get() >= 9) break } } }
        kotlinx.coroutines.delay(3_000)
        assertEquals(AttachmentPolicySource.FALLBACK_UNAVAILABLE, vm.attachmentPolicySource.value)
        // One read and two re-reads, each with its own bounded 429 retries.
        assertTrue(a.policyReads.get() <= 12, "re-reads must stop; got ${a.policyReads.get()}")
    }

    /**
     * CIRISAgent fix/node-proxy-loop: through 2.16.2 an absent `/v1/agent/…`
     * path laps agent → node → agent until the caller's bucket is empty (gate
     * run 39820724677: Remaining 47 → 0 in 1.5 s on one policy GET). An agent
     * whose health says it predates the route is not asked for it at all.
     */
    @Test
    fun anAgentOlderThanTheRouteIsNotAskedForIt() = runBlocking {
        val a = Agent(200 to agentPolicy, version = "2.16.2-stable").also { agents += it }
        val vm = vm(a)
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.FALLBACK_AGENT_TOO_OLD, vm.attachmentPolicySource.value)
        assertEquals(0, a.policyReads.get(), "the policy route was requested from an agent that predates it")
    }

    @Test
    fun anAgentWithTheRouteIsAskedForIt() = runBlocking {
        val a = Agent(200 to agentPolicy, version = "2.16.3-stable").also { agents += it }
        val vm = vm(a)
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.AGENT, vm.attachmentPolicySource.value)
        assertEquals(1, a.policyReads.get())
    }

    /** A node answers `/v1/system/health` with its own 0.x version; that is not an agent's. */
    @Test
    fun aNodesVersionIsNotReadAsAnAgents() = runBlocking {
        val a = Agent(200 to agentPolicy, version = "0.5.233").also { agents += it }
        val vm = vm(a)
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.AGENT, vm.attachmentPolicySource.value)
    }

    @Test
    fun aPaperclipTapDoesNotReReadALivePolicy() = runBlocking {
        val a = agent(200 to agentPolicy)
        val vm = vm(a)
        vm.loadAttachmentPolicy()
        vm.onAttachTapped()
        assertEquals(1, a.policyReads.get())
    }

    @Test
    fun anUnreachablePolicyAlsoFallsBack() = runBlocking {
        val vm = InteractViewModel(CIRISApiClient("http://127.0.0.1:9", "tok")).also { made += it }
        vm.loadAttachmentPolicy()
        assertEquals(AttachmentPolicySource.FALLBACK_UNAVAILABLE, vm.attachmentPolicySource.value)
        vm.addAttachment(file("a.png", "image/png"))
        assertEquals(1, vm.attachedFiles.value.size)
    }

    // --- policy-driven gating ---

    @Test
    fun textDocumentsAreAttachableWhenTheAgentReadsThem() = runBlocking {
        val vm = vm(agent(200 to agentPolicy))
        vm.loadAttachmentPolicy()
        for ((n, t) in listOf("a.txt" to "text/plain", "b.md" to "text/markdown", "c.csv" to "text/csv")) vm.addAttachment(file(n, t))
        assertEquals(3, vm.attachedFiles.value.size)
        assertNull(vm.attachmentRefused.value)
    }

    @Test
    fun theCountIsThePolicysPerKindCount() = runBlocking {
        val vm = vm(agent(200 to agentPolicy.replace("\"max_images\":5", "\"max_images\":2")))
        vm.loadAttachmentPolicy()
        repeat(2) { vm.addAttachment(file("p$it.png", "image/png")) }
        vm.addAttachment(file("p2.png", "image/png"))
        assertEquals(AttachmentRefusal.TooMany("p2.png", 2), vm.attachmentRefused.value)
        vm.dismissAttachmentRefusal()
        vm.addAttachment(file("d.pdf", "application/pdf"))
        assertEquals(3, vm.attachedFiles.value.size, "documents have their own count")
        assertNull(vm.attachmentRefused.value)
    }

    @Test
    fun theSizeIsThePolicysPerKindLimit() = runBlocking {
        val vm = vm(agent(200 to agentPolicy.replace("\"document_max_bytes\":10485760", "\"document_max_bytes\":1000")))
        vm.loadAttachmentPolicy()
        vm.addAttachment(file("big.pdf", "application/pdf", size = 2000))
        assertEquals(AttachmentRefusal.TooLarge(PickTooLarge("big.pdf", 2000, 1000)), vm.attachmentRefused.value)
        assertTrue(vm.attachedFiles.value.isEmpty())
    }

    @Test
    fun heicIsRefusedLocallyUnderTheLivePolicyToo() = runBlocking {
        val vm = vm(agent(200 to agentPolicy))
        vm.loadAttachmentPolicy()
        vm.addAttachment(file("IMG.HEIC", "image/heic"))
        assertEquals(AttachmentRefusal.UnsupportedType("IMG.HEIC", "image/heic"), vm.attachmentRefused.value)
    }

    // --- per-file verdicts on the SENT message ---

    private fun sendAll(interact: String, files: List<PickedFile>) = runBlocking {
        val a = agent(200 to agentPolicy, interact)
        val vm = vm(a)
        vm.loadAttachmentPolicy()
        files.forEach { vm.addAttachment(it) }
        vm.onInputTextChanged("look")
        vm.sendMessage()
        vm.settled()
        vm.messages.value.single { it.type == MessageType.USER }
    }

    private val files = listOf(
        file("photo.png", "image/png"),
        file("scan.pdf", "application/pdf"),
        file("notes.txt", "text/plain"),
    )

    private fun interactBody(outcome: String) = """{"data":{"message_id":"m1","response":"ok","state":"WORK","processing_time_ms":5,
        "outcome":"$outcome","attachments":[
          {"index":0,"kind":"image","filename":"photo.png","media_type":"image/png","status":"accepted","size_bytes":3},
          {"index":0,"kind":"document","filename":"scan.pdf","media_type":"application/pdf","status":"refused","reason":"no_text","size_bytes":3},
          {"index":1,"kind":"document","filename":"notes.txt","media_type":"text/plain","status":"accepted","extracted_chars":50000,"truncated":true}
        ]}}"""

    @Test
    fun eachVerdictLandsOnItsOwnChipOfTheSentMessage() {
        val sent = sendAll(interactBody("complete"), files).sentAttachments
        assertEquals(listOf("photo.png", "scan.pdf", "notes.txt"), sent.map { it.name })
        assertEquals(listOf("accepted", "refused", "accepted"), sent.map { it.outcome?.status })
        assertEquals("no_text", sent[1].outcome?.reason)
        assertTrue(sent[2].outcome!!.truncated)
    }

    @Test
    fun verdictsOnATimeoutResponseAreReadToo() {
        val sent = sendAll(interactBody("timeout"), files).sentAttachments
        assertEquals("no_text", sent[1].outcome?.reason)
    }

    @Test
    fun anAgentThatReportsNoVerdictsLeavesTheChipsWithoutOne() {
        val sent = sendAll("""{"data":{"message_id":"m1","response":"ok","state":"WORK","processing_time_ms":5}}""", files).sentAttachments
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.outcome == null })
    }
}
