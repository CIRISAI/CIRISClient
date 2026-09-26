package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientConsentWithdraw
import ai.ciris.mobile.shared.api.ConsentWithdrawApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.isRouteMissing
import ai.ciris.mobile.shared.models.federation.ContactListResponse
import ai.ciris.mobile.shared.models.federation.RemoveContactResponse
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
 * a withdrawn grant is already absent, so after [removeContact] the list is
 * RE-READ rather than edited: the node's fold is the answer, not our guess.
 *
 * @param nodeUrl where the NODE is right now. Removal is a node route signed
 *   with the person's pen; on a with-AI install `baseUrl` is the agent, which
 *   does not proxy it (CIRISAgent#1213). A provider, not a value, because the
 *   active node can be switched while this app-scoped model lives.
 */
class ContactsViewModel(
    apiClient: CIRISApiClient,
    private val nodeUrl: () -> String = { CIRISApiClient.LOCAL_NODE_URL },
    private val withdraw: ConsentWithdrawApi = ClientConsentWithdraw(apiClient),
    /** The list read. The real client's by default; a test's fake counts re-reads. */
    private val readContacts: suspend () -> ContactListResponse = { apiClient.listContacts() },
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
                val resp = readContacts()
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
                apiClient.listFederationPeers()
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
     * Add a contact by fedID. On success the list is refreshed and [justAdded]
     * carries the new contact so the screen can offer to open the chat.
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
            try {
                val added = apiClient.addContact(trimmed)
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

    // ── Remove a contact (CIRISServer#657) ───────────────────────────────────

    /** The key being removed right now, or null. */
    private val _removing = MutableStateFlow<String?>(null)
    val removing: StateFlow<String?> = _removing.asStateFlow()

    /** What the last removal actually did — see [ContactRemoval]. */
    private val _removal = MutableStateFlow<ContactRemoval?>(null)
    val removal: StateFlow<ContactRemoval?> = _removal.asStateFlow()

    /**
     * Withdraw the person's consent to [keyId] — `DELETE /v1/contacts/{key_id}`
     * on the NODE, signed by the person.
     *
     * The outcome is what the node SAID ([contactRemovalOf]), and the list is
     * re-read afterwards whatever it said, including the still-active arm: a
     * partial withdrawal changed the grants, and only the node's fold knows how.
     */
    fun removeContact(keyId: String) {
        val trimmed = keyId.trim()
        if (trimmed.isEmpty() || _removing.value != null) return
        val epoch = sessionEpoch
        val url = nodeUrl()
        viewModelScope.launch {
            _removing.value = trimmed
            _removal.value = null
            try {
                val resp = withdraw.removeContact(url, trimmed)
                if (epoch != sessionEpoch) return@launch
                _removal.value = contactRemovalOf(resp)
                PlatformLogger.i(
                    tag,
                    "[removeContact] ${trimmed.take(16)}… withdrawn=${resp.withdrawn.size} " +
                        "remaining=${resp.remainingGrants.size} contact=${resp.contact}",
                )
                refreshContacts()
            } catch (e: NodeRefusal) {
                if (epoch != sessionEpoch) return@launch
                _removal.value = if (e.isRouteMissing()) {
                    ContactRemoval.Unsupported(trimmed)
                } else {
                    ContactRemoval.Refused(trimmed, e.reasonId, e.detail)
                }
                PlatformLogger.w(tag, "[removeContact] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
            } catch (e: Exception) {
                if (epoch != sessionEpoch) return@launch
                _removal.value = ContactRemoval.Refused(trimmed, null, e.message ?: e::class.simpleName)
                PlatformLogger.e(tag, "[removeContact] ${e.message}", e)
            } finally {
                if (epoch == sessionEpoch) _removing.value = null
            }
        }
    }

    /** Acknowledge a removal outcome. Dismissing the still-active block changes no grant. */
    fun clearRemoval() {
        _removal.value = null
    }

    /** Acknowledge the one-shot [justAdded] after the screen has acted on it. */
    fun consumeJustAdded() {
        _justAdded.value = null
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
        _removing.value = null
        _removal.value = null
    }
}

/**
 * What a removal DID — never what was asked for.
 *
 * CC 1.5 has a flip side: a UI must not claim a withdrawal that did not happen.
 * Grants this node wrote before the person re-signed them are not the person's
 * to withdraw, stay live, and come back as `remaining_grants`; while any stand
 * the person is still a contact and the answer is [StillActive], never [Removed].
 */
sealed interface ContactRemoval {
    val keyId: String

    /** Every grant withdrawn, and the node no longer calls them a contact. */
    data class Removed(override val keyId: String, val withdrawn: Int) : ContactRemoval

    /** Some or none withdrawn; these grants are still active, because the node wrote them. */
    data class StillActive(
        override val keyId: String,
        val withdrawn: Int,
        val remainingGrants: List<String>,
    ) : ContactRemoval

    /** The node refused; nothing about the grants changed. */
    data class Refused(override val keyId: String, val reasonId: String?, val detail: String?) : ContactRemoval

    /** The node predates the route (bare 404): ciris-server older than 0.5.218. */
    data class Unsupported(override val keyId: String) : ContactRemoval
}

/** The one reading of a removal response: `complete`, or it is not removed. */
fun contactRemovalOf(resp: RemoveContactResponse): ContactRemoval =
    if (resp.complete) {
        ContactRemoval.Removed(resp.keyId, resp.withdrawn.size)
    } else {
        ContactRemoval.StillActive(resp.keyId, resp.withdrawn.size, resp.remainingGrants)
    }
