package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.FederationPeerDetailResponse
import ai.ciris.mobile.shared.models.federation.FederationPeerSASResponse
import ai.ciris.mobile.shared.models.federation.PeerAppearance
import ai.ciris.mobile.shared.models.federation.PeerTrustState
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Network → Peer Detail sub-screen.
 *
 * Lives as long as the screen does — recreated for each visited keyId.
 *
 * Owns the per-peer detail card, the SAS verification ceremony (CSD-104), and
 * the local-only appearance editor draft. Trust changes and appearance saves
 * round-trip through the API and reload the detail on success.
 */
class NetworkPeerDetailViewModel(
    apiClient: CIRISApiClient,
    val keyId: String,
) : BaseFederationViewModel(apiClient) {

    override val tag: String = "NetworkPeerDetailVM"

    // ─── Detail ─────────────────────────────────────────────────────────────

    private val _detail = MutableStateFlow<FederationPeerDetailResponse?>(null)
    val detail: StateFlow<FederationPeerDetailResponse?> = _detail.asStateFlow()

    /** Why there is no detail — null while loading or once loaded (CSD-104 §3a.5). */
    private val _detailFailure = MutableStateFlow<PeerDetailFailure?>(null)
    val detailFailure: StateFlow<PeerDetailFailure?> = _detailFailure.asStateFlow()

    // ─── SAS ceremony (CSD-104) ─────────────────────────────────────────────

    private val _sasRead = MutableStateFlow<SasRead>(SasRead.Loading)
    val sasRead: StateFlow<SasRead> = _sasRead.asStateFlow()

    /** The outcome whose ConfirmSheet is open, or null. */
    private val _pendingOutcome = MutableStateFlow<SasOutcome?>(null)
    val pendingOutcome: StateFlow<SasOutcome?> = _pendingOutcome.asStateFlow()

    private val _outcomeInFlight = MutableStateFlow(false)
    val outcomeInFlight: StateFlow<Boolean> = _outcomeInFlight.asStateFlow()

    /**
     * The last recorded (or refused) outcome. A MISMATCH stays here until the
     * screen is left: it is a safety event, and a result that vanished on the
     * next refresh would read as "nothing happened".
     */
    private val _lastOutcome = MutableStateFlow<SasOutcomeResult?>(null)
    val lastOutcome: StateFlow<SasOutcomeResult?> = _lastOutcome.asStateFlow()

    // ─── Trust state ────────────────────────────────────────────────────────

    private val _trustChangeInFlight = MutableStateFlow(false)
    val trustChangeInFlight: StateFlow<Boolean> = _trustChangeInFlight.asStateFlow()

    private val _pendingTrust = MutableStateFlow<PeerTrustState?>(null)
    val pendingTrust: StateFlow<PeerTrustState?> = _pendingTrust.asStateFlow()

    // ─── Appearance editor ──────────────────────────────────────────────────

    private val _appearanceExpanded = MutableStateFlow(false)
    val appearanceExpanded: StateFlow<Boolean> = _appearanceExpanded.asStateFlow()

    private val _appearanceDraft = MutableStateFlow(PeerAppearance())
    val appearanceDraft: StateFlow<PeerAppearance> = _appearanceDraft.asStateFlow()

    private val _appearanceSaving = MutableStateFlow(false)
    val appearanceSaving: StateFlow<Boolean> = _appearanceSaving.asStateFlow()

    // ─── Actions ────────────────────────────────────────────────────────────

    fun load() {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val resp = apiClient.getFederationPeer(keyId)
                _detail.value = resp
                _detailFailure.value = null
                _appearanceDraft.value = resp.peer.appearance ?: PeerAppearance()
            } catch (e: Exception) {
                PlatformLogger.e(tag, "getFederationPeer failed: ${e.message}", e)
                if (_detail.value == null) {
                    _detailFailure.value = peerDetailFailureOf(e)
                } else {
                    // A refresh over a loaded peer keeps what was read and says it failed.
                    _error.value = e.message ?: e::class.simpleName
                }
            } finally {
                _loading.value = false
            }
            loadSas()
        }
    }

    /**
     * Read the code and the recorded outcome. The read is unauthenticated and a
     * pure function of two public keys (`src/federation_peers.rs:837-839`), so
     * it is safe to issue on open; a failure is classified, never a spinner.
     */
    private suspend fun loadSas() {
        if (_sasRead.value !is SasRead.Ready) _sasRead.value = SasRead.Loading
        _sasRead.value = try {
            SasRead.Ready(apiClient.getFederationPeerSAS(keyId))
        } catch (e: Exception) {
            PlatformLogger.e(tag, "getFederationPeerSAS failed: ${e.message}", e)
            sasReadFailureOf(e)
        }
    }

    /**
     * Apply a trust change. BLOCKED requires confirm-first: the screen
     * stages the change via [requestTrust] which routes through [pendingTrust]
     * for a confirmation dialog; non-BLOCKED states apply directly via
     * [setTrust].
     */
    fun requestTrust(target: PeerTrustState) {
        if (target == PeerTrustState.BLOCKED) {
            _pendingTrust.value = target
        } else {
            setTrust(target)
        }
    }

    fun confirmPendingTrust() {
        val target = _pendingTrust.value ?: return
        _pendingTrust.value = null
        setTrust(target)
    }

    fun cancelPendingTrust() {
        _pendingTrust.value = null
    }

    private fun setTrust(target: PeerTrustState) {
        viewModelScope.launch {
            _trustChangeInFlight.value = true
            _error.value = null
            try {
                val updated = apiClient.setFederationPeerTrust(keyId, target)
                PlatformLogger.i(tag, "trust set → ${target.wire} for $keyId")
                // Patch the local detail with the updated peer state.
                _detail.value = _detail.value?.copy(peer = updated)
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "unknown error"
                PlatformLogger.e(tag, "setFederationPeerTrust failed: $msg", e)
                _error.value = msg
            } finally {
                _trustChangeInFlight.value = false
            }
        }
    }

    // ─── SAS outcome: request → ConfirmSheet → record ───────────────────────

    /** Open the ConfirmSheet for [outcome]. Nothing is written until [confirmOutcome]. */
    fun requestOutcome(outcome: SasOutcome) {
        if (_outcomeInFlight.value) return
        _pendingOutcome.value = outcome
    }

    /** Close the sheet. Writes nothing — backing out is not a mismatch. */
    fun cancelOutcome() {
        _pendingOutcome.value = null
    }

    fun confirmOutcome() {
        val outcome = _pendingOutcome.value ?: return
        _pendingOutcome.value = null
        viewModelScope.launch {
            _outcomeInFlight.value = true
            try {
                val result = recordSasOutcome(outcome) { write ->
                    when (write) {
                        is PeerWrite.Sas -> apiClient.setFederationPeerSASVerified(keyId, write.verified)
                        is PeerWrite.Trust -> {
                            val updated = apiClient.setFederationPeerTrust(keyId, write.trust)
                            _detail.value = _detail.value?.copy(peer = updated)
                        }
                    }
                }
                PlatformLogger.i(tag, "SAS outcome ${outcome.name} for $keyId → $result")
                _lastOutcome.value = result
            } finally {
                _outcomeInFlight.value = false
            }
            loadSas()
        }
    }

    fun toggleAppearanceExpanded() {
        _appearanceExpanded.value = !_appearanceExpanded.value
    }

    fun setAppearance(appearance: PeerAppearance) {
        _appearanceDraft.value = appearance
    }

    fun saveAppearance() {
        viewModelScope.launch {
            _appearanceSaving.value = true
            _error.value = null
            try {
                val updated = apiClient.setFederationPeerAppearance(keyId, _appearanceDraft.value)
                PlatformLogger.i(tag, "appearance saved for $keyId")
                _detail.value = _detail.value?.copy(peer = updated)
                _appearanceExpanded.value = false
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "unknown error"
                PlatformLogger.e(tag, "setFederationPeerAppearance failed: $msg", e)
                _error.value = msg
            } finally {
                _appearanceSaving.value = false
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CSD-104 — the SAS ceremony, as pure rules (tested in NetworkPeerDetailSasTest)
// ═══════════════════════════════════════════════════════════════════════════

/** Why the peer detail has no peer to show. */
sealed interface PeerDetailFailure {
    /** `404 {"error":"peer not found"}` — the key is not in this node's directory. */
    data object NotFound : PeerDetailFailure
    data class Failed(val detail: String?) : PeerDetailFailure
}

fun peerDetailFailureOf(e: Throwable): PeerDetailFailure {
    val msg = e.message
    // `getFederationPeer` throws "… failed: 404 Not Found for <key>"; the route
    // has existed since the peer card was built, so a 404 is the key, not the route.
    return if (msg != null && Regex("""\b404\b""").containsMatchIn(msg)) PeerDetailFailure.NotFound
    else PeerDetailFailure.Failed(msg ?: e::class.simpleName)
}

/** What reading the code produced. Error and "nothing to compare" never look alike. */
sealed interface SasRead {
    data object Loading : SasRead
    data class Ready(val sas: FederationPeerSASResponse) : SasRead {
        /** True only for a recorded `verified: true` — null (never recorded) is not verified. */
        val verified: Boolean get() = sas.verified == true
    }
    /**
     * 404 `PEER_SAS_UNAVAILABLE` / `PEER_NOT_FOUND`: the node answered, and the
     * key is not in its directory (an announced-but-unadmitted key is listed,
     * `src/federation_peers.rs:461-500`, and has nothing to compare against).
     */
    data object NotInDirectory : SasRead
    /** A 404 with no error id: this node predates the route (ciris-server < 0.5.115). */
    data class RouteAbsent(val detail: String?) : SasRead
    data class Failed(val detail: String?) : SasRead
}

/** The node's error ids that mean "no such key", on the read and on the write. */
private val KEY_NOT_IN_DIRECTORY = setOf("PEER_SAS_UNAVAILABLE", "PEER_NOT_FOUND")

fun sasReadFailureOf(e: Throwable): SasRead {
    val refusal = e as? NodeRefusal
    return when {
        refusal == null -> SasRead.Failed(e.message ?: e::class.simpleName)
        refusal.statusCode == 404 && refusal.detail in KEY_NOT_IN_DIRECTORY -> SasRead.NotInDirectory
        refusal.statusCode == 404 && refusal.detail == null && refusal.reasonId == null -> SasRead.RouteAbsent(refusal.message)
        else -> SasRead.Failed(refusal.detail ?: refusal.message)
    }
}

/**
 * The three answers a person can give after comparing codes. Backing out of
 * the sheet is not one of them: it writes nothing.
 */
enum class SasOutcome { MATCH, MISMATCH, WITHDRAW }

/** One sideband write the node is asked to make. */
sealed interface PeerWrite {
    data class Sas(val verified: Boolean) : PeerWrite
    data class Trust(val trust: PeerTrustState) : PeerWrite
}

/**
 * What each outcome writes, in order.
 *
 * MISMATCH is not WITHDRAW. Both clear the verification, because the node
 * has no separate mismatch record (CSD-104 §3b issue A). A mismatch also
 * stops trusting the key, and it does that FIRST: if only one write lands,
 * the protective one should be the one that does.
 */
fun SasOutcome.writes(): List<PeerWrite> = when (this) {
    SasOutcome.MATCH -> listOf(PeerWrite.Sas(verified = true))
    SasOutcome.MISMATCH -> listOf(PeerWrite.Trust(PeerTrustState.UNTRUSTED), PeerWrite.Sas(verified = false))
    SasOutcome.WITHDRAW -> listOf(PeerWrite.Sas(verified = false))
}

/** Why a write was refused — each has a different remedy. */
enum class SasWriteRefusal { NOT_OWNER, NOT_IN_DIRECTORY, ROUTE_ABSENT, FAILED }

fun sasWriteRefusalOf(e: Throwable): SasWriteRefusal {
    val refusal = e as? NodeRefusal ?: return SasWriteRefusal.FAILED
    return when {
        refusal.statusCode == 401 || refusal.statusCode == 403 -> SasWriteRefusal.NOT_OWNER
        refusal.statusCode == 404 && refusal.detail in KEY_NOT_IN_DIRECTORY -> SasWriteRefusal.NOT_IN_DIRECTORY
        refusal.statusCode == 404 -> SasWriteRefusal.ROUTE_ABSENT
        else -> SasWriteRefusal.FAILED
    }
}

sealed interface SasOutcomeResult {
    val outcome: SasOutcome
    data class Recorded(override val outcome: SasOutcome) : SasOutcomeResult
    /** [applied] landed before [failedAt] was refused; nothing after it was tried. */
    data class Refused(
        override val outcome: SasOutcome,
        val applied: List<PeerWrite>,
        val failedAt: PeerWrite,
        val reason: SasWriteRefusal,
        val detail: String?,
    ) : SasOutcomeResult
}

/** Apply an outcome's writes in order; the first refusal stops the run and is reported, never swallowed. */
suspend fun recordSasOutcome(outcome: SasOutcome, apply: suspend (PeerWrite) -> Unit): SasOutcomeResult {
    val applied = mutableListOf<PeerWrite>()
    for (w in outcome.writes()) {
        try {
            apply(w)
        } catch (e: Exception) {
            val detail = (e as? NodeRefusal)?.detail ?: e.message
            return SasOutcomeResult.Refused(outcome, applied.toList(), w, sasWriteRefusalOf(e), detail)
        }
        applied += w
    }
    return SasOutcomeResult.Recorded(outcome)
}

/** "482915" → "482 915": a six-digit code read aloud in two halves. Anything else is shown as sent. */
fun sasDigitsGrouped(digits: String): String =
    if (digits.length == 6 && digits.all { it.isDigit() }) digits.substring(0, 3) + " " + digits.substring(3) else digits
