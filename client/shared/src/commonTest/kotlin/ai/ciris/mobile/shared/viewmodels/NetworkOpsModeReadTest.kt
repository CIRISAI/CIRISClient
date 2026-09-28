package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.AgentMode
import ai.ciris.mobile.shared.models.AgentModeStatus
import ai.ciris.mobile.shared.ui.screens.NOT_READ
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import ai.ciris.mobile.shared.ui.screens.netopsModeValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * CSD-036 §6: the Network card printed `PROXY` on a node without an agent,
 * because the row rendered the selector's Kotlin default whether or not
 * anything was read. A mode that was not read has no rendering; the reason is
 * rendered instead.
 */
class NetworkOpsModeReadTest {

    private fun vm() = NetworkViewModel(CIRISApiClient("http://127.0.0.1:1", null))

    private fun status(mode: AgentMode) = AgentModeStatus(
        mode = mode,
        serverEligible = true,
        availableDiskBytes = 1L,
        serverMinimumDiskBytes = 1L,
        dataDir = "/tmp",
    )

    @Test
    fun nothingReadIsNotAMode() {
        val v = vm()
        assertNull(v.modeRead.value, "a mode nobody read")
        assertEquals(NOT_READ, netopsModeValue(v.modeRead.value))
    }

    @Test
    fun aNodeWithoutTheRouteSaysSoAndDropsTheReading() {
        val v = vm()
        v.recordModeRead(status(AgentMode.SERVER), null)
        assertEquals("SERVER", netopsModeValue(v.modeRead.value))

        v.recordModeRead(null, RuntimeException("Agent-mode fetch failed: 404 Not Found"))
        assertNull(v.modeRead.value, "a failed read must not leave the last reading standing")
        assertNull(v.status.value)
        assertIs<ReadFailure.NotOnThisNode>(v.modeFailure.value)
    }

    @Test
    fun aFailedReadIsAnErrorNotAnAbsentRoute() {
        val v = vm()
        v.recordModeRead(null, RuntimeException("connect refused"))
        assertIs<ReadFailure.Failed>(v.modeFailure.value)
        assertEquals(NOT_READ, netopsModeValue(v.modeRead.value))
    }
}
