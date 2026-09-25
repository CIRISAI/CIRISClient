package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientSelfDevices
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.SelfDevicesApi
import ai.ciris.mobile.shared.models.federation.AddOccurrenceBody
import ai.ciris.mobile.shared.models.federation.AddOccurrenceRequest
import ai.ciris.mobile.shared.models.federation.MintedIdentity
import ai.ciris.mobile.shared.models.federation.OwnedNodeDto
import ai.ciris.mobile.shared.models.federation.SelfOccurrence
import ai.ciris.mobile.shared.models.federation.SubjectBlindKey
import ai.ciris.mobile.shared.models.federation.nodeReportsRevocation
import ai.ciris.mobile.shared.models.federation.subjectBlindKeyFor
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where a node RELEASE stands (`POST /v1/self/nodes/{node_key_id}/release`).
 *
 * Two confirms guard releasing the node you are talking to, and the second one
 * is only reachable through the node's own refusal: [NeedsForce] is entered
 * from `self.release_self_requires_force` and nowhere else, and `force_self` is
 * sent only from [ConfirmingForce]. The app does not decide which node is
 * "this one" — the node does, by refusing.
 */
sealed interface ReleaseState {
    data object Idle : ReleaseState
    /** The first confirm (three facts) is up for [node]. */
    data class Confirming(val node: OwnedNodeDto) : ReleaseState
    /** The node refused because [node] is the one answering; the screen explains and may offer force. */
    data class NeedsForce(val node: OwnedNodeDto, val refusal: NodeRefusal) : ReleaseState
    /** The SECOND confirm, for a forced release of the node you are talking to. */
    data class ConfirmingForce(val node: OwnedNodeDto) : ReleaseState
    data class Working(val node: OwnedNodeDto, val force: Boolean) : ReleaseState
    /** Done. [releasedSelf]: the node released was the one answering, and this session ended with it. */
    data class Released(val nodeKeyId: String, val releasedSelf: Boolean) : ReleaseState
    /** The node refused by name (`self.not_your_node`, `self.release_incomplete`, …). */
    data class Refused(val node: OwnedNodeDto, val refusal: NodeRefusal) : ReleaseState
}

/**
 * Drives the **Identity Management** screen — "manage my self + log in as myself
 * on another device" (CIRISServer#76, CEG §5.6.8.8 / §11.7). The client half of
 * the second-occurrence / laptop-loss-resilience feature.
 *
 * Two roles, one page:
 *  - On the PRIMARY device: shows the self fed-ID + device roster, lets the user
 *    ADD a new device (by its fedcode) and REVOKE a lost / stolen one.
 *  - On a NEW device: [enrollThisDevice] mints a local fed-ID and surfaces ITS
 *    fedcode for the primary to scan / paste.
 *
 * ARCHITECTURE: the app holds NO keys and performs NO crypto. The ADD / REVOKE are
 * federation-signed — the LOCAL node signs with the user's resolved fed-ID signer
 * (same posture as the consent / delegation cards). The app only DRIVES the node.
 */
class IdentityManagementViewModel(
    private val apiClient: CIRISApiClient,
    /**
     * The NODE, not the brain.
     *
     * `federation.*` warnings are raised by the node walking its own
     * `federation_keys` (CIRISServer#490). In the folded-agent deployment the
     * brain answers `/v1/system/health` on 8080 and the node on 4243, so asking
     * the client's default base URL finds no federation warning and the repair
     * card stays hidden — silently, and precisely in the deployment where the
     * damaged portable ID lives (Codex, PR #4).
     */
    private val nodeBaseUrl: String = CIRISApiClient.LOCAL_NODE_URL,
    /**
     * Every read this screen makes on load, and the 0.5.216 device writes
     * (label, release). A seam so tests drive a fake, never a live port.
     */
    private val devices: SelfDevicesApi = ClientSelfDevices(apiClient, nodeBaseUrl),
) : ViewModel() {

    companion object {
        private const val TAG = "IdentityMgmtVM"
        const val REASON_RELEASE_SELF_REQUIRES_FORCE = "self.release_self_requires_force"
        const val REASON_RELEASE_INCOMPLETE = "self.release_incomplete"
    }

    /** The self fed-ID `key_id` whose roster we list / mutate (the node's bound owner). */
    private val _identityKeyId = MutableStateFlow<String?>(null)
    val identityKeyId: StateFlow<String?> = _identityKeyId.asStateFlow()

    /** The shareable fedcode of THIS device's self fed-ID, when known. */
    private val _selfFedcode = MutableStateFlow<String?>(null)
    val selfFedcode: StateFlow<String?> = _selfFedcode.asStateFlow()

    /**
     * The device roster: the ACTIVE occurrences of the self, then (on a
     * 0.5.216+ node) the revoked ones, each `revoked == true`. Revoked devices
     * are shown as revoked — not hidden, and not active.
     */
    private val _occurrences = MutableStateFlow<List<SelfOccurrence>>(emptyList())
    val occurrences: StateFlow<List<SelfOccurrence>> = _occurrences.asStateFlow()

    /**
     * The fedcode this NEW device just minted (role 3) — render as text + QR for the
     * primary to enroll. Null until [enrollThisDevice] succeeds.
     */
    private val _enrolledIdentity = MutableStateFlow<MintedIdentity?>(null)
    val enrolledIdentity: StateFlow<MintedIdentity?> = _enrolledIdentity.asStateFlow()

    /**
     * A subject-blind key row on THIS operator's roster (CIRISServer#490), when
     * the node reports one. Null is the normal state and the only state that
     * renders nothing — including when the node reports the code without naming
     * a subject, because a card that says "your identity is unusable" about an
     * unidentified row is worse than no card. See [subjectBlindKeyFor].
     */
    private val _subjectBlind = MutableStateFlow<SubjectBlindKey?>(null)
    val subjectBlind: StateFlow<SubjectBlindKey?> = _subjectBlind.asStateFlow()

    /** The nodes the bound owner owns (`/v1/setup/owned-nodes`), for release. */
    private val _ownedNodes = MutableStateFlow<List<OwnedNodeDto>>(emptyList())
    val ownedNodes: StateFlow<List<OwnedNodeDto>> = _ownedNodes.asStateFlow()

    /**
     * True when this node predates the 0.5.216 device routes: its roster rows
     * carry no `revoked`, or a label / release answered a bare 404 (no
     * `reason_id` — the route is not mounted). The screen then says the node
     * cannot do this yet, instead of an empty list or a dead button.
     */
    private val _devicesUnsupported = MutableStateFlow(false)
    val devicesUnsupported: StateFlow<Boolean> = _devicesUnsupported.asStateFlow()

    /** The occurrence whose name is being edited, or null. */
    private val _labelling = MutableStateFlow<String?>(null)
    val labelling: StateFlow<String?> = _labelling.asStateFlow()

    /** The node's refusal of the last label save, shown by its id. */
    private val _labelRefusal = MutableStateFlow<NodeRefusal?>(null)
    val labelRefusal: StateFlow<NodeRefusal?> = _labelRefusal.asStateFlow()

    private val _release = MutableStateFlow<ReleaseState>(ReleaseState.Idle)
    val release: StateFlow<ReleaseState> = _release.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        load()
    }

    /**
     * Resolve THIS device's self fed-ID (from the node's self-key-record) and load
     * its device roster. The node's bound owner fed-ID IS its signer on a
     * self-claimed node, so the self-key-record key_id is the roster's
     * `identity_key_id`. Each round-trip fails independently.
     */
    fun load() {
        if (_loading.value) return
        _loading.value = true
        viewModelScope.launch {
            try {
                _error.value = null
                // Prefer the BOUND OWNER fed-ID (the human — e.g. eric-moore-v1).
                // getSelfKeyRecord() returns the NODE key (ciris-client), which is
                // the node's own signer, NOT the owner; on a self-claimed node the
                // roster we list/manage is the OWNER's occurrence roster, so the
                // owner fed-ID is the correct identity_key_id. Fall back to the node
                // self-key-record only when the node is still unclaimed (no owner).
                val owner = refreshOwnedNodes()
                val keyId = _identityKeyId.value
                    ?: owner?.takeIf { it.isNotBlank() }
                    ?: runCatching { devices.selfKeyId() }
                        .onFailure { PlatformLogger.w(TAG, "[load] self-key-record: ${it.message}") }
                        .getOrNull()
                if (keyId == null) {
                    _error.value = "Couldn't resolve this device's identity. Sign in / mint a fed-ID first."
                    _occurrences.value = emptyList()
                    return@launch
                }
                _identityKeyId.value = keyId
                refreshRoster(keyId)
            } finally {
                _loading.value = false
            }
        }
    }

    /** Reload the owner's nodes; returns the bound owner, or null when it could not be read. */
    private suspend fun refreshOwnedNodes(): String? =
        runCatching { devices.ownedNodes() }
            .onSuccess { _ownedNodes.value = it.nodes }
            .onFailure { PlatformLogger.w(TAG, "[ownedNodes] ${it.message}") }
            .getOrNull()
            ?.owner

    private suspend fun refreshRoster(keyId: String) {
        runCatching { devices.occurrences(keyId) }
            .onSuccess {
                _occurrences.value = it.occurrences
                // Only a non-empty roster says anything about the node's
                // version; an empty one leaves the last verdict standing.
                nodeReportsRevocation(it.occurrences)?.let { reports ->
                    _devicesUnsupported.value = !reports
                }
            }
            .onFailure { e ->
                PlatformLogger.w(TAG, "[refreshRoster] ${e.message}")
                _error.value = "Couldn't load the device roster: ${e.message}"
            }
        refreshSubjectBlind(keyId)
    }

    /**
     * Ask the node whether any identity on this roster is subject-blind.
     *
     * AFTER the roster, and never in place of it: the scope is the roster, so a
     * failed roster load means an EMPTY scope, and an empty scope must resolve
     * to "no card" rather than to "cannot tell, show it anyway". Its own failure
     * is logged and not surfaced — the identity screen still works when the
     * node's health endpoint does not answer, and an error banner about a probe
     * the user did not ask for is noise on the screen they came here for.
     */
    private suspend fun refreshSubjectBlind(keyId: String) {
        // The ACTIVE roster only: a revoked device's key is no longer this
        // person's identity, and a repair card about it would be about nothing
        // they can use.
        val owned = buildSet {
            add(keyId)
            _occurrences.value.filter { it.revoked != true }.forEach { add(it.occurrenceKeyId) }
        }
        runCatching { devices.nodeWarnings() }
            .onSuccess { warnings ->
                val found = subjectBlindKeyFor(warnings, owned)
                _subjectBlind.value = found
                if (found != null) {
                    PlatformLogger.w(
                        TAG,
                        "[subjectBlind] ${found.keyId} is refused everywhere it replicates " +
                            "(CIRISServer#490); repair route=${found.actionUrl ?: "none given"}",
                    )
                } else {
                    // Say WHY the card is hidden when the node raised the code
                    // and this could not attribute it: silence here would look
                    // identical to a healthy roster, and the difference is a
                    // missing field in the node's warning, not a healthy key.
                    warnings
                        .filter { it.code == ai.ciris.mobile.shared.models.federation.WARNING_KEY_SUBJECT_BLIND }
                        .forEach {
                            PlatformLogger.w(
                                TAG,
                                "[subjectBlind] the node raised ${it.code} but named no subject " +
                                    "(no subject_key_id / key_id / key_id= in action_url), so it " +
                                    "cannot be attributed to this roster and no card is shown",
                            )
                        }
                }
            }
            .onFailure {
                // CLEAR IT. A probe that failed is not evidence the identity is
                // still broken, and this view model has no periodic retry — so a
                // stale value would keep a red "your identity is unusable" card
                // on screen for the rest of its life, including after the repair
                // succeeded (Codex, PR #4). Absence of evidence renders nothing
                // here, exactly as it does for an unattributed warning and an
                // empty roster.
                _subjectBlind.value = null
                PlatformLogger.w(TAG, "[subjectBlind] node health probe: ${it.message}")
            }
    }

    /** Reload the roster for the resolved self fed-ID. */
    fun refresh() {
        val keyId = _identityKeyId.value
        if (keyId == null) {
            load()
            return
        }
        _loading.value = true
        viewModelScope.launch {
            try {
                _error.value = null
                refreshOwnedNodes()
                refreshRoster(keyId)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * ADD a device (role 2, on the primary). [code] is the NEW device's fedcode (its
     * `occurrence_key_id` — the fedcode the new device showed via [enrollThisDevice]).
     * The node signs the enrollment with the user's fed-ID. [deviceClass] is one of
     * `phone | laptop | agent`.
     */
    fun addDevice(code: String, deviceClass: String = "laptop") {
        val occurrenceKeyId = code.trim()
        val keyId = _identityKeyId.value
        if (keyId == null) {
            _error.value = "This device's identity isn't resolved yet — try again."
            return
        }
        if (occurrenceKeyId.isEmpty()) {
            _error.value = "Paste or scan the new device's fed-ID (its key_id / fedcode)."
            return
        }
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                val result = apiClient.addOccurrence(
                    AddOccurrenceRequest(
                        identityKeyId = keyId,
                        occurrence = AddOccurrenceBody(
                            occurrenceKeyId = occurrenceKeyId,
                            deviceClass = deviceClass,
                        ),
                    ),
                )
                _notice.value = if (result.keyFreshlyRegistered) {
                    "Device enrolled and its key admitted."
                } else {
                    "Device enrolled."
                }
                refreshRoster(keyId)
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[addDevice] ${e.message}")
                val msg = e.message.orEmpty()
                _error.value = when {
                    msg.contains("401") -> "Sign in as yourself first, then add the device."
                    msg.contains("400") && msg.contains("occurrence_key_record") ->
                        "That fed-ID isn't known to this node yet — enroll it from the new device first (it must mint + publish its key)."
                    else -> "Couldn't add the device: ${e.message}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** REVOKE a (lost / stolen) device. Sign with a SURVIVING key, never the lost one. */
    fun revoke(occurrenceKeyId: String, reason: String? = null) {
        val keyId = _identityKeyId.value
        if (keyId == null) {
            _error.value = "This device's identity isn't resolved yet — try again."
            return
        }
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                apiClient.revokeOccurrence(keyId, occurrenceKeyId.trim(), reason)
                _notice.value = "Revoked ${occurrenceKeyId.take(16)}…"
                refreshRoster(keyId)
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[revoke] ${e.message}")
                val msg = e.message.orEmpty()
                _error.value = when {
                    msg.contains("401") -> "Sign in with a surviving key first."
                    else -> "Couldn't revoke: ${e.message}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * "Log in as yourself on another device" (role 3, on the NEW device): mint a
     * local fed-ID. The node mints the hybrid keypair in its keyring/substrate and
     * returns the shareable fedcode; the screen renders it as text + a QR for the
     * primary to scan / paste into [addDevice].
     */
    fun enrollThisDevice() {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                val minted = apiClient.mintUserIdentity()
                _enrolledIdentity.value = minted
                _notice.value = "Minted this device's fed-ID. Show its fedcode to your primary device to enroll it."
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[enrollThisDevice] ${e.message}")
                _error.value = "Couldn't mint this device's identity: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Dismiss the just-minted enroll card. */
    fun clearEnrolled() {
        _enrolledIdentity.value = null
    }

    /**
     * Create a **portable software identity occurrence** into [targetDir] (a USB
     * folder). The LOCAL node mints a fresh *software* hybrid keyset there and binds
     * it as a primary-authorized occurrence of the owner's self. The app passes only
     * the path; the node writes the seeds + does the crypto. A software keyset is
     * inherently insecure — the accepted bootstrap trade-off.
     */
    fun createPortableOccurrence(targetDir: String, label: String? = null) {
        val dir = targetDir.trim()
        if (dir.isEmpty()) {
            _error.value = "Choose the USB folder to write the portable keyset into."
            return
        }
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                val result = apiClient.createPortableOccurrence(dir, label)
                _notice.value =
                    "Portable software identity occurrence created: ${result.keyId.take(20)}… — stored on $dir"
                _identityKeyId.value?.let { refreshRoster(it) }
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[createPortableOccurrence] ${e.message}")
                val msg = e.message.orEmpty()
                _error.value = when {
                    msg.contains("401") || msg.contains("403") ->
                        "Sign in as the owner first, then create the portable occurrence."
                    msg.contains("no bound owner") || msg.contains("503") ->
                        "This node has no bound owner fed-ID yet — claim ownership first."
                    else -> "Couldn't create the portable occurrence: ${e.message}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * **Associate an existing fed-ID** as THIS device's active user identity. The
     * directory path installs a portable software keyset from [sourceDir]; the
     * YubiKey path ([yubikey] = true) is server-gated for now (501).
     */
    fun associateFedId(sourceDir: String? = null, yubikey: Boolean = false) {
        if (!yubikey && sourceDir.isNullOrBlank()) {
            _error.value = "Choose the folder holding the portable keyset to associate."
            return
        }
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _notice.value = null
        viewModelScope.launch {
            try {
                val result = apiClient.associateFedId(sourceDir = sourceDir, yubikey = yubikey)
                _notice.value =
                    "Associated this device as ${result.associatedKeyId?.take(20) ?: result.alias}…"
                load()
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[associateFedId] ${e.message}")
                val msg = e.message.orEmpty()
                _error.value = when {
                    msg.contains("501") ->
                        "YubiKey association isn't available yet — use a directory for now."
                    msg.contains("401") || msg.contains("403") ->
                        "Sign in as the owner first, then associate the fed-ID."
                    msg.contains("no portable keyset") ->
                        "No portable keyset found in that folder — pick the folder you wrote it to."
                    else -> "Couldn't associate the fed-ID: ${e.message}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // ─── Name a device (POST /v1/self/occurrence/label, 0.5.216) ────────────

    /** Open the name editor for [occurrenceKeyId]. */
    fun startLabel(occurrenceKeyId: String) {
        _labelRefusal.value = null
        _labelling.value = occurrenceKeyId
    }

    fun cancelLabel() {
        _labelRefusal.value = null
        _labelling.value = null
    }

    /**
     * Save [label] as the name of the device being edited. The node trims it
     * and holds the rule (1–64 characters, `self.label_empty`); its refusal is
     * kept whole in [labelRefusal] so the screen names it.
     */
    fun saveLabel(label: String) {
        val occurrence = _labelling.value ?: return
        if (_busy.value) return
        _busy.value = true
        _labelRefusal.value = null
        viewModelScope.launch {
            try {
                devices.labelOccurrence(occurrence, label)
                _labelling.value = null
                _identityKeyId.value?.let { refreshRoster(it) }
            } catch (e: NodeRefusal) {
                PlatformLogger.w(TAG, "[saveLabel] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                if (e.statusCode == 404 && e.reasonId == null) {
                    // The route is not mounted — a version fact, not a failure.
                    _devicesUnsupported.value = true
                    _labelling.value = null
                } else {
                    _labelRefusal.value = e
                }
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[saveLabel] ${e.message}")
                _labelRefusal.value = NodeRefusal(null, e.message, 0)
            } finally {
                _busy.value = false
            }
        }
    }

    // ─── Release a node (POST /v1/self/nodes/{id}/release, 0.5.216) ─────────

    /** Put up the first confirm for releasing [nodeKeyId]. */
    fun askRelease(nodeKeyId: String) {
        val node = _ownedNodes.value.firstOrNull { it.keyId == nodeKeyId } ?: OwnedNodeDto(nodeKeyId)
        _release.value = ReleaseState.Confirming(node)
    }

    fun cancelRelease() {
        _release.value = ReleaseState.Idle
    }

    /** The first confirm was accepted: release WITHOUT force. */
    fun confirmRelease() {
        val state = _release.value as? ReleaseState.Confirming ?: return
        release(state.node, force = false)
    }

    /**
     * The person read why the node refused and asked to go on: put up the
     * SECOND confirm. Only reachable from [ReleaseState.NeedsForce].
     */
    fun askForceRelease() {
        val state = _release.value as? ReleaseState.NeedsForce ?: return
        _release.value = ReleaseState.ConfirmingForce(state.node)
    }

    /** The second confirm was accepted: the only path that sends `force_self: true`. */
    fun confirmForceRelease() {
        val state = _release.value as? ReleaseState.ConfirmingForce ?: return
        release(state.node, force = true)
    }

    private fun release(node: OwnedNodeDto, force: Boolean) {
        _release.value = ReleaseState.Working(node, force)
        viewModelScope.launch {
            try {
                val result = devices.releaseNode(node.keyId, force)
                _release.value = ReleaseState.Released(node.keyId, result.releasedSelf)
                refreshOwnedNodes()
            } catch (e: NodeRefusal) {
                PlatformLogger.w(TAG, "[release] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                _release.value = when {
                    e.statusCode == 404 && e.reasonId == null -> {
                        _devicesUnsupported.value = true
                        ReleaseState.Idle
                    }
                    e.reasonId == REASON_RELEASE_SELF_REQUIRES_FORCE && !force -> ReleaseState.NeedsForce(node, e)
                    else -> ReleaseState.Refused(node, e)
                }
                // A release_incomplete was SIGNED: re-read what the node now lists.
                if (e.reasonId == REASON_RELEASE_INCOMPLETE) refreshOwnedNodes()
            } catch (e: Exception) {
                PlatformLogger.w(TAG, "[release] ${e.message}")
                _release.value = ReleaseState.Refused(node, NodeRefusal(null, e.message, 0))
            }
        }
    }

    fun clearMessages() {
        _error.value = null
        _notice.value = null
    }
}
