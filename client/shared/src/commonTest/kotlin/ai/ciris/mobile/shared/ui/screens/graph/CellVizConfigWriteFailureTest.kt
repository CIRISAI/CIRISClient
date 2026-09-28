package ai.ciris.mobile.shared.ui.screens.graph

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CSD-026 §6.3: a slider that did not persist looked identical to one that did
 * until the next launch, because `save` dropped every write's `Result`. The keys
 * that failed are now returned so the screen can say so.
 */
class CellVizConfigWriteFailureTest {

    @Test
    fun aRefusedWriteIsReportedByKey() = runTest {
        val failed = CellVizConfigStore.saveWith(CellVizConfig.DEFAULT) { key, _ ->
            if (key == CellVizConfigStore.ALL_KEYS.first()) Result.failure(IllegalStateException("keyring locked"))
            else Result.success(Unit)
        }
        assertEquals(listOf(CellVizConfigStore.ALL_KEYS.first()), failed)
    }

    @Test
    fun aFailedWriteDoesNotStopTheRest() = runTest {
        val written = mutableListOf<String>()
        CellVizConfigStore.saveWith(CellVizConfig.DEFAULT) { key, _ ->
            written += key
            Result.failure(IllegalStateException("x"))
        }
        assertEquals(CellVizConfigStore.ALL_KEYS.size, written.size, "best-effort per key is kept")
    }

    @Test
    fun aCleanSaveReportsNothing() = runTest {
        assertTrue(CellVizConfigStore.saveWith(CellVizConfig.DEFAULT) { _, _ -> Result.success(Unit) }.isEmpty())
    }
}
