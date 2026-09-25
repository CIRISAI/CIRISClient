package ai.ciris.mobile.shared.ui.nav

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SIGN-OUT IS ON MY IDENTITY, AND IT IS WIRED (CIRISClient#51).
 *
 * `CirclesNavTest.aBareNodeCanSignOutThroughMyIdentity` proves a bare node
 * reaches My Identity. This proves My Identity then has the control: the
 * screen carries `btn_logout`, and CIRISApp's render arm hands it the logout
 * path. There is no Compose UI test harness in this module, so it reads the
 * source — the same trade `CirclesNavCopyTest` makes for en.json.
 */
class SignOutReachTest {

    private fun src(rel: String): String {
        val roots = listOf("src/commonMain/kotlin", "shared/src/commonMain/kotlin", "client/shared/src/commonMain/kotlin")
        val f = roots.map { File(it, "ai/ciris/mobile/shared/$rel") }.firstOrNull { it.exists() }
            ?: error("$rel not found from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test
    fun myIdentityCarriesTheSignOutButton() {
        val screen = src("ui/screens/IdentityManagementScreen.kt")
        assertTrue("testableClickable(\"btn_logout\")" in screen, "My Identity has no btn_logout — a bare node cannot sign out")
        assertTrue(Regex("""fun IdentityManagementScreen\([\s\S]*?onLogout: \(\) -> Unit,\s*\)""").containsMatchIn(screen),
            "IdentityManagementScreen takes no required onLogout")
    }

    @Test
    fun cirisAppWiresMyIdentityToTheLogoutPath() {
        val app = src("CIRISApp.kt")
        val arm = Regex("""Screen\.IdentityManagement -> \{[\s\S]*?IdentityManagementScreen\(([\s\S]*?)\n {16}\)""").find(app)
            ?: error("the Screen.IdentityManagement render arm did not parse — the test is wrong, not the app")
        val args = arm.groupValues[1]
        assertTrue("onLogout =" in args, "CIRISApp does not pass onLogout to My Identity")
        assertTrue("settingsViewModel.logout" in args, "My Identity's sign-out is not the logout Settings uses")
    }

    /**
     * ONE LOGOUT PER CLICK. Settings' button used to run
     * `viewModel.logout { onLogout() }` while CIRISApp's `onLogout` runs
     * `settingsViewModel.logout` itself — two revocations, two token wipes, per
     * click. `onLogout` is the logout; the screens only call it.
     * SettingsViewModel is concrete over real storage and HTTP, so this reads
     * the source rather than counting through a fake.
     */
    @Test
    fun theScreensCallOnLogoutAndNeverTheViewModelsLogout() {
        for (rel in listOf("ui/screens/SettingsScreen.kt", "ui/screens/IdentityManagementScreen.kt")) {
            val screen = src(rel)
            assertTrue(!Regex("""\bviewModel\.logout\s*[({]""").containsMatchIn(screen), "$rel calls viewModel.logout as well as onLogout: logout runs twice")
            assertTrue("testableClickable(\"btn_logout\") { onLogout() }" in screen, "$rel's btn_logout does not call onLogout")
        }
    }

    /** "Signed in via …" is what the login flow RECORDED, never the platform's guess. */
    @Test
    fun theSignInLinesDoNotGuessTheProvider() {
        for (rel in listOf("ui/screens/SettingsScreen.kt", "ui/screens/IdentityManagementScreen.kt")) {
            assertTrue("getOAuthProviderName" !in src(rel), "$rel names the platform's OAuth provider as how this device signed in")
        }
    }
}
