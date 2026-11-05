package ai.ciris.mobile.shared.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExitDeadlineTest {
    @Test
    fun theHaltFiresAtTheDeadlineAndNotBefore() {
        val halted = CountDownLatch(1)
        armExitDeadline(deadlineMs = 200, stacksAfterMs = 100, printStacks = {}, halt = { halted.countDown() })
        assertFalse(halted.await(50, TimeUnit.MILLISECONDS), "the hooks get until the deadline")
        assertTrue(halted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun stacksThatNeverReturnDoNotStopTheHalt() {
        val halted = CountDownLatch(1)
        armExitDeadline(
            deadlineMs = 100,
            stacksAfterMs = 10,
            printStacks = { Thread.sleep(60_000) },
            halt = { halted.countDown() },
        )
        assertTrue(halted.await(2, TimeUnit.SECONDS))
    }
}
