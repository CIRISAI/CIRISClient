package ai.ciris.mobile.shared.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wiring, tested — because the wiring is what was missing.
 *
 * BackendSupervisor shipped in 0.5.196 with 18 tests, wired to lifecycle on
 * three platforms, and no screen read its state. It was correct and
 * disconnected, which from a user's side is indistinguishable from absent.
 * These assert the connection itself.
 */
class BackendStatusTest {

    private class Fake(var host: HostLiveness = HostLiveness.DEAD) : LocalBackendController {
        override val canRevive = true
        override suspend fun hostLiveness() = host
        override suspend fun revive(): Result<Unit> = Result.failure(RuntimeException("boom"))
    }

    @AfterTest fun cleanup() = BackendStatus.reset()

    @Test
    fun with_nobody_watching_the_ui_is_told_nothing() {
        BackendStatus.reset()
        assertNull(
            BackendStatus.state.value,
            "an un-instrumented build must not invent a status; null is 'nobody is watching', " +
                "which is neither healthy nor broken",
        )
    }

    @Test
    fun an_installed_supervisor_reaches_the_ui() = runTest {
        var t = 0L
        val sup = BackendSupervisor(
            probe = { ProbeOutcome.REFUSED },
            controller = Fake(),
            ownership = { Ownership.OURS },
            now = { t },
        )
        BackendStatus.install(sup, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        sup.onResumed()
        sup.tick()
        testScheduler.advanceUntilIdle()

        val seen = BackendStatus.state.value
        assertIs<BackendState>(seen, "the UI must see what the supervisor decided")
        // A dead host short-circuits patience: revive is requested, which
        // reopens a wait. Either way the UI now has a real state to render,
        // which is the whole point of this test.
        assertTrue(
            seen is BackendState.Thawing || seen is BackendState.Reviving || seen is BackendState.Down,
            "expected a live verdict from the supervisor, got $seen",
        )
    }

    @Test
    fun the_real_reason_survives_the_trip_to_the_ui() = runTest {
        var t = 0L
        val sup = BackendSupervisor(
            probe = { ProbeOutcome.REFUSED },
            controller = object : LocalBackendController {
                override val canRevive = true
                override suspend fun hostLiveness() = HostLiveness.DEAD
                override suspend fun revive() =
                    Result.failure<Unit>(IllegalStateException("ModuleNotFoundError: pydantic_core"))
            },
            ownership = { Ownership.OURS },
            policy = RevivePolicy(maxAttempts = 2),
            now = { t },
        )
        BackendStatus.install(sup, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        sup.onResumed()
        repeat(10) { t += 30_000; sup.tick() }
        testScheduler.advanceUntilIdle()

        val notice = noticeFor(BackendStatus.state.value!!)
        assertEquals(
            "ModuleNotFoundError: pydantic_core", notice.detail,
            "the string the service used to bury in a notification must reach the screen",
        )
        assertEquals(BackendAction.Retry, notice.action, "and it must not be a dead end")
    }

    @Test
    fun installing_twice_does_not_double_subscribe() = runTest {
        val sup = BackendSupervisor(
            probe = { ProbeOutcome.ANSWERED },
            controller = Fake(),
            ownership = { Ownership.OURS },
            now = { 0L },
        )
        // Both on the SAME eager scope: the guard makes the second a no-op, so
        // if the first were dropped there would be no subscriber at all — which
        // is exactly what this asserts against.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        BackendStatus.install(sup, scope)
        BackendStatus.install(sup, scope)
        sup.tick()
        testScheduler.advanceUntilIdle()
        assertEquals(
            BackendState.Live, BackendStatus.state.value,
            "a second install must not detach the first collector",
        )
    }
}
