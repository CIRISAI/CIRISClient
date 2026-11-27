package ai.ciris.mobile.shared.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The contract CIRISAgent's launcher reads (CIRISAgent#1385): `<epoch_ms> <pid>`, one line, replaced whole. */
class UiHeartbeatTest {
    @Test
    fun aBeatIsOneLineOfTimeAndPidAndLeavesNoTempFile() {
        val home = Files.createTempDirectory("ciris-hb").toFile()
        try {
            UiHeartbeat.beat(home, nowMs = 1_795_000_000_000, pid = 4242)
            UiHeartbeat.beat(home, nowMs = 1_795_000_005_000, pid = 4242)
            val dir = File(home, "run")
            assertEquals("1795000005000 4242\n", File(dir, UiHeartbeat.FILE_NAME).readText(), "the newest beat, whole")
            assertEquals(listOf(UiHeartbeat.FILE_NAME), dir.list()!!.toList(), "no temp file left behind")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun aHomeThatCannotBeWrittenCostsNothing() {
        val notADir = Files.createTempFile("ciris-hb", ".file").toFile()
        try {
            UiHeartbeat.beat(notADir) // must not throw
            assertTrue(notADir.isFile)
        } finally {
            notADir.delete()
        }
    }
}
