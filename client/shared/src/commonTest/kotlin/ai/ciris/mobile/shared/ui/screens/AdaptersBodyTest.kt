package ai.ciris.mobile.shared.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-020 §2: a failed `GET /v1/system/adapters` used to fall through to
 * `adapters.isEmpty()` and tell the person "No adapters yet · Tap + to add
 * one". Error and empty are two facts and must never be the same pixels.
 */
class AdaptersBodyTest {
    @Test
    fun aFailedReadIsNotAnEmptyList() {
        assertEquals(AdaptersBody.FAILED, adaptersBody(0, isLoading = false, failure = ReadFailure.Failed("HTTP 500")))
    }

    @Test
    fun aHostWithoutTheRouteIsNotAnEmptyList() {
        assertEquals(AdaptersBody.FAILED, adaptersBody(0, isLoading = false, failure = ReadFailure.NotOnThisNode("/v1/system/adapters")))
    }

    @Test
    fun aReadThatAnsweredNoneIsEmpty() {
        assertEquals(AdaptersBody.EMPTY, adaptersBody(0, isLoading = false, failure = null))
    }

    @Test
    fun loadingIsNeitherEmptyNorFailed() {
        assertEquals(AdaptersBody.LOADING, adaptersBody(0, isLoading = true, failure = null))
    }

    @Test
    fun aListAlreadyReadStaysDrawnWhenAPollFails() {
        assertEquals(AdaptersBody.LIST, adaptersBody(2, isLoading = false, failure = ReadFailure.Failed("timeout")))
    }
}
