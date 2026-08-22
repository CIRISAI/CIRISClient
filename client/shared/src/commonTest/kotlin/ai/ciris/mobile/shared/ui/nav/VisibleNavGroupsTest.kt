package ai.ciris.mobile.shared.ui.nav

import ai.ciris.mobile.shared.CIRISBuild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The runtime narrowing of the sidebar.
 *
 * One published client carries every surface; which ones it OFFERS depends on
 * the node it is attached to. These tests exist because that rule replaced a
 * compile-time flag, and a compile-time flag could not be got wrong at runtime
 * — this one can. The specific failure they guard against is a node user being
 * shown Interact, Tools, Memory or the agent settings: doors onto a wall, since
 * a bare node serves none of them.
 */
class VisibleNavGroupsTest {

    private fun groupIds(showAgent: Boolean) =
        visibleNavGroups(showAgent).map { it.id }

    @Test
    fun agentAttachmentSeesEveryGroupTheBuildCarries() {
        assertEquals(
            EPISTEMIC_NAV_GROUPS.map { it.id },
            groupIds(showAgent = true),
            "with the node serving agent surfaces, the sidebar is the full ceiling",
        )
    }

    @Test
    fun nodeAttachmentIsOfferedNoAgentOnlyGroup() {
        val ids = groupIds(showAgent = false)
        assertFalse(AGENT_GROUP.id in ids, "AGENT_GROUP offered against a bare node")
        assertFalse(CLIENT_GROUP.id in ids, "CLIENT_GROUP offered against a bare node")
    }

    @Test
    fun nodeAttachmentKeepsEverythingANodeCanActuallyServe() {
        val ids = groupIds(showAgent = false)
        // These are the node's own surfaces; narrowing must not take them.
        assertTrue(NODE_GROUP.id in ids)
        assertTrue(SAFETY_GROUP.id in ids)
        assertTrue(MANAGE_GROUP.id in ids)
        assertTrue(COMMONS_GROUP.id in ids)
    }

    @Test
    fun theAgentsOwnLayerIsWithheldButTheOtherScopesAreNot() {
        val commons = visibleNavGroups(showAgentSurfaces = false)
            .single { it.id == COMMONS_GROUP.id }
        assertFalse(
            NavSurface.LayerAgent in commons.surfaces,
            "the agent's self-scope layer is not a scope a bare node has",
        )
        assertTrue(NavSurface.LayerFamily in commons.surfaces)
        assertTrue(NavSurface.Commons in commons.surfaces)
    }

    @Test
    fun narrowingHidesSurfacesWithoutBreakingTheirRoutes() {
        // The point of narrowing the SIDEBAR and not the route table: a restored
        // screen or a deep link must still resolve, or a node that gains a brain
        // strands whoever was mid-navigation. allSurfaces() is the route
        // catalog and is deliberately taken from the ceiling.
        val everything = allSurfaces()
        assertEquals(
            CIRISBuild.HAS_AGENT,
            NavSurface.Interact in everything,
            "a surface the BUILD carries must stay routable even when the " +
                "attachment is not offered it; one the build dropped must not",
        )
    }

    @Test
    fun narrowingIsPurelySubtractive() {
        val full = visibleNavGroups(showAgentSurfaces = true)
        val narrowed = visibleNavGroups(showAgentSurfaces = false)
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
    fun aStrippedBuildStillNarrowsToTheSameNodeSidebar() {
        // The ceiling and the runtime gate are independent. If HAS_AGENT is ever
        // flipped false for a deliberately stripped build, a node attachment must
        // see exactly what it sees from the shipped build — otherwise the two
        // ways of arriving at "no agent surfaces" would disagree.
        if (!CIRISBuild.HAS_AGENT) {
            assertEquals(
                groupIds(showAgent = true),
                groupIds(showAgent = false),
                "a stripped build has no agent groups to withhold",
            )
        }
    }
}
