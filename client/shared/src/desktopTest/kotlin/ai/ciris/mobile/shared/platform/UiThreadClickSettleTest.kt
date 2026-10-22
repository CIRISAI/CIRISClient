package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.awt.EventQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `/click` AFTER `/input` RUNS THE HANDLER COMPOSED WITH THE INPUT (main's
 * gate run 39810667590, macos-ios, client 0.5.230: Test Connection checked
 * the provider with an EMPTY key the field already held).
 *
 * A real [Recomposer] on the EDT whose frames arrive [FRAME_MS] after they are
 * asked for, as on a slow runner; the probe registers its handler the way
 * `testableClickable` does (`rememberUpdatedState` + a tag-keyed effect) and
 * reports the value its lambda captured.
 */
class UiThreadClickSettleTest {

    private val tag = "btn_settle_probe"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "DefaultDispatcher-worker-test") }
    /** The EDT as a dispatcher: what Compose desktop's own Swing dispatcher is. */
    private object Edt : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = EventQueue.invokeLater(block)
    }
    private val scope = CoroutineScope(Edt + Job())
    private lateinit var clock: BroadcastFrameClock
    private var composition: Composition? = null

    private object UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Composable
    private fun Probe(key: String, report: (String) -> Unit) {
        val onClick: () -> Unit = { report(key) }
        val current by rememberUpdatedState(onClick)
        DisposableEffect(tag) {
            TestAutomation.registerClickHandler(tag) { current() }
            onDispose { TestAutomation.unregisterClickHandler(tag) }
        }
    }

    private fun compose(typed: MutableStateFlow<String>, report: (String) -> Unit) {
        clock = BroadcastFrameClock {
            javax.swing.Timer(FRAME_MS) { clock.sendFrame(System.nanoTime()) }.apply { isRepeats = false; start() }
        }
        val recomposer = Recomposer(scope.coroutineContext + clock)
        scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        EventQueue.invokeAndWait {
            composition = Composition(UnitApplier, recomposer).apply {
                setContent { val key by typed.collectAsState(); Probe(key, report) }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        EventQueue.invokeAndWait { composition?.dispose() }
        scope.cancel()
        TestAutomation.unregisterClickHandler(tag)
        UiThreadClick.settleTimeoutMs = 1_000L
        worker.shutdownNow()
    }

    private fun typeThenClick(typed: MutableStateFlow<String>, value: String): Boolean =
        worker.submit<Boolean> { typed.value = value; UiThreadClick.trigger(tag) }.get(10, TimeUnit.SECONDS)

    @Test
    fun a_click_right_after_input_sees_the_typed_value() {
        val typed = MutableStateFlow("")
        var seen: String? = null
        compose(typed) { seen = it }

        // The race is real: with no settle, the handler composed before the
        // input runs and reports the empty key.
        UiThreadClick.settleTimeoutMs = 0L
        typeThenClick(typed, "sk-first")
        assertEquals("", seen, "the race did not reproduce; this test proves nothing")

        // With the settle, the click waits for the frame that composed the key.
        Thread.sleep(3 * FRAME_MS.toLong())
        UiThreadClick.settleTimeoutMs = 2_000L
        typeThenClick(typed, "sk-second")
        assertEquals("sk-second", seen)
    }

    @Test
    fun an_idle_ui_is_clicked_without_waiting() {
        val typed = MutableStateFlow("k")
        var seen: String? = null
        compose(typed) { seen = it }
        Thread.sleep(3 * FRAME_MS.toLong())
        val started = System.nanoTime()
        assert(worker.submit<Boolean> { UiThreadClick.trigger(tag) }.get(10, TimeUnit.SECONDS))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("k", seen)
        kotlin.test.assertTrue(tookMs < FRAME_MS, "an idle UI waited ${tookMs}ms")
    }

    private companion object {
        const val FRAME_MS = 150
    }
}
