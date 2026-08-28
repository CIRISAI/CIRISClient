package ai.ciris.mobile.shared.models

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The floor is declared twice. This keeps the copies equal.
 *
 * `compat/matrix.json` has carried `node_min` since 0.5.185 — one row per
 * release, append-only, each with a written reason. `MIN_NODE_VERSION` is the
 * same fact in Kotlin, where the banner can read it. Nothing structural held
 * them together, and the first version of the constant was a DIFFERENT NUMBER:
 * the server's client-floor from CIRISServer#497, which answers the opposite
 * question. The client would have nagged on nodes the matrix calls supported.
 *
 * Reads the matrix rather than restating it. A test that hard-codes 0.5.168
 * proves the constant equals a literal in a test file, which is not the claim.
 */
class CompatibilityFloorMatchesMatrixTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val d = dir ?: return@repeat
            if (File(d, "compat/matrix.json").isFile) return d
            dir = d.parentFile
        }
        error("could not find compat/matrix.json above ${File(".").absolutePath}")
    }

    /** Minimal extraction — no JSON dependency in this source set. */
    private fun nodeMinFor(version: String): String? {
        val text = File(repoRoot(), "compat/matrix.json").readText()
        val idx = text.indexOf("\"client_version\": \"$version\"")
        if (idx < 0) return null
        return Regex("\"node_min\"\\s*:\\s*\"([^\"]+)\"")
            .find(text.substring(idx))?.groupValues?.get(1)
    }

    @Test
    fun the_constant_equals_the_matrix_row_for_this_version() {
        val nodeMin = nodeMinFor(CLIENT_VERSION)
        assertTrue(
            nodeMin != null,
            "compat/matrix.json has no row for $CLIENT_VERSION — a release without " +
                "its matrix row does not merge, and this is that gate reaching Kotlin",
        )
        assertEquals(
            nodeMin, MIN_NODE_VERSION,
            "MIN_NODE_VERSION and compat/matrix.json disagree about the oldest " +
                "supported node. Same fact, two copies; the matrix is where it is " +
                "reasoned about, so change it there and follow here.",
        )
    }
}
