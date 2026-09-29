package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.MemoryStatsApiData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A fresh node's graph store is EMPTY, not `Total nodes — 0` (CSD-040 §2): a
 * number where there is nothing is a reading nobody took.
 */
class StorageEmptyTest {
    @Test
    fun aFreshNodeIsEmptyNotZero() {
        val fresh = MemoryStatsApiData(totalNodes = 0, nodesByType = emptyMap(), nodesByScope = emptyMap(), recentNodes24h = 0)
        assertEquals(StorageGraphState.EMPTY, storageGraphState(fresh))
    }

    @Test
    fun anythingCountedIsPopulated() {
        val some = MemoryStatsApiData(totalNodes = 3, nodesByType = mapOf("concept" to 3), nodesByScope = mapOf("local" to 3), recentNodes24h = 1)
        assertEquals(StorageGraphState.POPULATED, storageGraphState(some))
        // A zero total with a histogram is the node contradicting itself; the histogram is shown.
        val odd = MemoryStatsApiData(totalNodes = 0, nodesByType = mapOf("concept" to 1), nodesByScope = emptyMap(), recentNodes24h = 0)
        assertEquals(StorageGraphState.POPULATED, storageGraphState(odd))
    }
}
