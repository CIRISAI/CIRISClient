package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.LogsDownload
import ai.ciris.mobile.shared.api.LogsDownloadApi
import ai.ciris.mobile.shared.platform.zipTextEntry
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Share on System Logs and the manifest the debug panel's Copy carries (CIRISClient#249). */
class LogsShareTest {
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private val agent = object : LogsDownloadApi {
        override suspend fun download(tailKib: Int) = LogsDownload.Got(zip("MANIFEST.txt" to "agent 2.17.0"), "ciris-logs.zip", 2)
    }

    @Test fun share_hands_over_the_bytes_that_were_downloaded() {
        val shared = mutableListOf<Pair<String, Int>>()
        val vm = LogsViewModel(CIRISApiClient("http://127.0.0.1:9", "t"), agent, save = { n, _, _ -> "Downloads/$n" },
            share = { n, t, b -> assertEquals("application/zip", t); shared += n to b.size; true }, canShare = true).also { made += it }
        assertFalse(vm.shareLogs(), "nothing to share before a download")
        vm.downloadLogs()
        assertTrue(vm.shareLogs())
        assertEquals("ciris-logs.zip", shared.single().first)
    }

    @Test fun share_is_never_attempted_where_this_app_cannot_share() {
        var asked = false
        val vm = LogsViewModel(CIRISApiClient("http://127.0.0.1:9", "t"), agent, save = { n, _, _ -> n },
            share = { _, _, _ -> asked = true; true }, canShare = false).also { made += it }
        vm.downloadLogs()
        assertFalse(vm.shareLogs()); assertFalse(asked)
    }

    @Test fun the_manifest_is_read_out_of_the_zip() {
        assertEquals("agent 2.17.0", zipTextEntry(zip("ciris_agent_x.log" to "…", "MANIFEST.txt" to "agent 2.17.0"), "MANIFEST.txt"))
        assertNull(zipTextEntry(zip("a.log" to "x"), "MANIFEST.txt"))
        assertNull(zipTextEntry("not a zip".toByteArray(), "MANIFEST.txt"))
    }
}
