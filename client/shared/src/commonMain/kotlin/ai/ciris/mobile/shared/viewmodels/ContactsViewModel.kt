package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientContactsApi
import ai.ciris.mobile.shared.api.ContactsApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.federation.ContactCodeResponse
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.models.federation.LocalPeerState
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the **Contacts** screen, which serves two different questions from two
 * different routes — deliberately, because they are two different sets.
 *
 *  - **Browse mode** — ``GET /v1/contacts``: the people the owner has actually
 *    consented to replicate with. This is the node client's HOME surface, so
 *    "who can I talk to" must not be answered with "every key this node has
 *    ever seen announced".
 *  - **Picker mode** (Delegations) — ``GET /v1/federation/peers``: any KNOWN
 *    identity. A delegation target need not be a contact, and narrowing the
 *    picker to contacts would silently remove valid targets.
 *
 * Contacts ⊂ peers, so the two lists are not interchangeable in either
 * direction. The contact set is persist's revocation-FOLDED consent peer set —
 * a withdrawn grant is already absent, which is why there is no "remove
 * contact" call here to pair with [addContact].
 *
 * It also drives the **Share my contact code** card (CSD-092): the person's
 * own code from `GET /v1/self/contact-code`, the device picker, and the rule
 * that a code reaching no one is not shown.
 *
 * @param nodeBaseUrl the NODE's own address. Adding a contact and the contact
 *   code go there whenever an agent sits at the api base ([contactsNodeUrl]).
 * @param api every node call this view model makes; a fake in tests.
 */
class ContactsViewModel(
    apiClient: CIRISApiClient,
    private val nodeBaseUrl: String = CIRISApiClient.LOCAL_NODE_URL,
    private val api: ContactsApi = ClientContactsApi(apiClient),
) : BaseFederationViewModel(apiClient) {

    override val tag: String = "ContactsVM"

    // ── Contacts (browse mode — GET /v1/contacts) ────────────────────────────

    /**
     * Session epoch: advanced by [clearSessionState]. Every coroutine that
     * publishes into this ViewModel captures it at LAUNCH and re-checks before
     * publishing — the clear empties the flows exactly once, and without the
     * gate an authenticated request still in flight at logout repopulates them
     * afterward, exposing the previous owner's contacts to the signed-out
     * screen or the next user (codex, fresh evidence after the first clear fix).
     */
    private var sessionEpoch: Long = 0L

    private val _allContacts = MutableStateFlow<List<Contact>>(emptyList())

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /** True once a contacts load has completed — tells "empty" from "not asked yet". */
    private val _contactsLoaded = MutableStateFlow(false)
    val contactsLoaded: StateFlow<Boolean> = _contactsLoaded.asStateFlow()

    /**
     * The node does not serve `/v1/contacts` at all — it predates the surface.
     *
     * The Android APK's EMBEDDED node pins `ciris-server` from PyPI and cannot
     * pin a release that has not published yet, so for one release the packaged
     * node 404s this route. That is a KNOWN, temporary, version-shaped fact, and
     * it must not render as "you have no contacts" (a lie) or as a red error (a
     * bug report for something working as designed).
     *
     * Detected as `404 with NO reason_id`: every refusal this surface authors
     * carries a typed id, and the GET has no 404 arm at all, so a bare 404 is
     * axum saying the route is not mounted. A 404 that DOES carry an id is a
     * real refusal and is reported normally.
     */
    private val _routeUnsupported = MutableStateFlow(false)
    val routeUnsupported: StateFlow<Boolean> = _routeUnsupported.asStateFlow()

    /**
     * A contact whose live grant does NOT cover `chat:` — messages to them
     * cannot replicate (CIRISServer#458). Keyed by key_id.
     *
     * BELT AND SUSPENDERS, deliberately. The server tightened `GET /v1/contacts`
     * to list only peers whose LIVE grant covers `chat:` — a contact IS someone
     * you can actually message — so on a current node this set stays empty and a
     * successful `POST /v1/contacts` always comes back carrying `chat:`. It is
     * kept because the client must not depend on that: an older node still
     * serves the wider set, and a narrow grant arriving from anywhere must be
     * SAID rather than rendered as an ordinary contact. Silence about it is
     * exactly how #458 stayed invisible.
     */
    private val _chatIneligible = MutableStateFlow<Set<String>>(emptySet())
    val chatIneligible: StateFlow<Set<String>> = _chatIneligible.asStateFlow()

    // ── Raw peer list (picker mode — all, unsearched) ────────────────────────

    private val _allPeers = MutableStateFlow<List<LocalPeerState>>(emptyList())

    // ── Search query (applies to whichever list is showing) ──────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ── Filtered peer view (recomputed whenever _allPeers or _searchQuery changes) ─

    private val _peers = MutableStateFlow<List<LocalPeerState>>(emptyList())
    val peers: StateFlow<List<LocalPeerState>> = _peers.asStateFlow()

    // ── Selection (used in picker mode) ──────────────────────────────────────

    private val _selectedPeer = MutableStateFlow<LocalPeerState?>(null)
    val selectedPeer: StateFlow<LocalPeerState?> = _selectedPeer.asStateFlow()

    // ── Add-a-contact ────────────────────────────────────────────────────────

    private val _addBusy = MutableStateFlow(false)
    val addBusy: StateFlow<Boolean> = _addBusy.asStateFlow()

    /**
     * The node's typed `reason_id` for the last add refusal, or null.
     *
     * Kept BESIDE [addError] rather than folded into it: the id is what lets the
     * screen tell `contacts.unknown_fed_id` ("admit the key first") from
     * `contacts.self_contact` ("that is you") from `contacts.store_unavailable`
     * ("the node could not answer"), and those have nothing in common but the
     * colour red.
     */
    private val _addRefusalReasonId = MutableStateFlow<String?>(null)
    val addRefusalReasonId: StateFlow<String?> = _addRefusalReasonId.asStateFlow()

    /** The node's English fallback for the last add refusal, or null. */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** The contact key_id most recently added — one-shot, consumed by the screen. */
    private val _justAdded = MutableStateFlow<Contact?>(null)
    val justAdded: StateFlow<Contact?> = _justAdded.asStateFlow()

    /**
     * Whether [justAdded] was a new grant or the person was already a contact
     * (`freshly_emitted: false`). Adding the same person twice is a no-op on
     * the node, and it is said as one — never as an error.
     */
    private val _addOutcome = MutableStateFlow<AddContactOutcome?>(null)
    val addOutcome: StateFlow<AddContactOutcome?> = _addOutcome.asStateFlow()

    // ── Share my contact code (CSD-092) ──────────────────────────────────────

    private val _contactCode = MutableStateFlow<ContactCodeState>(ContactCodeState.Closed)
    val contactCode: StateFlow<ContactCodeState> = _contactCode.asStateFlow()

    private val _contactCodeNodes = MutableStateFlow(ContactCodeNodes.ALL)
    val contactCodeNodes: StateFlow<ContactCodeNodes> = _contactCodeNodes.asStateFlow()

    /**
     * The ticked devices for [ContactCodeNodes.LIST]. Every available device is
     * ticked the first time the list arrives (the default is all of them); after
     * that it only loses devices the node stops offering.
     */
    private val _contactCodeTicked = MutableStateFlow<Set<String>>(emptySet())
    val contactCodeTicked: StateFlow<Set<String>> = _contactCodeTicked.asStateFlow()
    private var tickedSeeded = false

    /**
     * `self.node_not_announced`, when the picker's list went stale (a device was
     * made private after the card loaded). Kept beside a reloaded code, not in
     * place of it: the card says what happened and shows what is true now.
     */
    private val _contactCodeRefusal = MutableStateFlow<NodeRefusal?>(null)
    val contactCodeRefusal: StateFlow<NodeRefusal?> = _contactCodeRefusal.asStateFlow()

    private val _makeReachable = MutableStateFlow<MakeReachableState>(MakeReachableState.Idle)
    val makeReachable: StateFlow<MakeReachableState> = _makeReachable.asStateFlow()

    /** Bumped per contact-code request, so a slow answer to an old choice is dropped. */
    private var codeRequest = 0L

    /** Where the add, the code and the announce go — see [contactsNodeUrl]. */
    private fun nodeUrl(): String = contactsNodeUrl(apiClient.isNodeMode(), apiClient.baseUrl, nodeBaseUrl)

    init {
        load()
    }

    /** Initial load — idempotent; safe to call from a LaunchedEffect. */
    fun load() {
        refresh()
    }

    /** Pull a fresh contact list AND peer list from the node. */
    fun refresh() {
        refreshContacts()
        refreshPeers()
    }

    /** Pull a fresh contact list (browse mode). */
    fun refreshContacts() {
        val epoch = sessionEpoch
        viewModelScope.launch {
            if (epoch != sessionEpoch) return@launch
            _loading.value = true
            try {
                val resp = api.listContacts()
                // THE gate that matters: the await above is where a logout
                // interleaves. The clear emptied the flows once; publishing A's
                // response now would repopulate them for the signed-out screen
                // or the next user.
                if (epoch != sessionEpoch) return@launch
                _routeUnsupported.value = false
                _allContacts.value = sortedContacts(resp.contacts)
                applySearch()
            } catch (e: NodeRefusal) {
                if (epoch != sessionEpoch) return@launch
                if (e.statusCode == 404 && e.reasonId == null) {
                    // The route is not mounted — a version fact, not a failure.
                    _routeUnsupported.value = true
                    _error.value = null
                    PlatformLogger.i(tag, "[listContacts] node predates /v1/contacts (bare 404)")
                } else {
                    _error.value = e.detail ?: e.reasonId
                    PlatformLogger.w(
                        tag,
                        "[listContacts] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}",
                    )
                }
            } catch (e: Exception) {
                if (epoch != sessionEpoch) return@launch
                _error.value = e.message ?: e::class.simpleName
                PlatformLogger.e(tag, "[listContacts] ${e.message}", e)
            } finally {
                if (epoch == sessionEpoch) {
                    _loading.value = false
                    // Loaded means "the question was asked", success or not — an
                    // empty list after a failed call must not render as "you have
                    // no contacts".
                    _contactsLoaded.value = true
                }
            }
        }
    }

    /** Pull a fresh peer list from the node (picker mode). */
    fun refreshPeers() {
        val epoch = sessionEpoch
        viewModelScope.launch {
            runApi("listFederationPeers") {
                api.listPeers()
            }?.let { resp ->
                if (epoch != sessionEpoch) return@launch
                _allPeers.value = sortedPeers(resp.peers)
                applySearch()
            }
        }
    }

    /** Update the search query and refilter both lists locally. */
    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        applySearch()
    }

    /** Set (or clear) the picked identity. */
    fun selectPeer(peer: LocalPeerState?) {
        _selectedPeer.value = peer
    }

    /**
     * Add a contact by contact code (pasted or scanned) or fedID. On success the
     * list is refreshed and [justAdded] carries the contact so the screen can
     * offer to open the chat; [addOutcome] says whether they were already one.
     *
     * Refusals land in [addRefusalReasonId] + [addError] rather than the shared
     * [error] channel, so a failed add does not blank the list the user is
     * looking at.
     */
    fun addContact(keyId: String) {
        val trimmed = keyId.trim()
        if (trimmed.isEmpty()) return
        val epoch = sessionEpoch
        viewModelScope.launch {
            _addBusy.value = true
            _addRefusalReasonId.value = null
            _addError.value = null
            _addOutcome.value = null
            try {
                val added = api.addContact(nodeUrl(), trimmed)
                if (epoch != sessionEpoch) return@launch
                PlatformLogger.i(
                    tag,
                    "[addContact] ${trimmed.take(16)}… wrote_row=${added.freshlyEmitted} " +
                        "superseded=${added.supersededAttestationId?.take(16) ?: "none"} " +
                        "prefixes=${added.consentPrefixes.joinToString(",")}",
                )
                // CIRISServer#458: a contact whose grant does not cover `chat:`
                // is added, green, and unable to receive a single message. Record
                // it so the row can SAY so — silence here is how #458 hid.
                _chatIneligible.value = if (added.chatEligible) {
                    _chatIneligible.value - added.keyId
                } else {
                    PlatformLogger.w(
                        tag,
                        "[addContact] ${added.keyId.take(16)}… grant does NOT cover chat: " +
                            "(prefixes=${added.consentPrefixes.joinToString(",")})",
                    )
                    _chatIneligible.value + added.keyId
                }
                refreshContacts()
                _addOutcome.value = if (added.freshlyEmitted) AddContactOutcome.ADDED else AddContactOutcome.ALREADY
                _justAdded.value = Contact(
                    keyId = added.keyId,
                    chatCommunityId = added.chatCommunityId,
                    occurrenceKeyIds = added.occurrenceKeyIds,
                )
            } catch (e: NodeRefusal) {
                if (epoch != sessionEpoch) return@launch
                _addRefusalReasonId.value = e.reasonId
                _addError.value = e.detail
                PlatformLogger.w(
                    tag,
                    "[addContact] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}",
                )
            } catch (e: Exception) {
                if (epoch != sessionEpoch) return@launch
                _addError.value = e.message ?: e::class.simpleName
                PlatformLogger.e(tag, "[addContact] ${e.message}", e)
            } finally {
                if (epoch == sessionEpoch) _addBusy.value = false
            }
        }
    }

    /** Acknowledge the one-shot [justAdded] after the screen has acted on it. */
    fun consumeJustAdded() {
        _justAdded.value = null
        _addOutcome.value = null
    }

    // ── Share my contact code ────────────────────────────────────────────────

    /** Open the card and ask for the code, unless it is already open. */
    fun openContactCode() {
        if (_contactCode.value == ContactCodeState.Closed) loadContactCode()
    }

    fun closeContactCode() {
        codeRequest += 1
        _contactCode.value = ContactCodeState.Closed
        _contactCodeRefusal.value = null
        _makeReachable.value = MakeReachableState.Idle
    }

    /** Choose all / a list / no devices. Each choice is a new code, read back from the node. */
    fun setContactCodeNodes(mode: ContactCodeNodes) {
        _contactCodeNodes.value = mode
        _contactCodeRefusal.value = null
        loadContactCode()
    }

    /**
     * Tick or untick one device, never past [CONTACT_CODE_MAX_NODES]. Touching a
     * device IS choosing devices: from "all" or "none" the ticks start from what
     * that choice meant, the choice becomes the list, and the code is re-read.
     */
    fun toggleContactCodeNode(nodeKeyId: String) {
        val offered = (_contactCode.value as? ContactCodeState.Ready)
            ?.code?.availableNodes?.map { it.nodeKeyId }.orEmpty()
        val now = when (_contactCodeNodes.value) {
            ContactCodeNodes.ALL -> offered.take(CONTACT_CODE_MAX_NODES).toSet()
            ContactCodeNodes.NONE -> emptySet()
            ContactCodeNodes.LIST -> _contactCodeTicked.value
        }
        _contactCodeTicked.value = when {
            nodeKeyId in now -> now - nodeKeyId
            now.size >= CONTACT_CODE_MAX_NODES -> return
            else -> now + nodeKeyId
        }
        _contactCodeNodes.value = ContactCodeNodes.LIST
        _contactCodeRefusal.value = null
        loadContactCode()
    }

    /** Ask the node for the code the current picker choice describes. */
    fun loadContactCode() {
        val epoch = sessionEpoch
        val request = ++codeRequest
        val url = nodeUrl()
        val mode = _contactCodeNodes.value
        val query = contactCodeNodesQuery(mode, _contactCodeTicked.value)
        _contactCode.value = ContactCodeState.Loading
        viewModelScope.launch {
            try {
                val code = api.contactCode(url, query)
                if (isCurrent(epoch, request)) publishContactCode(code)
            } catch (e: NodeRefusal) {
                if (!isCurrent(epoch, request)) return@launch
                when {
                    e.statusCode == 404 && e.reasonId == null -> {
                        // The route is not mounted: a node older than 0.5.218.
                        // A version fact, never an empty card.
                        _contactCode.value = ContactCodeState.NodeTooOld
                        PlatformLogger.i(tag, "[contactCode] node predates /v1/self/contact-code (bare 404)")
                    }
                    e.reasonId == NODE_NOT_ANNOUNCED -> recoverFromStalePicker(e, url, mode, epoch, request)
                    else -> {
                        _contactCode.value = ContactCodeState.Failed(e.reasonId, e.detail)
                        PlatformLogger.w(
                            tag,
                            "[contactCode] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}",
                        )
                    }
                }
            } catch (e: Exception) {
                if (!isCurrent(epoch, request)) return@launch
                _contactCode.value = ContactCodeState.Failed(null, e.message ?: e::class.simpleName)
                PlatformLogger.e(tag, "[contactCode] ${e.message}", e)
            }
        }
    }

    private fun isCurrent(epoch: Long, request: Long) = epoch == sessionEpoch && request == codeRequest

    /**
     * `self.node_not_announced`: a ticked device is no longer announced. The
     * picker never offers a private device, so its list went stale. Re-read
     * what may be offered, drop what went private, show the code for what is
     * left, and keep the refusal on screen so the change is said.
     */
    private suspend fun recoverFromStalePicker(
        refusal: NodeRefusal,
        url: String,
        mode: ContactCodeNodes,
        epoch: Long,
        request: Long,
    ) {
        PlatformLogger.w(tag, "[contactCode] ${refusal.reasonId}: a ticked device is not announced; reloading the list")
        _contactCodeRefusal.value = refusal
        try {
            val fresh = api.contactCode(url, null)
            if (!isCurrent(epoch, request)) return
            val offered = fresh.availableNodes.map { it.nodeKeyId }.toSet()
            _contactCodeTicked.value = _contactCodeTicked.value intersect offered
            val code = if (mode == ContactCodeNodes.LIST) {
                api.contactCode(url, contactCodeNodesQuery(mode, _contactCodeTicked.value))
            } else {
                fresh
            }
            if (!isCurrent(epoch, request)) return
            publishContactCode(code)
        } catch (e: Exception) {
            if (!isCurrent(epoch, request)) return
            val r = e as? NodeRefusal
            _contactCode.value = ContactCodeState.Failed(r?.reasonId, r?.detail ?: e.message)
        }
    }

    private fun publishContactCode(code: ContactCodeResponse) {
        val offered = code.availableNodes.map { it.nodeKeyId }
        _contactCodeTicked.value = if (!tickedSeeded && offered.isNotEmpty()) {
            tickedSeeded = true
            offered.take(CONTACT_CODE_MAX_NODES).toSet()
        } else {
            _contactCodeTicked.value intersect offered.toSet()
        }
        // THE HONESTY RULE (CSD-092 `empty`). No announced device means every
        // code the node can mint names no device and resolves through a
        // directory that does not list this person: it reaches no one. The
        // node would mint it; the card does not hand it out.
        _contactCode.value = if (offered.isEmpty()) {
            ContactCodeState.Unreachable
        } else {
            ContactCodeState.Ready(code)
        }
        PlatformLogger.i(
            tag,
            "[contactCode] available=${offered.size} included=${code.includedNodes.size} shown=${offered.isNotEmpty()}",
        )
    }

    /**
     * Make THIS device reachable (`POST /v1/federation/announce`), the way out
     * of [ContactCodeState.Unreachable]. The binding widens at once; the
     * network announce follows at the node's next boot, and the card says both.
     */
    fun makeThisDeviceReachable() {
        if (_makeReachable.value == MakeReachableState.Busy) return
        val epoch = sessionEpoch
        val url = nodeUrl()
        _makeReachable.value = MakeReachableState.Busy
        viewModelScope.launch {
            try {
                val done = api.announceThisDevice(url)
                if (epoch != sessionEpoch) return@launch
                _makeReachable.value = MakeReachableState.Done(done.announceTakesEffect)
                loadContactCode()
            } catch (e: Exception) {
                if (epoch != sessionEpoch) return@launch
                _makeReachable.value = MakeReachableState.Failed(e.message ?: e::class.simpleName)
                PlatformLogger.e(tag, "[announce] ${e.message}", e)
            }
        }
    }

    /** Acknowledge an add refusal after the user sees it. */
    fun clearAddError() {
        _addRefusalReasonId.value = null
        _addError.value = null
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun applySearch() {
        val q = _searchQuery.value.trim().lowercase()
        _peers.value = if (q.isEmpty()) {
            _allPeers.value
        } else {
            _allPeers.value.filter { peer ->
                peer.keyId.lowercase().contains(q) ||
                    (peer.aliasOverride?.lowercase()?.contains(q) == true) ||
                    (peer.notes?.lowercase()?.contains(q) == true) ||
                    peer.trust.wire.contains(q) ||
                    peer.pubkeyEd25519Base64.lowercase().contains(q)
            }
        }
        _contacts.value = if (q.isEmpty()) {
            _allContacts.value
        } else {
            _allContacts.value.filter { c ->
                c.keyId.lowercase().contains(q) ||
                    (c.aliasOverride?.lowercase()?.contains(q) == true) ||
                    (c.notes?.lowercase()?.contains(q) == true) ||
                    c.trust.wire.contains(q) ||
                    (c.pubkeyEd25519Base64?.lowercase()?.contains(q) == true)
            }
        }
        PlatformLogger.d(
            tag,
            "applySearch q=${q.take(20)} → ${_peers.value.size}/${_allPeers.value.size} peers, " +
                "${_contacts.value.size}/${_allContacts.value.size} contacts",
        )
    }

    companion object {
        /** A device named for a contact code is not announced (CC 2.6.8 constraint 2). */
        const val NODE_NOT_ANNOUNCED = "self.node_not_announced"

        /**
         * Canonical peers first; within each group sort trusted > unknown >
         * untrusted > blocked, then most-recently-seen first.
         */
        private fun trustPriority(peer: LocalPeerState): Int = when (peer.trust) {
            ai.ciris.mobile.shared.models.federation.PeerTrustState.TRUSTED -> 0
            ai.ciris.mobile.shared.models.federation.PeerTrustState.UNKNOWN -> 1
            ai.ciris.mobile.shared.models.federation.PeerTrustState.UNTRUSTED -> 2
            ai.ciris.mobile.shared.models.federation.PeerTrustState.BLOCKED -> 3
        }

        fun sortedPeers(peers: List<LocalPeerState>): List<LocalPeerState> =
            peers.sortedWith(
                compareByDescending<LocalPeerState> { it.canonical }
                    .thenBy { trustPriority(it) }
                    .thenByDescending { it.lastSeen?.toEpochMilliseconds() ?: 0L },
            )

        /**
         * Open conversations first (a started chat is the thing the user came
         * for), then most-recently-seen, then by key_id so the order is stable
         * across refreshes when nothing else separates two rows.
         */
        fun sortedContacts(contacts: List<Contact>): List<Contact> =
            contacts.sortedWith(
                compareByDescending<Contact> { it.chatStarted }
                    .thenByDescending { it.lastSeen?.toEpochMilliseconds() ?: 0L }
                    .thenBy { it.keyId },
            )
    }

    /**
     * Clear every piece of session-owned state (logout).
     *
     * This ViewModel is CIRISApp-scoped and survives the login session — the
     * same shape as the approvals ViewModel's leak: after owner A logs out,
     * A's contact list stayed visible to the next signer-in until (unless) a
     * new fetch succeeded; for an observer, the owner-gated list stayed
     * exposed indefinitely. [routeUnsupported] is deliberately NOT cleared —
     * a lagging node lags regardless of who signs in; it is a node fact, not
     * session state.
     */
    fun clearSessionState() {
        sessionEpoch += 1
        _allContacts.value = emptyList()
        _contacts.value = emptyList()
        _contactsLoaded.value = false
        _chatIneligible.value = emptySet()
        _allPeers.value = emptyList()
        _peers.value = emptyList()
        _searchQuery.value = ""
        _selectedPeer.value = null
        _addBusy.value = false
        _addRefusalReasonId.value = null
        _addError.value = null
        _justAdded.value = null
        _addOutcome.value = null
        codeRequest += 1
        _contactCode.value = ContactCodeState.Closed
        _contactCodeNodes.value = ContactCodeNodes.ALL
        _contactCodeTicked.value = emptySet()
        tickedSeeded = false
        _contactCodeRefusal.value = null
        _makeReachable.value = MakeReachableState.Idle
    }
}
