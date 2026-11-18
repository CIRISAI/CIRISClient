package ai.ciris.mobile.shared.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reset Device is the most destructive thing this app can do, and it shipped
 * four wrong answers: it deleted an unrelated installed agent, then a whole git
 * checkout including uncommitted work, then anything sharing a directory with a
 * bare `data/`, then the state of a node the client was not even talking to.
 *
 * Every one of those is a statement about what SURVIVES a wipe, which is why
 * these tests assert survival against a populated tree rather than checking
 * that the intended targets are gone.
 */
class LocalDataWipeTest {

    private lateinit var home: File

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ciris-wipe-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun populate() {
        File(home, ".env").writeText("CIRIS_CONFIGURED=true")
        File(home, "data").mkdirs()
        File(home, "data/ciris_engine.db").writeText("db")
        File(home, "identity").mkdirs()
        File(home, "identity/node.key").writeText("secret")
        File(home, "logs").mkdirs()
        File(home, "config").mkdirs()
        File(home, "config/essential.yaml").writeText("tracked source")

        // Never ours: a wipe that takes these is the bug, not the feature.
        File(home, "my_thesis.txt").writeText("years of work")
        File(home, "src").mkdirs()
        File(home, "src/main.py").writeText("code")
        File(home, ".git").mkdirs()
        File(home, ".git/HEAD").writeText("ref: refs/heads/main")
    }

    @Test
    fun `removes generated state and nothing else`() {
        populate()
        assertTrue(wipeGeneratedState(home, checkout = false))

        assertFalse(File(home, ".env").exists())
        assertFalse(File(home, "data").exists())
        assertFalse(File(home, "identity").exists())
        assertFalse(File(home, "logs").exists())

        assertTrue(File(home, "my_thesis.txt").exists(), "deleted a file it did not write")
        assertTrue(File(home, "src/main.py").exists(), "deleted source that is not node state")
        assertTrue(File(home, ".git/HEAD").exists(), "deleted the repository")
    }

    @Test
    fun `never removes the home directory itself`() {
        populate()
        wipeGeneratedState(home, checkout = false)
        assertTrue(home.exists(), "home directory was removed — bug #2 and #3")
        assertTrue(home.isDirectory)
    }

    @Test
    fun `keeps tracked config in a checkout and removes it otherwise`() {
        populate()
        wipeGeneratedState(home, checkout = true)
        assertTrue(
            File(home, "config/essential.yaml").exists(),
            "deleted tracked source: config/ is node state only outside a checkout",
        )

        tearDown(); setUp(); populate()
        wipeGeneratedState(home, checkout = false)
        assertFalse(File(home, "config").exists(), "kept generated config in a dedicated home")
    }

    @Test
    fun `an empty home succeeds without creating anything`() {
        assertTrue(wipeGeneratedState(home, checkout = false))
        assertTrue(home.listFiles()?.isEmpty() == true)
    }

    // -- A HELD FILE (Windows) ------------------------------------------------
    //
    // On Windows an open handle makes a delete fail outright. The Linux test
    // host cannot hold a file that way, so the remover is the seam: it fails
    // the first N calls for `data` the way a handle closing a moment late does.

    private fun heldFor(failures: Int, calls: MutableMap<String, Int>): (File) -> Unit = { f ->
        val n = calls.merge(f.name, 1, Int::plus)!!
        if (!(f.name == "data" && n <= failures)) {
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
    }

    @Test
    fun `a handle released a moment late is retried and the wipe succeeds`() {
        populate()
        val calls = mutableMapOf<String, Int>()
        assertTrue(wipeGeneratedState(home, checkout = false, attempts = 5, pauseMs = 1, remove = heldFor(2, calls)))
        assertFalse(File(home, "data").exists())
        assertEquals(3, calls["data"], "two failed tries, then the one that removed it")
        assertEquals(1, calls[".env"], "an entry removed on the first try is not retried")
    }

    @Test
    fun `a handle that is never released fails the wipe after the bound`() {
        populate()
        val calls = mutableMapOf<String, Int>()
        assertFalse(wipeGeneratedState(home, checkout = false, attempts = 4, pauseMs = 1, remove = heldFor(Int.MAX_VALUE, calls)))
        assertTrue(File(home, "data/ciris_engine.db").exists())
        assertEquals(4, calls["data"])
        // and nothing outside the allow-list was touched by the retries
        assertTrue(File(home, "my_thesis.txt").exists())
    }
}
