package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.CommittedKeyDto
import ai.ciris.mobile.shared.models.federation.FinalGenesisFinishDto
import ai.ciris.mobile.shared.models.federation.FinalGenesisGrid
import ai.ciris.mobile.shared.models.federation.FinalGenesisItems
import ai.ciris.mobile.shared.models.federation.FinalGenesisProbe
import ai.ciris.mobile.shared.models.federation.FinalGenesisReason
import ai.ciris.mobile.shared.models.federation.FinalGenesisRefusal
import ai.ciris.mobile.shared.models.federation.FinalGenesisStatusDto
import ai.ciris.mobile.shared.models.federation.PlanConfirm
import ai.ciris.mobile.shared.models.federation.RECOVERY_PAIRING
import ai.ciris.mobile.shared.models.federation.RemintSourceDto
import ai.ciris.mobile.shared.models.federation.finalGenesisGrid
import ai.ciris.mobile.shared.models.federation.finalGenesisProbe
import ai.ciris.mobile.shared.models.federation.planConfirmFor
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the ceremony is, as the node says. */
sealed interface FinalGenesisPhase {
    /** Asking the node which re-mint it runs. */
    data object Probing : FinalGenesisPhase
    /** ≤0.5.219: the route is a bare 404, so the 2-of-3 sheet runs unchanged. */
    data object Legacy : FinalGenesisPhase
    /** 0.5.220, nothing planned: recovery keys and Plan. */
    data object NotPlanned : FinalGenesisPhase
    /** Planned: the grid. */
    data class Planned(val status: FinalGenesisStatusDto) : FinalGenesisPhase
    /** Assembled, verified, written. */
    data class Finished(val result: FinalGenesisFinishDto) : FinalGenesisPhase
    /** The node refused to say (403 off its own machine, 5xx, no socket). */
    data class Unavailable(val refusal: FinalGenesisRefusal) : FinalGenesisPhase
}

/** One holder's recovery-key row. The key is on record before anyone touches a token. */
sealed interface RecoveryRow {
    /** On record on the node; nothing read here. */
    data class OnRecord(val recoveryKeyId: String) : RecoveryRow
    data class Verifying(val recoveryKeyId: String) : RecoveryRow
    /** Read off the spare token and matched the record. */
    data class Verified(val key: CommittedKeyDto) : RecoveryRow
    data class Refused(val recoveryKeyId: String, val refusal: FinalGenesisRefusal) : RecoveryRow
    /** The holder has no recorded spare (outside the A1/B1/C1 pairing). */
    data object NoneRecorded : RecoveryRow
}

/** What a holder's last Sign said, beside the grid. */
sealed interface HolderSignNote {
    data object Signing : HolderSignNote
    data class Signed(val items: List<String>) : HolderSignNote
    /** `final_genesis.nothing_to_sign` — not an error: waiting for [on]. */
    data class NothingYet(val on: List<String>) : HolderSignNote
    data class Refused(val refusal: FinalGenesisRefusal) : HolderSignNote
}

/**
 * Drives the **final genesis** sheet (CIRISServer 0.5.220, `FSD/FINAL_GENESIS.md`):
 * recovery keys (on record; optionally verified) → Plan → the 3 × N grid, one
 * Sign per holder per round → Finish. Every state comes from
 * `GET /v1/accord/final-genesis`; nothing here is inferred from a version.
 *
 * Each action returns its [Job] so a test can join it. A PIN is a parameter of
 * the call that uses it, never stored here and never logged.
 */
class FinalGenesisViewModel(
    private val apiClient: CIRISApiClient,
    private val nodeUrl: () -> String = { CIRISApiClient.LOCAL_NODE_URL },
) : ViewModel() {

    companion object {
        private const val TAG = "FinalGenesisVM"
    }

    private val _phase = MutableStateFlow<FinalGenesisPhase>(FinalGenesisPhase.Probing)
    val phase: StateFlow<FinalGenesisPhase> = _phase.asStateFlow()

    private val _source = MutableStateFlow<RemintSourceDto?>(null)
    val source: StateFlow<RemintSourceDto?> = _source.asStateFlow()

    private val _sourceRefusal = MutableStateFlow<FinalGenesisRefusal?>(null)
    val sourceRefusal: StateFlow<FinalGenesisRefusal?> = _sourceRefusal.asStateFlow()

    private val _serveNodes = MutableStateFlow<Set<String>>(emptySet())
    /** The canonicals the plan seats. Defaults to the first the node lists. */
    val serveNodes: StateFlow<Set<String>> = _serveNodes.asStateFlow()

    private val _recovery = MutableStateFlow<Map<String, RecoveryRow>>(emptyMap())
    val recovery: StateFlow<Map<String, RecoveryRow>> = _recovery.asStateFlow()

    private val _confirm = MutableStateFlow<PlanConfirm?>(null)
    /** A ConfirmSheet the node's answer asked for — the clock, or replacing a plan. */
    val confirm: StateFlow<PlanConfirm?> = _confirm.asStateFlow()

    private val _clockChecked = MutableStateFlow(false)
    /** Set only by [confirmClock]; sent as `clock_checked` from then on. */
    val clockChecked: StateFlow<Boolean> = _clockChecked.asStateFlow()

    private val _replanning = MutableStateFlow(false)
    /** The operator asked to plan again over a planned ceremony. */
    val replanning: StateFlow<Boolean> = _replanning.asStateFlow()

    private val _notes = MutableStateFlow<Map<String, HolderSignNote>>(emptyMap())
    val notes: StateFlow<Map<String, HolderSignNote>> = _notes.asStateFlow()

    private val _refusal = MutableStateFlow<FinalGenesisRefusal?>(null)
    /** The last plan/finish/refresh refusal, rendered by id. */
    val refusal: StateFlow<FinalGenesisRefusal?> = _refusal.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Every item id seen this session: the node drops an item from `owed` once all three signed it. */
    private val known = mutableSetOf<String>()

    /** The holder roster: the re-mint source's, else whoever the node's `owed` names. */
    fun holders(): List<String> {
        val fromSource = _source.value?.holders.orEmpty().map { it.keyId }
        val fromStatus = (_phase.value as? FinalGenesisPhase.Planned)?.status?.owed?.values?.flatten().orEmpty()
        return (fromSource + fromStatus).distinct()
    }

    fun grid(): FinalGenesisGrid? = (_phase.value as? FinalGenesisPhase.Planned)?.let {
        finalGenesisGrid(holders(), it.status, known)
    }

    /** Open the sheet: which re-mint, and the pre-fill. */
    fun open(): Job = viewModelScope.launch {
        _refusal.value = null
        probe()
        if (_phase.value != FinalGenesisPhase.Legacy) loadSource()
    }

    /** Re-read the status (the sheet polls this while planned). */
    fun refresh(): Job = viewModelScope.launch { probe() }

    private suspend fun probe() {
        try {
            val status = apiClient.getFinalGenesisStatus(nodeUrl())
            planned(status)
        } catch (e: NodeRefusal) {
            when (finalGenesisProbe(e.statusCode, e.reasonId)) {
                FinalGenesisProbe.LEGACY -> _phase.value = FinalGenesisPhase.Legacy
                FinalGenesisProbe.NOT_PLANNED -> {
                    // A finished ceremony stays finished on screen; a node that
                    // lost its state is a different fact, shown as not planned.
                    if (_phase.value !is FinalGenesisPhase.Finished) _phase.value = FinalGenesisPhase.NotPlanned
                }
                FinalGenesisProbe.REFUSED -> _phase.value = FinalGenesisPhase.Unavailable(FinalGenesisRefusal.of(e))
            }
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[probe] ${e.message}")
            _phase.value = FinalGenesisPhase.Unavailable(FinalGenesisRefusal.transport(e))
        }
    }

    private fun planned(status: FinalGenesisStatusDto) {
        known += status.owed.keys
        known += status.signableNow
        if (_phase.value !is FinalGenesisPhase.Finished) _phase.value = FinalGenesisPhase.Planned(status)
    }

    private suspend fun loadSource() {
        try {
            val src = apiClient.getGenesisRemintSource(nodeUrl())
            _source.value = src
            _sourceRefusal.value = null
            if (_serveNodes.value.isEmpty()) {
                src.canonicals.firstOrNull()?.let { _serveNodes.value = setOf(it.keyId) }
            }
            _recovery.value = src.holders.associate { h ->
                h.keyId to (_recovery.value[h.keyId]
                    ?: RECOVERY_PAIRING[h.keyId]?.let { RecoveryRow.OnRecord(it) }
                    ?: RecoveryRow.NoneRecorded)
            }
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[loadSource] ${e.message}")
            _sourceRefusal.value = (e as? NodeRefusal)?.let { FinalGenesisRefusal.of(it) }
                ?: FinalGenesisRefusal.transport(e)
        }
    }

    fun toggleServeNode(keyId: String) {
        _serveNodes.value = if (keyId in _serveNodes.value) _serveNodes.value - keyId else _serveNodes.value + keyId
    }

    /** OPTIONAL: read [holder]'s spare off its token and check it against the record. */
    fun verifyRecovery(holder: String, usbPath: String, pin: String?, modulePath: String? = null): Job =
        viewModelScope.launch {
            val spare = RECOVERY_PAIRING[holder] ?: return@launch
            setRecovery(holder, RecoveryRow.Verifying(spare))
            try {
                val res = apiClient.verifyFinalGenesisRecoveryKey(holder, spare, usbPath, pin, modulePath, nodeUrl())
                setRecovery(holder, RecoveryRow.Verified(res.recoveryKey))
            } catch (e: NodeRefusal) {
                setRecovery(holder, RecoveryRow.Refused(spare, FinalGenesisRefusal.of(e)))
            } catch (e: Exception) {
                setRecovery(holder, RecoveryRow.Refused(spare, FinalGenesisRefusal.transport(e)))
            }
        }

    private fun setRecovery(holder: String, row: RecoveryRow) {
        _recovery.value = _recovery.value + (holder to row)
    }

    /** Plan with what has been confirmed so far. */
    fun plan(): Job = viewModelScope.launch { planNow() }

    /** The operator confirmed this host's clock is NTP-synchronized. */
    fun confirmClock(): Job {
        _clockChecked.value = true
        _confirm.value = null
        return viewModelScope.launch { planNow() }
    }

    /** The operator confirmed discarding the planned ceremony. */
    fun confirmReplace(): Job {
        _confirm.value = null
        replaceConfirmed = true
        return viewModelScope.launch { planNow() }
    }

    fun dismissConfirm() {
        _confirm.value = null
    }

    fun startReplan() {
        _replanning.value = true
    }

    fun cancelReplan() {
        _replanning.value = false
    }

    /** Once confirmed, a replace stays asked for until a plan lands (a clock confirm may come between). */
    private var replaceConfirmed = false

    private suspend fun planNow() {
        val replace = replaceConfirmed
        _busy.value = true
        _refusal.value = null
        try {
            val status = apiClient.planFinalGenesis(
                serveNodes = _serveNodes.value.toList(),
                clockChecked = _clockChecked.value,
                replace = replace,
                nodeUrl = nodeUrl(),
            )
            if (replace) known.clear()
            replaceConfirmed = false
            _replanning.value = false
            _notes.value = emptyMap()
            planned(status)
        } catch (e: NodeRefusal) {
            val ask = planConfirmFor(e.reasonId)
            if (ask != null && !(ask == PlanConfirm.CLOCK && _clockChecked.value) && !(ask == PlanConfirm.REPLACE && replace)) {
                _confirm.value = ask
            } else {
                _refusal.value = FinalGenesisRefusal.of(e)
            }
        } catch (e: Exception) {
            _refusal.value = FinalGenesisRefusal.transport(e)
        } finally {
            _busy.value = false
        }
    }

    /** One YubiKey session signs everything [holder] owes now. */
    fun sign(holder: String, usbPath: String, pin: String?, modulePath: String? = null): Job =
        viewModelScope.launch {
            _busy.value = true
            setNote(holder, HolderSignNote.Signing)
            try {
                val res = apiClient.signFinalGenesis(holder, usbPath, pin, modulePath, nodeUrl())
                setNote(holder, HolderSignNote.Signed(res.signed))
                // The answer names what is owed; signable_now needs the status.
                probe()
            } catch (e: NodeRefusal) {
                if (e.reasonId == FinalGenesisReason.NOTHING_TO_SIGN) {
                    probe()
                    val waitingOn = grid()?.charterOwedBy.orEmpty().filter { it != holder }
                    setNote(holder, HolderSignNote.NothingYet(waitingOn))
                } else {
                    setNote(holder, HolderSignNote.Refused(FinalGenesisRefusal.of(e)))
                }
            } catch (e: Exception) {
                setNote(holder, HolderSignNote.Refused(FinalGenesisRefusal.transport(e)))
            } finally {
                _busy.value = false
            }
        }

    private fun setNote(holder: String, note: HolderSignNote) {
        _notes.value = _notes.value + (holder to note)
    }

    /** Assemble, verify and write the bundle. */
    fun finish(): Job = viewModelScope.launch {
        _busy.value = true
        _refusal.value = null
        try {
            _phase.value = FinalGenesisPhase.Finished(apiClient.finishFinalGenesis(nodeUrl()))
        } catch (e: NodeRefusal) {
            _refusal.value = FinalGenesisRefusal.of(e)
            if (e.reasonId == FinalGenesisReason.CEREMONY_INCOMPLETE) probe()
        } catch (e: Exception) {
            _refusal.value = FinalGenesisRefusal.transport(e)
        } finally {
            _busy.value = false
        }
    }

    /** Items, in bundle order, for a holder's round — what the grid's columns read. */
    fun itemsOfRound(round: Int): List<String> =
        grid()?.items.orEmpty().filter { FinalGenesisItems.round(it) == round }
}
