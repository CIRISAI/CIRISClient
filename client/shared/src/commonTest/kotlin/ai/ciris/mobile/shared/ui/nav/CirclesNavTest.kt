package ai.ciris.mobile.shared.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE ONE TREE, PINNED.
 *
 * Every surface the app can show is placed exactly once — in a tab for some
 * circles, or under an instrument — or is a flow. No orphans (the fourteen
 * chevron-only surfaces the old rail had), no cross-framing (the four filed
 * under one group while hanging off a parent in another), and the node build
 * is a subset of the agent build (CIRISServer#479: the nav is a function of
 * the PROBED node — a node user must never be offered a door onto a wall).
 */
class CirclesNavTest {

    private val allSurfaces: List<NavSurface> = listOf(
        NavSurface.Interact, NavSurface.Sessions, NavSurface.Tickets, NavSurface.Scheduler, NavSurface.Tools,
        NavSurface.Services, NavSurface.Logs, NavSurface.Transport, NavSurface.Telemetry, NavSurface.GraphMemory,
        NavSurface.Memory, NavSurface.WiseAuthority, NavSurface.LLMSettings, NavSurface.System, NavSurface.Runtime,
        NavSurface.Config, NavSurface.Skills, NavSurface.AgentSettings, NavSurface.HealthReputation,
        NavSurface.Users, NavSurface.Adapters, NavSurface.NetworkOps, NavSurface.Storage, NavSurface.Audit,
        NavSurface.Consent, NavSurface.Data, NavSurface.Trust, NavSurface.Nodes, NavSurface.ManageConsent,
        NavSurface.Contacts, NavSurface.Delegations, NavSurface.IdentityManagement, NavSurface.Accord,
        NavSurface.ProvisionAccordHolder, NavSurface.AccordCeremony, NavSurface.Moderation, NavSurface.ChildSafety,
        NavSurface.Wallet, NavSurface.Billing, NavSurface.LayerAgent, NavSurface.EnvironmentGraph,
        NavSurface.Delegation, NavSurface.Constitutional, NavSurface.Commons, NavSurface.LayerFamily,
        NavSurface.LayerLocalCommunity, NavSurface.LayerGlobalCommunities, NavSurface.LayerGlobalCommons,
        NavSurface.ClientInterface, NavSurface.Help,
    )

    @Test
    fun everySurfaceIsPlacedExactlyOnceOrIsAFlow() {
        for (s in allSurfaces) {
            val inTabs = CirclesNav.placements.count { it.surface == s }
            val inInstruments = CirclesNav.instruments.count { s in it.surfaces }
            val flow = s.id in FLOW_ONLY_SURFACES
            assertEquals(1, inTabs + inInstruments + (if (flow) 1 else 0), "${s.id}: placed $inTabs× in tabs, $inInstruments× in instruments, flow=$flow")
        }
    }

    @Test
    fun theTabsAreTheSevenInTheLockedOrder() {
        assertEquals(listOf("files", "chats", "people", "safety", "rules", "decisions", "record"), Tab.entries.map { it.id })
        assertEquals(listOf("agent", "family", "local-community", "global-communities", "global-commons"), CirclesNav.circles.map { it.id })
    }

    @Test
    fun peopleSafetyRulesAndRecordAreNeverEmptyOnAnyBuild() {
        for (c in CirclesNav.circles) for (hasAgent in listOf(false, true)) {
            for (t in listOf(Tab.PEOPLE, Tab.SAFETY, Tab.RULES, Tab.RECORD)) {
                assertTrue(CirclesNav.cards(c, t, hasAgent).isNotEmpty(), "${c.id} › ${t.id} (agent=$hasAgent) has no cards")
            }
        }
    }

    @Test
    fun theHonestEmptiesAreWhereTheDesignSaysTheyAre() {
        assertTrue(CirclesNav.cards(CohortScope.AGENT, Tab.DECISIONS, hasAgent = true).isEmpty(), "Just me has nobody to decide with")
        assertEquals("nav.empty.decisions_agent", CirclesNav.emptyKey(CohortScope.AGENT, Tab.DECISIONS))
        assertEquals("nav.empty.decisions_family", CirclesNav.emptyKey(CohortScope.FAMILY, Tab.DECISIONS))
        assertEquals("nav.empty.files", CirclesNav.emptyKey(CohortScope.FAMILY, Tab.FILES))
    }

    /**
     * WITH NO AGENT, JUST ME › CHATS IS EMPTY FOR A REASON, AND IT IS NOT
     * "no conversations yet". There is nobody to converse with; saying
     * otherwise tells a node owner to wait for something that is not coming.
     */
    @Test
    fun justMeChatsWithNoAgentSaysThereIsNoAgent() {
        assertTrue(CirclesNav.cards(CohortScope.AGENT, Tab.CHATS, hasAgent = false).isEmpty())
        assertEquals("nav.empty.chats_agent", CirclesNav.emptyKey(CohortScope.AGENT, Tab.CHATS))
        assertEquals("nav.empty.chats", CirclesNav.emptyKey(CohortScope.FAMILY, Tab.CHATS))
    }

    @Test
    fun theNodeBuildIsASubsetOfTheAgentBuild() {
        for (c in CirclesNav.circles) for (t in Tab.entries) {
            val node = CirclesNav.cards(c, t, hasAgent = false)
            val agent = CirclesNav.cards(c, t, hasAgent = true)
            assertTrue(agent.containsAll(node), "${c.id} › ${t.id}: the node build offers something the agent build does not")
        }
        for (i in CirclesNav.instruments) assertTrue(i.surfaces(true).containsAll(i.surfaces(false)), i.id)
        assertTrue(CirclesNav.instruments(true).containsAll(CirclesNav.instruments(false)))
        assertFalse(NavSurface.Interact in CirclesNav.cards(CohortScope.AGENT, Tab.CHATS, hasAgent = false), "Interact offered against a bare node")
        assertTrue(NavSurface.Interact in CirclesNav.cards(CohortScope.AGENT, Tab.CHATS, hasAgent = true))
    }

    /**
     * A BARE NODE CAN STILL SIGN OUT (CIRISClient#51) — through My Identity,
     * where `btn_logout` lives on every build. There is no "account" in CIRIS,
     * only identities, so there is no Account row: it was a second route onto
     * Settings, kept only because Settings was once agent-gated.
     */
    @Test
    fun aBareNodeCanSignOutThroughMyIdentity() {
        val inst = CirclesNav.instrumentOf(NavSurface.IdentityManagement)
        assertNotNull(inst)
        assertEquals("nav_instrument_devices_keys", inst.tag)
        assertTrue(NavSurface.IdentityManagement in inst.surfaces(hasAgent = false), "a bare node cannot reach My Identity, so cannot sign out")
        assertTrue(inst in CirclesNav.instruments(hasAgent = false), "Devices & keys is not offered on a bare node")
        assertFalse(CirclesNav.isAgentOnly(NavSurface.IdentityManagement))
        // Settings stays reachable on every build too (language, ground).
        assertTrue(NavSurface.AgentSettings in inst.surfaces(hasAgent = false))
    }

    @Test
    fun thereIsNoAccountSurface() {
        val placed = CirclesNav.placements.map { it.surface.id } + CirclesNav.instruments.flatMap { i -> i.surfaces.map { it.id } }
        assertFalse("account" in placed, "an Account row is back: CIRIS has identities, not accounts")
        assertFalse("account" in FLOW_ONLY_SURFACES)
    }

    /**
     * THIS AGENT IS THE BRAIN; THIS NODE IS THE SUBSTRATE (CC 4.4.3.4.3:
     * `agency:*` vs `infra:*`). The split is also the host split, so every
     * This-agent row calls routes only the agent serves and the whole
     * instrument goes with the agent — while This node keeps every row on a
     * bare node, Memory included, because the node serves `/v1/memory/stats`, `timeline` and `query`.
     */
    @Test
    fun thisAgentIsTheBrainAndGoesWithIt() {
        val agent = CirclesNav.instruments.single { it.id == "this-agent" }
        assertEquals("nav_instrument_this_agent", agent.tag)
        assertEquals(
            setOf(
                NavSurface.LLMSettings, NavSurface.Adapters, NavSurface.Services, NavSurface.Telemetry,
                NavSurface.Runtime, NavSurface.Sessions, NavSurface.Tickets, NavSurface.Scheduler,
                NavSurface.Tools, NavSurface.Skills,
            ),
            agent.surfaces.toSet(),
        )
        for (s in agent.surfaces) assertTrue(CirclesNav.isAgentOnly(s), "${s.id} calls only agent routes and is offered to a bare node")
        assertTrue(agent.isEmpty(hasAgent = false))
        assertFalse(agent in CirclesNav.instruments(hasAgent = false), "an empty This agent is offered to a bare node")
        assertTrue(agent in CirclesNav.instruments(hasAgent = true))

        val node = CirclesNav.instruments.single { it.id == "this-node" }
        assertEquals(
            setOf(
                NavSurface.Nodes, NavSurface.Transport, NavSurface.NetworkOps, NavSurface.Config,
                NavSurface.Logs, NavSurface.System, NavSurface.Memory, NavSurface.GraphMemory,
            ),
            node.surfaces.toSet(),
        )
        assertEquals(node.surfaces, node.surfaces(hasAgent = false), "This node hides something the node itself serves")
    }

    /** The four the CSDs caught offered to a bare node, and the one caught hidden from it. */
    @Test
    fun theAgentOnlyFlagsFollowTheRoutes() {
        for (s in listOf(NavSurface.Adapters, NavSurface.Services, NavSurface.Runtime, NavSurface.Telemetry, NavSurface.WiseAuthority)) {
            assertTrue(CirclesNav.isAgentOnly(s), "${s.id}: every route it calls is the agent's")
        }
        assertFalse(CirclesNav.isAgentOnly(NavSurface.Memory), "memory: the node serves /v1/memory/stats|timeline|query")
        assertFalse(CirclesNav.isAgentOnly(NavSurface.GraphMemory), "graph-memory: same store as memory")
    }

    @Test
    fun theHomesTheGatesLeanOnAreWhereTheyWere() {
        // CIRISAgent's runner and the CSDs name these; they must resolve.
        assertEquals(Tab.PEOPLE, CirclesNav.tabOf(NavSurface.Contacts))
        assertEquals(Tab.RULES, CirclesNav.tabOf(NavSurface.LayerFamily))
        assertEquals(Tab.RULES, CirclesNav.tabOf(NavSurface.LayerLocalCommunity))
        assertEquals(Tab.RULES, CirclesNav.tabOf(NavSurface.LayerGlobalCommons))
        assertEquals(Tab.DECISIONS, CirclesNav.tabOf(NavSurface.HealthReputation))
        assertEquals("this-node", CirclesNav.instrumentOf(NavSurface.Nodes)?.id)
        assertEquals("nav_epistemic_layer_family", CirclesNav.navTag(NavSurface.LayerFamily))
        assertEquals("circle_local_community", CirclesNav.circleTag(CohortScope.LOCAL_COMMUNITY))
    }

    /**
     * A TAB IS NAMED FOR WHAT IT HOLDS. The live shell showed Just me › Files
     * rendering the memory-graph list and Chats carrying the scheduler; both
     * read as the tab lying about its contents, and every card built on top
     * would have inherited that. Files holds files — so until the files spine
     * exists it holds NOTHING, and says so.
     */
    @Test
    fun filesHoldsOnlyFiles() {
        for (c in CirclesNav.circles) for (hasAgent in listOf(false, true)) {
            assertTrue(
                CirclesNav.cards(c, Tab.FILES, hasAgent).isEmpty(),
                "${c.id} › files (agent=$hasAgent): only files belong in Files — the spine is B3",
            )
        }
        assertEquals("nav.empty.files_agent", CirclesNav.emptyKey(CohortScope.AGENT, Tab.FILES))
        assertNull(CirclesNav.tabOf(NavSurface.Memory), "the memory graph is an instrument, not a file")
        assertEquals("this-node", CirclesNav.instrumentOf(NavSurface.Memory)?.id)
    }

    @Test
    fun chatsHoldsOnlyConversations() {
        for (c in CirclesNav.circles) {
            val cards = CirclesNav.cards(c, Tab.CHATS, hasAgent = true)
            assertTrue(cards.all { it == NavSurface.Interact }, "${c.id} › chats holds something that is not a conversation: $cards")
        }
        // How the machine runs is not who you talk to.
        for (s in listOf(NavSurface.Sessions, NavSurface.Tickets, NavSurface.Scheduler)) {
            assertNull(CirclesNav.tabOf(s), "${s.id} is in a circle tab")
            assertEquals("this-agent", CirclesNav.instrumentOf(s)?.id, s.id)
        }
    }

    /**
     * ONE PLACE FOR THE ACCORD. In an emergency nobody should have to remember
     * which of three tabs the kill switch was filed under: the accord family,
     * its holder roster, the flow that provisions a holder and the `accord:*`
     * attestations are all Everyone › Safety.
     */
    @Test
    fun theAccordIsAllUnderEveryoneSafety() {
        val accord = listOf(NavSurface.Accord, NavSurface.ProvisionAccordHolder, NavSurface.Constitutional)
        for (s in accord) {
            assertEquals(Tab.SAFETY, CirclesNav.tabOf(s), "${s.id} is not in Safety")
            assertEquals(
                setOf(CohortScope.GLOBAL_COMMONS),
                CirclesNav.placementOf(s)?.circles,
                "${s.id} is not in Everyone alone",
            )
        }
        assertTrue(CirclesNav.cards(CohortScope.GLOBAL_COMMONS, Tab.SAFETY, hasAgent = false).containsAll(accord))
    }

    /** Settings is this device's, not a rule of a circle — and every build has one. */
    @Test
    fun settingsLivesUnderThisDeviceOnEveryBuild() {
        assertNull(CirclesNav.tabOf(NavSurface.AgentSettings))
        val inst = CirclesNav.instrumentOf(NavSurface.AgentSettings)
        assertEquals("devices-keys", inst?.id)
        assertTrue(NavSurface.AgentSettings in inst!!.surfaces(hasAgent = false), "a node build cannot reach Settings")
        assertEquals("devices-keys", CirclesNav.instrumentOf(NavSurface.ClientInterface)?.id)
        assertFalse(NavSurface.LLMSettings in CirclesNav.instrumentOf(NavSurface.LLMSettings)!!.surfaces(hasAgent = false), "a node build has no model to configure")
    }

    @Test
    fun aSurfaceOpensInTheCurrentCircleWhenItIsThereElseItsFirst() {
        assertEquals(CohortScope.FAMILY, CirclesNav.circleFor(NavSurface.Contacts, CohortScope.FAMILY))
        assertEquals(CohortScope.LOCAL_COMMUNITY, CirclesNav.circleFor(NavSurface.Moderation, CohortScope.AGENT))
        assertEquals(CohortScope.GLOBAL_COMMONS, CirclesNav.circleFor(NavSurface.Accord, CohortScope.AGENT))
        assertEquals(null, CirclesNav.circleFor(NavSurface.Nodes, CohortScope.AGENT), "an instrument has no circle")
    }
}
