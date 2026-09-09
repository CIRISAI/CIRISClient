package ai.ciris.mobile.shared.api

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **An operator's answer outranks our inference** (CIRISClient#52).
 *
 * `LOCAL_NODE_URL` has two writers and they are not equals:
 *
 *   * the platform entry point, from `CIRIS_NODE_URL` or a CLI flag — the
 *     operator saying where their node is, *including its port*;
 *   * the run-without-AI hand-off, which resolves the node from
 *     `NODE_ONLY_ENDPOINT` and therefore always names the **4243 default**.
 *
 * Before the split there was one setter and the hand-off won, so an operator
 * running on :9999 was silently moved to :4243 the moment setup completed —
 * one unreachable address swapped for another, with the log line saying only
 * that the node "is" somewhere it is not.
 *
 * These cases pin the precedence in both directions, because a rule that only
 * protects the operator would also stop the hand-off correcting a DEFAULT, and
 * correcting the default is the entire point of the hand-off.
 */
class LocalNodeUrlAuthorityTest {

    private lateinit var original: String

    @BeforeTest
    fun capture() {
        original = CIRISApiClient.LOCAL_NODE_URL
    }

    @AfterTest
    fun restore() {
        // The companion is process-global; leaving it moved would make whichever
        // test ran next fail for a reason that had nothing to do with it.
        CIRISApiClient.setLocalNodeUrl(original)
    }

    @Test
    fun anInferredMoveCorrectsADefaultedAddress() {
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:4243")
        CIRISApiClient.setInferredLocalNodeUrl("http://127.0.0.1:4243")
        assertEquals("http://127.0.0.1:4243", CIRISApiClient.LOCAL_NODE_URL)
        assertFalse(
            CIRISApiClient.localNodeUrlIsExplicit,
            "a defaulted address must stay correctable by the hand-off",
        )
    }

    @Test
    fun anInferredMoveMustNotOverruleAnOperatorsCustomPort() {
        // THE #52 CASE. The operator named :9999; the hand-off wants :4243.
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:9999", explicit = true)
        CIRISApiClient.setInferredLocalNodeUrl("http://127.0.0.1:4243")
        assertEquals(
            "http://127.0.0.1:9999",
            CIRISApiClient.LOCAL_NODE_URL,
            "the hand-off moved a node the operator had placed deliberately",
        )
    }

    @Test
    fun anOperatorMayStillMoveItAfterwards() {
        // Explicit does not mean frozen — it means "not by inference". A later
        // declaration is another operator answer and must land.
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:9999", explicit = true)
        CIRISApiClient.setLocalNodeUrl("http://127.0.0.1:8888", explicit = true)
        assertEquals("http://127.0.0.1:8888", CIRISApiClient.LOCAL_NODE_URL)
        assertTrue(CIRISApiClient.localNodeUrlIsExplicit)
    }

    @Test
    fun trailingSlashesAndPaddingDoNotCreateADifferentNode() {
        // Two spellings of one address would make every "is this the node?"
        // comparison in the client answer no.
        CIRISApiClient.setLocalNodeUrl("  http://127.0.0.1:4243/  ")
        assertEquals("http://127.0.0.1:4243", CIRISApiClient.LOCAL_NODE_URL)
    }
}
