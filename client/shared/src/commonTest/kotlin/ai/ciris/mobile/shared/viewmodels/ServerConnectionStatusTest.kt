package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** CSD-084: a refused connection is not a deliberate disconnect, and rows are addressable. */
class ServerConnectionStatusTest {

    @Test
    fun anErrorDoesNotReadAsADisconnect() {
        assertNotEquals(
            serverStatusKey(ConnectionStatus.DISCONNECTED),
            serverStatusKey(ConnectionStatus.ERROR),
        )
    }

    @Test
    fun eachRecentRowHasItsOwnTag() {
        assertEquals("http_192_168_50_8_8080", recentConnectionTagSuffix("http://192.168.50.8:8080"))
        assertNotEquals(
            recentConnectionTagSuffix("http://127.0.0.1:8080"),
            recentConnectionTagSuffix("http://127.0.0.1:4243"),
        )
    }
}
