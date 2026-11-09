package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.platform.PickedFileStream
import com.sun.net.httpserver.HttpServer
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The streamed upload's bytes on a real socket, read the way CIRISServer's
 * `parse_upload` reads them: every field before the `file` part, `size` equal to
 * the file's length, and the file whole and last.
 */
class StreamedUploadWireTest {
    private lateinit var server: HttpServer
    private val body = AtomicReference<ByteArray>()
    private val type = AtomicReference<String>()

    @BeforeTest fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/files") { ex ->
            type.set(ex.requestHeaders.getFirst("Content-Type"))
            body.set(ex.requestBody.readBytes())
            val ok = """{"attestation_id":"att-1","addressed":true,"cohort":"self","room":"self","tier":"Self","crossed":true}""".toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.start()
    }

    @AfterTest fun tearDown() = server.stop(0)

    @Test fun fieldsComeFirstSizeIsExactAndTheFileIsLastAndWhole() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val file = File.createTempFile("ciris-upload", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        val pick = PickedFileStream("data.bin", "application/octet-stream", file.length(), ByteArray(0)) {
            file.inputStream().toByteReadChannel()
        }
        var lastProgress = 0L
        CIRISApiClient("http://127.0.0.1:${server.address.port}").writeFileStreamed(
            cohort = "self",
            roomId = null,
            mediaType = "application/octet-stream",
            file = pick,
            onProgress = { sent, _ -> lastProgress = sent },
            nodeUrl = "http://127.0.0.1:${server.address.port}",
        )

        val boundary = type.get().substringAfter("boundary=").trim('"')
        val raw = String(body.get(), Charsets.ISO_8859_1)
        val parts = raw.split("--$boundary").filter { it.contains("Content-Disposition") }
        // Print each part head for diagnosis, then read names quoted or not.
        val names = parts.map { Regex("""[;\s]name="?([^";\r\n]+)"?""").find(it)!!.groupValues[1] }
        assertEquals(listOf("cohort", "media_type", "filename", "size", "file"), names)
        val size = parts.first { Regex("""[;\s]name="?size"?[;\r]""").containsMatchIn(it) }.substringAfter("\r\n\r\n").trim()
        assertEquals(bytes.size.toString(), size)
        val sent = parts.last().substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
        assertTrue(sent.contentEquals(bytes), "the file part is the file, whole")
        assertEquals(bytes.size.toLong(), lastProgress)
    }
}
