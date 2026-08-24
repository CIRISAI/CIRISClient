package ai.ciris.mobile.shared.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The nav is a function of the PROBED node, not of the build (CIRISServer#479).
 *
 * These exist because that rule replaced a compile-time constant, and a
 * compile-time constant could not be got wrong at runtime — this can. The
 * specific failure they guard against is a node user being offered Interact,
 * Tools, Memory or the agent settings: doors onto a wall, since a node with no
 * brain serves none of them. The opposite mistake is cheap by design — a
 * surface that appears a moment late once the probe lands costs nothing — so
 * every assertion here is about what must NOT be offered.
 */
class NavGatingTest {

    private fun groupIds(hasAgent: Boolean) =
        epistemicNavGroups(hasAgent).map { it.id }

    @Test
    fun anAgentAttachmentIsOfferedTheAgentGroups() {
        val ids = groupIds(hasAgent = true)
        assertTrue(AGENT_GROUP.id in ids, "AGENT_GROUP withheld from an agent")
        assertTrue(CLIENT_GROUP.id in ids, "CLIENT_GROUP withheld from an agent")
    }

    @Test
    fun aBareNodeIsOfferedNoAgentOnlyGroup() {
        val ids = groupIds(hasAgent = false)
        assertFalse(AGENT_GROUP.id in ids, "AGENT_GROUP offered against a bare node")
        assertFalse(CLIENT_GROUP.id in ids, "CLIENT_GROUP offered against a bare node")
    }

    @Test
    fun aBareNodeKeepsEverythingANodeCanActuallyServe() {
        val ids = groupIds(hasAgent = false)
        assertTrue(NODE_GROUP.id in ids)
        assertTrue(SAFETY_GROUP.id in ids)
        assertTrue(MANAGE_GROUP.id in ids)
        assertTrue(commonsGroup(hasAgent = false).id in ids)
    }

    @Test
    fun theAgentsOwnLayerIsWithheldButTheOtherScopesAreNot() {
        val commons = commonsGroup(hasAgent = false)
        assertFalse(
            NavSurface.LayerAgent in commons.surfaces,
            "the agent's self-scope layer is not a scope a bare node has",
        )
        assertTrue(NavSurface.LayerFamily in commons.surfaces)
        assertTrue(NavSurface.Commons in commons.surfaces)
        assertTrue(
            NavSurface.LayerAgent in commonsGroup(hasAgent = true).surfaces,
            "and it must come back the moment the node has a brain",
        )
    }

    @Test
    fun narrowingHidesSurfacesWithoutBreakingTheirRoutes() {
        // The sidebar narrows; the ROUTE TABLE does not. A restored screen or a
        // deep link has to keep resolving, or a node that gains a brain strands
        // whoever was mid-navigation. allSurfaces() is that catalog.
        assertTrue(
            NavSurface.Interact in allSurfaces(),
            "Interact must stay routable even when it is not offered",
        )
        assertFalse(
            AGENT_GROUP.id in groupIds(hasAgent = false),
            "…and must still not be advertised to a bare node",
        )
    }

    @Test
    fun narrowingIsPurelySubtractive() {
        val full = epistemicNavGroups(hasAgent = true)
        val narrowed = epistemicNavGroups(hasAgent = false)
        assertTrue(
            narrowed.size <= full.size,
            "narrowing may only remove groups, never add one",
        )
        narrowed.forEach { group ->
            val same = full.single { it.id == group.id }
            assertTrue(
                same.surfaces.containsAll(group.surfaces),
                "group ${group.id} gained a surface when narrowed",
            )
        }
    }

    @Test
    fun theTwoModesDifferOnlyInTheAgentSurfaces() {
        // A regression that quietly dropped a NODE surface from the node build
        // would still pass every assertion above. This pins the difference
        // itself: exactly the agent-only groups, nothing else.
        val full = epistemicNavGroups(hasAgent = true).map { it.id }.toSet()
        val narrowed = epistemicNavGroups(hasAgent = false).map { it.id }.toSet()
        assertEquals(
            setOf(AGENT_GROUP.id, CLIENT_GROUP.id),
            full - narrowed,
            "the mode should decide the agent groups and nothing else",
        )
        assertTrue(narrowed - full == emptySet<String>())
    }
}
