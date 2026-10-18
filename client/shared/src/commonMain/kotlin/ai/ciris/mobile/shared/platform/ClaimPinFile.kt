package ai.ciris.mobile.shared.platform

import kotlinx.coroutines.delay

/**
 * **The claim PIN is a file on disk, never the log** (operator ruling;
 * CIRISClient#184).
 *
 * Since ciris-server 0.5.227 the startup banner names the claim PIN FILE
 * instead of printing the PIN, so there is nothing in any log to parse. The
 * only source is the node's own 0600 `claim_pin` — the path it declares as
 * `claim_pin_file` in `GET /v1/setup/status`, else `<home>/claim_pin`. Each
 * platform's [PythonRuntimeProtocol.readLocalClaimPin] is a single read of
 * that file; this is the bounded wait around it.
 *
 * The node writes the file a few seconds AFTER health answers (~3 s observed),
 * so "absent" right after boot means NOT YET. Absent at the end of the window
 * means the node is already claimed (the PIN is consumed on claim) or the file
 * is not readable by this user — and the caller says so by name
 * ([PIN_FILE_ABSENT]), not by telling anyone to read a console.
 */
object ClaimPinFile {
    /** How long to wait for the file to appear. */
    const val WAIT_MS = 30_000L

    /** How often to re-read it. Never a tight loop. */
    const val POLL_MS = 500L

    /** The named outcome when the file never appeared (`mobile.setup_claim_pin_file_absent`). */
    const val PIN_FILE_ABSENT = "claim.pin_file_absent"

    /**
     * Re-read [read] every [pollMs] until it yields a non-blank PIN or
     * [timeoutMs] has been spent waiting; `null` when it never did. Each read
     * is fresh: a file that appears late is still found, and a file that
     * changed (a reset re-armed the node) is read as it is now.
     */
    suspend fun await(
        timeoutMs: Long = WAIT_MS,
        pollMs: Long = POLL_MS,
        sleep: suspend (Long) -> Unit = { delay(it) },
        read: suspend () -> String?,
    ): String? {
        require(pollMs > 0) { "pollMs must be positive" }
        var waited = 0L
        while (true) {
            val pin = try {
                read()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            pin?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (waited >= timeoutMs) return null
            sleep(pollMs)
            waited += pollMs
        }
    }
}
