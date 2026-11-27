package ai.ciris.mobile.shared.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The desktop window's heartbeat (docs/PLAN-0.5.244.md item 2.7): one line,
 * `<epoch_ms> <pid>`, at `$CIRIS_HOME/run/desktop_ui.heartbeat`, written every
 * [INTERVAL_MS] from the UI thread. CIRISAgent's desktop_launcher.py reads it
 * (CIRISAgent#1385) and reports a window stale for 30 s, without ending it.
 *
 * It proves the UI thread is turning, nothing more: a window whose clicks are
 * swallowed by an overlay (2026-11-27) still beats.
 */
object UiHeartbeat {
    const val INTERVAL_MS = 5_000L
    const val FILE_NAME = "desktop_ui.heartbeat"

    /** `CIRIS_HOME`, else `~/ciris`: the home rule the rest of the desktop uses. */
    fun defaultHome(): File = File(System.getenv("CIRIS_HOME") ?: "${System.getProperty("user.home")}/ciris")

    /**
     * Writes one beat by atomic replace, so a reader never sees half a line.
     * A failure is swallowed: a missing heartbeat must never cost the window.
     */
    fun beat(home: File = defaultHome(), nowMs: Long = System.currentTimeMillis(), pid: Long = ProcessHandle.current().pid()) {
        runCatching {
            val dir = File(home, "run").apply { mkdirs() }
            val tmp = File.createTempFile(".$FILE_NAME.", ".tmp", dir)
            try {
                tmp.writeText("$nowMs $pid\n")
                Files.move(tmp.toPath(), File(dir, FILE_NAME).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                tmp.delete()
            }
        }
    }
}
