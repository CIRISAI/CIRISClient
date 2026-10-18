package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.viewmodels.SetupViewModel
import com.sun.net.httpserver.HttpServer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The claim PIN is a file on disk, never the log** (CIRISClient#184).
 *
 * Since ciris-server 0.5.227 the boot banner names the claim PIN FILE instead
 * of printing the PIN. The client waits on the file — bounded, re-read every
 * poll — and names the outcome when it never appears; nothing in the client
 * parses a log for a PIN any more.
 */
class ClaimPinFileTest {

    private fun readOf(f: File): suspend () -> String? = { f.takeIf { it.canRead() }?.readText() }

    @Test
    fun aFileThatAppearsLateIsRead() = runBlocking {
        val dir = Files.createTempDirectory("claimpin").toFile()
        val pinFile = File(dir, "claim_pin")
        try {
            // The node writes claim_pin ~3 s after health answers; here, 600 ms
            // after the wait begins.
            val writer = thread { Thread.sleep(600); pinFile.writeText("7F3K-Q9MZ\n") }
            val started = System.nanoTime()
            val pin = ClaimPinFile.await(timeoutMs = 5_000, pollMs = 100, read = readOf(pinFile))
            writer.join()
            assertEquals("7F3K-Q9MZ", pin, "trimmed, read once it appeared")
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 500, "it waited for the file rather than returning early")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFileThatNeverAppearsIsNullAfterTheBoundNotForever() = runBlocking {
        val dir = Files.createTempDirectory("claimpin").toFile()
        try {
            var reads = 0
            val pin = ClaimPinFile.await(timeoutMs = 1_000, pollMs = 100, sleep = {}) {
                reads++
                readOf(File(dir, "claim_pin"))()
            }
            assertNull(pin)
            assertEquals(11, reads, "one read per poll across the window, then stop")
        } finally {
            dir.deleteRecursively()
        }
    }

    private var savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL

    // A view model launches on viewModelScope from init and from its acts.
    // Left running past resetMain(), a call that comes back resumes with no
    // test Main and throws outside any test; kotlinx-coroutines-test then pins
    // it on the NEXT runTest (PR #203: PortalConnectTest failed with
    // UncaughtExceptionsBeforeTest). Every view model made here is cancelled first.
    private val viewModels = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL
    }

    @AfterTest fun tearDown() {
        CIRISApiClient.setLocalNodeUrl(savedNodeUrl)
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun theClaimNamesAPinFileThatNeverAppearedAndSaysNothingAboutAConsole() = runBlocking {
        // A node that mints the fed-ID (so the claim reaches the PIN wait) and
        // never writes claim_pin.
        val node = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.use { it.readBytes() }
                // An UNCLAIMED node (CIRISClient#184 item 3 asks it): no
                // owner-binding, no ROOT. An owned node is OwnedNodeClaimTest's.
                val (status, body) = when (ex.requestURI.path) {
                    "/v1/self/identity" ->
                        200 to """{"key_id":"owner-abc123","fedcode":"CIRIS-V2-TEST","identity_type":"user"}"""
                    "/v1/setup/owned-nodes" -> 200 to """{"owner":null,"nodes":[]}"""
                    "/v1/setup/status" -> 200 to """{"data":{"is_first_run":true,"setup_required":true,"config_exists":false}}"""
                    else -> 404 to ""
                }
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
        val dir = Files.createTempDirectory("claimpin").toFile()
        try {
            val url = "http://127.0.0.1:${node.address.port}"
            CIRISApiClient.setLocalNodeUrl(url)
            val vm = SetupViewModel(CIRISApiClient(url, "setup-tok"), hasAgent = false).also { viewModels += it }
            vm.claimLocalNodeOwnership(claimPinProvider = {
                ClaimPinFile.await(timeoutMs = 300, pollMs = 100, read = readOf(File(dir, "claim_pin")))
            })
            withContext(Dispatchers.Default) {
                withTimeout(15_000) { vm.state.first { !it.ownershipClaim.inProgress } }
            }
            val claim = vm.state.value.ownershipClaim
            assertEquals(ClaimPinFile.PIN_FILE_ABSENT, claim.errorId)
            assertEquals(false, claim.claimed)
            assertTrue(claim.errorRecoverable, "the node can be claimed later; not a dead end")
            assertTrue(!(claim.error ?: "").contains("console", ignoreCase = true), "no console carries the PIN: ${claim.error}")
        } finally {
            node.stop(0)
            dir.deleteRecursively()
        }
    }

    /**
     * NO BANNER PARSING REMAINS. Every main source set of the client is read
     * for the shape of a claim PIN (`XXXX-XXXX` Crockford base32) and for the
     * names the banner capture went by. The one allowed hit is the debug
     * bundle's redaction, which is defence in depth, not capture.
     */
    @Test
    fun noClientSourceParsesALogForTheClaimPin() {
        val roots = listOf("src/commonMain", "src/androidMain", "src/desktopMain", "src/iosMain", "src/wasmJsMain", "../androidApp/src/main", "../iosApp")
            .map { File(it) }.filter { it.isDirectory }
        assertTrue(roots.any { it.path.endsWith("commonMain") }, "source tree not found from ${File(".").absolutePath}")
        val pinShape = Regex("""\[0-9A-HJKMNP-TV-Z]\{4}-""")
        val names = listOf("parseOwnershipBanner", "scanFileLineForOwnership", "isClaimPinCaptured", "localClaimPin", "tailNodeLogsForOwnership", "CLAIM_PIN_REGEX")
        val allowed = setOf("DebugBundle.kt")
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "swift") && it.name !in allowed }
                .flatMap { f ->
                    f.readLines().mapIndexedNotNull { i, line ->
                        val hit = pinShape.containsMatchIn(line) || names.any { line.contains(it) }
                        if (hit) "${f.path}:${i + 1}: ${line.trim()}" else null
                    }
                }.toList()
        }
        assertTrue(hits.isEmpty(), "claim-PIN banner parsing remains:\n" + hits.joinToString("\n"))
    }
}
