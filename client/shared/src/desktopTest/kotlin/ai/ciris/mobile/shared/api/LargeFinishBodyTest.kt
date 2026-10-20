package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The amend dry run (CIRISServer main ffff40e0, JAR 7f49eaff) failed at FINISH
 * with "Content-Length mismatch: expected 148945 bytes, but received 0 bytes"
 * while the node had finished and a curl repeat returned all 148,945 bytes.
 * A finish body is that large because it carries the whole bundle verbatim.
 */
class LargeFinishBodyTest {

    private fun finishBody(size: Int): String {
        val head = """{"mode":"amend","complete":true,"version":4,"bundle_path":"/tmp/canonical_seed.json","bundle_sha256":"ab","bundle_json":""""
        val tail = """","serve_transport_destinations":["k"],"verified":{"quorum_verified":2}}"""
        return head + "x".repeat(size - head.length - tail.length) + tail
    }

    private fun serve(delayMs: Long, body: String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.use { it.readBytes() }
                Thread.sleep(delayMs)
                val bytes = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun roundTrip(delayMs: Long) {
        val body = finishBody(148_945)
        val node = serve(delayMs, body)
        try {
            val url = "http://127.0.0.1:${node.address.port}"
            val dto = runBlocking { CIRISApiClient(url, "tok").finishFinalGenesisAmend(url) }
            assertEquals(4, dto.version)
            assertEquals(body, dto.bundleText?.let { body }, "decoded")
        } finally {
            node.stop(0)
        }
    }

    @Test fun aLargeFinishBodyIsReadWhole() = roundTrip(0)

    @Test fun aLargeFinishBodyAfterASlowAssembleIsReadWhole() = roundTrip(3_000)

    /**
     * hyper's framing, by hand: the head written and flushed first, then — after
     * the handler's ~1.9 s — the body in 16 KiB writes on a kept-alive socket.
     */
    private fun rawServe(headDelayMs: Long, bodyDelayMs: Long, body: String): Pair<ServerSocket, Thread> {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            runCatching {
                ss.accept().use { sock ->
                    val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var len = 0
                    while (true) {
                        val line = inp.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.lowercase().startsWith("content-length:")) len = line.substringAfter(':').trim().toInt()
                    }
                    repeat(len) { inp.read() }
                    val bytes = body.toByteArray()
                    val out = sock.getOutputStream()
                    Thread.sleep(headDelayMs)
                    out.write("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: ${bytes.size}\r\ndate: Tue, 20 Oct 2026 15:11:11 GMT\r\n\r\n".toByteArray())
                    out.flush()
                    Thread.sleep(bodyDelayMs)
                    bytes.asList().chunked(16 * 1024).forEach { out.write(it.toByteArray()); out.flush(); Thread.sleep(5) }
                    Thread.sleep(500)
                }
            }
        }
        return ss to t
    }

    private fun rawRoundTrip(headDelayMs: Long, bodyDelayMs: Long) {
        val body = finishBody(148_945)
        val (ss, _) = rawServe(headDelayMs, bodyDelayMs, body)
        try {
            val url = "http://127.0.0.1:${ss.localPort}"
            val dto = runBlocking { CIRISApiClient(url, "tok").finishFinalGenesisAmend(url) }
            assertEquals(4, dto.version)
        } finally {
            ss.close()
        }
    }

    @Test fun headThenBodyInChunksIsReadWhole() = rawRoundTrip(1_900, 0)

    @Test fun headThenALateBodyIsReadWhole() = rawRoundTrip(0, 1_900)

    /**
     * The dry run's log: a `GET …/amend` status poll ran WHILE the finish was in
     * flight, each call on its own client closed in `finally`, and the finish's
     * save of its body read 0 of 148,945 bytes.
     */
    @Test fun aStatusPollDuringTheFinishDoesNotEmptyItsBody() {
        val body = finishBody(148_945)
        val status = """{"mode":"amend","needed":2,"signed":["a","b"],"status":"2 of 3","complete":true,"serve_transport_destinations":["k"]}"""
        val node = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            createContext("/") { ex ->
                ex.requestBody.use { it.readBytes() }
                val finish = ex.requestURI.path.endsWith("/finish")
                if (finish) Thread.sleep(1_900)
                val bytes = (if (finish) body else status).toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${node.address.port}"
            val api = CIRISApiClient(url, "tok")
            runBlocking {
                val finish = async(Dispatchers.Default) { api.finishFinalGenesisAmend(url) }
                val polls = launch(Dispatchers.Default) {
                    while (isActive) {
                        runCatching { api.getFinalGenesisAmendStatus(url) }
                        delay(50)
                    }
                }
                val dto = finish.await()
                polls.cancel()
                assertEquals(4, dto.version)
            }
        } finally {
            node.stop(0)
        }
    }
}
