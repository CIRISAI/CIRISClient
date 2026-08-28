package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.models.CLIENT_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop version is the version, not a constant someone remembers to bump.
 *
 * `getAppVersion()` prefers the JAR manifest, and the Compose uber-jar writes
 * only `Main-Class` — so the fallback is the ONLY value desktop ever reports.
 * As a hand-maintained literal it drifted to "2.3.2" while builds shipped
 * 2.9.x, and a diagnostics bundle from a 0.5.191 build named 2.3.2 as its
 * version (CIRISClient#11).
 */
class DesktopVersionTest {

    @Test
    fun desktop_reports_the_version_this_build_actually_is() {
        assertEquals(CLIENT_VERSION, getAppVersion())
    }

    @Test
    fun and_it_is_not_the_literal_that_drifted() {
        // Named explicitly so a future re-vendor that reintroduces the constant
        // fails here rather than shipping a bundle that misreports its build.
        assertTrue(getAppVersion() != "2.3.2", "desktop is reporting the stale hand-maintained version")
    }
}
