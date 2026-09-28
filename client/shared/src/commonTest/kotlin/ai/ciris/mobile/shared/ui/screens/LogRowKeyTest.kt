package ai.ciris.mobile.shared.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-029 addresses a log row by its time — `logs_row_ts_20260925093129` —
 * because a flow knows when the line it is looking for was written and cannot
 * know the row's id. The key must be the same whatever the node's timestamp
 * carries after the second.
 */
class LogRowKeyTest {

    private fun entry(timestamp: String) =
        LogEntryData(id = "x", timestamp = timestamp, level = "INFO", service = "s", message = "m")

    @Test
    fun the_key_is_the_timestamp_to_the_second() {
        assertEquals("20260925093129", entry("2026-09-25T09:31:29Z").rowKey)
    }

    @Test
    fun fractions_and_offsets_do_not_change_the_key() {
        assertEquals("20260925093129", entry("2026-09-25T09:31:29.123456+00:00").rowKey)
        assertEquals("20260925093129", entry("2026-09-25T09:31:29.5Z").rowKey)
    }
}
