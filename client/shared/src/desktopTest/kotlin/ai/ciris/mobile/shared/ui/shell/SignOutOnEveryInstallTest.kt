package ai.ciris.mobile.shared.ui.shell

import ai.ciris.mobile.shared.auth.SESSION_KEYS
import ai.ciris.mobile.shared.auth.SessionStore
import ai.ciris.mobile.shared.auth.SignOut
import ai.ciris.mobile.shared.auth.clearSession
import ai.ciris.mobile.shared.platform.TestAutomation
import ai.ciris.mobile.shared.ui.theme.BrightnessPreference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A RUN-WITHOUT-AI INSTALL CAN SIGN OUT, AND IT IS THE SAME SIGN-OUT (CIRISClient#51).
 *
 * The node-mode client has no agent Settings, so My things carries the way
 * out: `btn_my_things_sign_out` → the three-fact confirm → [SignOut.confirm],
 * which clears the session from secure storage and lands on Login. Rendered
 * headless at a phone with `hasAgent = false` and driven through the same
 * click handlers a harness drives.
 */
class SignOutOnEveryInstallTest {

    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    private val registered = mutableMapOf<String, Box>()
    private val density = 3f
    private val widthPx = (360 * density).toInt()
    private val heightPx = (640 * density).toInt()

    @BeforeTest
    fun arm() = TestAutomation.configure(
        onRegister = { tag, x, y, w, h, _ -> registered[tag] = Box(x, y, w, h) },
        onUnregister = { registered.remove(it) },
        onSetScreen = {},
        onClear = { registered.clear() },
        isEnabled = { true },
    )

    @AfterTest
    fun disarm() = TestAutomation.configure(
        onRegister = { _, _, _, _, _, _ -> },
        onUnregister = {},
        onSetScreen = {},
        onClear = {},
        isEnabled = { false },
    )

    /** Secure storage holding a signed-in session. */
    private class Store : SessionStore {
        var token: String? = "tok-123"
        val values = SESSION_KEYS.associateWith { "v" }.toMutableMap()
        override suspend fun deleteAccessToken(): Result<Unit> { token = null; return Result.success(Unit) }
        override suspend fun delete(key: String): Result<Unit> { values.remove(key); return Result.success(Unit) }
    }

    private fun assertOnScreen(tag: String) {
        val b = assertNotNull(registered[tag], "$tag is not on screen: ${registered.keys}")
        assertTrue(b.w > 0 && b.h > 0 && b.x >= 0 && b.y >= 0 && b.x + b.w <= widthPx && b.y + b.h <= heightPx, "$tag lies off a phone: $b")
    }

    @Test
    fun aNodeModeClientSignsOutFromMyThingsThroughTheConfirm() {
        val store = Store()
        var route = "Contacts"
        var stopped = 0
        var token: String? = "tok-123"
        // The wiring CIRISApp gives it, with SettingsViewModel.logout's storage half
        // (clearSession) over a fake store in place of the platform keyring.
        val signOut = SignOut(
            stopChat = { stopped++ },
            logout = { done -> runBlocking { clearSession(store) }; done() },
            toLogin = { token = null; route = "Login" },
        )
        var sheetOpen by mutableStateOf(true)
        ImageComposeScene(width = widthPx, height = heightPx, density = Density(density)) {
            if (sheetOpen) {
                MyThingsSheet(
                    hasAgent = false,
                    version = "v0",
                    brightness = BrightnessPreference.SYSTEM,
                    onBrightness = {},
                    onInstrument = {},
                    onDismiss = { sheetOpen = false },
                    onSignOut = { sheetOpen = false; signOut.ask() },
                )
            }
            SignOutConfirm(signOut)
        }.use { scene ->
            var t = 0L
            fun frames(n: Int = 120) = repeat(n) { _ -> scene.render(t); t += 16_000_000L }
            frames()
            assertOnScreen(SIGN_OUT_TAG)

            assertTrue(TestAutomation.triggerClick(SIGN_OUT_TAG), "$SIGN_OUT_TAG is not drivable")
            frames()
            // The row opens the confirm; it signs nothing out by itself.
            assertEquals("tok-123", store.token, "the row signed out without a confirm")
            assertEquals("Contacts", route)
            assertOnScreen("sheet_sign_out")
            for (i in 1..3) assertTrue("sign_out_fact_$i" in registered, "fact $i of the sign-out confirm is missing: ${registered.keys}")
            assertOnScreen("btn_sign_out_cancel")
            assertOnScreen("btn_sign_out_confirm")

            assertTrue(TestAutomation.triggerClick("btn_sign_out_confirm"))
            frames()
            assertNull(store.token, "the access token is still in secure storage")
            assertTrue(store.values.isEmpty(), "session keys left in secure storage: ${store.values.keys}")
            assertNull(token, "the in-memory token survived sign-out")
            assertEquals("Login", route)
            assertEquals(1, stopped, "chat polling was not stopped exactly once")
            assertFalse("sheet_sign_out" in registered, "the confirm stayed open after signing out")
        }
    }

    @Test
    fun cancellingTheConfirmLeavesTheSessionAlone() {
        var signedOut = false
        val signOut = SignOut(stopChat = {}, logout = { signedOut = true; it() }, toLogin = {})
        signOut.ask()
        assertTrue(signOut.confirming.value)
        signOut.dismiss()
        assertFalse(signOut.confirming.value)
        assertFalse(signedOut)
    }

    // ── No second sign-out path ──────────────────────────────────────────────

    private fun app(): String {
        val roots = listOf("src/commonMain/kotlin", "shared/src/commonMain/kotlin", "client/shared/src/commonMain/kotlin")
        return roots.map { File(it, "ai/ciris/mobile/shared/CIRISApp.kt") }.first { it.exists() }.readText()
    }

    /**
     * The agent install's controls and My things' run the SAME [SignOut]: one
     * construction, over `settingsViewModel.logout`, and every logout callback
     * is `signOut.now()`. CIRISApp is a 6,000-line composable with no harness,
     * so this reads its source — the trade `SignOutReachTest` makes.
     */
    @Test
    fun everySignOutControlRunsTheOneSignOut() {
        val src = app()
        assertEquals(1, Regex("""ai\.ciris\.mobile\.shared\.auth\.SignOut\(""").findAll(src).count(), "more than one SignOut in CIRISApp")
        assertTrue("logout = { done -> settingsViewModel.logout(done) }" in src, "the SignOut is not over SettingsViewModel.logout")
        assertTrue("onSignOut = { showMyThings = false; signOut.ask() }" in src, "My things' sign-out does not open the one sign-out's confirm")
        assertTrue("SignOutConfirm(signOut)" in src, "the confirm is not hosted over the one sign-out")
        val callbacks = Regex("""(onLogout|onLogoutClick) = \{([\s\S]*?)\n\s*\},""").findAll(src).toList()
        assertTrue(callbacks.size >= 3, "expected Settings', My Identity's and the menu's logout callbacks; parsed ${callbacks.size}")
        for (m in callbacks) {
            val body = m.groupValues[2]
            assertTrue("signOut.now()" in body, "${m.groupValues[1]} does not run signOut.now():$body")
            assertFalse("settingsViewModel.logout" in body, "${m.groupValues[1]} carries its own logout — a second sign-out path:$body")
        }
    }
}
