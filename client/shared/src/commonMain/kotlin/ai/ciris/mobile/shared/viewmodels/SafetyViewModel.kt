package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.safety.AgeAssurance
import ai.ciris.mobile.shared.models.safety.AgeBand
import ai.ciris.mobile.shared.models.safety.ExistenceVerdict
import ai.ciris.mobile.shared.models.safety.ModerationDuty
import ai.ciris.mobile.shared.models.safety.SafetyHonesty
import ai.ciris.mobile.shared.models.safety.SafetyStatusResponse
import ai.ciris.mobile.shared.models.safety.WatchlistClass
import ai.ciris.mobile.shared.models.safety.WatchlistEnable
import ai.ciris.mobile.shared.models.safety.WatchlistHonesty
import ai.ciris.mobile.shared.models.safety.WatchlistListResponse
import ai.ciris.mobile.shared.models.safety.WatchlistMode
import ai.ciris.mobile.shared.models.safety.WatchlistResponse
import ai.ciris.mobile.shared.platform.PlatformLogger
import ai.ciris.mobile.shared.ui.screens.ReadFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "SafetyViewModel"

/**
 * The node calls the Safety card makes (CSD-066 §3), as an interface so the
 * view model is driven by a fake in `SafetyCardTest`. Every call goes to the
 * LOCAL node (`CIRISApiClient.LOCAL_NODE_URL`); the app does no crypto.
 */
interface SafetyApi {
    /** `GET /v1/setup/owned-nodes` — `owner` is the bound owner's fed-ID, the PERSON. */
    suspend fun ownedNodes(): OwnedNodesDto

    /** `GET /v1/federation/self-key-record` — the NODE's own signer key, not the person. */
    suspend fun selfKeyId(): String

    /** `GET /v1/safety/status/{key_id}`. */
    suspend fun safetyStatus(keyId: String): SafetyStatusResponse

    /** `GET /v1/safety/watchlist/{group_key_id}`. */
    suspend fun watchlist(groupKeyId: String): WatchlistListResponse

    /** `POST /v1/safety/watchlist` — hybrid-signed on the node; throws [NodeRefusal] on a non-2xx. */
    suspend fun setWatchlist(
        signerKeyId: String,
        groupKeyId: String,
        watchlistId: String,
        watchlistClass: WatchlistClass,
        enabled: Boolean,
        mode: WatchlistMode,
        routeToModerator: String?,
    ): WatchlistResponse

    /** `POST /v1/self/age {band}` — owner-session; the node signs as the owner's fed-ID. */
    suspend fun setAgeSelf(band: AgeBand): String
}

/** [SafetyApi] over the real client. */
class ClientSafetyApi(private val client: CIRISApiClient) : SafetyApi {
    override suspend fun ownedNodes(): OwnedNodesDto = client.getOwnedNodes()
    override suspend fun selfKeyId(): String = client.getSelfKeyRecord(CIRISApiClient.LOCAL_NODE_URL).keyId
    override suspend fun safetyStatus(keyId: String): SafetyStatusResponse = client.getSafetyStatus(keyId)
    override suspend fun watchlist(groupKeyId: String): WatchlistListResponse = client.getWatchlist(groupKeyId)
    override suspend fun setWatchlist(
        signerKeyId: String,
        groupKeyId: String,
        watchlistId: String,
        watchlistClass: WatchlistClass,
        enabled: Boolean,
        mode: WatchlistMode,
        routeToModerator: String?,
    ): WatchlistResponse = client.setWatchlist(
        signerKeyId = signerKeyId,
        groupKeyId = groupKeyId,
        watchlistId = watchlistId,
        watchlistClass = watchlistClass,
        enabled = enabled,
        mode = mode,
        routeToModerator = routeToModerator,
    )
    override suspend fun setAgeSelf(band: AgeBand): String =
        client.setAgeSelf(if (band == AgeBand.MINOR) "minor" else "adult")
}

/**
 * The last read of a group's enables. Four states, four renderings: an empty
 * list, a failed read and a group never asked about must not look alike on a
 * mechanism whose default is "off" (CSD-066 §2).
 */
sealed interface WatchlistRead {
    data object NotAsked : WatchlistRead
    data object Loading : WatchlistRead
    data class Loaded(val enables: List<WatchlistEnable>, val honesty: WatchlistHonesty?) : WatchlistRead
    data class Failed(val failure: ReadFailure) : WatchlistRead
}

/**
 * Why the node did not take a watchlist enable/disable. [Unsigned] is the
 * node asking for the moderate-holder's request signature (`x-ciris-key-id` +
 * Ed25519/ML-DSA), which this app does not produce: not a wrong password, and
 * not "not a holder" — that is [NotAHolder], the 403.
 */
sealed interface WatchlistWriteRefusal {
    data object Unsigned : WatchlistWriteRefusal
    data object NotAHolder : WatchlistWriteRefusal
    data class Refused(val reasonId: String?, val detail: String?, val status: Int) : WatchlistWriteRefusal
    data class Failed(val detail: String?) : WatchlistWriteRefusal

    companion object {
        fun of(e: Throwable): WatchlistWriteRefusal = when {
            e is NodeRefusal && e.statusCode == 401 -> Unsigned
            e is NodeRefusal && e.statusCode == 403 -> NotAHolder
            e is NodeRefusal -> Refused(e.reasonId, e.detail, e.statusCode)
            else -> Failed(e.message ?: e::class.simpleName)
        }
    }
}

/** Restating the owner's self-declared band from the Safety card (`POST /v1/self/age`). */
sealed interface AgeRestate {
    data object Idle : AgeRestate
    data class Working(val band: AgeBand) : AgeRestate
    data class Recorded(val band: AgeBand) : AgeRestate
    data class Failed(val band: AgeBand, val detail: String?) : AgeRestate
}

/**
 * UI state for the holistic SAFETY surface (moderation + child-safety cards).
 *
 * The app holds NO keys and performs NO crypto: this VM only DRIVES the local
 * node's `/v1/safety/` endpoints and surfaces the results. The caller's
 * identity key_id is resolved by probing the local node's self-key-record (it
 * becomes both the `signer_key_id` for actions and the `key_id` for status).
 */
data class SafetyState(
    // ── Caller identity (probed from the local node) ──
    /** This device's federation key_id, or null until probed (no identity yet). */
    val selfKeyId: String? = null,
    val identityProbed: Boolean = false,

    // ── Protective posture (GET /v1/safety/status/{key_id}) ──
    /**
     * WHOSE posture the card shows: the bound owner's fed-ID (the person),
     * read from owned-nodes `owner`; only an unclaimed node falls back to its
     * own signer key. Null until resolved. The read is gated to THIS key on
     * the client — the node serves the status and age-assurance reads to any
     * caller, and this card never asks about anyone else (CSD-066 §3).
     */
    val subjectKeyId: String? = null,
    /** True when [subjectKeyId] is the owner's fed-ID; false when it is the node's key (unclaimed). */
    val subjectIsOwner: Boolean = false,
    /**
     * Why whose posture this is could not be resolved: the owned-nodes read
     * FAILED. Not "unclaimed" — only a successful answer naming no owner is
     * that — so no posture is read and the node key is never substituted.
     */
    val subjectFailure: ReadFailure? = null,
    val ageAssurance: AgeAssurance? = null,
    val statusHonesty: SafetyHonesty? = null,
    val statusLoading: Boolean = false,
    /** Why the posture could not be read — never rendered as "no band on record". */
    val statusFailure: ReadFailure? = null,
    val ageRestate: AgeRestate = AgeRestate.Idle,

    // ── Moderation card ──
    /** The community key_id the report / named-moderator lookup is scoped to. */
    val communityKeyId: String = "",
    val selectedDuty: ModerationDuty = ModerationDuty.MODERATE,
    /** The `moderation:{allegation_type}` token (free-vocab). */
    val allegationType: String = "",
    /** Comma-separated target key_ids the report names (optional). */
    val targetKeyIdsRaw: String = "",
    val reportNote: String = "",
    val filing: Boolean = false,
    /** Result of the last filing: the attestation id, or null. */
    val lastModerationAttestationId: String? = null,

    // ── Named-moderator existence invariant (GET /v1/safety/named-moderator) ──
    val namedModeratorLoading: Boolean = false,
    val namedModeratorVerdict: ExistenceVerdict? = null,
    /** Always true on the wire — surfaced verbatim (better no group than one
     *  with no moderator). */
    val namedModeratorFailsSecure: Boolean = true,
    /**
     * Why the last verdict read produced no verdict. Its own field, never the
     * shared [error]: on a fail-secure invariant "we could not ask" and "this
     * community has no moderator" are opposites, and a null verdict alone
     * cannot tell them apart from "never asked" (CSD-065 §2).
     */
    val namedModeratorFailure: ReadFailure? = null,

    // ── Child-safety / watchlist card ──
    /** The group key_id the watchlist applies to (you must hold `moderate`). */
    val watchlistGroupKeyId: String = "",
    val watchlistId: String = "",
    val watchlistClass: WatchlistClass = WatchlistClass.OTHER_CONTENT,
    val watchlistMode: WatchlistMode = WatchlistMode.ALERT_ONLY,
    val routeToModerator: String = "",
    val watchlistLoading: Boolean = false,
    val watchlistEnables: List<WatchlistEnable> = emptyList(),
    val watchlistHonesty: WatchlistHonesty? = null,
    /** The typed read: what the enables list, the empty line, the spinner and the failure render from. */
    val watchlistRead: WatchlistRead = WatchlistRead.NotAsked,
    val watchlistMutating: Boolean = false,
    /** The node's answer to the last enable/disable, when it did not take it. */
    val watchlistWriteRefusal: WatchlistWriteRefusal? = null,

    // ── Shared ──
    val error: String? = null,
    val message: String? = null,
)

/**
 * Drives the **holistic SAFETY surface** — moderation + child-safety, built
 * AHEAD of content. Every action is a localhost call to THIS device's local node
 * (`http://127.0.0.1:8080`). The app does NO crypto; the node's substrate signs
 * + runs the admission gates (the §11.10 duty gate, the CC 4.5.4 existence
 * invariant). 403s from non-duty-holders are surfaced honestly.
 */
class SafetyViewModel(
    private val apiClient: CIRISApiClient,
    /** The Safety card's node calls. The real client's by default; a fake in tests. */
    private val api: SafetyApi = ClientSafetyApi(apiClient),
) : ViewModel() {

    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    /**
     * Probe THIS device's local node for the caller's federation key_id, then
     * load the protective posture (age assurance) for the PERSON. The node key
     * is the `signer_key_id` for moderation/watchlist actions; the posture's
     * subject is the bound owner's fed-ID (owned-nodes `owner`), the way
     * IdentityManagement resolves it — a node's signer key has no age, and
     * asking about it drew "unknown" for a person who had declared a band
     * (CSD-066 §3). Only an unclaimed node falls back to its own key.
     * If the node holds no identity yet, the cards surface that honestly.
     */
    fun probeIdentityAndStatus() {
        viewModelScope.launch {
            val keyId = try {
                api.selfKeyId()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "probeIdentity: local node has no identity yet: ${e.message}")
                null
            }
            // The PERSON: the bound owner's fed-ID. A node's signer key has no
            // age; only an unclaimed node — a SUCCESSFUL owned-nodes answer
            // naming no owner — is asked about its own. A FAILED lookup is not
            // "unclaimed": substituting the node key there showed the wrong
            // posture and hid the owner's controls (Codex, PR #126).
            val owner: Result<String?> = try {
                Result.success(api.ownedNodes().owner?.takeIf { it.isNotBlank() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "probeIdentity: owned-nodes unreadable (${e.message}); whose posture is unresolved")
                Result.failure(e)
            }
            val failure = owner.exceptionOrNull()
            val ownerKey = owner.getOrNull()
            val subject = if (failure != null) null else ownerKey ?: keyId
            _state.value = _state.value.copy(
                selfKeyId = keyId,
                identityProbed = true,
                subjectKeyId = subject,
                subjectIsOwner = ownerKey != null,
                subjectFailure = failure?.let { ReadFailure.of(it) },
            )
            if (subject != null) loadStatus(subject)
        }
    }

    /**
     * Load the aggregate protective posture (`GET /v1/safety/status/{key_id}`)
     * — for the resolved subject only. A failed read is its own state
     * ([SafetyState.statusFailure]), never "no band on record".
     */
    fun loadStatus(keyId: String) {
        _state.value = _state.value.copy(statusLoading = true, statusFailure = null, error = null)
        viewModelScope.launch {
            try {
                val resp = api.safetyStatus(keyId)
                _state.value = _state.value.copy(
                    statusLoading = false,
                    ageAssurance = resp.ageAssurance,
                    statusHonesty = resp.honesty,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "safety status read failed for $keyId: ${e.message}")
                _state.value = _state.value.copy(
                    statusLoading = false,
                    ageAssurance = null,
                    statusFailure = ReadFailure.of(e),
                )
            }
        }
    }

    /**
     * **(Re)state the owner's self-declared age band** — `POST /v1/self/age`,
     * the owner-session route the wizard uses. The node signs the attestation
     * as the OWNER's fed-ID; the app holds no keys. The federation route
     * (`POST /v1/safety/age-assurance`) wants a subject-signed request and is
     * not used. After the node answers, the posture is re-read from the node:
     * the card shows what the node recorded, not what was asked for.
     */
    fun restateAgeBand(band: AgeBand) {
        val s = _state.value
        if (s.ageRestate is AgeRestate.Working) return
        _state.value = s.copy(ageRestate = AgeRestate.Working(band), error = null, message = null)
        viewModelScope.launch {
            try {
                api.setAgeSelf(band)
                _state.value = _state.value.copy(ageRestate = AgeRestate.Recorded(band))
                _state.value.subjectKeyId?.let { loadStatus(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "restateAgeBand failed: ${e.message}")
                _state.value = _state.value.copy(ageRestate = AgeRestate.Failed(band, e.message ?: e::class.simpleName))
            }
        }
    }

    // ── Moderation card setters ──
    /** A different community drops the last one's verdict: it answered about a key no longer on screen. */
    fun setCommunityKeyId(v: String) {
        val s = _state.value
        _state.value = if (v.trim() == s.communityKeyId.trim()) {
            s.copy(communityKeyId = v)
        } else {
            s.copy(communityKeyId = v, namedModeratorVerdict = null, namedModeratorFailure = null)
        }
    }
    fun setDuty(d: ModerationDuty) { _state.value = _state.value.copy(selectedDuty = d) }
    fun setAllegationType(v: String) { _state.value = _state.value.copy(allegationType = v) }
    fun setTargetKeyIdsRaw(v: String) { _state.value = _state.value.copy(targetKeyIdsRaw = v) }
    fun setReportNote(v: String) { _state.value = _state.value.copy(reportNote = v) }

    /**
     * **File a ModerationEvent** (`POST /v1/safety/moderation`). Admitted by the
     * node IFF the signer holds the duty or sits on a live delegated chain;
     * non-holders get a 403 surfaced here. The app does no crypto.
     */
    fun fileModeration() {
        val s = _state.value
        val signer = s.selfKeyId
        if (signer == null) {
            _state.value = s.copy(error = "No federation identity on this device yet.")
            return
        }
        if (s.communityKeyId.isBlank() || s.allegationType.isBlank()) {
            _state.value = s.copy(error = "Community and allegation type are required.")
            return
        }
        val targets = s.targetKeyIdsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        _state.value = s.copy(filing = true, error = null, message = null, lastModerationAttestationId = null)
        viewModelScope.launch {
            try {
                val resp = apiClient.fileModeration(
                    signerKeyId = signer,
                    communityKeyId = s.communityKeyId.trim(),
                    duty = s.selectedDuty,
                    allegationType = s.allegationType.trim(),
                    targetKeyIds = targets,
                    note = s.reportNote.trim().ifBlank { null },
                )
                _state.value = _state.value.copy(
                    filing = false,
                    lastModerationAttestationId = resp.attestationId,
                    message = "Report filed (${resp.duty}).",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    filing = false,
                    error = "Couldn't file report: ${e.message}",
                )
            }
        }
    }

    /**
     * **Look up the named moderator** for the current community
     * (`GET /v1/safety/named-moderator/{community_key_id}`). Surfaces the CC
     * 4.5.4 existence verdict (operate / auto_promote / quiesce).
     */
    fun loadNamedModerator() {
        val community = _state.value.communityKeyId.trim()
        if (community.isBlank()) {
            _state.value = _state.value.copy(error = "Enter a community key_id first.")
            return
        }
        // The previous community's verdict is dropped the moment a new one is
        // asked about: a stale "Moderated" under a new key is a false clean.
        _state.value = _state.value.copy(
            namedModeratorLoading = true,
            namedModeratorVerdict = null,
            namedModeratorFailure = null,
            error = null,
        )
        viewModelScope.launch {
            try {
                val resp = apiClient.getNamedModerator(community)
                _state.value = _state.value.copy(
                    namedModeratorLoading = false,
                    namedModeratorVerdict = resp.existence,
                    namedModeratorFailsSecure = resp.failsSecure,
                )
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "named-moderator read failed: ${e.message}")
                _state.value = _state.value.copy(
                    namedModeratorLoading = false,
                    namedModeratorFailure = ReadFailure.of(e),
                )
            }
        }
    }

    // ── Child-safety / watchlist setters ──
    fun setWatchlistGroupKeyId(v: String) { _state.value = _state.value.copy(watchlistGroupKeyId = v) }
    fun setWatchlistId(v: String) { _state.value = _state.value.copy(watchlistId = v) }
    fun setWatchlistClass(c: WatchlistClass) { _state.value = _state.value.copy(watchlistClass = c) }
    fun setWatchlistMode(m: WatchlistMode) { _state.value = _state.value.copy(watchlistMode = m) }
    fun setRouteToModerator(v: String) { _state.value = _state.value.copy(routeToModerator = v) }

    /** Load the current watchlist enables + honesty block for the group. */
    fun loadWatchlist() {
        val group = _state.value.watchlistGroupKeyId.trim()
        if (group.isBlank()) {
            _state.value = _state.value.copy(error = "Enter a group key_id first.")
            return
        }
        // A new read drops the last group's list: a stale "nothing watched"
        // under a new key would be a false clean (the same rule as the
        // named-moderator verdict).
        _state.value = _state.value.copy(
            watchlistLoading = true,
            watchlistRead = WatchlistRead.Loading,
            watchlistEnables = emptyList(),
            error = null,
        )
        viewModelScope.launch {
            try {
                val resp = api.watchlist(group)
                _state.value = _state.value.copy(
                    watchlistLoading = false,
                    watchlistEnables = resp.enables,
                    watchlistHonesty = resp.honesty,
                    watchlistRead = WatchlistRead.Loaded(resp.enables, resp.honesty),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "watchlist read failed for $group: ${e.message}")
                _state.value = _state.value.copy(
                    watchlistLoading = false,
                    watchlistRead = WatchlistRead.Failed(ReadFailure.of(e)),
                )
            }
        }
    }

    /**
     * **Enable or disable** a per-group watchlist (`POST /v1/safety/watchlist`).
     * Opt-in, default OFF, per-group, NEVER global. `moderate`-gated; CSAM also
     * `takedown`-gated. Disable is a POST with `enabled=false` (the node emits a
     * `withdraws`). Non-authorized signers get a 403 surfaced here.
     */
    fun setWatchlistEnabled(enabled: Boolean) {
        val s = _state.value
        val signer = s.selfKeyId
        if (signer == null) {
            _state.value = s.copy(error = "No federation identity on this device yet.")
            return
        }
        if (s.watchlistGroupKeyId.isBlank() || s.watchlistId.isBlank()) {
            _state.value = s.copy(error = "Group and watchlist id are required.")
            return
        }
        _state.value = s.copy(watchlistMutating = true, watchlistWriteRefusal = null, error = null, message = null)
        viewModelScope.launch {
            try {
                api.setWatchlist(
                    signerKeyId = signer,
                    groupKeyId = s.watchlistGroupKeyId.trim(),
                    watchlistId = s.watchlistId.trim(),
                    watchlistClass = s.watchlistClass,
                    enabled = enabled,
                    mode = s.watchlistMode,
                    routeToModerator = s.routeToModerator.trim().ifBlank { null },
                )
                _state.value = _state.value.copy(
                    watchlistMutating = false,
                    message = if (enabled) "Watchlist enabled for this group." else "Watchlist disabled for this group.",
                )
                // What is on now is what the node lists, not what was asked for.
                loadWatchlist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "watchlist write refused: ${e.message}")
                _state.value = _state.value.copy(
                    watchlistMutating = false,
                    watchlistWriteRefusal = WatchlistWriteRefusal.of(e),
                )
            }
        }
    }

    fun clearMessages() { _state.value = _state.value.copy(error = null, message = null) }
}
