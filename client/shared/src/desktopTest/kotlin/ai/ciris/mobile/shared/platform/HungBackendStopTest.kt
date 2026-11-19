package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.api.CIRISApiClient
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Codex on CIRISClient#316: when Reset's own bound runs out while a health probe
 * is in flight, that cancellation said nothing about the backend, yet it was
 * caught as a failed probe and the backend read as stopped. A real loopback
 * socket that accepts and never answers is a live, hung backend.
 */
class HungBackendStopTest {
    @Test
    fun theBoundRunningOutMidProbeLeavesTheBackendRunning() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { hung ->
            val url = "http://127.0.0.1:${hung.localPort}"
            val api = CIRISApiClient(baseUrl = url)
            // The probe's own timeout is 2 s; the bound is shorter, so it ends mid-probe.
            val r = stopAttachedBackend(listOf(url), { 202 }, api::answersAt, timeoutMs = 1_500, pollMs = 100)
            assertEquals(AttachedStop.StillRunning(listOf(url)), r)
        }
    }
}
