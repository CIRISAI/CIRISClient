package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared scaffolding for the 10 federation sub-screen ViewModels.
 *
 * Intentionally minimal — the federation sub-screens have very
 * different state shapes (peer list, peer detail, SAS verification,
 * events stream, content fetch, ...), so this base only carries the
 * pieces every screen actually shares:
 *
 *  - the injected [CIRISApiClient]
 *  - a [loading] flag for spinner state
 *  - an [error] string for surface-level failure messaging
 *  - a [runApi] helper that wraps a suspend call with consistent
 *    loading-flag + try/catch wiring
 *
 * Sub-screens add their own typed [StateFlow]s and call [runApi] for
 * the network round-trips. Do not push specialised state into this
 * base; the temptation to grow it into a god-base is real.
 */
abstract class BaseFederationViewModel(
    protected val apiClient: CIRISApiClient,
) : ViewModel() {

    protected abstract val tag: String

    protected val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    protected val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Why the screen's PRIMARY read produced no reading, or null once it has
     * read (CSD/3 §2.2). [error] is a dismissable banner string; this is not.
     * A screen whose list or counters were never read renders this instead of
     * its empty state or its zeroes, so a failed read can never be dismissed
     * into looking like "nothing here" (CSD-033/046/047/048/049).
     */
    protected val _readFailure = MutableStateFlow<ReadFailure?>(null)
    val readFailure: StateFlow<ReadFailure?> = _readFailure.asStateFlow()

    /**
     * [runApi] for the screen's primary read: on failure it also records
     * WHICH failure ([ReadFailure.of]: absent route vs failed read); on
     * success it clears it. Returns the result, or null on failure.
     */
    protected suspend fun <T : Any> runRead(
        operation: String,
        block: suspend () -> T,
    ): T? = try {
        _loading.value = true
        _error.value = null
        block().also { _readFailure.value = null }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        val msg = e.message ?: e::class.simpleName ?: "unknown error"
        _error.value = msg
        _readFailure.value = ReadFailure.of(e)
        PlatformLogger.e(tag, "[$operation] $msg", e)
        null
    } finally {
        _loading.value = false
    }

    /** Acknowledge a transient error after the user sees it. */
    fun clearError() {
        _error.value = null
    }

    /**
     * Execute one network round-trip with shared loading + error
     * scaffolding. Returns the call's result on success, or ``null``
     * on exception (the exception message lands in [error]).
     *
     * Sub-screens should call this from inside their own
     * ``viewModelScope.launch`` blocks when they need to compose
     * multiple round-trips into one user action; otherwise prefer
     * [launchApi] which handles the launch boilerplate too.
     */
    protected suspend fun <T> runApi(
        operation: String,
        block: suspend () -> T,
    ): T? = try {
        _loading.value = true
        _error.value = null
        block()
    } catch (e: Exception) {
        val msg = e.message ?: e::class.simpleName ?: "unknown error"
        _error.value = msg
        PlatformLogger.e(tag, "[$operation] $msg", e)
        null
    } finally {
        _loading.value = false
    }

    /**
     * Convenience launch + [runApi] for single-call user actions.
     * Optional [onSuccess] handler runs only when the call succeeded.
     */
    protected fun <T> launchApi(
        operation: String,
        block: suspend () -> T,
        onSuccess: (T) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = runApi(operation, block) ?: return@launch
            onSuccess(result)
        }
    }
}
