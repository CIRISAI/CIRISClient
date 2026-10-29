package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **Agents you own** (CSD-132): which owned nodes the list shows, and how sure
 * it can be. What the owner's node does not know is unknown, never "no agent".
 */
class OwnedAgentsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theNewFieldsDecodeAndAnOlderRowLeavesThemUnknown() {
        val rows = json.decodeFromString(
            OwnedNodesDto.serializer(),
            """{"owner":"me","nodes":[
                {"key_id":"agent-node","is_self":false,"label":"Echo","announced":true,"agents":["occ-1"],"roster_member":false},
                {"key_id":"old-node","is_self":false}
            ]}""",
        ).nodes
        assertEquals("Echo", rows[0].label)
        assertEquals(listOf("occ-1"), rows[0].agents)
        assertNull(rows[1].agents, "an older node says nothing about agents: unknown")
        assertNull(rows[1].announced)
        assertNull(rows[1].rosterMember)
    }

    @Test
    fun agentsFirstThenNodesThatMayRunOneAndNeverYourOwnPlainDevices() {
        val nodes = listOf(
            OwnedNodeDto("phone", isSelf = true, agents = emptyList(), rosterMember = true),
            OwnedNodeDto("laptop", agents = emptyList(), rosterMember = true),
            OwnedNodeDto("managed", agents = null, rosterMember = false),
            OwnedNodeDto("home-agent", agents = listOf("occ-9"), rosterMember = true),
            OwnedNodeDto("legacy", agents = null, rosterMember = null),
            OwnedNodeDto("server", agents = emptyList(), rosterMember = false),
        )
        assertEquals(
            listOf(
                "home-agent" to OwnedAgentKind.AGENT,
                "managed" to OwnedAgentKind.MAYBE_AGENT,
                "legacy" to OwnedAgentKind.MAYBE_AGENT,
            ),
            ownedAgents(nodes).map { (n, k) -> n.keyId to k },
        )
    }
}
