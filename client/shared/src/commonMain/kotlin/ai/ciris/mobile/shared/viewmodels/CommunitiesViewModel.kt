package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.models.federation.CommunityChangeOutcome
import ai.ciris.mobile.shared.models.federation.CommunityPendingChange
import ai.ciris.mobile.shared.models.federation.CommunityRoom
import ai.ciris.mobile.shared.models.federation.CommunityRoomMember
import ai.ciris.mobile.shared.models.federation.Contact
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * **One tier's communities** — the rooms the owner is active in at
 * `community` (Neighbours) or `affiliations` (Communities and Businesses),
 * read from the NODE's `/v1/communities` (CSD-102, CSD-103).
 *
 * One instance per tier, app-scoped, shared by that circle's three cards: the
 * community itself (Rules), who is in it (People) and the rooms you talk in
 * (Chats). Shared because a change started in one is finished in another: an
 * add in People that the room's rule holds for more signatures is collected
 * and assembled from Rules, and the envelope is held HERE — the node keeps
 * nothing between `…/envelope` and `…/assemble`.
 *
 * # Four reads, never two
 *
 * A read that failed, a node without the route, a room you are not in, and a
 * tier with no rooms are four different facts. The list read is
 * [CommunityListRead]; the single room is [CommunityDetailRead], where
 * `community.not_found` is its own state because the node answers it for a
 * room that does not exist AND for one you are not in — deliberately the same
 * (`communities.rs::not_found`), so the screen says both, not either.
 */
class CommunitiesViewModel(
    apiClient: CIRISApiClient,
    /** `community` or `affiliations` — this instance's tier, fixed for its life. */
    val tier: String,
    /** The node these routes live on. Node-owned: never the agent's base URL (CIRISAgent#1213). */
    private val nodeUrl: () -> String = { CIRISApiClient.LOCAL_NODE_URL },
) : BaseFederationViewModel(apiClient) {

    override val tag: String = "CommunitiesVM[$tier]"

    /** Advanced by [clearSessionState]; every publish after an await re-checks it (ContactsViewModel's rule). */
    private var sessionEpoch: Long = 0L

    private val _rooms = MutableStateFlow<CommunityListRead>(CommunityListRead.NotAsked)
    /** Every room the node listed for the caller, BEFORE the tier filter; screens filter with [roomsFor]. */
    val rooms: StateFlow<CommunityListRead> = _rooms.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** Best effort, for names and the add-member picker. A failure here hides nothing the node said. */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _detail = MutableStateFlow<CommunityDetailRead>(CommunityDetailRead.NotAsked)
    val detail: StateFlow<CommunityDetailRead> = _detail.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _refusal = MutableStateFlow<CommunityActRefusal?>(null)
    /** The last act the node refused, with its id — never folded into the list's error. */
    val refusal: StateFlow<CommunityActRefusal?> = _refusal.asStateFlow()

    private val _applied = MutableStateFlow<String?>(null)
    /** The op name of the last change the node applied (`add`, `remove`, `role`, `leave`, `dissolve`), one-shot. */
    val applied: StateFlow<String?> = _applied.asStateFlow()

    private val _pending = MutableStateFlow<Map<String, CommunityPendingChange>>(emptyMap())
    /** Community id → the change its rule is holding for signatures. Lives only here. */
    val pending: StateFlow<Map<String, CommunityPendingChange>> = _pending.asStateFlow()

    private val _cosignature = MutableStateFlow<String?>(null)
    /** The signature this node's owner just made over someone else's envelope, as JSON to send back. */
    val cosignature: StateFlow<String?> = _cosignature.asStateFlow()

    // ── Reads ────────────────────────────────────────────────────────────────

    fun refresh() {
        val epoch = sessionEpoch
        viewModelScope.launch {
            if (_rooms.value !is CommunityListRead.Loaded) _rooms.value = CommunityListRead.Loading
            val next = try {
                CommunityListRead.Loaded(apiClient.listCommunities(nodeUrl()).communities)
            } catch (e: Exception) {
                PlatformLogger.w(tag, "[listCommunities] ${e.message}")
                CommunityListRead.Failed(classify(e))
            }
            if (epoch != sessionEpoch) return@launch
            _rooms.value = next
        }
        viewModelScope.launch {
            val list = try { apiClient.listContacts(nodeUrl()).contacts } catch (e: Exception) {
                PlatformLogger.i(tag, "[listContacts] names unavailable: ${e.message}")
                null
            }
            if (epoch != sessionEpoch || list == null) return@launch
            _contacts.value = list
        }
    }

    /** Open one room: the single-room read carries the appointed moderators and the plane counts. */
    fun select(communityId: String) {
        _selected.value = communityId
        loadDetail(communityId)
    }

    fun clearSelection() {
        _selected.value = null
        _detail.value = CommunityDetailRead.NotAsked
    }

    private fun loadDetail(communityId: String) {
        val epoch = sessionEpoch
        viewModelScope.launch {
            _detail.value = CommunityDetailRead.Loading
            val next = try {
                CommunityDetailRead.Loaded(apiClient.getCommunity(communityId, nodeUrl()))
            } catch (e: Exception) {
                detailFailure(e)
            }
            if (epoch != sessionEpoch || _selected.value != communityId) return@launch
            _detail.value = next
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Found a room at this instance's tier. [protocol] is the room's rule; a
     * quorum's N is the founding roster (the caller plus [members]), which is
     * why the screen derives it rather than asking.
     */
    fun create(name: String, members: List<String>, protocol: String?) {
        act("create") {
            val room = apiClient.createCommunity(name.trim(), tier, members, protocol, nodeUrl())
            _applied.value = "create"
            refresh()
            select(room.communityId)
        }
    }

    fun addMember(communityId: String, keyId: String, role: String? = null) =
        governed("add", communityId) { apiClient.addCommunityMember(communityId, keyId.trim(), role, nodeUrl()) }

    fun removeMember(communityId: String, keyId: String) =
        governed("remove", communityId) { apiClient.removeCommunityMember(communityId, keyId, nodeUrl()) }

    fun changeRole(communityId: String, keyId: String, role: String) =
        governed("role", communityId) { apiClient.changeCommunityRole(communityId, keyId, role.trim(), nodeUrl()) }

    fun dissolve(communityId: String) =
        governed("dissolve", communityId) { apiClient.dissolveCommunity(communityId, nodeUrl()) }

    /** Leave: always your own act, never held for a quorum. */
    fun leave(communityId: String) {
        act("leave") {
            apiClient.leaveCommunity(communityId, nodeUrl())
            _applied.value = "leave"
            _pending.value = _pending.value - communityId
            clearSelection()
            refresh()
        }
    }

    /**
     * Sign a change another member built. [envelopeText] is what they sent: the
     * pending body or the bare envelope. The result is a signature to send BACK
     * to them — this node applies nothing.
     */
    fun cosign(communityId: String, envelopeText: String) {
        val envelope = parseEnvelope(envelopeText)
        if (envelope == null) {
            _refusal.value = CommunityActRefusal("cosign", MALFORMED_PASTE, null)
            return
        }
        act("cosign") {
            val sig = apiClient.cosignCommunityChange(communityId, envelope, nodeUrl())
            _cosignature.value = sigJson.encodeToString(JsonElement.serializer(), sig.signature)
        }
    }

    /** Add a co-signature someone sent back to the change pending on [communityId]. */
    fun addSignature(communityId: String, signatureText: String): Boolean {
        val p = _pending.value[communityId] ?: return false
        val sig = parseSignature(signatureText)
        if (sig == null) {
            _refusal.value = CommunityActRefusal("assemble", MALFORMED_PASTE, null)
            return false
        }
        if (sig in p.signatures) return true
        _pending.value = _pending.value + (communityId to p.copy(signatures = p.signatures + sig))
        return true
    }

    fun assemble(communityId: String) {
        val p = _pending.value[communityId] ?: return
        governed("assemble", communityId) {
            apiClient.assembleCommunityChange(communityId, p.changeEnvelope, p.signatures, nodeUrl())
        }
    }

    fun discardPending(communityId: String) {
        _pending.value = _pending.value - communityId
    }

    fun consumeApplied() { _applied.value = null }
    fun clearRefusal() { _refusal.value = null }
    fun clearCosignature() { _cosignature.value = null }

    private fun governed(op: String, communityId: String, call: suspend () -> CommunityChangeOutcome) {
        act(op) {
            when (val out = call()) {
                is CommunityChangeOutcome.Applied -> {
                    _pending.value = _pending.value - communityId
                    _applied.value = out.result.op.ifBlank { op }
                    if (out.result.op == "dissolve") clearSelection() else if (_selected.value == communityId) loadDetail(communityId)
                    refresh()
                }
                is CommunityChangeOutcome.Pending -> {
                    // Keep the signatures already collected: the node returns
                    // exactly what it was given, so this is a no-op for assemble
                    // and the caller's own share for a direct write.
                    _pending.value = _pending.value + (communityId to out.change)
                    PlatformLogger.i(tag, "[$op] held: ${out.change.valid} of ${out.change.required} under ${out.change.consensusProtocol}")
                }
            }
        }
    }

    private fun act(op: String, block: suspend () -> Unit) {
        val epoch = sessionEpoch
        viewModelScope.launch {
            _busy.value = true
            _refusal.value = null
            try {
                block()
            } catch (e: NodeRefusal) {
                if (epoch != sessionEpoch) return@launch
                _refusal.value = CommunityActRefusal(op, e.reasonId, e.detail, e.statusCode)
                PlatformLogger.w(tag, "[$op] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
            } catch (e: Exception) {
                if (epoch != sessionEpoch) return@launch
                _refusal.value = CommunityActRefusal(op, null, e.message ?: e::class.simpleName)
                PlatformLogger.e(tag, "[$op] ${e.message}", e)
            } finally {
                if (epoch == sessionEpoch) _busy.value = false
            }
        }
    }

    /** Logout: the rooms, the pending envelopes and the names are owner-gated content. */
    fun clearSessionState() {
        sessionEpoch += 1
        _rooms.value = CommunityListRead.NotAsked
        _contacts.value = emptyList()
        _selected.value = null
        _detail.value = CommunityDetailRead.NotAsked
        _busy.value = false
        _refusal.value = null
        _applied.value = null
        _pending.value = emptyMap()
        _cosignature.value = null
    }

    companion object {
        /** A paste that is not JSON of the expected shape. The client's own id, never the node's. */
        const val MALFORMED_PASTE = "mobile.community_paste_malformed"
        const val NOT_FOUND = "community.not_found"

        private val sigJson = Json { prettyPrint = false }
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Why a read produced no reading. A bare 404 (no `reason_id`) is the
         * route missing on this node — every refusal `communities.rs` authors
         * carries an id, so an id-less 404 is axum saying nothing is mounted.
         */
        fun classify(e: Throwable): CommunityReadFailure = when {
            e is RouteNotOnThisHost -> CommunityReadFailure.NotOnThisNode
            e is NodeRefusal && e.statusCode == 404 && e.reasonId == null -> CommunityReadFailure.NotOnThisNode
            e is NodeRefusal -> CommunityReadFailure.Refused(e.reasonId, e.detail)
            else -> CommunityReadFailure.Refused(null, e.message ?: e::class.simpleName)
        }

        fun detailFailure(e: Throwable): CommunityDetailRead =
            if (e is NodeRefusal && e.reasonId == NOT_FOUND) CommunityDetailRead.NotFound
            else CommunityDetailRead.Failed(classify(e))

        /**
         * The rooms a card of this tier shows. The community itself (Rules)
         * and its roster (People) are N-member rooms only: a pair room's roster
         * IS its identity (`community.pair_room_fixed`), so it has no governance
         * to show. Chats shows every room of the tier, pairs included — a pair
         * room is `tier: community` on the wire, so it folds to Neighbours.
         */
        fun roomsFor(rooms: List<CommunityRoom>, tier: String, includePairs: Boolean): List<CommunityRoom> =
            rooms.filter { it.tier == tier && (includePairs || !it.isPair) }
                .sortedWith(compareBy<CommunityRoom> { it.isPair }.thenBy { it.name.lowercase() }.thenBy { it.communityId })

        /** The contact a pair room is with: the contact whose derived pair id IS the room's id. */
        fun pairContact(room: CommunityRoom, contacts: List<Contact>): Contact? =
            if (!room.isPair) null else contacts.firstOrNull { it.chatCommunityId == room.communityId }

        /**
         * A member who joined after the room was founded: on the widening plane,
         * listed and sealed to, and refused the message read until
         * CIRISPersist#907. Null when either instant does not parse — unknown is
         * not "late".
         */
        fun addedAfterFounding(member: CommunityRoomMember, room: CommunityRoom): Boolean? {
            val joined = member.joinedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val founded = room.foundedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            return joined > founded
        }

        /**
         * The rule a new room declares. `quorum` needs its M; N is the founding
         * roster (the founder plus [memberCount]), and the node refuses a strict
         * minority (`2M > N`), so this does not pre-empt it.
         */
        fun protocolFor(choice: String, quorumM: Int?, memberCount: Int): String? = when (choice) {
            "founder_only" -> null
            "unanimous", "majority" -> choice
            "quorum" -> quorumM?.let { "quorum:$it/${memberCount + 1}" }
            else -> null
        }

        /** An envelope from a paste: the whole pending body, or the envelope itself. */
        fun parseEnvelope(text: String): JsonObject? {
            val obj = runCatching { lenient.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return null
            val inner = obj["change_envelope"] as? JsonObject
            return inner ?: obj.takeIf { it.isNotEmpty() }
        }

        /** A signature from a paste: the cosign response (`{signature: …}`) or the signature itself. */
        fun parseSignature(text: String): JsonElement? {
            val obj = runCatching { lenient.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return null
            return obj["signature"] ?: obj.takeIf { it.isNotEmpty() }
        }

        /** The pending envelope as the text a member sends to the others. */
        fun shareText(p: CommunityPendingChange): String =
            sigJson.encodeToString(JsonObject.serializer(), p.changeEnvelope)
    }
}

/** The list read. Four states that must never draw alike. */
sealed interface CommunityListRead {
    data object NotAsked : CommunityListRead
    data object Loading : CommunityListRead
    data class Loaded(val rooms: List<CommunityRoom>) : CommunityListRead
    data class Failed(val failure: CommunityReadFailure) : CommunityListRead
}

/** The single-room read. [NotFound] is "not a room you are in, or not a room" — the node says both at once. */
sealed interface CommunityDetailRead {
    data object NotAsked : CommunityDetailRead
    data object Loading : CommunityDetailRead
    data class Loaded(val room: CommunityRoom) : CommunityDetailRead
    data object NotFound : CommunityDetailRead
    data class Failed(val failure: CommunityReadFailure) : CommunityDetailRead
}

sealed interface CommunityReadFailure {
    /** This node does not serve `/v1/communities` (it predates 0.5.216). A fact about the node. */
    data object NotOnThisNode : CommunityReadFailure
    /** The node was asked and refused or failed; [reasonId] is its typed id when it sent one. */
    data class Refused(val reasonId: String?, val detail: String?) : CommunityReadFailure
}

/** A refused act, kept with the op that asked, so the screen can say which. */
data class CommunityActRefusal(
    val op: String,
    val reasonId: String?,
    val detail: String?,
    val statusCode: Int? = null,
)
