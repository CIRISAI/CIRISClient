package ai.ciris.mobile.shared.testing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * `/tree` IS READ WHILE THE UI WRITES (release run 40260542326, ios-ai:
 * `GET /tree -> HTTP 500 ConcurrentModificationException`).
 *
 * The iOS and Android servers answer from their own threads while Compose
 * registers, moves and removes elements on the UI thread. One thread here does
 * what composition does — register, add a click handler, a scrollable, remove
 * — as fast as it can, while another does what `/tree`, `/click` and `/scroll`
 * read. Any exception on the reader fails the test.
 */
class TestAutomationStateConcurrencyTest {

    @AfterTest fun tearDown() { TestAutomationState.clearElements() }

    @Test
    fun reading_the_registry_while_the_ui_writes_it_never_throws() {
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val writer = thread(name = "ui") {
            start.await()
            var i = 0
            while (running.get()) {
                val tag = "probe_${i % 200}"
                TestAutomationState.registerElement(tag, i % 50, i % 70, 10, 10, null)
                TestAutomationState.registerClickHandler(tag) {}
                val token = TestAutomationState.registerScrollable("s$i")
                TestAutomationState.setScrollableCapacity(token, i % 3)
                if (i % 3 == 0) TestAutomationState.unregisterElement("probe_${(i + 100) % 200}")
                TestAutomationState.unregisterScrollable(token)
                i++
            }
        }
        val reader = thread(name = "server") {
            start.await()
            try {
                repeat(20_000) {
                    TestAutomationState.getAllElements().values.count { e -> e.visible }
                    TestAutomationState.onScreenDrivable()
                    TestAutomationState.hasClickHandler("probe_7")
                    TestAutomationState.isActiveScrollable(1L, "s3")
                    TestAutomationState.hasScrollable()
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        start.countDown()
        reader.join(60_000)
        running.set(false)
        writer.join(10_000)
        assertNull(failure.get(), "the server's read threw while the UI wrote: ${failure.get()}")
    }
}
