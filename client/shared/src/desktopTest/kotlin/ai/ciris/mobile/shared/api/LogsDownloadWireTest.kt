package ai.ciris.mobile.shared.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The route's contract over a real socket (CIRISAgent f8cce2431, 2.17.0). */
class LogsDownloadWireTest {
    private val servers = mutableListOf<HttpServer>()
    @AfterTest fun tearDown() { servers.forEach { it.stop(0) }; runBlocking { AgentRateGovernor.resetForTests() } }

    private fun agent(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap(), seen: MutableList<String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            seen += "${ex.requestMethod} ${ex.requestURI} auth=${ex.requestHeaders.getFirst("Authorization")}"
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
        }
        s.start(); servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test fun the_zip_its_name_and_count_come_back_from_the_agent_base_url() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val zip = byteArrayOf(0x50, 0x4b, 3, 4, 9, 9)
        val url = agent(200, zip, mapOf("Content-Type" to "application/zip",
            "Content-Disposition" to "attachment; filename=\"ciris-logs-20261026T120000Z.zip\"", "X-CIRIS-Log-Files" to "4"), seen)
        val got = assertIs<LogsDownload.Got>(CIRISApiClient(url, "sess:tok").downloadLogsZip(512))
        assertTrue(got.bytes.contentEquals(zip))
        assertEquals("ciris-logs-20261026T120000Z.zip", got.fileName)
        assertEquals(4, got.fileCount)
        assertEquals(listOf("GET /v1/telemetry/logs/download?tail_kib=512 auth=Bearer sess:tok"), seen.toList())
    }

    @Test fun a_404_is_an_agent_too_old_and_a_403_is_not_the_owner() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        assertEquals(LogsDownload.AgentTooOld, CIRISApiClient(agent(404, """{"detail":"Not Found"}""".toByteArray(), seen = seen), "t").downloadLogsZip())
        val r = CIRISApiClient(agent(403, """{"detail":"Insufficient permissions. Requires ADMIN role or higher."}""".toByteArray(), seen = seen), "t").downloadLogsZip()
        assertEquals(LogsDownload.NotOwner("Insufficient permissions. Requires ADMIN role or higher."), r)
    }

    @Test fun tail_kib_is_kept_inside_the_agents_bounds() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val url = agent(404, ByteArray(0), seen = seen)
        CIRISApiClient(url, "t").downloadLogsZip(0); CIRISApiClient(url, "t").downloadLogsZip(99_999)
        assertEquals(listOf("tail_kib=1", "tail_kib=8192"), seen.map { it.substringAfter("?").substringBefore(" ") })
    }
}
