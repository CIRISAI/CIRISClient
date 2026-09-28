package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.NodeProfile
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-035 §2.2: the Nodes list never draws a failed owned-nodes read as a
 * plain list. The failure path still lists the local node (so the loopback
 * stays usable), which is exactly why the body has to say the rows are
 * unverified: one row reading "This device" is also what a person who owns
 * only this node sees.
 */
class NodesListBodyTest {
    private val local = NodeProfile(id = "local", name = "This device", baseUrl = "http://127.0.0.1:4243", isLocal = true)

    @Test
    fun a_failed_owned_nodes_read_is_not_a_plain_list() {
        val failed = OwnedNodesRead.Failed(ReadFailure.of(RuntimeException("owned-nodes fetch failed: 500")))
        assertEquals(NodesListBody.LIST_UNVERIFIED, nodesListBody(failed, listOf(local)))
    }

    @Test
    fun a_read_list_is_a_list_and_a_read_empty_is_empty() {
        assertEquals(NodesListBody.LIST, nodesListBody(OwnedNodesRead.Read, listOf(local)))
        assertEquals(NodesListBody.EMPTY, nodesListBody(OwnedNodesRead.Read, emptyList()))
    }

    @Test
    fun before_the_first_read_nothing_is_empty_yet() {
        assertEquals(NodesListBody.LOADING, nodesListBody(null, emptyList()))
    }
}
