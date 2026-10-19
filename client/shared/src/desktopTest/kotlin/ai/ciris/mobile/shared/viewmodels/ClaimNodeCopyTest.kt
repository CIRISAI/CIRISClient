package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.localization.LocalizationHelper
import ai.ciris.mobile.shared.models.NodeProfile
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The Claim a node flow sends nobody to a console** (CIRISClient#184 item 3).
 *
 * The claim PIN is the node's claim_pin FILE; no console carries it since
 * ciris-server 0.5.227. The Claim a node screen's own copy was fixed in #198,
 * but the refusals it renders came from [NodeSwitcherViewModel.claimAdmin] as
 * hard-coded English that still said "the one-time PIN shown on the node's
 * console". They render by id now, through the bundle.
 */
class ClaimNodeCopyTest {

    private var savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL

    // A view model launches on viewModelScope from init and from its acts.
    // Left running past resetMain(), a call that comes back resumes with no
    // test Main and throws outside any test; kotlinx-coroutines-test then pins
    // it on the NEXT runTest (PR #203: PortalConnectTest failed with
    // UncaughtExceptionsBeforeTest). Every view model made here is stopped, and waited for, first (stopViewModels).
    private val viewModels = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL
        // The view model reloads owned-nodes on init: point it at nothing.
        CIRISApiClient.setLocalNodeUrl(CLOSED)
    }

    @AfterTest fun tearDown() {
        CIRISApiClient.setLocalNodeUrl(savedNodeUrl)
        stopViewModels(viewModels)
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun aBlankPinAsksForTheClaimPinFileNotAConsole() {
        val vm = NodeSwitcherViewModel(CIRISApiClient(CLOSED, null)).also { viewModels += it }
        val pinned = NodeProfile(
            id = "n1",
            name = "n1",
            baseUrl = CLOSED,
            pinnedKeyId = "node-key",
            pinnedPubkeyBase64 = "AAAA",
        )
        vm.claimAdmin(pinned, claimPin = "  ")
        val error = vm.bootstrap.value.claimError ?: ""
        assertEquals(LocalizationHelper.getString("mobile.claim_node_err_pin_blank"), error)
        assertTrue(!error.contains("console", ignoreCase = true), "no console carries the PIN: $error")
    }

    /** No string literal in the claim flow's view model or screen mentions a console. */
    @Test
    fun theClaimFlowSourceCarriesNoConsoleCopy() {
        val files = listOf(
            "src/commonMain/kotlin/ai/ciris/mobile/shared/viewmodels/NodeSwitcherViewModel.kt",
            "src/commonMain/kotlin/ai/ciris/mobile/shared/ui/screens/ClaimNodeScreen.kt",
        ).map { File(it) }
        assertTrue(files.all { it.isFile }, "sources not found from ${File(".").absolutePath}")
        val literal = Regex("\"[^\"]*console[^\"]*\"", RegexOption.IGNORE_CASE)
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (literal.containsMatchIn(code)) "${f.path}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue(hits.isEmpty(), "claim copy still sends someone to a console:\n" + hits.joinToString("\n"))
    }

    /** Every key the flow renders resolves in en.json, and none says "console". */
    @Test
    fun theClaimFailureKeysResolveAndSayNothingAboutAConsole() {
        val en = kotlinx.serialization.json.Json.parseToJsonElement(
            File("src/desktopMain/resources/localization/en.json").readText(),
        ) as kotlinx.serialization.json.JsonObject
        val mobile = en["mobile"] as kotlinx.serialization.json.JsonObject
        for (key in CLAIM_KEYS) {
            val text = (mobile[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            assertTrue(text != null, "mobile.$key does not resolve in en.json")
            assertTrue(!text.contains("console", ignoreCase = true), "mobile.$key sends someone to a console: $text")
        }
    }

    private companion object {
        const val CLOSED = "http://127.0.0.1:1"
        val CLAIM_KEYS = listOf(
            "claim_node_err_pin_blank",
            "claim_node_err_pin_rejected",
            "claim_node_err_already_claimed",
        )
    }
}
