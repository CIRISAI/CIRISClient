package ai.ciris.mobile.shared.testing

import ai.ciris.mobile.shared.platform.SensitiveInputs
import ai.ciris.mobile.shared.platform.TestAutomation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A PIN typed by automation is applied and never echoed** (Codex on #154).
 *
 * `/input` stored the submitted text with `setInputValue`, answered it back in
 * `text`, and `/tree` / `/element` exposed it as `inputValue` and mirrored it
 * into `text` — so a YubiKey PIN typed into the final-genesis sheet was
 * readable by anything that could reach the automation port. A sensitive sink
 * applies the text and acknowledges it, and nothing else ever holds it.
 *
 * Shared handler, so this is what Android and iOS serve; desktop's own
 * `/input` and `/act` read the same [SensitiveInputs] registry.
 */
class SensitiveInputTest {

    private val pin = "input_sensitive_probe_pin"
    private val secret = "4815162342"

    @BeforeTest
    fun setup() {
        TestAutomationState.clearElements()
        TestAutomationState.registerElement(pin, 10, 20, 100, 40, null)
        TestAutomation.registerInputSink(pin)
        SensitiveInputs.mark(pin)
    }

    @AfterTest
    fun cleanup() {
        TestAutomationState.clearElements()
        TestAutomation.unregisterInputSink(pin)
        SensitiveInputs.unmark(pin)
    }

    @Test
    fun aSensitiveInputIsAppliedAcknowledgedAndNeverEchoed() = runTest {
        var applied: String? = null
        val collector = CoroutineScope(UnconfinedTestDispatcher(testScheduler)).launch {
            TestAutomation.textInputRequests.collect { req ->
                if (req != null) {
                    applied = req.text
                    TestAutomation.clearTextInputRequest()
                }
            }
        }
        val r = TestAutomationHandler.handleInput(InputRequest(pin, secret, clearFirst = true))
        collector.cancel()

        assertTrue(r.success, r.error ?: "")
        assertEquals(secret, applied, "the field still receives it")
        assertNull(r.text, "the acknowledgement does not echo it")
        assertNull(TestAutomation.inputValue(pin), "nothing stores it")
        val element = TestAutomationHandler.handleGetElement(pin)!!
        assertNull(element.inputValue)
        assertFalse((element.text ?: "").contains(secret))
        val tree = TestAutomationHandler.getJson().encodeToString(TreeResponse.serializer(), TestAutomationHandler.handleTree())
        assertTrue(pin in tree, "the field is present on /tree")
        assertFalse(secret in tree, "and its value is not")
    }

    @Test
    fun aValueReportedForASensitiveFieldIsNotHeld() {
        // A field reporting through the ordinary path (setInputValue) by mistake
        // still does not surface on /element.
        TestAutomation.setInputValue(pin, secret)
        assertNull(TestAutomationHandler.handleGetElement(pin)?.inputValue)
        assertFalse((TestAutomationHandler.handleGetElement(pin)?.text ?: "").contains(secret))
    }

    @Test
    fun anOrdinaryFieldIsStillReadBack() = runTest {
        SensitiveInputs.unmark(pin)
        val collector = CoroutineScope(UnconfinedTestDispatcher(testScheduler)).launch {
            TestAutomation.textInputRequests.collect { req -> if (req != null) TestAutomation.clearTextInputRequest() }
        }
        val r = TestAutomationHandler.handleInput(InputRequest(pin, "qaadmin", clearFirst = true))
        collector.cancel()
        assertEquals("qaadmin", r.text)
        assertEquals("qaadmin", TestAutomationHandler.handleGetElement(pin)?.inputValue)
    }
}
