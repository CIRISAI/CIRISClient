package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.platform.EnvFileUpdater
import ai.ciris.mobile.shared.platform.SecureStorage
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The lens-traces card offers "Enable" only on an affirmative
 * adapter-not-loaded answer (Codex, PR #126): a refresh that failed before it
 * ever asked the accord route left `accordFailure` null, and null drew Enable.
 */
class DataManagementAccordOfferTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun aRefreshThatFailedBeforeTheAccordReadDoesNotOfferEnable() {
        var accordAsked = false
        val vm = DataManagementViewModel(
            CIRISApiClient(baseUrl = "http://127.0.0.1:9"),
            SecureStorage(),
            EnvFileUpdater(),
            readLensIdentifier = { throw RuntimeException("API error: HTTP 503 Service Unavailable") },
            readAccordSettings = { accordAsked = true; throw IllegalStateException("never reached") },
        )
        vm.refresh()
        assertEquals(false, accordAsked)
        val offer = accordOfferOf(vm.accordSettings.value, vm.accordFailure.value)
        assertNotEquals<AccordOffer>(AccordOffer.EnableAdapter, offer, "a read that failed is never drawn as the Enable button")
        assertIs<AccordOffer.Failure>(offer)
    }

    @Test
    fun enableIsOfferedOnlyOnTheAdapterNotLoadedAnswer() {
        assertEquals(AccordOffer.Unread, accordOfferOf(null, null), "a read nobody made is not 'adapter not loaded'")
        assertEquals(AccordOffer.EnableAdapter, accordOfferOf(null, ReadFailure.NotOnThisNode("HTTP 404")))
        assertIs<AccordOffer.Failure>(accordOfferOf(null, ReadFailure.Failed("HTTP 500")))
    }
}
