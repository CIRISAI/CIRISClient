package ai.ciris.mobile.shared.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug log entry for UI display
 */
data class DebugLogEntry(
    val id: Long,
    val timestamp: Long,
    val level: String,  // DEBUG, INFO, WARN, ERROR
    val tag: String,
    val message: String
) {
    // Unicode symbols — render on all platforms including WASM/Skia
    val symbol: String get() = when (level) {
        "DEBUG" -> "\u25CB"  // ○
        "INFO" -> "\u2139"   // ℹ
        "WARN" -> "\u26A0"   // ⚠
        "ERROR" -> "\u2716"  // ✖
        else -> "\u22EF"     // ⋯
    }

    val formattedTime: String get() {
        val hours = ((timestamp / 3600000) % 24).toInt()
        val minutes = ((timestamp / 60000) % 60).toInt()
        val seconds = ((timestamp / 1000) % 60).toInt()
        val displayHours = if (hours == 0) 12 else if (hours > 12) hours - 12 else hours
        return "$displayHours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

/**
 * Thread-safe buffer that stores recent debug logs for UI display.
 * Best-in-class logging that surfaces to the UI for easy troubleshooting.
 */
object DebugLogBuffer {
    private const val MAX_ENTRIES = 200
    private val _entries = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val entries: StateFlow<List<DebugLogEntry>> = _entries.asStateFlow()

    // Count of errors since last clear - shown as badge
    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    // Latest error message for quick display
    private val _latestError = MutableStateFlow<String?>(null)
    val latestError: StateFlow<String?> = _latestError.asStateFlow()

    /**
     * Add a log entry to the buffer
     */
    /**
     * Append one entry.
     *
     * ATOMIC BECAUSE THE WRITERS ARE CONCURRENT (Codex, PR #9). Desktop logging
     * arrives from several polling and I/O coroutines at once, and the original
     * `idCounter++` plus read-modify-write of `_entries.value` could hand two
     * entries the same id and let one thread's list overwrite another's — losing
     * exactly the failure logs this buffer exists to preserve, and losing them
     * hardest under load, which is when a stuck install is being diagnosed.
     *
     * The id is derived from the list INSIDE the same `update`, rather than from
     * a separate counter. `update` retries its lambda on contention, so a
     * counter incremented alongside it would advance once per attempt and mint
     * duplicates under exactly the load this is meant to survive. Derived from
     * the list, the id is whatever the winning attempt computes — and there is
     * no second piece of state to disagree with it. No new dependency, which is
     * the other reason: atomicfu is not on this module.
     */
    fun add(level: String, tag: String, message: String) {
        val stamp = currentTimeMillis()
        _entries.update { current ->
            val entry = DebugLogEntry(
                id = (current.lastOrNull()?.id ?: 0L) + 1L,
                timestamp = stamp,
                level = level,
                tag = tag,
                message = message
            )
            (current + entry).takeLast(MAX_ENTRIES)
        }

        if (level == "ERROR" || level == "WARN") {
            _errorCount.update { it + 1 }
            if (level == "ERROR") {
                _latestError.value = "[$tag] $message"
            }
        }
    }

    /**
     * Clear all log entries
     */
    fun clear() {
        _entries.value = emptyList()
        _errorCount.value = 0
        _latestError.value = null
    }

    /**
     * Clear just the error count (after user has seen errors)
     */
    fun clearErrorCount() {
        _errorCount.value = 0
    }

    /**
     * Dismiss the latest error
     */
    fun dismissLatestError() {
        _latestError.value = null
    }

    /**
     * Get filtered entries
     */
    fun getFiltered(level: String? = null, tag: String? = null): List<DebugLogEntry> {
        return _entries.value.filter { entry ->
            (level == null || entry.level == level) &&
            (tag == null || entry.tag.contains(tag, ignoreCase = true))
        }
    }

    /**
     * Get only error entries
     */
    fun getErrors(): List<DebugLogEntry> = getFiltered(level = "ERROR")

    /**
     * Get only warn and error entries
     */
    fun getWarningsAndErrors(): List<DebugLogEntry> {
        return _entries.value.filter { it.level == "WARN" || it.level == "ERROR" }
    }
}

/**
 * Platform-specific current time in milliseconds
 */
expect fun currentTimeMillis(): Long
