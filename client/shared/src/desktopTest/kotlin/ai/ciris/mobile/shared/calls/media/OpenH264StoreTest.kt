package ai.ciris.mobile.shared.calls.media

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in for Cisco's server on this machine. Records what each request carried. */
internal class ServedFiles : AutoCloseable {
    class Request(val path: String, val headers: Map<String, List<String>>)

    val files = mutableMapOf<String, ByteArray>()
    val requests = CopyOnWriteArrayList<Request>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { x ->
            requests += Request(x.requestURI.path, x.requestHeaders.mapKeys { it.key.lowercase() })
            val body = files[x.requestURI.path]
            if (body == null) {
                x.sendResponseHeaders(404, -1)
            } else {
                x.sendResponseHeaders(200, body.size.toLong())
                x.responseBody.use { it.write(body) }
            }
            x.close()
        }
        start()
    }
    val baseUrl = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

/**
 * The download, the check and the switch, against a stand-in server and a
 * directory of the test's own. The "library" here is 53 bytes of text: these
 * tests are about what is fetched, kept and deleted, not about H.264.
 */
class OpenH264StoreTest {
    private val library = "OpenH264 Video Codec provided by Cisco Systems, Inc.\n".toByteArray()
    private val packed = checkNotNull(javaClass.getResource("/bzip2/text.bz2")).readBytes()
    private val build = OpenH264Build("libtest.so", OpenH264Store.sha256(library))

    private lateinit var dir: File
    private lateinit var cisco: ServedFiles

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("openh264-test").toFile()
        cisco = ServedFiles()
    }

    @AfterTest fun tearDown() {
        cisco.close()
        dir.deleteRecursively()
    }

    private fun store(b: OpenH264Build = build) = OpenH264Store(dir, b, cisco.baseUrl)
    private fun kept(): List<String> = dir.list()?.sorted().orEmpty()

    @Test fun whatIsKeptIsExactlyThePublishedLibrary() {
        cisco.files["/libtest.so.bz2"] = packed
        val s = store()
        assertNull(s.verified(), "nothing is there before the download")
        val file = s.download()
        assertContentEquals(library, file.readBytes())
        assertEquals(listOf("libtest.so"), kept(), "only the library: no partial file left beside it")
        assertEquals(file, s.verified())
    }

    @Test fun theRequestCarriesNothingAboutThePersonOrTheMachine() {
        cisco.files["/libtest.so.bz2"] = packed
        store().download()
        val r = cisco.requests.single()
        assertEquals("/libtest.so.bz2", r.path)
        assertEquals(listOf("ciris-client"), r.headers["user-agent"], "not Java's own, which names the runtime's version")
        assertEquals(setOf("user-agent", "host", "accept", "connection"), r.headers.keys, "no cookie, no referrer, nothing else")
    }

    @Test fun aFileThatIsNotThePublishedOneIsNotKept() {
        cisco.files["/libtest.so.bz2"] = checkNotNull(javaClass.getResource("/bzip2/runs.bz2")).readBytes()
        val e = assertFailsWith<OpenH264CheckFailed> { store().download() }
        assertTrue("sha256" in e.message!!, e.message)
        assertEquals(emptyList(), kept())
    }

    @Test fun whatIsNotBzip2IsNotKept() {
        cisco.files["/libtest.so.bz2"] = "<html>captive portal</html>".toByteArray()
        assertFailsWith<OpenH264CheckFailed> { store().download() }
        assertEquals(emptyList(), kept())
    }

    @Test fun aMissingFileIsADownloadFailure() {
        val e = assertFailsWith<IOException> { store().download() }
        assertTrue("404" in e.message!!, e.message)
        assertEquals(emptyList(), kept())
    }

    @Test fun moreBytesThanAnyPublishedFileIsRefusedWhileReading() {
        cisco.files["/libtest.so.bz2"] = ByteArray(OpenH264Store.MAX_DOWNLOAD + 1)
        val e = assertFailsWith<OpenH264CheckFailed> { store().download() }
        assertTrue("more than" in e.message!!, e.message)
        assertEquals(emptyList(), kept())
    }

    @Test fun aLibraryChangedOnDiskIsDeletedNotUsed() {
        cisco.files["/libtest.so.bz2"] = packed
        val s = store()
        val file = s.download()
        file.appendBytes(byteArrayOf(0))
        assertNull(s.verified())
        assertEquals(emptyList(), kept(), "the changed file is gone")
    }

    @Test fun removeDeletesTheLibrary() {
        cisco.files["/libtest.so.bz2"] = packed
        val s = store()
        s.download()
        s.remove()
        assertEquals(emptyList(), kept())
        assertNull(s.verified())
    }

    /** Where the system would not let go of the file when it was turned off (Windows), the mark wins at the next start. */
    @Test fun turnedOffButNotDeletedIsNotUsedAndIsDeletedAtTheNextStart() {
        cisco.files["/libtest.so.bz2"] = packed
        val s = store()
        s.download()
        File(dir, "libtest.so.off").writeText("turned off\n")
        assertNull(s.verified())
        assertEquals(emptyList(), kept())
    }

    private fun switch(load: ((File) -> OpenH264Api)? = null): DesktopH264 {
        val scope = CoroutineScope(Dispatchers.Unconfined) // turnOn() finishes before it returns
        return if (load == null) DesktopH264(store(), scope) else DesktopH264(store(), scope, load)
    }

    @Test fun nothingIsRequestedUntilThePersonTurnsItOn() {
        cisco.files["/libtest.so.bz2"] = packed
        val h = switch()
        assertEquals(H264Download.State.OFF, h.state.value)
        assertNull(h.api)
        assertEquals(0, cisco.requests.size, "making the switch asked the server for nothing")
        assertEquals(emptyList(), kept())
    }

    @Test fun eachFailureHasItsOwnStateAndCanBeTriedAgain() {
        val h = switch()
        h.turnOn()
        assertEquals(H264Download.State.FAILED_DOWNLOAD, h.state.value)

        cisco.files["/libtest.so.bz2"] = "not it".toByteArray()
        h.turnOn()
        assertEquals(H264Download.State.FAILED_CHECK, h.state.value)

        // The right bytes, and they are 53 bytes of text: the system refuses to load them.
        // This goes through the real loader, so it also shows JNA itself works here.
        cisco.files["/libtest.so.bz2"] = packed
        h.turnOn()
        assertEquals(H264Download.State.FAILED_LOAD, h.state.value)
        assertNull(h.api)
        assertEquals(3, cisco.requests.size)

        h.turnOff()
        assertEquals(H264Download.State.OFF, h.state.value)
        assertEquals(emptyList(), kept(), "turning it off deletes what was downloaded")
    }

    @Test fun theLicenceShownIsCiscosTextWithTheSentenceItRequires() {
        val text = switch().licence
        assertTrue(H264Download.ATTRIBUTION in text, "the sentence Cisco requires beside the switch is the one in its licence")
        assertTrue("separately downloaded to an end user" in text)
        assertTrue("PERSONAL USE OF A CONSUMER" in text)
        assertTrue(text.trimEnd().endsWith("v1.0"), "the whole text, to its last line")
    }

    @Test fun eachSystemGetsItsOwnBuildAndItsOwnDirectory() {
        assertEquals("libopenh264-2.7.0-linux64.8.so", OpenH264Builds.forMachine("Linux", "amd64")?.fileName)
        assertEquals("libopenh264-2.7.0-linux-arm64.8.so", OpenH264Builds.forMachine("Linux", "aarch64")?.fileName)
        assertEquals("libopenh264-2.7.0-mac-x64.dylib", OpenH264Builds.forMachine("Mac OS X", "x86_64")?.fileName)
        assertEquals("libopenh264-2.7.0-mac-arm64.dylib", OpenH264Builds.forMachine("Mac OS X", "aarch64")?.fileName)
        assertEquals("openh264-2.7.0-win64.dll", OpenH264Builds.forMachine("Windows 11", "amd64")?.fileName)
        assertEquals("openh264-2.7.0-win-arm64.dll", OpenH264Builds.forMachine("Windows 11", "aarch64")?.fileName)
        assertNull(OpenH264Builds.forMachine("Linux", "arm"), "32-bit: no switch is shown")
        assertNull(OpenH264Builds.forMachine("FreeBSD", "amd64"))

        val none: (String) -> String? = { null }
        assertEquals(File("/home/a/.local/share/ciris-client/openh264"), OpenH264Builds.directory("Linux", none, "/home/a"))
        assertEquals(File("/d/ciris-client/openh264"), OpenH264Builds.directory("Linux", { if (it == "XDG_DATA_HOME") "/d" else null }, "/home/a"))
        assertEquals(File("/Users/a/Library/Application Support/ciris-client/openh264"), OpenH264Builds.directory("Mac OS X", none, "/Users/a"))
        assertEquals(File("L/ciris-client/openh264"), OpenH264Builds.directory("Windows 11", { if (it == "LOCALAPPDATA") "L" else null }, "H"))
    }
}
