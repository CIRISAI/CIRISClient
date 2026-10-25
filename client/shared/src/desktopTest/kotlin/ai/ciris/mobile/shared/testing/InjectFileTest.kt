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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/inject-file` ON EVERY PLATFORM (CIRISClient#222).
 *
 * iOS and Android had no route, so csd_010_interact_files' `attach:` could run
 * only on desktop, and desktop answered success 200 ms after posting whether or
 * not a chat screen took the file. Every server now answers through
 * [TestAutomationHandler.handleInjectFile]. The "chat screen" here is a
 * collector shaped like `InteractViewModel`'s file-injection observer: take
 * the file, then clear the request.
 */
class InjectFileTest {

    private val taken = CopyOnWriteArrayList<String>()
    private var scope: CoroutineScope? = null

    private fun aChatScreen(takeAfterMs: Long = 0) {
        scope = CoroutineScope(Dispatchers.Default + Job()).also {
            it.launch {
                TestAutomation.fileInjectionRequests.collect { f ->
                    if (f != null) {
                        delay(takeAfterMs)
                        taken += f.name
                        TestAutomation.clearFileInjectionRequest()
                    }
                }
            }
        }
    }

    private fun file(name: String, size: Long = 3) = InjectFileRequest(name, "application/pdf", "AAAA", size)

    @BeforeTest fun setUp() { TestAutomation.clearFileInjectionRequest() }
    @AfterTest fun tearDown() { scope?.cancel(); TestAutomation.clearFileInjectionRequest() }

    @Test
    fun a_file_the_chat_screen_takes_is_success() = runBlocking {
        aChatScreen()
        val r = TestAutomationHandler.handleInjectFile(file("notes.pdf"))
        assertTrue(r.success, r.error)
        assertEquals(listOf("notes.pdf"), taken.toList())
    }

    @Test
    fun with_no_chat_screen_open_it_says_so_and_leaves_nothing_behind() = runBlocking {
        val r = TestAutomationHandler.handleInjectFile(file("notes.pdf"))
        assertFalse(r.success, "a file nothing took must not report success")
        assertTrue((r.error ?: "").contains("nothing took"), r.error ?: "")
        assertNull(TestAutomation.fileInjectionRequests.value, "an untaken file must not wait for the next chat screen")
    }

    @Test
    fun a_file_over_the_paperclip_ceiling_is_refused_before_it_is_posted() = runBlocking {
        aChatScreen()
        val r = TestAutomationHandler.handleInjectFile(file("big.pdf", TestAutomationHandler.INJECT_MAX_BYTES + 1))
        assertFalse(r.success)
        assertTrue(taken.isEmpty())
    }

    @Test
    fun three_files_back_to_back_on_a_slow_screen_are_all_taken_in_order() = runBlocking {
        aChatScreen(takeAfterMs = 300)
        for (n in listOf("notes.pdf", "notes.docx", "swatch.png")) {
            assertTrue(TestAutomationHandler.handleInjectFile(file(n)).success)
        }
        assertEquals(listOf("notes.pdf", "notes.docx", "swatch.png"), taken.toList())
    }
}
