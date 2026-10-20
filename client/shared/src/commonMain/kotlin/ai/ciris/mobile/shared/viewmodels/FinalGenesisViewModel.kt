package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.CommittedKeyDto
import ai.ciris.mobile.shared.models.federation.FinalGenesisAmendStatusDto
import ai.ciris.mobile.shared.models.federation.FinalGenesisFinishDto
import ai.ciris.mobile.shared.models.federation.TransitFetch
import ai.ciris.mobile.shared.models.federation.TransitRows
import ai.ciris.mobile.shared.models.federation.parseTransitRows
import ai.ciris.mobile.shared.models.federation.transitRowSubject
import ai.ciris.mobile.shared.models.federation.FinalGenesisGrid
import ai.ciris.mobile.shared.models.federation.FinalGenesisItems
import ai.ciris.mobile.shared.models.federation.FinalGenesisProbe
import ai.ciris.mobile.shared.models.federation.FinalGenesisReason
import ai.ciris.mobile.shared.models.federation.FinalGenesisRefusal
import ai.ciris.mobile.shared.models.federation.FinalGenesisStatusDto
import ai.ciris.mobile.shared.models.federation.PlanConfirm
import ai.ciris.mobile.shared.models.federation.RECOVERY_PAIRING
import ai.ciris.mobile.shared.models.federation.RecoveryKeySource
import ai.ciris.mobile.shared.models.federation.RemintSourceDto
import ai.ciris.mobile.shared.models.federation.finalGenesisGrid
import ai.ciris.mobile.shared.models.federation.finalGenesisProbe
import ai.ciris.mobile.shared.models.federation.PlanServeNode
import ai.ciris.mobile.shared.models.federation.initialDialHint
import ai.ciris.mobile.shared.models.federation.isDialHint
import ai.ciris.mobile.shared.models.federation.planConfirmFor
import ai.ciris.mobile.shared.models.federation.shortCommitment
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

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

/**
 * One holder's recovery-key row. [commitment] is the string the charter will
 * carry (`GET …/recovery-keys`); null on a node from before that route.
 */
sealed interface RecoveryRow {
    /** On the accord ceremony's record (`source: record`); nothing read here. */
    data class OnRecord(val recoveryKeyId: String, val commitment: String? = null) : RecoveryRow
    data class Verifying(val recoveryKeyId: String) : RecoveryRow
    /** Read off the spare token on this node (`source: hardware`). [key] when this session read it. */
    data class Verified(val recoveryKeyId: String, val commitment: String? = null, val key: CommittedKeyDto? = null) : RecoveryRow
    /**
     * Nothing on record (`recovery_key_id: null`): the spare must be read off
     * its token before a plan. [spare] is the id to read, when the pairing knows it.
     */
    data class Missing(val spare: String?) : RecoveryRow
    /** A node without the route, and a holder outside the A1/B1/C1 pairing. */
    data object NoneRecorded : RecoveryRow
    /**
     * `GET …/recovery-keys` failed with anything but a bare 404: what the node
     * will commit is unknown, so nothing is drawn for it and Plan waits.
     */
    data object Unread : RecoveryRow
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
    /** How long the sheet's first probe waits for a node that is not up yet (#149/#151). */
    private val nodeWaitDeadlineSeconds: Int = NodeBindWait.deadlineSeconds(),
) : ViewModel() {

    private val _nodeWait = MutableStateFlow<NodeWait>(NodeWait.Idle)
    /** The first probe's wait for the node: Waiting while it is not up yet. */
    val nodeWait: StateFlow<NodeWait> = _nodeWait.asStateFlow()

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

    private val _dialHints = MutableStateFlow<Map<String, String>>(emptyMap())
    /** canonical key id → the `host:port` the plan seats it at; editable, prefilled by [initialDialHint]. */
    val dialHints: StateFlow<Map<String, String>> = _dialHints.asStateFlow()

    private val _recovery = MutableStateFlow<Map<String, RecoveryRow>>(emptyMap())
    val recovery: StateFlow<Map<String, RecoveryRow>> = _recovery.asStateFlow()

    private val _planBlockedBy = MutableStateFlow<List<String>>(emptyList())
    /** Holders the node has no recovery key for — Plan waits on these, and only these. */
    val planBlockedBy: StateFlow<List<String>> = _planBlockedBy.asStateFlow()

    private val _recoveryRefusal = MutableStateFlow<FinalGenesisRefusal?>(null)
    /** `GET …/recovery-keys` refused (anything but a bare 404, which is an older 0.5.220). */
    val recoveryRefusal: StateFlow<FinalGenesisRefusal?> = _recoveryRefusal.asStateFlow()

    private val _recoveryUnavailable = MutableStateFlow(false)
    /** True while `GET …/recovery-keys` is failing (not a bare 404): Plan is blocked until a Retry reads it. */
    val recoveryUnavailable: StateFlow<Boolean> = _recoveryUnavailable.asStateFlow()

    private val _recoveryFailures = MutableStateFlow<Map<String, FinalGenesisRefusal>>(emptyMap())
    /** A token read that failed, by holder — shown BESIDE the row, which keeps what the node said. */
    val recoveryFailures: StateFlow<Map<String, FinalGenesisRefusal>> = _recoveryFailures.asStateFlow()

    /** Whether this node serves `GET …/recovery-keys`; null until asked. */
    private var recoveryRoute: Boolean? = null

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
        // The view model outlives the sheet: a finished result belongs to the
        // display that produced it, and a new sheet shows what the node says now.
        if (_phase.value is FinalGenesisPhase.Finished) {
            _phase.value = FinalGenesisPhase.Probing
            // A finished ceremony's items are not the next one's.
            known.clear()
        }
        _refusal.value = null
        _recoveryFailures.value = emptyMap()
        // A new display: the dial hints are what the node serves NOW. An edit
        // survives only a Retry inside the same form (Codex on #154).
        _dialHints.value = emptyMap()
        probe(first = true)
        if (_phase.value != FinalGenesisPhase.Legacy && _phase.value !is FinalGenesisPhase.Unavailable) loadSource()
    }

    /**
     * Re-read the status (the sheet polls this, awaiting each). One probe at a
     * time: a refresh while one is out returns that one rather than racing it.
     */
    fun refresh(): Job =
        probeJob?.takeIf { it.isActive } ?: viewModelScope.launch { probe() }.also { probeJob = it }

    private var probeJob: Job? = null
    private val probeMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Bumped by every state an action or a probe applies. A probe applies its
     * answer only if nothing was applied since it started, so a slow answer can
     * never land over a newer one.
     */
    private var stateEpoch = 0L

    private suspend fun probe(first: Boolean = false) = probeMutex.withLock {
        val started = stateEpoch
        val apply: () -> Unit = try {
            // The sheet's FIRST read waits out a node that is not up yet.
            val status = if (first) {
                awaitNodeFirstRead(nodeUrl(), onWait = { _nodeWait.value = it }, deadlineSeconds = nodeWaitDeadlineSeconds) {
                    apiClient.getFinalGenesisStatus(nodeUrl())
                }
            } else {
                apiClient.getFinalGenesisStatus(nodeUrl())
            }
            if (status == null) {
                val waited = _nodeWait.value as? NodeWait.TimedOut
                ({
                    _phase.value = FinalGenesisPhase.Unavailable(
                        FinalGenesisRefusal("mobile.final_genesis_node_unreachable", waited?.detail, 0),
                    )
                })
            } else {
                ({ planned(status) })
            }
        } catch (e: NodeRefusal) {
            when (finalGenesisProbe(e.statusCode, e.reasonId, e.body)) {
                FinalGenesisProbe.LEGACY -> ({ _phase.value = FinalGenesisPhase.Legacy })
                FinalGenesisProbe.NOT_PLANNED -> ({
                    // No ceremony on the node: whatever is planned next is a new one.
                    known.clear()
                    // A finished ceremony stays finished on screen; a node that
                    // lost its state is a different fact, shown as not planned.
                    if (_phase.value !is FinalGenesisPhase.Finished) _phase.value = FinalGenesisPhase.NotPlanned
                })
                FinalGenesisProbe.REFUSED -> ({ _phase.value = FinalGenesisPhase.Unavailable(FinalGenesisRefusal.of(e)) })
            }
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[probe] ${e.message}")
            ({ _phase.value = FinalGenesisPhase.Unavailable(FinalGenesisRefusal.transport(e)) })
        }
        if (stateEpoch == started) {
            apply()
            stateEpoch++
        }
    }

    /**
     * Run one ceremony action (plan, sign, finish, a token read) unless one is
     * already running — checked and claimed SYNCHRONOUSLY, before anything
     * launches, so two rapid taps open exactly one hardware session.
     */
    private fun exclusive(block: suspend () -> Unit): Job {
        if (_busy.value) return Job().apply { complete() }
        _busy.value = true
        return viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun planned(status: FinalGenesisStatusDto) {
        known += status.owed.keys
        known += status.signableNow
        if (_phase.value !is FinalGenesisPhase.Finished) _phase.value = FinalGenesisPhase.Planned(status)
    }

    private suspend fun loadSource(withRecovery: Boolean = true) {
        try {
            val src = apiClient.getGenesisRemintSource(nodeUrl())
            _source.value = src
            _sourceRefusal.value = null
            // A canonical withdrawn or superseded since the last open leaves the
            // selection: no chip would show it, and the plan would still seat it.
            _serveNodes.value = _serveNodes.value intersect src.canonicals.map { it.keyId }.toSet()
            if (_serveNodes.value.isEmpty()) {
                src.canonicals.firstOrNull()?.let { _serveNodes.value = setOf(it.keyId) }
            }
            // Prefill each canonical's address once; what the operator typed stays.
            _dialHints.value = src.canonicals.associate { c ->
                c.keyId to (_dialHints.value[c.keyId] ?: initialDialHint(c.transportHints))
            }
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[loadSource] ${e.message}")
            _sourceRefusal.value = (e as? NodeRefusal)?.let { FinalGenesisRefusal.of(it) }
                ?: FinalGenesisRefusal.transport(e)
        }
        if (withRecovery) loadRecoveryKeys()
    }

    /**
     * Each holder's recovery key as the node will commit to it. A bare 404 is a
     * 0.5.220 node from before `GET …/recovery-keys`: the rows fall back to the
     * pairing, "on record", with no commitment to show.
     */
    private suspend fun loadRecoveryKeys() {
        val holders = _source.value?.holders.orEmpty().map { it.keyId }
        try {
            val res = apiClient.getFinalGenesisRecoveryKeys(nodeUrl())
            recoveryRoute = true
            _recoveryRefusal.value = null
            _recoveryUnavailable.value = false
            val byHolder = res.recoveryKeys.associateBy { it.holderKeyId }
            _recovery.value = (holders + byHolder.keys).distinct().associateWith { h ->
                val entry = byHolder[h]
                when {
                    // The node answered and left this holder out: unknown, not
                    // the pairing — that fallback is the bare-404 path's alone.
                    entry == null -> RecoveryRow.Unread
                    entry.recoveryKeyId == null -> RecoveryRow.Missing(RECOVERY_PAIRING[h])
                    entry.source == RecoveryKeySource.HARDWARE -> RecoveryRow.Verified(
                        entry.recoveryKeyId,
                        entry.commitment,
                        (_recovery.value[h] as? RecoveryRow.Verified)?.key,
                    )
                    else -> RecoveryRow.OnRecord(entry.recoveryKeyId, entry.commitment)
                }
            }
            _planBlockedBy.value = res.recoveryKeys.filter { it.recoveryKeyId == null }.map { it.holderKeyId }
        } catch (e: Exception) {
            val refusal = e as? NodeRefusal
            _planBlockedBy.value = emptyList()
            if (refusal != null && refusal.statusCode == 404 && refusal.body.isNullOrBlank()) {
                // ONLY a bare 404: a 0.5.220 build from before the route.
                recoveryRoute = false
                _recoveryRefusal.value = null
                _recoveryUnavailable.value = false
                _recovery.value = holders.associateWith { h -> _recovery.value[h] ?: fallbackRow(h) }
            } else {
                // Anything else is a node that has the route and did not answer:
                // never draw keys it did not supply, and hold Plan until Retry.
                PlatformLogger.w(TAG, "[loadRecoveryKeys] ${e.message}")
                _recoveryRefusal.value = refusal?.let { FinalGenesisRefusal.of(it) } ?: FinalGenesisRefusal.transport(e)
                _recoveryUnavailable.value = true
                _recovery.value = holders.associateWith { RecoveryRow.Unread }
            }
        }
    }

    /** Re-read the re-mint source after it failed. */
    fun retrySource(): Job = viewModelScope.launch { loadSource() }

    /** Re-read `GET …/recovery-keys` after it failed. */
    fun retryRecoveryKeys(): Job = viewModelScope.launch { loadRecoveryKeys() }

    private fun fallbackRow(holder: String): RecoveryRow =
        RECOVERY_PAIRING[holder]?.let { RecoveryRow.OnRecord(it) } ?: RecoveryRow.NoneRecorded

    /**
     * The spare [holder]'s token must open as. For A1/B1/C1 it is the pairing,
     * full stop: the node refuses any other pair (4da726e8 `check_recovery_keys`),
     * so a key it listed under the wrong holder is never the one to read. Other
     * rosters use the id the node lists.
     */
    fun spareFor(holder: String): String? = RECOVERY_PAIRING[holder] ?: when (val row = _recovery.value[holder]) {
        is RecoveryRow.OnRecord -> row.recoveryKeyId
        is RecoveryRow.Verified -> row.recoveryKeyId
        is RecoveryRow.Verifying -> row.recoveryKeyId
        is RecoveryRow.Missing -> row.spare
        else -> null
    }

    fun setDialHint(keyId: String, value: String) {
        _dialHints.value = _dialHints.value + (keyId to value)
    }

    /** The seated canonicals whose address is not a valid `host:port`. */
    fun serveNodesWithoutDialHint(): List<String> =
        _serveNodes.value.filter { !isDialHint(_dialHints.value[it].orEmpty()) }.sorted()

    fun toggleServeNode(keyId: String) {
        _serveNodes.value = if (keyId in _serveNodes.value) _serveNodes.value - keyId else _serveNodes.value + keyId
    }

    /**
     * Read [holder]'s spare off its token: optional when the key is on record,
     * required when the node has none (`recovery_key_id: null`).
     */
    fun verifyRecovery(holder: String, usbPath: String, pin: String?, modulePath: String? = null, pivSlot: String? = null): Job =
        exclusive {
            val spare = spareFor(holder) ?: return@exclusive
            val prior = _recovery.value[holder]
            setRecovery(holder, RecoveryRow.Verifying(spare))
            _recoveryFailures.value = _recoveryFailures.value - holder
            // A failed read changes nothing the node said: the row goes back to
            // what it was (a missing key stays missing) and the failure sits beside it.
            fun failed(r: FinalGenesisRefusal) {
                prior?.let { setRecovery(holder, it) } ?: run { _recovery.value = _recovery.value - holder }
                _recoveryFailures.value = _recoveryFailures.value + (holder to r)
            }
            try {
                val res = apiClient.verifyFinalGenesisRecoveryKey(holder, spare, usbPath, pin, modulePath, nodeUrl(), pivSlot)
                setRecovery(holder, RecoveryRow.Verified(res.recoveryKey.keyId, null, res.recoveryKey))
                // The node's own account of what it will commit to, commitment included.
                if (recoveryRoute != false) loadRecoveryKeys()
            } catch (e: NodeRefusal) {
                failed(FinalGenesisRefusal.of(e))
            } catch (e: Exception) {
                failed(FinalGenesisRefusal.transport(e))
            }
        }

    private fun setRecovery(holder: String, row: RecoveryRow) {
        _recovery.value = _recovery.value + (holder to row)
    }

    /**
     * A new plan (the Plan button, first time or "Plan again"). Neither confirm
     * carries over: the clock confirm is for one ceremony instant, and a
     * replace is approved for one request.
     */
    fun plan(): Job {
        _clockChecked.value = false
        replaceConfirmed = false
        // The sheet disables Plan for the same reasons; this holds it if a tap gets through.
        if (planHeld()) return Job().apply { complete() }
        return exclusive { planNow() }
    }

    /** Plan waits on the node's recovery keys: unread, or missing for a holder. */
    fun planHeld(): Boolean =
        _recoveryUnavailable.value || _planBlockedBy.value.isNotEmpty() ||
            _recovery.value.values.any { it == RecoveryRow.Unread } ||
            // A failed refresh of the source leaves a roster and canonicals the
            // node may no longer list; nothing is planned from them.
            _sourceRefusal.value != null || _source.value == null ||
            // Every seated canonical needs the address peers dial it at.
            serveNodesWithoutDialHint().isNotEmpty()

    /** The operator confirmed this host's clock is NTP-synchronized. */
    fun confirmClock(): Job {
        _clockChecked.value = true
        _confirm.value = null
        return exclusive { planNow() }
    }

    /** The operator confirmed discarding the planned ceremony. */
    fun confirmReplace(): Job {
        _confirm.value = null
        replaceConfirmed = true
        return exclusive { planNow() }
    }

    fun dismissConfirm() {
        _confirm.value = null
        replaceConfirmed = false
    }

    fun startReplan() {
        _replanning.value = true
    }

    fun cancelReplan() {
        _replanning.value = false
        replaceConfirmed = false
    }

    /** Set by [confirmReplace] for the ONE request it approves; cleared when that request ends, whatever it ends in. */
    private var replaceConfirmed = false

    private suspend fun planNow() {
        val replace = replaceConfirmed
        replaceConfirmed = false
        _refusal.value = null
        try {
            val status = apiClient.planFinalGenesis(
                serveNodes = _serveNodes.value.sorted().map { PlanServeNode(it, _dialHints.value[it].orEmpty()) },
                clockChecked = _clockChecked.value,
                replace = replace,
                nodeUrl = nodeUrl(),
            )
            if (replace) known.clear()
            // The instant is stamped; the next plan is a new instant and asks again.
            _clockChecked.value = false
            _replanning.value = false
            _notes.value = emptyMap()
            stateEpoch++
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
        }
    }

    /** One YubiKey session signs everything [holder] owes now. */
    fun sign(holder: String, usbPath: String, pin: String?, modulePath: String? = null, pivSlot: String? = null): Job =
        exclusive {
            setNote(holder, HolderSignNote.Signing)
            try {
                val res = apiClient.signFinalGenesis(holder, usbPath, pin, modulePath, nodeUrl(), pivSlot)
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
            }
        }

    private fun setNote(holder: String, note: HolderSignNote) {
        _notes.value = _notes.value + (holder to note)
    }

    /** Assemble, verify and write the bundle. */
    fun finish(): Job = exclusive {
        _refusal.value = null
        try {
            val done = apiClient.finishFinalGenesis(nodeUrl())
            stateEpoch++
            _phase.value = FinalGenesisPhase.Finished(done)
        } catch (e: NodeRefusal) {
            _refusal.value = FinalGenesisRefusal.of(e)
            if (e.reasonId == FinalGenesisReason.CEREMONY_INCOMPLETE) probe()
        } catch (e: Exception) {
            _refusal.value = FinalGenesisRefusal.transport(e)
        }
    }

    /** Items, in bundle order, for a holder's round — what the grid's columns read. */
    fun itemsOfRound(round: Int): List<String> =
        grid()?.items.orEmpty().filter { FinalGenesisItems.round(it) == round }

    // ─── Re-scrub: the 2-of-3 AMEND (CC 5.3.4; CIRISServer#798) ─────────────
    //
    // The steward's ruling on CIRISConstitution#172: the bundle v4 re-mint is an
    // amend of the pinned bundle — carried byte for byte, each seated canonical's
    // signed transit row added, authorized by the charter's quorum (2 of 3).
    // One authorization per holder, any holders, until complete. No rounds, no
    // recovery keys (the commitments are carried).

    private val _amendPhase = MutableStateFlow<AmendPhase>(AmendPhase.Probing)
    val amendPhase: StateFlow<AmendPhase> = _amendPhase.asStateFlow()

    private val _amendRowsText = MutableStateFlow("")
    /** Pasted or attached transit rows — the alternative to [fetchTransitRow]. */
    val amendRowsText: StateFlow<String> = _amendRowsText.asStateFlow()

    private val _canonicalUrl = MutableStateFlow("")
    /** The canonical's base URL its transit row is fetched from. */
    val canonicalUrl: StateFlow<String> = _canonicalUrl.asStateFlow()

    private val _transitFetch = MutableStateFlow<TransitFetch>(TransitFetch.Idle)
    val transitFetch: StateFlow<TransitFetch> = _transitFetch.asStateFlow()

    private val _amendConfirmReplace = MutableStateFlow(false)
    /** 409 `final_genesis.amend_already_planned` asked whether to discard the planned amend. */
    val amendConfirmReplace: StateFlow<Boolean> = _amendConfirmReplace.asStateFlow()

    private val _amendReplanning = MutableStateFlow(false)
    val amendReplanning: StateFlow<Boolean> = _amendReplanning.asStateFlow()

    private val _amendNotes = MutableStateFlow<Map<String, HolderSignNote>>(emptyMap())
    val amendNotes: StateFlow<Map<String, HolderSignNote>> = _amendNotes.asStateFlow()

    private val _amendRefusal = MutableStateFlow<FinalGenesisRefusal?>(null)
    /** The last amend plan/finish/refresh refusal, rendered by id. */
    val amendRefusal: StateFlow<FinalGenesisRefusal?> = _amendRefusal.asStateFlow()

    /** The holders who may authorize: the re-mint source's roster, plus anyone the node says signed. */
    fun amendHolders(): List<String> {
        val signed = (_amendPhase.value as? AmendPhase.Planned)?.status?.signed.orEmpty()
        return (_source.value?.holders.orEmpty().map { it.keyId } + signed).distinct()
    }

    fun amendRows(): TransitRows = parseTransitRows(_amendRowsText.value)

    fun setAmendRowsText(text: String) {
        _amendRowsText.value = text
    }

    /** Append an attached file's text on its own line (several rows may be attached). */
    fun attachAmendRows(text: String) {
        val cur = _amendRowsText.value.trimEnd()
        _amendRowsText.value = if (cur.isEmpty()) text.trim() else cur + "\n" + text.trim()
    }

    fun setCanonicalUrl(url: String) {
        _canonicalUrl.value = url
    }

    /** Open the re-scrub sheet: the amend's status and the roster. */
    fun openAmend(): Job = viewModelScope.launch {
        if (_amendPhase.value is AmendPhase.Finished) _amendPhase.value = AmendPhase.Probing
        _amendRefusal.value = null
        _amendReplanning.value = false
        probeAmend()
        if (_amendPhase.value != AmendPhase.TooOld) loadSource(withRecovery = false)
    }

    /** Re-read the amend's status (the sheet polls this while planned). */
    fun refreshAmend(): Job = viewModelScope.launch { probeAmend() }

    private suspend fun probeAmend() {
        try {
            val s = apiClient.getFinalGenesisAmendStatus(nodeUrl())
            if (_amendPhase.value !is AmendPhase.Finished) _amendPhase.value = AmendPhase.Planned(s)
        } catch (e: NodeRefusal) {
            _amendPhase.value = when {
                e.reasonId == FinalGenesisReason.AMEND_NOT_PLANNED -> AmendPhase.NotPlanned
                e.statusCode == 404 && e.reasonId == null && e.body.isNullOrBlank() -> AmendPhase.TooOld
                else -> AmendPhase.Unavailable(FinalGenesisRefusal.of(e))
            }
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[probeAmend] ${e.message}")
            _amendPhase.value = AmendPhase.Unavailable(FinalGenesisRefusal.transport(e))
        }
    }

    /**
     * Fetch the canonical's signed transit row (`GET <canonical>/v1/trust-root/transit-row`)
     * — the expected path. Shown before planning; planned by [planAmend].
     */
    fun fetchTransitRow(): Job {
        val url = _canonicalUrl.value.trim()
        if (url.isEmpty() || _transitFetch.value == TransitFetch.Fetching) return Job().apply { complete() }
        _transitFetch.value = TransitFetch.Fetching
        return viewModelScope.launch {
            _transitFetch.value = try {
                TransitFetch.Fetched(apiClient.fetchCanonicalTransitRow(url))
            } catch (e: NodeRefusal) {
                if (e.statusCode == 404 && e.reasonId == null && e.body.isNullOrBlank()) TransitFetch.TooOld
                else TransitFetch.Refused(FinalGenesisRefusal.of(e))
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[fetchTransitRow] ${e.message}")
                TransitFetch.Refused(FinalGenesisRefusal.transport(e))
            }
        }
    }

    fun clearTransitFetch() {
        _transitFetch.value = TransitFetch.Idle
    }

    /** The rows the plan carries: the fetched one, then pasted rows about other canonicals. Null when the paste does not parse. */
    fun amendPlanRows(): List<kotlinx.serialization.json.JsonObject>? {
        val fetched = (_transitFetch.value as? TransitFetch.Fetched)?.row
        val pasted = when (val r = amendRows()) {
            TransitRows.Empty -> emptyList()
            is TransitRows.Rows -> r.rows
            TransitRows.Invalid -> return null
        }
        if (fetched == null) return pasted
        return listOf(fetched.row) + pasted.filter { transitRowSubject(it) == null || transitRowSubject(it) != fetched.keyId }
    }

    /** Plan the amend. Nothing is sent while the pasted rows do not parse. */
    fun planAmend(): Job {
        if (amendPlanRows() == null) return Job().apply { complete() }
        return exclusive { planAmendNow(replace = false) }
    }

    /** The operator confirmed discarding the planned amend: this ONE request carries `replace`. */
    fun confirmAmendReplace(): Job {
        _amendConfirmReplace.value = false
        return exclusive { planAmendNow(replace = true) }
    }

    fun dismissAmendReplace() {
        _amendConfirmReplace.value = false
    }

    fun startAmendReplan() {
        _amendReplanning.value = true
    }

    fun cancelAmendReplan() {
        _amendReplanning.value = false
    }

    private suspend fun planAmendNow(replace: Boolean) {
        val rows = amendPlanRows() ?: return
        _amendRefusal.value = null
        try {
            val s = apiClient.planFinalGenesisAmend(rows, replace = replace, nodeUrl = nodeUrl())
            _amendNotes.value = emptyMap()
            _amendReplanning.value = false
            _amendPhase.value = AmendPhase.Planned(s)
        } catch (e: NodeRefusal) {
            when {
                e.reasonId == FinalGenesisReason.AMEND_ALREADY_PLANNED && !replace -> _amendConfirmReplace.value = true
                e.statusCode == 404 && e.reasonId == null && e.body.isNullOrBlank() -> _amendPhase.value = AmendPhase.TooOld
                else -> _amendRefusal.value = FinalGenesisRefusal.of(e)
            }
        } catch (e: Exception) {
            _amendRefusal.value = FinalGenesisRefusal.transport(e)
        }
    }

    /** Whether [holder] may authorize now: planned, not complete, not already signed. */
    fun amendCanSign(holder: String): Boolean {
        val s = (_amendPhase.value as? AmendPhase.Planned)?.status ?: return false
        return !s.complete && holder !in s.signed
    }

    /** One YubiKey + USB authorization by [holder] over the amended digest. */
    fun signAmend(holder: String, usbPath: String, pin: String?, modulePath: String? = null, pivSlot: String? = null): Job {
        if (!amendCanSign(holder.trim())) return Job().apply { complete() }
        return exclusive {
            setAmendNote(holder, HolderSignNote.Signing)
            try {
                val s = apiClient.signFinalGenesisAmend(holder, usbPath, pin, modulePath, nodeUrl(), pivSlot)
                setAmendNote(holder, HolderSignNote.Signed(listOf(holder)))
                _amendPhase.value = AmendPhase.Planned(s)
            } catch (e: NodeRefusal) {
                setAmendNote(holder, HolderSignNote.Refused(FinalGenesisRefusal.of(e)))
                if (e.reasonId == FinalGenesisReason.AMEND_NOT_PLANNED) probeAmend()
            } catch (e: Exception) {
                setAmendNote(holder, HolderSignNote.Refused(FinalGenesisRefusal.transport(e)))
            }
        }
    }

    private fun setAmendNote(holder: String, note: HolderSignNote) {
        _amendNotes.value = _amendNotes.value + (holder to note)
    }

    /** Assemble at the quorum, verify, write the v4 bundle. */
    fun finishAmend(): Job = exclusive {
        _amendRefusal.value = null
        try {
            _amendPhase.value = AmendPhase.Finished(apiClient.finishFinalGenesisAmend(nodeUrl()))
        } catch (e: NodeRefusal) {
            _amendRefusal.value = FinalGenesisRefusal.of(e)
            if (e.reasonId == FinalGenesisReason.AMEND_QUORUM_NOT_MET || e.reasonId == FinalGenesisReason.AMEND_NOT_PLANNED) probeAmend()
        } catch (e: Exception) {
            _amendRefusal.value = FinalGenesisRefusal.transport(e)
        }
    }
}

/** Where the re-scrub (the 2-of-3 amend) is, as the node says. */
sealed interface AmendPhase {
    data object Probing : AmendPhase
    /** A bare 404 on `GET …/amend`: the node is older than the amend routes (CIRISServer 0.5.232). */
    data object TooOld : AmendPhase
    /** 409 `final_genesis.amend_not_planned`. */
    data object NotPlanned : AmendPhase
    data class Planned(val status: FinalGenesisAmendStatusDto) : AmendPhase
    data class Finished(val result: FinalGenesisFinishDto) : AmendPhase
    data class Unavailable(val refusal: FinalGenesisRefusal) : AmendPhase
}

/**
 * The commitment a recovery row shows: the node's own `recovery_commitment`,
 * prefix only, and NOTHING when the node did not report one. A hash computed
 * here over the Ed25519 half alone looked like the charter's commitment and was
 * not (Codex on #154); there is no look-alike.
 */
fun recoveryCommitmentShown(row: RecoveryRow): String? = when (row) {
    is RecoveryRow.OnRecord -> shortCommitment(row.commitment)
    is RecoveryRow.Verified -> shortCommitment(row.commitment)
    else -> null
}
