package ai.ciris.mobile.shared.platform

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A restart waits for the ports, not the pid (CIRISClient#40).
 *
 * The failure this guards is silent from the user's side: reset, set up
 * again, and the new backend dies on "Edge transport ports are held by another
 * process" — a message about a thing the user never heard of, caused by the
 * backend they just stopped taking two more seconds to let go.
 */
class PortReleaseTest {

    /** A clock and a sleep that agree with each other, so the loop is deterministic. */
    private class FakeTime {
        var t = 0L
        val now: () -> Long = { t }
        val sleep: (Long) -> Unit = { t += it }
    }

    @Test
    fun free_ports_return_at_once() {
        val ft = FakeTime()
        var probes = 0
        val held = PortRelease.awaitFree(listOf(8080, 4243, 4242), 15_000, isFree = { probes++; true }, now = ft.now, sleep = ft.sleep)
        assertTrue(held.isEmpty())
        assertEquals(3, probes, "one probe per port, no sleeping")
        assertEquals(0L, ft.t)
    }

    @Test
    fun a_port_released_after_the_pid_died_is_waited_for() {
        // THE CASE. SIGKILL returned, the pid is gone, :4242 takes ~2s to clear.
        val ft = FakeTime()
        val held = PortRelease.awaitFree(
            listOf(8080, 4243, 4242), 15_000, pollMs = 200,
            isFree = { port -> port != 4242 || ft.t >= 2_000 },
            now = ft.now, sleep = ft.sleep,
        )
        assertTrue(held.isEmpty(), "must wait through the release, not report the pid's death as done")
        assertEquals(2_000L, ft.t)
    }

    @Test
    fun a_port_never_released_is_reported_not_thrown() {
        // Nobody catches on a shutdown path, so the answer is a value.
        val ft = FakeTime()
        val held = PortRelease.awaitFree(listOf(8080, 4242), 3_000, pollMs = 500, isFree = { it != 4242 }, now = ft.now, sleep = ft.sleep)
        assertEquals(listOf(4242), held)
        assertTrue(ft.t >= 3_000L, "the full timeout is spent before giving up")
    }

    @Test
    fun the_probe_is_a_real_bind() {
        // Bind an ephemeral port ourselves: it must read as held while we hold
        // it and as free the moment we let go. This is the only probe that
        // cannot be fooled by a process that is dead but whose socket is not.
        val sock = ServerSocket()
        sock.bind(InetSocketAddress("127.0.0.1", 0))
        val port = sock.localPort
        try {
            assertFalse(PortRelease.isFree(port), "a bound port must not read as free")
        } finally {
            sock.close()
        }
        assertTrue(PortRelease.isFree(port), "a closed port must read as free")
    }
}
