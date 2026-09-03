@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.testing

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSLog
import platform.posix.*
import kotlin.native.concurrent.Worker

// Network byte order conversion (big-endian)
private fun htons(value: UShort): UShort {
    return (((value.toInt() and 0xFF) shl 8) or ((value.toInt() shr 8) and 0xFF)).toUShort()
}
private fun htonl(value: UInt): UInt {
    return ((value and 0xFFu) shl 24) or
        ((value and 0xFF00u) shl 8) or
        ((value shr 8) and 0xFF00u) or
        ((value shr 24) and 0xFFu)
}

/**
 * Minimal HTTP server for iOS using POSIX sockets.
 * Provides the same test automation endpoints as the desktop Ktor server.
 * Only starts when CIRIS_TEST_MODE=true.
 */
class IOSTestAutomationServer(private val port: Int = 9091) {

    private var serverSocket: Int = -1
    private var acceptWorker: Worker? = null
    private var running = false
    private var scope: CoroutineScope? = null

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun start() {
        if (running) return
        running = true
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // THE ACCEPT LOOP GETS ITS OWN THREAD, NOT A SHARED DISPATCHER.
        //
        // `accept()` is a BLOCKING posix call. Running it on Dispatchers.Default
        // parked one of that pool's few workers indefinitely, and every
        // connection it accepted was handed back to the SAME pool to be served —
        // so the loop could starve the handlers it was spawning. The log said
        // "Server started on http://localhost:9091" and nothing ever answered,
        // which is what the nightly saw: a clean startup through to the wizard,
        // and /health silent for 120s (CIRISClient#28).
        //
        // A dedicated worker cannot starve anything: it blocks in accept(), and
        // the handlers keep the pool to themselves.
        acceptWorker = Worker.start(name = "ciris-ios-test-accept")
        acceptWorker?.executeAfter(0L, {
            try {
                runBlocking { startServer() }
            } catch (e: Throwable) {
                NSLog("[TestAutomation.ios] Server error: ${e.message}")
            }
        })
    }

    fun stop() {
        running = false
        if (serverSocket >= 0) {
            close(serverSocket)
            serverSocket = -1
        }
        scope?.cancel()
        scope = null
        // The worker is blocked in accept(); closing the socket above is what
        // releases it, so request termination without waiting on it.
        acceptWorker?.requestTermination(processScheduledJobs = false)
        acceptWorker = null
        NSLog("[TestAutomation.ios] Server stopped")
    }

    private suspend fun startServer() {
        serverSocket = socket(AF_INET, SOCK_STREAM, 0)
        if (serverSocket < 0) {
            NSLog("[TestAutomation.ios] Failed to create socket")
            return
        }

        // Allow port reuse
        memScoped {
            val optval = alloc<IntVar>()
            optval.value = 1
            setsockopt(serverSocket, SOL_SOCKET, SO_REUSEADDR, optval.ptr, sizeOf<IntVar>().toUInt())
        }

        // Bind to localhost only - never expose to LAN (security)
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = htons(port.toUShort())
            addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK.toUInt())

            if (bind(serverSocket, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) < 0) {
                NSLog("[TestAutomation.ios] Failed to bind to port $port")
                close(serverSocket)
                return
            }
        }

        if (listen(serverSocket, 5) < 0) {
            NSLog("[TestAutomation.ios] Failed to listen")
            close(serverSocket)
            return
        }

        NSLog("[TestAutomation.ios] Server started on http://localhost:$port")

        while (running) {
            val clientSocket = accept(serverSocket, null, null)
            if (clientSocket < 0) {
                if (running) delay(100)
                continue
            }

            // Handle each connection in a coroutine
            scope?.launch {
                try {
                    handleConnection(clientSocket)
                } catch (e: Exception) {
                    NSLog("[TestAutomation.ios] Connection error: ${e.message}")
                } finally {
                    close(clientSocket)
                }
            }
        }
    }

    /**
     * Byte offset of the CRLFCRLF terminating the headers, or -1.
     *
     * Searched over BYTES, not over a decoded string: a character index and a
     * byte offset diverge the moment anything non-ASCII appears, and the body
     * offset derived from it would slice mid-character. Headers are ASCII in
     * practice, which is exactly the kind of "in practice" that stops being
     * true quietly.
     */
    private fun indexOfHeaderEnd(buf: ByteArray, len: Int): Int {
        var i = 0
        while (i + 3 < len) {
            if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte() &&
                buf[i + 2] == 13.toByte() && buf[i + 3] == 10.toByte()
            ) return i
            i++
        }
        return -1
    }

    /** One recv() into [into] at [offset]; bytes read, or <= 0 at EOF/error. */
    private fun readChunk(clientSocket: Int, into: ByteArray, offset: Int): Int =
        if (offset >= into.size) 0 else memScoped {
            into.usePinned { pinned ->
                recv(clientSocket, pinned.addressOf(offset), (into.size - offset).toULong(), 0).toInt()
            }
        }

    private suspend fun handleConnection(clientSocket: Int) {
        // A REQUEST IS NOT ONE recv() (CIRISClient#35).
        //
        // This read once into an 8 KB buffer and parsed whatever had arrived.
        // TCP does not promise that headers and body land together, and httpx
        // (which the QA gate uses) writes them as separate segments — so every
        // GET worked and every POST decoded an EMPTY body, then returned the
        // kotlinx exception text as the response. CIRISAgent had to route iOS
        // through a one-segment transport to get the wizard running at all.
        //
        // So: read until the header terminator, then keep reading until
        // Content-Length bytes of body have arrived.
        val buffer = ByteArray(65536)
        var filled = 0
        var headerEnd = -1

        while (filled < buffer.size) {
            val n = readChunk(clientSocket, buffer, filled)
            if (n <= 0) break
            filled += n
            headerEnd = indexOfHeaderEnd(buffer, filled)
            if (headerEnd >= 0) break
        }
        if (filled <= 0 || headerEnd < 0) return

        val headerText = buffer.decodeToString(0, headerEnd)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return

        // Parse request line
        val parts = lines[0].split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].split("?")[0]

        // Content-Length decides how much body is still owed. Absent or
        // unparseable means no body — a GET, or a client that sent none.
        val contentLength = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
            ?: 0

        val bodyStart = headerEnd + 4
        while (filled - bodyStart < contentLength && filled < buffer.size) {
            val n = readChunk(clientSocket, buffer, filled)
            if (n <= 0) break   // peer closed early; decode what we have and let route() report it
            filled += n
        }

        val body = if (filled > bodyStart) {
            buffer.decodeToString(bodyStart, minOf(filled, bodyStart + contentLength).coerceAtLeast(bodyStart))
        } else {
            ""
        }

        // Route
        val (statusCode, responseBody) = route(method, path, body)

        // Send response
        val response = "HTTP/1.1 $statusCode OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${responseBody.encodeToByteArray().size}\r\n" +
            "\r\n" +
            responseBody

        val responseBytes = response.encodeToByteArray()
        memScoped {
            responseBytes.usePinned { pinned ->
                send(clientSocket, pinned.addressOf(0), responseBytes.size.toULong(), 0)
            }
        }
    }

    private suspend fun route(method: String, path: String, body: String): Pair<Int, String> {
        return try {
            when {
                method == "OPTIONS" -> 200 to "{}"
                method == "GET" && path == "/health" ->
                    200 to json.encodeToString(TestAutomationHandler.handleHealth())
                method == "GET" && path == "/tree" ->
                    200 to json.encodeToString(TestAutomationHandler.handleTree())
                method == "GET" && path == "/screen" ->
                    200 to json.encodeToString(TestAutomationHandler.handleScreen())
                // Tagged-but-not-drivable on this screen — the harness pre-flight.
                method == "GET" && path == "/undrivable" ->
                    200 to json.encodeToString(TestAutomationHandler.handleUndrivable())
                // The app's own account of its gates.
                method == "GET" && path == "/state" ->
                    200 to json.encodeToString(TestAutomationHandler.handleState())
                method == "POST" && path == "/click" -> {
                    val req = json.decodeFromString<ClickRequest>(body)
                    val resp = TestAutomationHandler.handleClick(req)
                    (if (resp.success) 200 else 404) to json.encodeToString(resp)
                }
                method == "POST" && path == "/input" -> {
                    val req = json.decodeFromString<InputRequest>(body)
                    200 to json.encodeToString(TestAutomationHandler.handleInput(req))
                }
                method == "POST" && path == "/wait" -> {
                    val req = json.decodeFromString<WaitRequest>(body)
                    val resp = TestAutomationHandler.handleWait(req)
                    (if (resp.success) 200 else 404) to json.encodeToString(resp)
                }
                method == "POST" && path == "/scroll" -> {
                    val req = json.decodeFromString<ScrollRequest>(body)
                    200 to json.encodeToString(TestAutomationHandler.handleScroll(req))
                }
                method == "GET" && path.startsWith("/element/") -> {
                    val tag = path.removePrefix("/element/")
                    val elem = TestAutomationHandler.handleGetElement(tag)
                    if (elem != null) 200 to json.encodeToString(elem)
                    else 404 to """{"error":"Element not found"}"""
                }
                else -> 404 to """{"error":"Not found: $method $path"}"""
            }
        } catch (e: Exception) {
            // BUILD THE ERROR WITH THE SERIALIZER, NOT WITH STRING PASTE.
            //
            // kotlinx decode failures carry newlines ("...at path: $\nJSON input:
            // ..."), and a hand-quoted "{\"error\":\"$msg\"}" put them raw
            // inside a JSON string — so a client parsing the reply got a parse
            // error about our parse error, and reported it as the server
            // returning non-JSON (CIRISClient#35). Encoding escapes whatever the
            // message contains.
            500 to json.encodeToString(
                mapOf("error" to (e.message ?: e::class.simpleName ?: "unknown error"))
            )
        }
    }

    companion object {
        private var instance: IOSTestAutomationServer? = null

        fun startIfEnabled() {
            val testMode = platform.posix.getenv("CIRIS_TEST_MODE")?.toKString()?.lowercase()
            if (testMode in listOf("true", "1", "yes")) {
                val port = platform.posix.getenv("CIRIS_TEST_PORT")?.toKString()?.toIntOrNull() ?: 9091
                                // ONE SERVER, ONCE (CIRISClient#31).
                //
                // Without this a second call built a second server whose bind
                // FAILED on the already-taken port, and then overwrote
                // `instance` with the broken one — so a later stop() stopped
                // the wrong object and left the working server running. The
                // reporter found it on iOS; Android had the identical hole, and
                // it matters the moment a host wants to start earlier than
                // CIRISApp's LaunchedEffect.
                if (instance != null) {
                    NSLog("[TestAutomation.ios] Server already started; ignoring")
                    return
                }
                NSLog("[TestAutomation.ios] Test mode enabled, starting server on port $port")
                instance = IOSTestAutomationServer(port).also { it.start() }
            }
        }

        fun stop() {
            instance?.stop()
            instance = null
        }
    }
}
