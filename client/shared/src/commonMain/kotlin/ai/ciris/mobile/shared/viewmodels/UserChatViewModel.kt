package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ChatApi
import ai.ciris.mobile.shared.api.ClientChatApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.chat.ChatCommunity
import ai.ciris.mobile.shared.models.chat.PairPhase
import ai.ciris.mobile.shared.models.chat.pairPhase
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the **user-to-user chat** — a two-member community whose messages ARE
 * CEG attestations (`src/contacts_chat.rs`).
 *
 * The transcript arrives OLDEST FIRST with each row's structural composers
 * already folded into its `status`, so this holds the server's order verbatim
 * and never re-sorts: `asserted_at` is a signed field, and a client that
 * re-ordered on a locally-parsed timestamp would disagree with the other member
 * about what was said when.
 *
 * Two ways in (CSD-091). A PAIR room is opened through `POST /v1/chat` with the
 * contact's key ([enter]); a room of more than two is entered by its id
 * ([enterRoom]) — `GET/POST /v1/chat/{id}/messages` serve N-member rooms
 * (CIRISServer#594) and `POST /v1/chat` is pair-only by construction.
 *
 * @param nodeUrl where the NODE is right now. Every chat route is the node's
 *   (`src/contacts_chat.rs`); on a with-AI install `baseUrl` is the agent
 *   (CIRISAgent#1213). A provider, because the active node can be switched
 *   while this app-scoped model lives. Read ONCE per room, on entering it:
 *   every operation of that room goes to the node it was opened on.
 * @param chat every chat call this view model makes; a fake in tests.
 */
class UserChatViewModel(
    apiClient: CIRISApiClient,
    private val nodeUrl: () -> String = { CIRISApiClient.LOCAL_NODE_URL },
    private val chat: ChatApi = ClientChatApi(apiClient),
) : BaseFederationViewModel(apiClient) {

    override val tag: String = "UserChatVM"

    private val _community = MutableStateFlow<ChatCommunity?>(null)
    val community: StateFlow<ChatCommunity?> = _community.asStateFlow()

    /**
     * How many people are in the room, when the room row said so and the
     * transcript cannot: `GET /v1/chat/{id}/messages` carries no roster, and an
     * N-member room is never opened through `POST /v1/chat`, which is the only
     * read that returns one. Null for a pair, whose count is the roster.
     */
    private val _memberCount = MutableStateFlow<Int?>(null)
    val memberCount: StateFlow<Int?> = _memberCount.asStateFlow()

    /**
     * Where a PAIR room's two-step join stands (ciris-server 0.5.218,
     * CIRISServer#706), decided by [pairPhase] from `POST /v1/chat`'s answer.
     * [PairPhase.OPEN] for a room of more than two and for every answer from a
     * 0.5.217 node, which never sends `state` — that node behaves as before.
     *
     * Anything but OPEN is NOT an empty room: the screen shows who the room is
     * waiting on, no composer, and no transcript read (the room cannot carry a
     * message until both people are seated, and a read of a room this person
     * is not seated in is a refusal, not a conversation).
     */
    private val _pairPhase = MutableStateFlow(PairPhase.OPEN)
    val pairPhase: StateFlow<PairPhase> = _pairPhase.asStateFlow()

    /**
     * Pair rooms whose invitation THIS session accepted (`POST /v1/chat`
     * answered `accepted`). At 0.5.218 the joiner's next `POST /v1/chat`
     * answers `awaiting_invitation` — the invitation it accepted is no longer
     * pending — and without this the person who just said yes would be told
     * the other side has not opened the room yet.
     */
    private val acceptedHere = mutableSetOf<String>()

    /** The contact of the open pair room, for [recheck]; null for a room of more than two. */
    private var roomContactKeyId: String? = null

    private val _messages = MutableStateFlow<List<CegChatMessage>>(emptyList())
    val messages: StateFlow<List<CegChatMessage>> = _messages.asStateFlow()

    /** True once a transcript load has completed — tells "empty" from "not asked yet". */
    private val _transcriptLoaded = MutableStateFlow(false)
    val transcriptLoaded: StateFlow<Boolean> = _transcriptLoaded.asStateFlow()

    /** The draft the composer is holding. Owned here so a refusal does not eat it. */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /**
     * The node's typed `reason_id` for the last refusal on this surface.
     *
     * `chat.not_a_contact`, `chat.not_a_member`, `chat.message_too_large` and
     * `chat.empty_message` all arrive as red text otherwise, and each names a
     * different next move.
     */
    private val _refusalReasonId = MutableStateFlow<String?>(null)
    val refusalReasonId: StateFlow<String?> = _refusalReasonId.asStateFlow()

    /** The node's English fallback for the last refusal, or null. */
    private val _refusalDetail = MutableStateFlow<String?>(null)
    val refusalDetail: StateFlow<String?> = _refusalDetail.asStateFlow()

    /**
     * **THE PUBLICATION GUARD. Nothing may write [_community] or [_messages]
     * without checking it.**
     *
     * The synchronous reset in [enter] closes the case where a room is entered
     * AFTER a previous one settled. It does nothing for the case where two
     * entries are IN FLIGHT AT ONCE: open A, open B before A's `startChat`
     * round-trip returns, and A's late response publishes over B — after B is
     * already the visible conversation. `send()` addresses `_community.value`,
     * so the draft the user typed under B's name is then signed, promoted to the
     * community tier and replicated into the room with A. A message is an
     * attestation: there is no unsend. The transcript load has the same shape and
     * would paint A's history under B's header.
     *
     * Cancelling [entryJob] is the tidy half but CANNOT be the correctness half —
     * cancellation is cooperative, so a job already past its last suspension
     * point still runs to its assignment. So every publication is gated on this
     * counter instead: each [enter] / [refresh] takes a ticket, and a result
     * whose ticket is no longer current is DISCARDED rather than published.
     *
     * Confined to the main dispatcher by `viewModelScope`, so a plain `Long` is
     * sufficient — every read and write happens on one thread.
     *
     * The chat calls go through [ChatApi], which a test can fake
     * (`UserChatViewModelTest`); the race itself is still not driven there —
     * the guard is small and the invariant is written HERE, at the thing it
     * protects, rather than only in a test file.
     */
    private var entryEpoch: Long = 0L

    /** The in-flight entry, cancelled when a newer one supersedes it. */
    private var entryJob: Job? = null

    /**
     * The node the open room lives on, captured ONCE on entering it
     * (Codex, PR #126). The provider answers "where is the node NOW", and the
     * active node can be switched while `open()` is suspended; reading it per
     * operation opened the room on A and then read and SENT on B. Every
     * operation of a room — open, transcript, refresh, send — uses this.
     */
    private var roomNodeUrl: String? = null

    /** Take a ticket; every later publication must still hold the current one. */
    private fun nextEpoch(): Long {
        entryEpoch += 1
        return entryEpoch
    }

    /**
     * Enter the room with [contactKeyId], then load the transcript.
     *
     * # Why this ALWAYS calls `POST /v1/chat`
     *
     * The earlier shape skipped the call when the contact card said the room
     * already existed, and loaded the transcript straight from the card's
     * derived id. That left [_community] holding whatever room was viewed
     * BEFORE — this ViewModel is a process singleton, created once in
     * `CIRISApp` — and [send] / [refresh] both read the community id from
     * there. On a fresh instance they silently no-opped; on a second
     * conversation they addressed the FIRST one, which means a draft written
     * to Bob could be committed, signed and replicated into the room with
     * Alice. A message is an attestation: there is no unsend.
     *
     * `start_chat` returns BEFORE any write when the community exists
     * (`lookup_community` → `freshly_created: false`), so for an open room this
     * is a read, and it is the only read that answers with the authoritative
     * roster the header needs. The contact card's `chat_started` is therefore
     * advisory, not load bearing — one fewer client-side guess that can be
     * wrong.
     *
     * The reset below is SYNCHRONOUS and happens before the first suspension
     * point: between navigating and `startChat` returning there is a window in
     * which the composer is live, and a stale [_community] in that window is
     * the same wrong-room send by another route.
     */
    fun enter(communityId: String, contactKeyId: String) {
        // Both of these are SYNCHRONOUS and happen before any suspension point.
        val epoch = nextEpoch()
        entryJob?.cancel()
        resetIfAnotherRoom(communityId)
        val node = nodeUrl()
        roomNodeUrl = node
        roomContactKeyId = contactKeyId
        // A pair's count is the roster POST /v1/chat returns, never a room's.
        _memberCount.value = null
        entryJob = viewModelScope.launch {
            clearRefusal()
            openPair(node, contactKeyId, epoch, "enter")
        }
    }

    /**
     * Ask the node again where a pair room's join stands — `POST /v1/chat`
     * again, which is idempotent (the creator's re-open re-derives the same
     * record; a held acceptance is widened by the opener's own pen; the
     * joiner's call accepts the creator's invitation once it has arrived,
     * which is the request the joiner already made). When the room is OPEN
     * the transcript is read. A no-op for an open room or a room of more than
     * two: there is nothing to re-check.
     */
    fun recheck() {
        if (_pairPhase.value == PairPhase.OPEN) return
        val contact = roomContactKeyId ?: return
        val node = roomNodeUrl ?: return
        val epoch = entryEpoch
        viewModelScope.launch { openPair(node, contact, epoch, "recheck") }
    }

    /** `POST /v1/chat`, published only under [epoch]; reads the transcript once the room is OPEN. */
    private suspend fun openPair(node: String, contactKeyId: String, epoch: Long, operation: String) {
        val opened = callTyped("startChat") { chat.open(node, contactKeyId) } ?: return
        if (epoch != entryEpoch) {
            // A newer room was entered while this one was opening. Publishing
            // now is the wrong-room send.
            PlatformLogger.i(
                tag,
                "[$operation] discarding stale open for ${opened.communityId.take(24)}… " +
                    "(epoch $epoch != $entryEpoch)",
            )
            return
        }
        if (opened.state == ChatCommunity.STATE_ACCEPTED) acceptedHere += opened.communityId
        val phase = opened.pairPhase(acceptedHere = opened.communityId in acceptedHere)
        _community.value = opened
        _pairPhase.value = phase
        PlatformLogger.i(
            tag,
            "[$operation] community=${opened.communityId.take(24)}… fresh=${opened.freshlyCreated} " +
                "state=${opened.state ?: "<none>"} phase=$phase",
        )
        if (phase == PairPhase.OPEN) {
            loadMessages(node, opened.communityId, epoch)
        } else {
            // Not an empty room and not a refused read: nothing to read yet.
            _messages.value = emptyList()
            _transcriptLoaded.value = false
        }
    }

    /**
     * Enter a room of more than two BY ITS ID — no `POST /v1/chat`, which is
     * pair-only (`start_chat`: "the two-member community for (owner, contact)")
     * and would refuse or, worse, open a pair with whoever's key was passed.
     * `GET /v1/chat/{id}/messages` reads an N-member room (CIRISServer#594):
     * persist's admission is the membership gate, and a non-member is refused
     * `chat.not_a_member`, which renders as a refusal and not as an empty room.
     *
     * [name] and [memberCount] come from the room row (`GET /v1/communities`):
     * the transcript carries no roster, so the header is told what the list
     * knew. The same epoch guard as [enter] applies — a late transcript for a
     * room the person has since left is discarded, never painted.
     */
    fun enterRoom(communityId: String, name: String, memberCount: Int?) {
        val epoch = nextEpoch()
        entryJob?.cancel()
        resetIfAnotherRoom(communityId)
        val node = nodeUrl()
        roomNodeUrl = node
        roomContactKeyId = null
        // A room of more than two is founded and joined through CSD-102's
        // routes; by the time it is listed, it is a room.
        _pairPhase.value = PairPhase.OPEN
        _memberCount.value = memberCount
        _community.value = ChatCommunity(communityId = communityId, communityName = name)
        PlatformLogger.i(tag, "[enterRoom] community=${communityId.take(24)}… members=${memberCount ?: "?"}")
        entryJob = viewModelScope.launch {
            clearRefusal()
            loadMessages(node, communityId, epoch)
        }
    }

    /** SYNCHRONOUS, before any suspension point — see [enter]. */
    private fun resetIfAnotherRoom(communityId: String) {
        if (_community.value?.communityId != communityId) {
            _community.value = null
            // Until the node says otherwise, a new room is not known to be
            // waiting on anyone — and must not inherit the last room's wait.
            _pairPhase.value = PairPhase.OPEN
            _messages.value = emptyList()
            _transcriptLoaded.value = false
            // The draft belongs to the room it was written in, not to the screen.
            _draft.value = ""
        }
    }

    /**
     * Re-read the transcript for the community currently open — or, for a pair
     * room still joining, ask the node where the join stands ([recheck]).
     */
    fun refresh() {
        if (_pairPhase.value != PairPhase.OPEN) {
            recheck()
            return
        }
        val id = _community.value?.communityId ?: return
        val node = roomNodeUrl ?: return
        val epoch = entryEpoch
        viewModelScope.launch { loadMessages(node, id, epoch) }
    }

    fun setDraft(text: String) {
        _draft.value = text
    }

    /**
     * Send the draft.
     *
     * A `null` `message` in the response is a SUCCESS — the row landed and only
     * the read-back projection did not resolve — so the draft is cleared and the
     * list refreshed either way. Reporting a failed send there would be a lie
     * about a committed write.
     */
    fun send() {
        // A pair room that is not open has nobody to carry a message to.
        if (_pairPhase.value != PairPhase.OPEN) return
        val id = _community.value?.communityId ?: return
        // The room's node, captured with the room — never the provider's answer now.
        val node = roomNodeUrl ?: return
        val text = _draft.value
        if (text.isBlank()) return
        // Captured at SEND TIME, not at completion. Reading `entryEpoch` after
        // the round-trip compares against whatever room is CURRENT — so a send
        // finishing after the user entered room B passed the guard and painted
        // A's transcript under B's header, while A's completion also cleared
        // B's freshly-typed draft. The message itself still lands in A (the id
        // was captured) — only the local after-effects must be discarded.
        val epochAtSend = entryEpoch
        viewModelScope.launch {
            _sending.value = true
            clearRefusal()
            try {
                val result = chat.send(node, id, text)
                if (epochAtSend == entryEpoch) {
                    _draft.value = ""
                }
                if (result.message != null) {
                    PlatformLogger.i(tag, "[send] ${result.attestationId.take(24)}… projected")
                } else {
                    PlatformLogger.i(
                        tag,
                        "[send] ${result.attestationId.take(24)}… landed; read-back deferred — refreshing",
                    )
                }
                loadMessages(node, id, epochAtSend)
            } catch (e: NodeRefusal) {
                if (epochAtSend == entryEpoch) recordRefusal("send", e)
            } catch (e: Exception) {
                if (epochAtSend == entryEpoch) {
                    _refusalDetail.value = e.message ?: e::class.simpleName
                }
                PlatformLogger.e(tag, "[send] ${e.message}", e)
            } finally {
                // Unconditional: a stale completion clearing the spinner is
                // corrective (the new room has no send in flight), and a live
                // one must always clear it.
                _sending.value = false
            }
        }
    }

    /** Acknowledge a refusal after the user sees it. */
    fun clearRefusal() {
        _refusalReasonId.value = null
        _refusalDetail.value = null
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    /**
     * Read the transcript for [communityId] and publish it ONLY if [epoch] is
     * still the current entry (see [entryEpoch]) — otherwise A's history paints
     * under B's header.
     */
    private suspend fun loadMessages(node: String, communityId: String, epoch: Long) {
        _loading.value = true
        try {
            val transcript = chat.transcript(node, communityId)
            if (epoch != entryEpoch) {
                PlatformLogger.i(
                    tag,
                    "[listChatMessages] discarding stale transcript for " +
                        "${communityId.take(24)}… (epoch $epoch != $entryEpoch)",
                )
                return
            }
            // Server order, verbatim: oldest first, by the SIGNED asserted_at.
            _messages.value = transcript.messages
        } catch (e: NodeRefusal) {
            if (epoch == entryEpoch) recordRefusal("listChatMessages", e)
        } catch (e: Exception) {
            if (epoch == entryEpoch) {
                _refusalDetail.value = e.message ?: e::class.simpleName
                PlatformLogger.e(tag, "[listChatMessages] ${e.message}", e)
            }
        } finally {
            // A superseded load must not clear the NEW room's spinner or claim
            // its transcript arrived.
            if (epoch == entryEpoch) {
                _loading.value = false
                _transcriptLoaded.value = true
            }
        }
    }

    private suspend fun <T> callTyped(operation: String, block: suspend () -> T): T? = try {
        _loading.value = true
        block()
    } catch (e: NodeRefusal) {
        recordRefusal(operation, e)
        null
    } catch (e: Exception) {
        _refusalDetail.value = e.message ?: e::class.simpleName
        PlatformLogger.e(tag, "[$operation] ${e.message}", e)
        null
    } finally {
        _loading.value = false
    }

    private fun recordRefusal(operation: String, e: NodeRefusal) {
        _refusalReasonId.value = e.reasonId
        _refusalDetail.value = e.detail
        PlatformLogger.w(
            tag,
            "[$operation] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}",
        )
    }
}
