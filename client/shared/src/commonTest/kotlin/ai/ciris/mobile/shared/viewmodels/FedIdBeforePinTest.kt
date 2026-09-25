package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE FED-ID FIRST, THE PIN SECOND (CIRISClient#68).
 *
 * The mint used to run after the claim-PIN wait, so a PIN that never arrived
 * cost the owner their federation identity along with the claim. The order is
 * observable without a node: point the client at a closed port and see which
 * of the two it asks for first. The mint fails, and the PIN is never asked
 * for — under the old order the PIN was asked for first, and a missing PIN
 * returned before any mint was attempted.
 */
class FedIdBeforePinTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private var savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL

    // THE MINT GOES TO THE GLOBAL LOCAL NODE, not to this client's address.
    // Left at its default this test POSTed /v1/self/identity to whatever was
    // serving 127.0.0.1:4243 on the machine running it — and minted a real
    // user identity on a developer's live node. Pin the global to a port
    // nothing listens on for the duration, and put it back after.
    @BeforeTest fun setup() {
        Dispatchers.setMain(dispatcher)
        savedNodeUrl = CIRISApiClient.LOCAL_NODE_URL
        CIRISApiClient.setLocalNodeUrl(CLOSED)
    }
    @AfterTest fun tearDown() {
        CIRISApiClient.setLocalNodeUrl(savedNodeUrl)
        Dispatchers.resetMain()
    }

    @Test
    fun theFedIdIsMintedBeforeThePinIsAwaited() = runBlocking {
        // Nothing listens on port 1: the mint's request is refused at once.
        val vm = SetupViewModel(CIRISApiClient(CLOSED, null), hasAgent = true)
        var pinAsked = false

        vm.claimLocalNodeOwnership(claimPinProvider = { pinAsked = true; null })
        withContext(Dispatchers.Default) {
            withTimeout(15_000) { vm.state.first { !it.ownershipClaim.inProgress } }
        }

        assertFalse(pinAsked, "the PIN was awaited before the fed-ID was minted — a missing PIN would cost the identity")
        val error = vm.state.value.ownershipClaim.error ?: ""
        assertTrue(error.contains("federation identity"), "expected the mint's failure, got: $error")
        assertEquals(false, vm.state.value.ownershipClaim.claimed)
    }

    private companion object {
        /** Port 1 on loopback: refused at once, and never a real node. */
        const val CLOSED = "http://127.0.0.1:1"
    }
}
