package ai.ciris.mobile.shared.platform

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
    fun trigger(testTag: String): Boolean {
        if (EventQueue.isDispatchThread()) return TestAutomation.triggerClick(testTag)
        var clicked = false
        try {
            EventQueue.invokeAndWait { clicked = TestAutomation.triggerClick(testTag) }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        return clicked
    }
}
