package ai.ciris.mobile.shared.platform

/**
 * The tail of the agent's own log files, `(file name, text)` pairs, for the
 * debug bundle.
 *
 * ON ANDROID THE BUNDLE IS THE ONLY WAY OUT. The agent writes
 * `<CIRIS_HOME>/logs/latest.log` and `incidents_latest.log` into the app's
 * private files; a release build has no `run-as`, so a bundle that carries only
 * the Kotlin log buffer leaves the agent's side of a failure unreadable (the
 * maintainer's own phone, 2026-10-26: client 0.5.231, agent app 2.16.5 build
 * 208). Desktop keeps them in a plain directory and the iOS harness collects
 * them from the simulator, so those platforms add nothing here.
 *
 * Each tail is at most [maxBytesEach] bytes, cut at a line start: the bundle is
 * also copied to the clipboard, which on Android rides a binder transaction
 * capped near 1 MB. The bundle redacts the whole text, these tails included.
 */
expect fun agentLogTails(maxBytesEach: Int = AGENT_LOG_TAIL_BYTES): List<Pair<String, String>>

const val AGENT_LOG_TAIL_BYTES = 32 * 1024

/** The agent's log files the bundle tails, newest-run first. */
val AGENT_LOG_FILES = listOf("latest.log", "incidents_latest.log")

/**
 * The last [maxBytes] of [bytes] as text, starting at a line boundary.
 * [earlierCut] says the caller already dropped bytes before [bytes] (a file
 * read from an offset), so the first, partial line goes too.
 */
fun tailText(bytes: ByteArray, maxBytes: Int, earlierCut: Boolean = false): String {
    val cut = bytes.size > maxBytes || earlierCut
    val text = (if (bytes.size > maxBytes) bytes.copyOfRange(bytes.size - maxBytes, bytes.size) else bytes).decodeToString()
    if (!cut) return text
    val nl = text.indexOf('\n')
    return "… (earlier lines cut)\n" + if (nl >= 0) text.substring(nl + 1) else text
}
