package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientDelegations
import ai.ciris.mobile.shared.api.DelegationsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.CreateDelegationResponse
import ai.ciris.mobile.shared.models.federation.DelegationConstraints
import ai.ciris.mobile.shared.models.federation.DelegationDto
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the **Delegations** card (CSD-055) — the owner's view of who they've
 * authorized to act on their behalf (active device-authorization grants), plus
 * the offer / approve / refuse / revoke controls. CSD-001's read-only
 * "Delegation" preamble was folded into this card; its one honest sentence —
 * that authority delegated TO the owner cannot be read yet — lives here now.
 *
 * The flow: an agent generates a device code out-of-band (`POST
 * /v1/auth/device/code`) and shows the owner the short `user_code`. The owner
 * enters it here and approves (or refuses) — the human-consent gate. The local
 * node mints a delegated `dgrant:` token (the owner's authority, attributed to
 * the agent). The app drives the LOCAL node only with the owner session; it
 * holds no crypto.
 */
class DelegationsViewModel(
    private val apiClient: CIRISApiClient,
    private val api: DelegationsApi = ClientDelegations(apiClient),
) : ViewModel() {

    companion object {
        private const val TAG = "DelegationsVM"

        /** The owner-facing sentence for a failed call: 401 means sign in; else the node's own words. */
        internal fun failure(e: Exception, action: String): String {
            val status = (e as? NodeRefusal)?.statusCode
            return when {
                status == 401 -> "Sign in as the owner first."
                status == 403 -> "Only the node's owner can $action — a delegated session cannot."
                else -> "Couldn't $action: ${e.message}"
            }
        }
    }

    private val _delegations = MutableStateFlow<List<DelegationDto>>(emptyList())
    val delegations: StateFlow<List<DelegationDto>> = _delegations.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Why the LIST could not be read, or null when it was. Distinct from [error]
     * (the last act's failure) on purpose: a list that failed to load is not a
     * list with nothing in it, and the Manage pane must not say "No active
     * delegations" over a read that never happened.
     */
    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The most recently created delegation — the URL + PIN to hand to the agent. */
    private val _lastCreated = MutableStateFlow<CreateDelegationResponse?>(null)
    val lastCreated: StateFlow<CreateDelegationResponse?> = _lastCreated.asStateFlow()

    /**
     * The grant a revoke is waiting on the owner's confirmation for. A revoke is
     * permanent (the node signs a `withdraws` that never expires), so it goes
     * behind a ConfirmSheet naming who, what changes and who signs.
     */
    private val _pendingRevoke = MutableStateFlow<DelegationDto?>(null)
    val pendingRevoke: StateFlow<DelegationDto?> = _pendingRevoke.asStateFlow()

    /**
     * The local node's base URL (``http://host:port``) — surfaced so the offer card
     * can show/copy the full ``host:port`` the agent dials (the claim URL is relative).
     */
    val nodeBaseUrl: String get() = apiClient.baseUrl

    // NOTE: do NOT refresh() in init — the VM is constructed at app startup, before the
    // owner has logged in, so an init fetch hits /v1/auth/device/grants with no bearer →
    // 401 "missing bearer session token", and that error sticks in the UI. The Delegations
    // screen calls refresh() on entry (LaunchedEffect), by which point the session token is
    // set. (CIRISServer node-client: deferred-until-auth. #117.)

    /** Reload the active delegations from the local node. */
    fun refresh() {
        _loading.value = true
        viewModelScope.launch {
            try {
                _delegations.value = api.list()
                _listError.value = null
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[refresh] ${e.message}")
                _listError.value = failure(e, "read your delegations")
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Create a delegation to hand to an agent. Requires the owner session (sign
     * in first). `mode` is `"create"` (mint a fresh agent fed-ID) or `"existing"`
     * (bind `existingKeyId`). On success [lastCreated] carries the claim URL +
     * PIN the owner hands over, and the active list refreshes.
     */
    fun createDelegation(
        label: String,
        mode: String,
        existingKeyId: String?,
        constraints: DelegationConstraints? = null,
    ) {
        val name = label.trim()
        if (name.isEmpty()) {
            _error.value = "Give the agent a label (e.g. my-laptop-agent)."
            return
        }
        if (mode == "existing" && existingKeyId?.trim().isNullOrEmpty()) {
            _error.value = "Enter the existing fed-ID key_id, or switch to creating a new one."
            return
        }
        act("create the delegation") {
            _lastCreated.value = api.create(name, mode, existingKeyId?.trim(), constraints)
            _notice.value = "Delegation created — hand the URL + PIN to the agent."
            refresh()
        }
    }

    /** Dismiss the just-created delegation result card. */
    fun clearLastCreated() {
        _lastCreated.value = null
    }

    /**
     * Approve a pending device code the agent showed you. Requires the owner
     * session (sign in first). On success the agent receives its delegated token.
     */
    fun approve(userCode: String, constraints: DelegationConstraints? = null) {
        val code = userCode.trim()
        if (code.isEmpty()) {
            _error.value = "Enter the code the agent gave you (e.g. ABCD-1234)."
            return
        }
        act("approve", unknownCode = true) {
            api.approve(code, constraints)
            _notice.value = "Approved — the agent is now authorized to act on your behalf."
            refresh()
        }
    }

    /**
     * Refuse a pending device code (`POST /v1/auth/device/deny`). The owner's
     * "no": before this the only answers to a code someone handed you were
     * approve, or let it expire.
     */
    fun deny(userCode: String) {
        val code = userCode.trim()
        if (code.isEmpty()) {
            _error.value = "Enter the code you want to refuse (e.g. ABCD-1234)."
            return
        }
        act("refuse the code", unknownCode = true) {
            api.deny(code)
            _notice.value = "Refused — the code $code can no longer be used to act for you."
        }
    }

    /** Ask to revoke [grant]: nothing is sent until [confirmRevoke]. */
    fun askRevoke(grant: DelegationDto) {
        _pendingRevoke.value = grant
    }

    fun cancelRevoke() {
        _pendingRevoke.value = null
    }

    /** Revoke the grant [askRevoke] named (withdraw the agent's authority). */
    fun confirmRevoke() {
        val grant = _pendingRevoke.value ?: return
        _pendingRevoke.value = null
        act("revoke") {
            api.revoke(grant.clientId)
            _notice.value = "Revoked ${grant.clientId}."
            refresh()
        }
    }

    fun clearMessages() {
        _error.value = null
        _notice.value = null
    }

    private fun act(action: String, unknownCode: Boolean = false, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[$action] ${e.message}")
                _error.value =
                    if (unknownCode && (e as? NodeRefusal)?.statusCode == 404) {
                        "That code wasn't recognized — check it with the agent (codes expire)."
                    } else {
                        failure(e, action)
                    }
            } finally {
                _busy.value = false
            }
        }
    }
}
