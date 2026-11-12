package ai.ciris.mobile.shared.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A desktop starts its node with `--home` alone only when the node says it is
 * 0.5.246 or later. Every other answer keeps `--key-id ciris-client`: an older
 * node with no `--key-id` boots an existing desktop home as a second identity.
 */
class NodeLaunchArgsTest {
    private val legacy = listOf("--home", "/h", "--key-id", "ciris-client")

    @Test fun aNodeThatPicksItsOwnKeyGetsOnlyItsHome() {
        assertEquals(listOf("--home", "/h"), NodeLaunchArgs.forNode("/h", listOf(0, 5, 246)))
        assertEquals(listOf("--home", "/h"), NodeLaunchArgs.forNode("/h", listOf(0, 5, 300)))
        assertEquals(listOf("--home", "/h"), NodeLaunchArgs.forNode("/h", listOf(0, 6, 0)))
        assertEquals(listOf("--home", "/h"), NodeLaunchArgs.forNode("/h", listOf(1, 0, 0)))
    }

    @Test fun anOlderNodeOrAnUnknownOneIsStartedAsBefore() {
        assertEquals(legacy, NodeLaunchArgs.forNode("/h", listOf(0, 5, 245)))
        assertEquals(legacy, NodeLaunchArgs.forNode("/h", listOf(0, 4, 999)))
        assertEquals(legacy, NodeLaunchArgs.forNode("/h", null), "no answer is not 0.5.246")
    }

    @Test fun theVersionIsTheLineTheServerPrintsAndNothingElse() {
        assertEquals(listOf(0, 5, 246), NodeLaunchArgs.parseVersion("ciris-server 0.5.246\n"))
        assertEquals(listOf(0, 5, 246), NodeLaunchArgs.parseVersion("some banner\nciris-server 0.5.246\n"))
        assertNull(NodeLaunchArgs.parseVersion("unknown serve arg: --version"))
        assertNull(NodeLaunchArgs.parseVersion(""))
        // A number that only looks like a version, in a path or a note, is not one.
        assertNull(NodeLaunchArgs.parseVersion("keyring tier pinned: home /home/u/ciris-0.9.900 (tracked: CIRISServer#783)"))
        assertNull(NodeLaunchArgs.parseVersion("ciris-server 0.5.246 --home /x"))
        assertNull(NodeLaunchArgs.parseVersion("other-server 0.5.246"))
    }

    /** The probe against real processes: one that answers, one that refuses, one that hangs, one that is not there. */
    @Test fun theProbeBelievesOnlyACleanAnswer() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val dir = kotlin.io.path.createTempDirectory("node-version").toFile()
        fun script(name: String, body: String) = File(dir, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }.absolutePath
        try {
            assertEquals(listOf(0, 5, 246), NodeLaunchArgs.probe(script("new", "echo 'ciris-server 0.5.246'")))
            assertNull(NodeLaunchArgs.probe(script("old", "echo 'unknown serve arg: --version' >&2; exit 2")),
                "an older node refuses the flag, and its refusal is not a version")
            assertNull(NodeLaunchArgs.probe(script("liar", "echo '0.5.246'; exit 1")), "a version on a failed exit is not believed")
            assertNull(NodeLaunchArgs.probe(script("hang", "sleep 30"), timeoutSeconds = 1), "a node that does not answer is not waited for")
            assertEquals(
                listOf(0, 5, 245),
                NodeLaunchArgs.probe(script("noisy", "echo 'ciris_server: keyring tier pinned, home /h/ciris-0.9.900 (CIRISServer#783)' >&2; echo 'ciris-server 0.5.245'")),
                "a note on stderr that holds a version-like number is not the version",
            )
            assertNull(NodeLaunchArgs.probe(script("stderr-only", "echo 'ciris-server 0.5.246' >&2")), "stdout is where the server says it")
            assertNull(NodeLaunchArgs.probe(File(dir, "absent").absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }
}
