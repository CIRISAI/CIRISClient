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

    private class Agent(private val policy: Pair<Int, String>, private val interact: String = "") {
        val bodies: MutableList<String> = CopyOnWriteArrayList()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.use { it.readBytes().decodeToString() }
                val (status, out) = when (ex.requestURI.path) {
                    "/v1/agent/attachments/policy" -> policy
                    "/v1/agent/interact" -> { bodies += body; 200 to interact }
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
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain(); agents.forEach { it.server.stop(0) } }

    private fun agent(policy: Pair<Int, String>, interact: String = "") = Agent(policy, interact).also { agents += it }
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
