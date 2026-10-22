package ai.ciris.mobile.shared.platform

import java.awt.EventQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * EVERY REGISTERED CLICK HANDLER RUNS ON THE UI THREAD (release gate
 * 39706713027, windows-ai).
 *
 * The desktop test server answers `/click` from a Ktor worker and used to call
 * the handler right there:
 *
 *   click 'input_message' did not succeed (POST /click -> HTTP 500:
 *   {"error": "Detected multithreaded access to SnapshotStateObserver:
 *   previousThreadId=25), currentThread={id=34, name=DefaultDispatcher-worker-8}
 *
 * A handler is a composable's lambda; the composer's (PR #215) requests focus,
 * which reads snapshot state the EDT owns. The shared handler (iOS, Android)
 * already hops to `Dispatchers.Main`; desktop's server bypassed it. The test
 * calls [UiThreadClick.trigger] exactly as the server does: from a thread
 * that is not the EDT.
 */
class UiThreadClickTest {

    private val tag = "btn_ui_thread_probe"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "DefaultDispatcher-worker-test") }

    @AfterTest
    fun tearDown() {
        TestAutomation.unregisterClickHandler(tag)
        worker.shutdownNow()
    }

    @Test
    fun a_click_from_a_server_worker_runs_its_handler_on_the_ui_thread_and_waits_for_it() {
        var ranOnEdt: Boolean? = null
        var finished = false
        TestAutomation.registerClickHandler(tag) {
            ranOnEdt = EventQueue.isDispatchThread()
            Thread.sleep(50)
            finished = true
        }
        val clicked = worker.submit<Boolean> { UiThreadClick.trigger(tag) }.get(10, TimeUnit.SECONDS)
        assertTrue(clicked)
        assertEquals(true, ranOnEdt, "the handler ran on the server's worker, not the EDT")
        assertTrue(finished, "/click answered before its handler finished")
    }

    @Test
    fun a_handler_that_throws_reaches_the_caller_as_its_own_exception() {
        TestAutomation.registerClickHandler(tag) { error("handler blew up") }
        val thrown = assertFailsWith<java.util.concurrent.ExecutionException> {
            worker.submit<Boolean> { UiThreadClick.trigger(tag) }.get(10, TimeUnit.SECONDS)
        }
        assertTrue(thrown.cause is IllegalStateException, "got ${thrown.cause}")
        assertEquals("handler blew up", thrown.cause?.message)
    }

    @Test
    fun an_unregistered_tag_is_still_false() {
        val clicked = worker.submit<Boolean> { UiThreadClick.trigger("btn_nobody_home") }.get(10, TimeUnit.SECONDS)
        assertEquals(false, clicked)
    }
}
