package ai.ciris.mobile.shared.testing

import ai.ciris.mobile.shared.platform.TestAutomation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BACK-TO-BACK `/input` ON A SLOW FIRST FRAME KEEPS EVERY VALUE (CIRISClient#240).
 *
 * CIRISAgent run 40192130788, macOS desktop, cold first launch: the driver
 * typed username, password and confirm back to back. Desktop posted each into
 * the conflating request flow, slept a fixed 100 ms and answered success; the
 * UI thread had not collected the username when the password replaced it, and
 * the wizard sat on "Username is required". Desktop now answers through the
 * shared [TestAutomationHandler.applyInput]. The "screen" here collects only
 * after [COLLECT_MS], as that first launch did.
 */
class InputApplyOrderTest {

    private val user = "input_apply_probe_username"
    private val pass = "input_apply_probe_password"
    private val applied = CopyOnWriteArrayList<Pair<String, String>>()
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    @BeforeTest
    fun setUp() {
        TestAutomation.clearTextInputRequest()
        TestAutomation.registerInputSink(user)
        TestAutomation.registerInputSink(pass)
        scope.launch {
            TestAutomation.textInputRequests.collect { req ->
                if (req != null) {
                    delay(COLLECT_MS)
                    // A collector reads the request when it finally runs, as a
                    // real screen's LaunchedEffect does.
                    TestAutomation.textInputRequests.value?.let { applied += it.testTag to it.text }
                    TestAutomation.clearTextInputRequest()
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        TestAutomation.unregisterInputSink(user)
        TestAutomation.unregisterInputSink(pass)
        TestAutomation.clearTextInputRequest()
    }

    @Test
    fun username_then_password_back_to_back_both_land_in_order() = runBlocking {
        val a = TestAutomationHandler.applyInput(user, "qaadmin", clearFirst = true)
        val b = TestAutomationHandler.applyInput(pass, "pw-1", clearFirst = true)
        assertTrue(a.success && b.success, "${a.error} / ${b.error}")
        assertEquals(listOf(user to "qaadmin", pass to "pw-1"), applied.toList())
    }

    /** The old desktop path, kept to show the test sees the loss it guards against. */
    @Test
    fun the_old_post_and_sleep_path_loses_the_username() = runBlocking {
        TestAutomation.requestTextInput(user, "qaadmin", true); delay(100)
        TestAutomation.requestTextInput(pass, "pw-1", true); delay(100)
        delay(3 * COLLECT_MS)
        assertEquals(listOf(pass to "pw-1"), applied.toList(), "the race did not reproduce")
    }

    private companion object {
        const val COLLECT_MS = 300L
    }
}
