package ai.ciris.mobile.shared.api

/**
 * **The agent's log download** — CIRISAgent 2.17.0
 * `GET /v1/telemetry/logs/download?tail_kib=N` (CIRISClient#249).
 *
 * Served by the AGENT only (:8080 on an embedded install; the node on :4243
 * does not proxy it). Owner (ADMIN) session. A zip of the tail of every agent
 * log and every node log under `<CIRIS_HOME>/logs`, redacted, with a
 * MANIFEST.txt; `Content-Disposition` names it `ciris-logs-<UTC>.zip` and
 * `X-CIRIS-Log-Files` counts the logs in it.
 */
sealed interface LogsDownload {
    /** The zip, the name the agent gave it, and how many log files it holds. */
    class Got(val bytes: ByteArray, val fileName: String, val fileCount: Int?) : LogsDownload
    /** 404: an agent before 2.17.0 has no such route. */
    data object AgentTooOld : LogsDownload
    /** 403: only the owner (ADMIN or above) may download the logs. */
    data class NotOwner(val detail: String?) : LogsDownload
    /** Anything else: the status and the agent's own words, when it said any. */
    data class Failed(val status: Int, val detail: String?) : LogsDownload
}

/** What System Logs asks the agent for. An interface so the view model is driven by a fake in tests. */
interface LogsDownloadApi {
    suspend fun download(tailKib: Int = DEFAULT_TAIL_KIB): LogsDownload

    companion object {
        /** The agent's own default per file; its bounds are 1..8192. */
        const val DEFAULT_TAIL_KIB = 2048
    }
}

class ClientLogsDownload(private val client: CIRISApiClient) : LogsDownloadApi {
    override suspend fun download(tailKib: Int): LogsDownload = client.downloadLogsZip(tailKib)
}

/** The file name from a `Content-Disposition` header, or null. */
fun dispositionFileName(header: String?): String? =
    header?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
        ?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
