package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.LogsDownload
import ai.ciris.mobile.shared.api.LogsDownloadApi
import ai.ciris.mobile.shared.api.dispositionFileName
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** System Logs' Download (CIRISClient#249; CIRISAgent 2.17.0 GET /v1/telemetry/logs/download). */
class LogsDownloadTest {
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private class Agent(val answer: LogsDownload) : LogsDownloadApi {
        var asked = 0
        override suspend fun download(tailKib: Int): LogsDownload { asked++; return answer }
    }

    private val saved = mutableListOf<Triple<String, String, Int>>()
    private fun vm(agent: Agent, writes: Boolean = true) = LogsViewModel(
        CIRISApiClient("http://127.0.0.1:9", "tok"), agent,
        save = { n, t, b -> saved += Triple(n, t, b.size); if (writes) "Downloads/$n" else null },
    ).also { made += it }

    @Test fun a_zip_is_saved_where_the_person_can_find_it() {
        val zip = LogsDownload.Got(ByteArray(10), "ciris-logs-20261026T120000Z.zip", 5)
        val vm = vm(Agent(zip)); vm.downloadLogs()
        assertEquals(LogsDownloadState.Saved("Downloads/ciris-logs-20261026T120000Z.zip", 5), vm.download.value)
        assertEquals(listOf(Triple("ciris-logs-20261026T120000Z.zip", "application/zip", 10)), saved)
    }

    @Test fun a_zip_that_cannot_be_written_says_nothing_was_written() {
        val vm = vm(Agent(LogsDownload.Got(ByteArray(3), "l.zip", 1)), writes = false); vm.downloadLogs()
        assertEquals(LogsDownloadState.NotWritten("l.zip"), vm.download.value)
    }

    @Test fun an_agent_before_2_17_is_too_old_not_a_failure_and_nothing_is_saved() {
        val vm = vm(Agent(LogsDownload.AgentTooOld)); vm.downloadLogs()
        assertEquals(LogsDownloadState.AgentTooOld, vm.download.value)
        assertTrue(saved.isEmpty())
    }

    @Test fun not_the_owner_and_other_failures_are_said() {
        val a = vm(Agent(LogsDownload.NotOwner("Insufficient permissions."))); a.downloadLogs()
        assertEquals(LogsDownloadState.NotOwner, a.download.value)
        val b = vm(Agent(LogsDownload.Failed(500, null))); b.downloadLogs()
        assertEquals(LogsDownloadState.Failed("HTTP 500"), b.download.value)
    }

    @Test fun the_file_name_comes_from_content_disposition_and_never_carries_a_path() {
        assertEquals("ciris-logs-20261026T120000Z.zip", dispositionFileName("""attachment; filename="ciris-logs-20261026T120000Z.zip""""))
        assertEquals("evil.zip", dispositionFileName("""attachment; filename="../../evil.zip""""))
        assertNull(dispositionFileName(null))
        assertNull(dispositionFileName("attachment"))
    }
}
