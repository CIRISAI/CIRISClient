package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CIRISClient#165: the iOS crash hook must leave `kotlin_crash.log` behind
 * whatever its log call does. The segfault itself needs a simulator; what can
 * be held here is the order (file first), the isolation of the two sinks, the
 * report's content, and the NSLog escaping that removes the varargs.
 */
class CrashReportTest {

    @Test
    fun the_file_is_written_before_anything_is_logged() {
        // On iOS the log call was the thing that killed the process, so
        // whatever runs after it does not run at all. The write must come first.
        val calls = mutableListOf<String>()
        CrashReport.record(
            IllegalStateException("boom"),
            write = { calls += "write" },
            log = { calls += "log" },
        )
        assertEquals(listOf("write", "log"), calls)
    }

    @Test
    fun a_failing_log_does_not_cost_the_file_and_a_failing_write_does_not_cost_the_log() {
        var written: String? = null
        CrashReport.record(IllegalStateException("boom"), write = { written = it }, log = { error("log broke") })
        assertTrue(written!!.contains("boom"))

        var logged: String? = null
        CrashReport.record(IllegalStateException("boom"), write = { error("disk broke") }, log = { logged = it })
        assertTrue(logged!!.contains("boom"))
    }

    @Test
    fun the_report_names_the_class_message_causes_and_suppressed() {
        val root = IllegalArgumentException("root cause")
        val t = IllegalStateException("outer", root)
        t.addSuppressed(RuntimeException("coroutine context: FlushCoroutineDispatcher"))
        val report = CrashReport.format(t)
        assertTrue(report.startsWith("UNHANDLED IllegalStateException: outer\n"), report)
        assertTrue(report.contains("CAUSED BY IllegalArgumentException: root cause"), report)
        assertTrue(report.contains("SUPPRESSED RuntimeException: coroutine context: FlushCoroutineDispatcher"), report)
    }

    @Test
    fun a_cause_cycle_does_not_hang_the_hook() {
        // The hook runs right before abort(); a cause chain that loops must
        // not keep it spinning there.
        val report = CrashReport.format(SelfCaused("loop"))
        assertTrue(report.startsWith("UNHANDLED SelfCaused: loop\n"), report)
        assertFalse(report.contains("CAUSED BY"), report)
    }

    @Test
    fun the_nslog_literal_has_no_conversion_specifier_left() {
        // The report is user-facing text from anywhere — a server message, a
        // URL with %20, a Python "%s" log line. Passed as NSLog's format with
        // no varargs, any surviving specifier reads an argument that is not there.
        val escaped = CrashReport.nsLogLiteral("[CrashRecorder] UNHANDLED x: 100% %@ %s %d %%")
        assertEquals("[CrashRecorder] UNHANDLED x: 100%% %%@ %%s %%d %%%%", escaped)
        // Every % is now part of a %% pair.
        assertFalse(Regex("(?<!%)%(%%)*(?!%)").containsMatchIn(escaped), escaped)
    }
}

private class SelfCaused(message: String) : Exception(message) {
    override val cause: Throwable get() = this
}
