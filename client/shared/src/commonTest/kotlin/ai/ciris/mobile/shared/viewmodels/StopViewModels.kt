package ai.ciris.mobile.shared.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Cancel every coroutine these view models launched AND WAIT for each to end.
 * Call it in `@AfterTest` BEFORE `Dispatchers.resetMain()`.
 *
 * Cancelling alone is not enough. A coroutine parked in `delay` finishes its
 * cancellation when it is next resumed, and that resume is dispatched on
 * `Dispatchers.Main`, from the delay timer's thread, after `cancel()` has
 * returned. If `resetMain()` has run by then, there is no Main: it throws
 * "Module with the Main dispatcher is missing" outside any test, and
 * kotlinx-coroutines-test pins it on the NEXT `runTest` as
 * `UncaughtExceptionsBeforeTest`. That is how `SetupViewModel`'s add-device
 * poll (`startAddDevicePolling`) failed `BillingViewModelTest` and
 * `PortalConnectTest` on slow CI runners (PR #203) although
 * `AddDeviceSetupTest` and `AddDeviceWireTest` called `resetState()`, which
 * cancels the poll job without joining it.
 *
 * For tests whose Main is `Dispatchers.Unconfined` (or another real
 * dispatcher). Under a `StandardTestDispatcher` a cancelled coroutine's resume
 * is queued on the test scheduler, which nothing advances after the test, so
 * it never runs and does not leak; joining there would wait forever, so the
 * join is bounded and fails loudly instead.
 */
internal fun stopViewModels(viewModels: Collection<ViewModel>) = runBlocking {
    withTimeout(10_000) {
        viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
    }
}
