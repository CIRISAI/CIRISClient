package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue
import java.lang.reflect.InvocationTargetException

/**
 * DESKTOP AUTOMATION'S ONLY WAY TO FIRE A REGISTERED CLICK HANDLER: ON THE
 * SWING EDT, AND AWAITED.
 *
 * A handler is a composable's lambda. It writes `remember { mutableStateOf }`
 * state, requests focus, opens sheets: all snapshot state the EDT owns. The
 * test server answers `/click` and `/act` from a Ktor worker and used to call
 * [TestAutomation.triggerClick] right there. Most handlers got away with it;
 * the composer's focus request (PR #215) did not (release gate 39706713027,
 * windows-ai):
 *
 *   POST /click -> HTTP 500: Detected multithreaded access to
 *   SnapshotStateObserver: previousThreadId=25), currentThread={id=34,
 *   name=DefaultDispatcher-worker-8}
 *
 * The shared handler iOS and Android serve from already hops to
 * `Dispatchers.Main` (TestAutomationHandler.handleClick, CIRISClient#30);
 * this is the same rule for desktop's own server. `invokeAndWait`, so the
 * HTTP answer is given after the handler has run, and a handler's exception
 * reaches the caller as itself rather than wrapped.
 *
 * NOT folded into [TestAutomation.triggerClick]: headless desktop tests drive
 * an `ImageComposeScene` on the test thread and call `triggerClick` there,
 * where the test thread IS the scene's UI thread. Moving those handlers to the
 * EDT would create the very cross-thread access this fixes.
 */
object UiThreadClick {
    /**
     * The longest a click waits for the UI to take in what came before it.
     * A bound, not a delay: an idle UI is clicked at once.
     */
    internal var settleTimeoutMs: Long = 1_000L

    fun trigger(testTag: String): Boolean {
        if (EventQueue.isDispatchThread()) return TestAutomation.triggerClick(testTag)
        awaitUiSettled()
        var clicked = false
        try {
            EventQueue.invokeAndWait { clicked = TestAutomation.triggerClick(testTag) }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        return clicked
    }

    /**
     * THE HANDLER IS THE ONE THE LAST RECOMPOSITION REGISTERED (main's gate run
     * 39810667590, macos-ios, client 0.5.230). `testableClickable` hands the
     * registry `{ currentOnClick() }`, and `rememberUpdatedState` swaps in the
     * newest lambda only when the control recomposes. Setup's Test Connection
     * lambda reads `state.llmApiKey` from the `state` it was composed with, so
     * a `/click` that ran after `/input` typed the key but before the frame that
     * recomposed with it checked the provider with an EMPTY key: "Enter an API
     * key to check this provider." Seen on the slower macOS runner only.
     *
     * So before the handler runs: let the EDT drain what is queued ahead of us
     * (a `collectAsState` collector resuming with the typed value), publish
     * pending snapshot writes, and wait until no running [Recomposer] has
     * pending work — bounded by [settleTimeoutMs].
     */
    private fun awaitUiSettled() {
        val deadline = System.nanoTime() + settleTimeoutMs * 1_000_000L
        while (true) {
            var settled = false
            EventQueue.invokeAndWait {
                Snapshot.sendApplyNotifications()
                settled = Recomposer.runningRecomposers.value.none { info ->
                    val state = runBlocking { info.state.first() }
                    state == Recomposer.State.PendingWork || state == Recomposer.State.InactivePendingWork
                }
            }
            if (settled || System.nanoTime() >= deadline) return
            Thread.sleep(4)
        }
    }
}
