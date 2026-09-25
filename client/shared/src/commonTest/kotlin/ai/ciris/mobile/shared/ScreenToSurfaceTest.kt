package ai.ciris.mobile.shared

import ai.ciris.mobile.shared.ui.nav.NavSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The disposition of every screen with no nav row of its own (CSD-085 §2).
 *
 * These were one `when` branch that sent Startup, Login, Setup,
 * ServerConnection, ClaimNode and Help all to [NavSurface.Help]. Four of them
 * were masked by `showSidebar`; ClaimNode was not, so the claim screen
 * rendered with Help highlighted and a null tab — and a flow asserting "I am
 * on Help" from the highlighted row would have passed on it.
 */
class ScreenToSurfaceTest {

    @Test
    fun preShellScreensHaveNoSurface() {
        // The same four showSidebar drops: no shell, so nothing to highlight.
        for (s in listOf(Screen.Startup, Screen.Login, Screen.Setup, Screen.ServerConnection)) {
            assertNull(screenToSurface(s), "$s is pre-shell and must not light a surface")
        }
    }

    @Test
    fun nodeLeavesKeepNodesLit() {
        // Parent-surface convention: a leaf keeps its parent card lit
        // (UserChat -> Contacts, DutyConferral -> Accord, SkillImport -> Skills).
        assertEquals(NavSurface.Nodes, screenToSurface(Screen.ClaimNode))
        assertEquals(NavSurface.Nodes, screenToSurface(Screen.VerifyAgent))
    }

    @Test
    fun addFederationIdHasNoSingleParent() {
        assertNull(screenToSurface(Screen.AddFederationId))
    }

    @Test
    fun onlyHelpIsHelp() {
        assertEquals(NavSurface.Help, screenToSurface(Screen.Help))
        val flowOnly = listOf(
            Screen.Startup, Screen.Login, Screen.Setup, Screen.ServerConnection,
            Screen.ClaimNode, Screen.VerifyAgent, Screen.AddFederationId,
        )
        for (s in flowOnly) {
            assertNotEquals(NavSurface.Help, screenToSurface(s), "$s must not highlight Help")
        }
    }

    @Test
    fun theStatedConventionStillHolds() {
        assertEquals(NavSurface.Contacts, screenToSurface(Screen.UserChat("k1", "c1", "Ada")))
        assertEquals(NavSurface.Accord, screenToSurface(Screen.DutyConferral))
        assertEquals(NavSurface.Skills, screenToSurface(Screen.SkillImport))
    }
}
