package ai.ciris.mobile.shared.backend

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE ASSUMPTION EVERYTHING ELSE RESTS ON, TESTED AGAINST REAL SOCKETS.
 *
 * The supervisor is safe to be patient only because a genuinely dead backend is
 * caught by evidence rather than by waiting out the clock, and on JVM platforms
 * that evidence is which exception the probe gets:
 *
 *   ConnectException     -> REFUSED  -> nothing is listening, act now
 *   SocketTimeoutException -> TIMEOUT -> may be thawing, keep waiting
 *
 * Every fake in BackendSupervisorTest takes that mapping as given. If the real
 * socket layer throws something else — and it does differ by JDK, by platform,
 * and between "connection refused" and "accepted then silent" — the whole
 * evidence model degrades to a 30-second wait on a dead node, silently. Fakes
 * cannot catch that. This binds real ports and finds out.
 */
class ProbeContractTest {

    /** A port nothing is listening on: bind it, learn the number, release it. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun nothing_listening_is_REFUSED_not_a_timeout() = runBlocking {
        val url = "http://127.0.0.1:${deadPort()}"
        val outcome = DesktopBackendController.probe(url, timeoutMs = 2_000)
        assertEquals(
            ProbeOutcome.REFUSED, outcome,
            "a closed port must be positive evidence of death — if this is TIMEOUT, " +
                "every dead backend waits out the full thaw budget before recovering",
        )
    }

    @Test
    fun a_socket_that_accepts_and_never_answers_is_a_TIMEOUT() {
        // The thawing signature: something holds the port but cannot answer yet.
        // Must NOT be read as death, or we restart a backend that is waking up —
        // the iOS bug, reproduced at the socket layer.
        val server = ServerSocket(0)
        val port = server.localPort
        val accepter = Thread {
            runCatching {
                val s = server.accept()
                Thread.sleep(10_000)   // accept, then say nothing
                s.close()
            }
        }.apply { isDaemon = true; start() }

        try {
            val outcome = runBlocking {
                DesktopBackendController.probe("http://127.0.0.1:$port", timeoutMs = 500)
            }
            assertEquals(
                ProbeOutcome.TIMEOUT, outcome,
                "an accepted-but-silent socket is the thawing signature and must not read as death",
            )
        } finally {
            server.close()
            accepter.interrupt()
        }
    }

    @Test
    fun an_unresolvable_host_is_TRANSPORT_not_a_dead_backend() = runBlocking {
        // "We could not ask" — restarting a node cannot help, and telling the
        // user to check their connection is only honest HERE.
        val outcome = DesktopBackendController.probe(
            "http://this-host-does-not-exist.invalid", timeoutMs = 2_000,
        )
        assertEquals(ProbeOutcome.TRANSPORT, outcome)
    }

    @Test
    fun a_real_200_is_ANSWERED() {
        val server = ServerSocket(0)
        val port = server.localPort
        Thread {
            runCatching {
                val s = server.accept()
                s.getInputStream().bufferedReader().readLine()
                s.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Length: 15\r\n\r\n{\"status\":\"ok\"}").toByteArray()
                )
                s.getOutputStream().flush()
                s.close()
            }
        }.apply { isDaemon = true; start() }

        try {
            val outcome = runBlocking {
                DesktopBackendController.probe("http://127.0.0.1:$port", timeoutMs = 3_000)
            }
            assertEquals(ProbeOutcome.ANSWERED, outcome)
        } finally {
            server.close()
        }
    }

    @Test
    fun a_node_answering_5xx_is_not_healthy_but_is_not_dead_either() {
        // A booting node answers 503 while its services come up. It is not Live
        // — the supervisor must not report a working backend to someone who
        // cannot use it — but it is emphatically not DEAD: something answered.
        // Reading this as REFUSED would restart a node that was starting
        // normally, and on a platform that cannot report host liveness that is
        // a boot loop. This test exists because the first version did exactly
        // that.
        val server = ServerSocket(0)
        val port = server.localPort
        Thread {
            runCatching {
                val s = server.accept()
                s.getInputStream().bufferedReader().readLine()
                s.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                s.close()
            }
        }.apply { isDaemon = true; start() }

        try {
            val outcome = runBlocking {
                DesktopBackendController.probe("http://127.0.0.1:$port", timeoutMs = 3_000)
            }
            assertEquals(
                ProbeOutcome.TIMEOUT, outcome,
                "listening-but-not-ready must be patience, not a restart",
            )
        } finally {
            server.close()
        }
    }
}
