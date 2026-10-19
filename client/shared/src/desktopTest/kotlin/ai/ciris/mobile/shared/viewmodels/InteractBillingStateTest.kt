package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On Interact, a stale BILLING credential is a credits state and a dead
 * SESSION is a session state (CIRISClient#171). The first used to be carried
 * in the same field as the second and rendered the same banner, whose button
 * signed the person out of an agent that was answering them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InteractBillingStateTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    // lint: test-scheduler-main -- Main is a StandardTestDispatcher: a cancelled view model's resume queues on its scheduler and never runs, so nothing leaks, and stopViewModels() would wait forever.
    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_stale_billing_credential_touches_nothing_about_the_session() {
        val vm = InteractViewModel(CIRISApiClient("http://127.0.0.1:9"))

        vm.onBillingCredentialStale()

        assertTrue(vm.creditStatus.value.billingCredentialStale, "credits are paused")
        assertFalse(vm.sessionExpired.value, "the session is not in question")
        assertNull(vm.authError.value, "and nothing navigates to Login")
    }

    @Test
    fun a_session_expiry_is_the_session_state_and_not_a_credits_one() {
        val vm = InteractViewModel(CIRISApiClient("http://127.0.0.1:9"))

        vm.onSessionExpired()

        assertTrue(vm.sessionExpired.value)
        assertFalse(vm.creditStatus.value.billingCredentialStale)
    }

    @Test
    fun a_refreshed_credential_retires_the_credits_state() {
        val vm = InteractViewModel(CIRISApiClient("http://127.0.0.1:9"))
        vm.onBillingCredentialStale()
        vm.onBillingRefreshStarted()
        assertTrue(vm.creditStatus.value.billingCredentialRefreshing)

        vm.onBillingRefreshFinished()
        vm.onBillingCredentialRefreshed()

        assertFalse(vm.creditStatus.value.billingCredentialRefreshing)
        assertFalse(vm.creditStatus.value.billingCredentialStale)
    }
}
