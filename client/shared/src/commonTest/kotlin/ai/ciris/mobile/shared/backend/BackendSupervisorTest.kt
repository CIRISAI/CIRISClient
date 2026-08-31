package ai.ciris.mobile.shared.backend

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The resume/restart contract.
 *
 * Two of these tests are the field bugs written down: [ios_does_not_restart_a_backend_that_is_merely_thawing]
 * is the iOS cold boot on every resume, and [android_recovers_a_service_that_stopped_itself]
 * is the Android backend that stayed dead until relaunch. If either regresses,
 * that platform's failure is back.
 *
 * No real time passes here. The clock is a variable and the supervisor is
 * tick-driven, so a 30-second budget and a 60-second sustained-health window
 * are asserted in microseconds.
 */
class BackendSupervisorTest {

    /** Scriptable host + probe, so each test states its scenario as data. */
    private class Fake(
        var host: HostLiveness = HostLiveness.ALIVE,
        private val revivable: Boolean = true,
        var reviveResult: () -> Result<Unit> = { Result.success(Unit) },
    ) : LocalBackendController {
        var reviveCalls = 0
        override val canRevive: Boolean get() = revivable
        override suspend fun hostLiveness() = host
        override suspend fun revive(): Result<Unit> {
            reviveCalls++
            return reviveResult()
        }
    }

    private class Clock(var t: Long = 0) { fun now() = t; fun advance(ms: Long) { t += ms } }

    private fun supervisor(
        fake: Fake,
        clock: Clock,
        outcome: () -> ProbeOutcome,
        ownership: Ownership = Ownership.OURS,
        policy: RevivePolicy = RevivePolicy(),
    ) = BackendSupervisor(
        probe = { outcome() },
        controller = fake,
        ownership = { ownership },
        policy = policy,
        now = clock::now,
    )

    // ------------------------------------------------------------------
    // The iOS bug
    // ------------------------------------------------------------------

    @Test
    fun ios_does_not_restart_a_backend_that_is_merely_thawing() = runTest {
        // The exact iOS scenario: app resumed, interpreter still thawing, so
        // the socket does not answer yet — but the runtime thread IS executing.
        val fake = Fake(host = HostLiveness.ALIVE)
        val clock = Clock()
        var outcome = ProbeOutcome.TIMEOUT
        val sup = supervisor(fake, clock, { outcome })

        sup.onResumed()

        // Well past the 2s deadline that shipped in PythonBridge.swift.
        repeat(20) { clock.advance(1_000); sup.tick() }

        assertIs<BackendState.Thawing>(sup.state.value, "a live host that has not answered is THAWING")
        assertEquals(0, fake.reviveCalls, "iOS cold-booted here on every resume; it must not")

        // And it wakes on its own, which is the whole point of waiting.
        outcome = ProbeOutcome.ANSWERED
        sup.tick()
        assertEquals(BackendState.Live, sup.state.value)
        assertEquals(0, fake.reviveCalls, "resumed without a restart")
    }

    @Test
    fun a_finished_runtime_thread_skips_the_wait_entirely() = runTest {
        // The branch PythonBridge.swift logged and never took: isFinished means
        // dead, and waiting 30s for a dead thread only delays the fix.
        val fake = Fake(host = HostLiveness.DEAD)
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.TIMEOUT })

        sup.onResumed()
        sup.tick()

        assertEquals(1, fake.reviveCalls, "positive evidence of death must short-circuit patience")
    }

    // ------------------------------------------------------------------
    // The Android bug
    // ------------------------------------------------------------------

    @Test
    fun android_recovers_a_service_that_stopped_itself() = runTest {
        // stopSelf() after the 3-minute background timeout. START_STICKY cannot
        // fire for this, and nothing else was watching.
        val fake = Fake(host = HostLiveness.DEAD)
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED })

        sup.onResumed()
        sup.tick()

        assertTrue(fake.reviveCalls > 0, "the service stopped itself; nothing else will bring it back")
    }

    @Test
    fun a_refused_connection_on_an_unknown_host_is_death_not_patience() = runTest {
        // Desktop/wasm-shaped: the platform cannot report host liveness, but
        // the socket layer said nothing is listening. That IS evidence.
        val fake = Fake(host = HostLiveness.UNKNOWN)
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED })

        sup.onResumed()
        sup.tick()

        assertEquals(1, fake.reviveCalls)
    }

    // ------------------------------------------------------------------
    // Never revive what is not ours
    // ------------------------------------------------------------------

    @Test
    fun a_remote_node_is_never_restarted_even_when_the_platform_could() = runTest {
        val fake = Fake(host = HostLiveness.DEAD, revivable = true)
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED }, ownership = Ownership.REMOTE)

        sup.onResumed()
        repeat(10) { clock.advance(5_000); sup.tick() }

        assertEquals(BackendState.NotOurs, sup.state.value)
        assertEquals(0, fake.reviveCalls, "someone else's research agent is not ours to restart")
    }

    @Test
    fun airplane_mode_is_not_a_dead_backend() = runTest {
        val fake = Fake(host = HostLiveness.UNKNOWN)
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.TRANSPORT })

        sup.onResumed()
        repeat(10) { clock.advance(10_000); sup.tick() }

        assertIs<BackendState.Unreachable>(sup.state.value)
        assertEquals(0, fake.reviveCalls, "restarting a node does not bring back the Wi-Fi")
    }

    @Test
    fun a_backgrounded_app_does_not_fight_the_deliberate_battery_stop() = runTest {
        val fake = Fake(host = HostLiveness.DEAD)
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED })

        sup.onBackgrounded()
        sup.tick()

        assertEquals(0, fake.reviveCalls, "the 3-minute stop is a feature; reviving here is a battery leak")
    }

    // ------------------------------------------------------------------
    // Crash-loop protection
    // ------------------------------------------------------------------

    @Test
    fun revive_attempts_are_capped_and_then_it_asks_for_a_human() = runTest {
        val fake = Fake(host = HostLiveness.DEAD, reviveResult = { Result.failure(RuntimeException("boom")) })
        val clock = Clock()
        val policy = RevivePolicy(maxAttempts = 3)
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED }, policy = policy)

        sup.onResumed()
        repeat(40) { clock.advance(30_000); sup.tick() }

        assertEquals(3, fake.reviveCalls, "capped")
        val s = sup.state.value
        assertIs<BackendState.GaveUp>(s)
        assertEquals("boom", s.lastError, "the UI needs something specific to say")
    }

    @Test
    fun backoff_grows_between_attempts() = runTest {
        val fake = Fake(host = HostLiveness.DEAD, reviveResult = { Result.failure(RuntimeException("x")) })
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED }, policy = RevivePolicy(maxAttempts = 3))

        sup.onResumed()
        sup.tick()
        assertEquals(1, fake.reviveCalls)

        // Inside the 1s backoff: no second attempt.
        clock.advance(500); sup.tick()
        assertEquals(1, fake.reviveCalls, "backoff must actually hold")

        clock.advance(600); sup.tick()
        assertEquals(2, fake.reviveCalls)
    }

    /**
     * THE CRASH-LOOP TRAP.
     *
     * A backend that dies two seconds after every boot. Resetting the attempt
     * counter on first success — the obvious implementation — would clear it on
     * each brief success, so the cap could never trip and the device would loop
     * until the battery was flat. The guard has to require SUSTAINED health.
     */
    @Test
    fun a_backend_that_dies_right_after_every_boot_still_trips_the_cap() = runTest {
        val fake = Fake(host = HostLiveness.DEAD)
        val clock = Clock()
        var healthy = false
        val policy = RevivePolicy(maxAttempts = 3, sustainedHealthMs = 60_000)
        val sup = supervisor(
            fake, clock,
            { if (healthy) ProbeOutcome.ANSWERED else ProbeOutcome.REFUSED },
            policy = policy,
        )

        sup.onResumed()
        repeat(30) {
            // brief success, then death again — 2s of health, never 60
            healthy = true; clock.advance(2_000); sup.tick()
            healthy = false; clock.advance(30_000); sup.tick()
        }

        assertIs<BackendState.GaveUp>(sup.state.value, "2s of health must not clear the counter")
        assertEquals(3, fake.reviveCalls)
    }

    @Test
    fun sustained_health_does_clear_the_counter() = runTest {
        val fake = Fake(host = HostLiveness.DEAD, reviveResult = { Result.failure(RuntimeException("x")) })
        val clock = Clock()
        var healthy = false
        val policy = RevivePolicy(maxAttempts = 3, sustainedHealthMs = 60_000)
        val sup = supervisor(
            fake, clock,
            { if (healthy) ProbeOutcome.ANSWERED else ProbeOutcome.REFUSED },
            policy = policy,
        )

        sup.onResumed()
        clock.advance(30_000); sup.tick()          // attempt 1
        assertEquals(1, fake.reviveCalls)

        healthy = true
        sup.tick()                                  // live
        clock.advance(90_000); sup.tick()           // sustained past 60s -> reset
        assertEquals(BackendState.Live, sup.state.value)

        healthy = false
        fake.reviveResult = { Result.failure(RuntimeException("x")) }
        clock.advance(30_000); sup.tick()
        assertEquals(2, fake.reviveCalls)
        assertTrue(sup.state.value !is BackendState.GaveUp, "counter was cleared by real health")
    }

    // ------------------------------------------------------------------
    // Honesty of the reported state
    // ------------------------------------------------------------------

    @Test
    fun a_platform_without_a_local_backend_reports_but_never_pretends() = runTest {
        val clock = Clock()
        val sup = BackendSupervisor(
            probe = { ProbeOutcome.REFUSED },
            controller = NoLocalBackend,
            ownership = { Ownership.OURS },
            now = clock::now,
        )

        sup.onResumed()
        clock.advance(60_000); sup.tick()

        assertIs<BackendState.Down>(sup.state.value, "honest about the backend")
        // and no exception from NoLocalBackend.revive(), because it is never called
    }

    @Test
    fun a_requested_revive_is_not_reported_as_success() = runTest {
        // The revive CALL succeeding means we asked, not that the backend
        // answered. Same discipline as everywhere else here.
        val fake = Fake(host = HostLiveness.DEAD)
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED })

        sup.onResumed()
        sup.tick()

        assertEquals(1, fake.reviveCalls)
        assertIs<BackendState.Thawing>(sup.state.value, "asked, not confirmed — the next probe decides")
    }

    // ------------------------------------------------------------------
    // Ownership
    // ------------------------------------------------------------------

    @Test
    fun only_loopback_is_ours_to_restart() {
        assertEquals(Ownership.OURS, ownershipOf("http://127.0.0.1:4243"))
        assertEquals(Ownership.OURS, ownershipOf("http://localhost:8080"))
        assertEquals(Ownership.OURS, ownershipOf("http://0.0.0.0:8080"))
        assertEquals(Ownership.OURS, ownershipOf("HTTP://LOCALHOST:8080"), "case must not decide ownership")
    }

    @Test
    fun a_node_on_the_lan_belongs_to_someone_else() {
        // Same desk, still not ours. A research agent on the next machine is
        // exactly the case that must never be restarted from here.
        assertEquals(Ownership.REMOTE, ownershipOf("http://192.168.1.40:4243"))
        assertEquals(Ownership.REMOTE, ownershipOf("https://node.example.org"))
        assertEquals(Ownership.REMOTE, ownershipOf("http://10.0.0.7:8080"))
    }

    @Test
    fun manual_retry_clears_the_guard() = runTest {
        val fake = Fake(host = HostLiveness.DEAD, reviveResult = { Result.failure(RuntimeException("x")) })
        val clock = Clock()
        val sup = supervisor(fake, clock, { ProbeOutcome.REFUSED }, policy = RevivePolicy(maxAttempts = 2))

        sup.onResumed()
        repeat(20) { clock.advance(30_000); sup.tick() }
        assertIs<BackendState.GaveUp>(sup.state.value)

        sup.retryNow()
        clock.advance(30_000); sup.tick()
        assertTrue(fake.reviveCalls > 2, "a human said try again")
    }
}
