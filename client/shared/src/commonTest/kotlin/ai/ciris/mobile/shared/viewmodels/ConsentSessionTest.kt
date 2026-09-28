package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.models.PartnershipQueue
import ai.ciris.mobile.shared.ui.screens.ConsentAuditEntryData
import ai.ciris.mobile.shared.ui.screens.ConsentScreenData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Consent card forgets the previous owner when the session ends (CSD-054
 * §2; the Manage Consent half of the same defect was PR #116). The screen's
 * spinner shows only while the model holds NO record, so a record left over
 * from the last owner is what the next one reads for the whole reload.
 */
class ConsentSessionTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private val previousOwner = ConsentScreenData(
        hasConsent = true,
        currentStream = "partnered",
        expiresAt = null,
        partnershipPending = true,
        auditEntries = listOf(ConsentAuditEntryData("e1", "2026-09-01T00:00:00Z", "temporary", "partnered", "alice", null)),
    )

    private fun vm() = ConsentViewModel(CIRISApiClient(baseUrl = "http://127.0.0.1:9"), initialData = previousOwner)

    @Test
    fun aSessionEndForgetsThePreviousOwnersRecord() {
        val vm = vm()
        assertTrue(vm.consentData.value.hasConsent)
        vm.sessionChanged(authenticated = false)
        val s = vm.consentData.value
        assertFalse(s.hasConsent)
        assertNull(s.currentStream)
        assertFalse(s.partnershipPending)
        assertTrue(s.auditEntries.isEmpty())
        assertEquals(PartnershipQueue.Loading, vm.partnershipQueue.value)
        assertNull(vm.partnershipOptions.value)
        assertTrue(vm.partnershipHistory.value.isEmpty())
    }

    @Test
    fun aSessionThatExistsIsNotAReset() {
        val vm = vm()
        // Home Assistant add-on mode: the token stays null by design, and the
        // effect passes consentSessionAuthenticated(null, isHAAddonMode=true).
        vm.sessionChanged(consentSessionAuthenticated(currentAccessToken = null, isHAAddonMode = true))
        assertTrue(vm.consentData.value.hasConsent, "an ingress-header session is a session, not a logout")
    }
}
