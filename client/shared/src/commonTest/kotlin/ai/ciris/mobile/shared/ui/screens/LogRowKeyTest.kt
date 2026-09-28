package ai.ciris.mobile.shared.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CSD-029 addresses a log row by its time — `logs_row_ts_20260925093129` —
 * because a flow knows when the line it is looking for was written and cannot
 * know the row's id. The key must be the same whatever the node's timestamp
 * carries after the second.
 */
class LogRowKeyTest {

    private fun entry(timestamp: String, id: String = "x", message: String = "m") =
        LogEntryData(id = id, timestamp = timestamp, level = "INFO", service = "s", message = message)

    @Test
    fun the_key_starts_with_the_timestamp_to_the_second() {
        assertTrue(entry("2026-09-25T09:31:29Z").rowKey.startsWith("20260925093129_"))
    }

    @Test
    fun fractions_and_offsets_do_not_change_the_second_prefix() {
        assertTrue(entry("2026-09-25T09:31:29.123456+00:00").rowKey.startsWith("20260925093129_"))
        assertTrue(entry("2026-09-25T09:31:29.5Z").rowKey.startsWith("20260925093129_"))
    }

    // Two rows in one second registered under ONE `logs_row_ts_*` tag, so the
    // tree could address only one of them (Codex, PR #115).

    @Test
    fun two_rows_in_the_same_second_have_different_keys() {
        val a = entry("2026-09-25T09:31:29Z", id = "row-1", message = "first")
        val b = entry("2026-09-25T09:31:29Z", id = "row-2", message = "second")
        assertNotEquals(a.rowKey, b.rowKey)
        assertTrue(a.rowKey.startsWith("20260925093129_") && b.rowKey.startsWith("20260925093129_"))
    }

    @Test
    fun the_fraction_is_the_disambiguator_when_the_node_sends_one() {
        assertEquals("20260925093129_123456", entry("2026-09-25T09:31:29.123456+00:00").rowKey)
        assertEquals("20260925093129_000", entry("2026-09-25T09:31:29.000Z").rowKey)
        // The offset's digits are never part of the key.
        assertEquals("20260925093129_5", entry("2026-09-25T09:31:29.5+02:00").rowKey)
    }

    @Test
    fun the_key_is_stable_for_the_same_row() {
        assertEquals(entry("2026-09-25T09:31:29Z", id = "row-1").rowKey, entry("2026-09-25T09:31:29Z", id = "row-1").rowKey)
    }

    @Test
    fun a_row_with_no_id_falls_back_to_its_message() {
        val a = entry("2026-09-25T09:31:29Z", id = "", message = "first")
        val b = entry("2026-09-25T09:31:29Z", id = "", message = "second")
        assertNotEquals(a.rowKey, b.rowKey)
    }
}
