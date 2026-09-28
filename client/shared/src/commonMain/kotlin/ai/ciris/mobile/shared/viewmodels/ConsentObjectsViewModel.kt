package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.federation.FederationConsentScopes

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientConsentWithdraw
import ai.ciris.mobile.shared.api.ClientPeeringApi
import ai.ciris.mobile.shared.api.ConsentWithdrawApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.PeeringApi
import ai.ciris.mobile.shared.api.isRouteMissing
import ai.ciris.mobile.shared.models.NodeProfile
import ai.ciris.mobile.shared.models.federation.PeeringRequest
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Status of one direction of a bilateral consent:replication grant.
 */
enum class GrantDirectionState { IDLE, IN_PROGRESS, GRANTED, FAILED }

/**
 * UI state for the consent-objects card.
 *
 * The bilateral grant is **ratified iff both directions are [GrantDirectionState.GRANTED]**.
 */
data class ConsentObjectsState(
    val nodeA: NodeProfile? = null,
    val nodeB: NodeProfile? = null,
    /** Direction A→B: node A grants consent:replication to B (e.g. prefixes ["capacity:"]). */
    val aToB: GrantDirectionState = GrantDirectionState.IDLE,
    /** Direction B→A: node B grants consent:replication to A (e.g. prefixes ["health:"]). */
    val bToA: GrantDirectionState = GrantDirectionState.IDLE,
    val isRunning: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /**
     * The A→B grant's `attestation_id`, as node A named it when it granted —
     * the id `POST /v1/federation/peering/revoke` takes. Null when this screen
     * has not seen the grant: the node serves no read of its peering grants, so
     * a grant made elsewhere cannot be named here.
     */
    val aToBGrantId: String? = null,
    /** Does node A mount the revoke route? Decided at runtime, never by a build flag. */
    val revokeRoute: RevokeRoute = RevokeRoute.UNKNOWN,
    val isRevoking: Boolean = false,
    /**
     * Grants the node reported STILL ACTIVE after a withdraw: node-authored rows
     * (written before the person re-signed them), which the person cannot
     * withdraw. Non-empty means the withdraw did NOT happen for these.
     */
    val remainingGrants: List<String> = emptyList(),
    /** The `withdraws` row the node signed as the person, after a complete revoke. */
    val withdrawnBy: String? = null,
    /** The node's typed refusal of the last revoke, if any. */
    val revokeRefusalId: String? = null,
    val revokeRefusalDetail: String? = null,
) {
    val isRatified: Boolean
        get() = aToB == GrantDirectionState.GRANTED && bToA == GrantDirectionState.GRANTED

    /**
     * This state with the LAST revoke's outcome forgotten: the still-active
     * list, the `withdraws` id, the refusal and the message line. Taken at the
     * start of a revoke, at the start of a new bilateral set-up and when its
     * A→B grant is accepted — a fresh grant under "Withdrawn. The node recorded
     * your withdrawal" would be reporting the previous grant's fate as this
     * one's (Codex, PR #115).
     */
    fun withoutRevokeOutcome(): ConsentObjectsState = copy(
        message = null,
        remainingGrants = emptyList(),
        withdrawnBy = null,
        revokeRefusalId = null,
        revokeRefusalDetail = null,
    )
    val canRun: Boolean
        get() = nodeA != null && nodeB != null && !isRunning
    /**
     * Revoke is live when there is a named grant to withdraw and the node has
     * not shown it lacks the route. [RevokeRoute.UNKNOWN] stays live: the POST
     * itself then decides, and a bare 404 flips it to [RevokeRoute.MISSING].
     */
    val canRevoke: Boolean
        get() = nodeA != null && !aToBGrantId.isNullOrBlank() && revokeRoute != RevokeRoute.MISSING &&
            !isRevoking && !isRunning
}

/** Whether node A mounts `POST /v1/federation/peering/revoke` (ciris-server 0.5.218+). */
enum class RevokeRoute { UNKNOWN, MOUNTED, MISSING }

/**
 * Is there a session for the consent card to serve — the rule CIRISApp's
 * token effect applies before it tells [ConsentObjectsViewModel.sessionChanged].
 *
 * A null token is NOT a logout in Home Assistant add-on mode: there,
 * authentication is supplied by ingress headers and `currentAccessToken`
 * deliberately stays null for the whole session, exactly as the approval
 * watch and the fed-ID catch-up effects already treat it. Keying the reset
 * on the token alone reset the card right after its load and never started
 * it (Codex, PR #116).
 */
fun consentSessionAuthenticated(currentAccessToken: String?, isHAAddonMode: Boolean): Boolean =
    currentAccessToken != null || isHAAddonMode

/**
 * Drives the **consent-objects card** — the bilateral consent:replication setup
 * across the two connected fabric nodes.
 *
 * Flow (using the multi-node connections from the node switcher, change #1):
 *   1. GET A's self-key-record (from node A)
 *   2. GET B's self-key-record (from node B)
 *   3. POST /v1/federation/peering to A with peer=B, prefixes [aToBPrefixes]
 *   4. POST /v1/federation/peering to B with peer=A, prefixes [bToAPrefixes]
 *   5. report both directions; ratified iff both grants present.
 *
 * Each node is addressed by its own [NodeProfile.baseUrl] + session token
 * through [PeeringApi] and [ConsentWithdrawApi].
 *
 * SESSION-SCOPED, APP-LIVED. This model outlives the owner's session, so every
 * coroutine captures [sessionEpoch] at launch and publishes only while it still
 * matches: a revoke or set-up suspended across a logout must not resume and
 * write the previous owner's grant back over the reset (Codex, PR #116) — the
 * same gate [ContactsViewModel] keeps. And because a mutating request is an
 * ACT on the owner's behalf, not a read, the epoch is asked again immediately
 * before every POST, and [resetSession] cancels the jobs outright: after a
 * node switch node A's recorded token can still be valid, and a set-up that
 * resumed from its key-record read would otherwise write a grant for an owner
 * who had already left.
 */
class ConsentObjectsViewModel(
    private val apiClient: CIRISApiClient,
    private val withdraw: ConsentWithdrawApi = ClientConsentWithdraw(apiClient),
    /** The owned-nodes read, the key records and the peering POSTs. The real client's by default; a fake in tests. */
    private val peering: PeeringApi = ClientPeeringApi(apiClient),
    /** Where the card starts. Empty in the app; a test seeds a granted A→B to revoke. */
    initialState: ConsentObjectsState = ConsentObjectsState(),
) : ViewModel() {

    companion object {
        private const val TAG = "ConsentObjectsVM"
        val DEFAULT_A_TO_B_PREFIXES = FederationConsentScopes.TO_CANONICAL
        val DEFAULT_B_TO_A_PREFIXES = FederationConsentScopes.FROM_PEER
    }

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<ConsentObjectsState> = _state.asStateFlow()

    /** Advanced by [resetSession]; a coroutine from an older epoch publishes nothing. */
    private var sessionEpoch = 0L

    /** The load [sessionStarted] owns, so a session that begins twice loads once. Cancelled by [resetSession]. */
    private var loadJob: Job? = null

    /** The set-up and revoke in flight, if any — cancelled by [resetSession] before they can write. */
    private var setupJob: Job? = null
    private var revokeJob: Job? = null

    init {
        loadJob = viewModelScope.launch { loadNodes() }
    }

    /** Is this still the session the request belongs to? Asked immediately before every mutating request. */
    private fun live(epoch: Long): Boolean = epoch == sessionEpoch

    /** Apply [f] to the state — unless the session it belongs to has ended. */
    private fun publish(epoch: Long, f: (ConsentObjectsState) -> ConsentObjectsState): Boolean {
        if (!live(epoch)) return false
        _state.value = f(_state.value)
        return true
    }

    /**
     * Default the two peering endpoints LIVE from the local node's owned-nodes
     * projection (#125 — no client-side profile cache): node A = this local node,
     * node B = the first owned REMOTE node, if any. The card lets the user re-pick.
     *
     * NOTE: owned REMOTE nodes carry no reachable endpoint yet (just a key_id), so
     * a live B has an empty [NodeProfile.baseUrl] and the bilateral peering POSTs
     * are part of the OUT-OF-SCOPE mesh-addressing phase; the card surfaces the
     * pair but the cross-node grant needs the mesh transport to land first.
     *
     * Node A's SESSION is kept across a re-read. The client's token is the
     * ACTIVE node's, which after a switch is B's; rebuilding A with it would
     * make the next probe and revoke authenticate to A as B (Codex, PR #116).
     * Only a card holding no session for A takes the client's — the first
     * load, and the first load after [resetSession].
     */
    suspend fun loadNodes() {
        val epoch = sessionEpoch
        val owned = try {
            peering.ownedNodes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[loadNodes] owned-nodes unavailable (${e.message})")
            null
        }
        if (!live(epoch)) return
        val kept = _state.value.nodeA?.takeIf { it.baseUrl == CIRISApiClient.LOCAL_NODE_URL }?.sessionToken
        val a = NodeProfile(
            id = NodeProfile.idFor(CIRISApiClient.LOCAL_NODE_URL),
            name = "This device",
            baseUrl = CIRISApiClient.LOCAL_NODE_URL,
            sessionToken = kept ?: apiClient.getAccessToken(),
            pinnedKeyId = owned?.nodes?.firstOrNull { it.isSelf }?.keyId,
            isLocal = true,
            isOwned = true,
        )
        val b = owned?.nodes?.firstOrNull { !it.isSelf }?.let { on ->
            NodeProfile(id = on.keyId, name = on.keyId, baseUrl = "", pinnedKeyId = on.keyId, isOwned = true)
        }
        if (!publish(epoch) { it.copy(nodeA = a, nodeB = b) }) return
        probeRevokeRoute(a.baseUrl, a.sessionToken, epoch)
    }

    /**
     * Ask node A whether it mounts the revoke route. A "could not tell" never
     * overwrites a definite answer — in particular not a MISSING the POST
     * itself established.
     */
    private suspend fun probeRevokeRoute(nodeUrl: String, token: String?, epoch: Long) {
        val mounted = try {
            withdraw.revokeRouteMounted(nodeUrl, token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[probeRevokeRoute] ${e.message}")
            null
        }
        val route = when (mounted) {
            true -> RevokeRoute.MOUNTED
            false -> RevokeRoute.MISSING
            null -> return
        }
        if (!live(epoch)) return
        PlatformLogger.i(TAG, "[probeRevokeRoute] $nodeUrl revoke route: $route")
        publish(epoch) { it.copy(revokeRoute = route) }
    }

    /**
     * Withdraw node A's grant to B — `POST {A}/v1/federation/peering/revoke`,
     * signed by the PERSON (the node wields the owner's pen; it never withdraws
     * consent as itself).
     *
     * The row moves on what the node ANSWERS the POST with, not on the click:
     * a direction leaves GRANTED only when the node says it withdrew that grant
     * and signed the `withdraws`; a node-authored grant comes back in
     * [ConsentObjectsState.remainingGrants] and its direction stays GRANTED,
     * because it is. There is no read of the node's peering grants to re-read
     * the row from. The [loadNodes] afterwards refreshes the node pair and the
     * route answer, which are readable.
     */
    fun revokeAToB() {
        val s = _state.value
        val nodeA = s.nodeA
        val grantId = s.aToBGrantId
        if (!s.canRevoke || nodeA == null || grantId.isNullOrBlank()) {
            PlatformLogger.w(TAG, "[revokeAToB] nothing to revoke (grant=$grantId route=${s.revokeRoute} revoking=${s.isRevoking})")
            return
        }
        val epoch = sessionEpoch
        _state.value = s.withoutRevokeOutcome().copy(isRevoking = true, error = null)
        revokeJob = viewModelScope.launch {
            try {
                // The withdrawal is an act on the owner's behalf: not for an
                // owner who has left, whatever token node A still holds.
                if (!live(epoch)) return@launch
                // As node A's OWN session: the client's token is the active
                // node's, which after a switch is not A (Codex, PR #115).
                val resp = withdraw.revokeGrant(nodeA.baseUrl, grantId, nodeA.sessionToken)
                if (resp.complete && resp.attestationId == grantId) {
                    PlatformLogger.i(TAG, "[revokeAToB] withdrawn grant=${grantId.take(16)}… withdraws=${resp.withdraws?.take(16)}")
                    publish(epoch) {
                        it.copy(
                            revokeRoute = RevokeRoute.MOUNTED,
                            aToB = GrantDirectionState.IDLE,
                            aToBGrantId = null,
                            withdrawnBy = resp.withdraws,
                        )
                    }
                } else {
                    PlatformLogger.w(TAG, "[revokeAToB] NOT withdrawn — still active: ${resp.remainingGrants}")
                    publish(epoch) {
                        it.copy(
                            revokeRoute = RevokeRoute.MOUNTED,
                            remainingGrants = resp.remainingGrants.ifEmpty { listOf(grantId) },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeRefusal) {
                if (e.isRouteMissing()) {
                    PlatformLogger.i(TAG, "[revokeAToB] node predates the revoke route (bare 404)")
                    publish(epoch) { it.copy(revokeRoute = RevokeRoute.MISSING) }
                } else {
                    PlatformLogger.w(TAG, "[revokeAToB] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                    publish(epoch) { it.copy(revokeRefusalId = e.reasonId, revokeRefusalDetail = e.detail) }
                }
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "[revokeAToB] ${e.message}", e)
                publish(epoch) { it.copy(revokeRefusalDetail = e.message ?: e::class.simpleName) }
            } finally {
                publish(epoch) { it.copy(isRevoking = false) }
            }
            // THE RE-READ of what can be read: the node pair and the route
            // answer. Not for a session that ended while the POST was out.
            if (live(epoch)) loadNodes()
        }
    }

    fun setNodes(a: NodeProfile?, b: NodeProfile?) {
        _state.value = _state.value.copy(nodeA = a, nodeB = b, aToB = GrantDirectionState.IDLE, bToA = GrantDirectionState.IDLE)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
    }

    /**
     * CIRISApp's token effect: [authenticated] is [consentSessionAuthenticated]
     * of the token and the add-on mode. A session ends → [resetSession]; a
     * session exists → [sessionStarted].
     */
    fun sessionChanged(authenticated: Boolean) {
        if (authenticated) sessionStarted() else resetSession()
    }

    /**
     * The session ended — every way out: the three logout menus, a session
     * expiring under Interact, Billing's sign-in-again. CIRISApp calls this
     * from the one effect that sees the session go (Codex, PR #116).
     *
     * This ViewModel is CIRISApp-scoped and outlives the session, so without
     * this the next signer-in inherits the previous owner's grant id, direction
     * rows, revoke outcome, route answer, node pair (the remote node's identity,
     * and with it `canRun`) and — through [NodeProfile.sessionToken] on node A —
     * their bearer token. Everything goes; the epoch advances so a request
     * still out cannot write any of it back; the load, set-up and revoke in
     * flight are CANCELLED, so a set-up suspended at its key-record read cannot
     * go on to POST a grant with the departed owner's token, and a load
     * suspended across the reset is not mistaken by [sessionStarted] for the
     * next owner's; [sessionStarted] reloads the pair for the next owner.
     */
    fun resetSession() {
        sessionEpoch += 1
        loadJob?.cancel()
        setupJob?.cancel()
        revokeJob?.cancel()
        loadJob = null
        setupJob = null
        revokeJob = null
        _state.value = ConsentObjectsState()
    }

    /**
     * A session began (CIRISApp's token effect). Loads the node pair when the
     * card holds none — after [resetSession], or while the initial load has not
     * landed — and otherwise does nothing, so the effect firing on first
     * composition does not double the load [init] already owns.
     */
    fun sessionStarted() {
        if (_state.value.nodeA != null || loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { loadNodes() }
    }

    /**
     * Run the full bilateral peering. Each direction is reported independently
     * so a partial success (one grant emitted, the other failing) is visible.
     */
    fun runBilateralPeering(
        aToBPrefixes: List<String> = DEFAULT_A_TO_B_PREFIXES,
        bToAPrefixes: List<String> = DEFAULT_B_TO_A_PREFIXES,
    ) {
        val s = _state.value
        val nodeA = s.nodeA
        val nodeB = s.nodeB
        if (nodeA == null || nodeB == null || s.isRunning) {
            PlatformLogger.w(TAG, "[runBilateralPeering] need two nodes (a=$nodeA b=$nodeB) and not already running")
            return
        }
        val epoch = sessionEpoch

        // A new set-up starts from nothing the last revoke said: its outcome
        // was about a grant this run replaces.
        _state.value = s.withoutRevokeOutcome().copy(
            isRunning = true,
            error = null,
            aToB = GrantDirectionState.IN_PROGRESS,
            bToA = GrantDirectionState.IN_PROGRESS,
        )

        setupJob = viewModelScope.launch {
            try {
                // 1 + 2: fetch each node's self-key-record.
                PlatformLogger.i(TAG, "[runBilateralPeering] fetching self-key-records A=${nodeA.baseUrl} B=${nodeB.baseUrl}")
                val recordA = peering.selfKeyRecord(nodeA.baseUrl, nodeA.sessionToken)
                val recordB = peering.selfKeyRecord(nodeB.baseUrl, nodeB.sessionToken)

                // The owner may have left while the records were read. A grant
                // is written for a person, and node A's token being still
                // valid does not make it theirs to write.
                if (!live(epoch)) return@launch

                // 3: POST peering to A with peer = B. Keep the grant row id the
                // node names: it is the only handle the revoke route takes.
                val aGrant = try {
                    peering.peer(
                        nodeA.baseUrl,
                        nodeA.sessionToken,
                        PeeringRequest(
                            peerKeyId = recordB.keyId,
                            peerKeyRecord = recordB,
                            attestationPrefixes = aToBPrefixes,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlatformLogger.e(TAG, "[runBilateralPeering] A→B failed: ${e.message}", e)
                    null
                }
                val aGranted = aGrant?.isGranted == true
                // An accepted grant is a NEW grant: whatever the last revoke
                // reported was about its predecessor.
                if (!publish(epoch) {
                        it.withoutRevokeOutcome().copy(
                            aToB = if (aGranted) GrantDirectionState.GRANTED else GrantDirectionState.FAILED,
                            aToBGrantId = aGrant?.grantRowId,
                        )
                    }
                ) return@launch

                // 4: POST peering to B with peer = A — again only for a session
                // that is still on.
                if (!live(epoch)) return@launch
                val bGranted = try {
                    val resp = peering.peer(
                        nodeB.baseUrl,
                        nodeB.sessionToken,
                        PeeringRequest(
                            peerKeyId = recordA.keyId,
                            peerKeyRecord = recordA,
                            attestationPrefixes = bToAPrefixes,
                        ),
                    )
                    resp.isGranted
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlatformLogger.e(TAG, "[runBilateralPeering] B→A failed: ${e.message}", e)
                    false
                }
                val ratified = aGranted && bGranted
                publish(epoch) {
                    it.copy(
                        bToA = if (bGranted) GrantDirectionState.GRANTED else GrantDirectionState.FAILED,
                        isRunning = false,
                        message = if (ratified) "Bilateral consent:replication ratified"
                        else "Partial: A→B=${aGranted}, B→A=${bGranted}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "[runBilateralPeering] failed before grants: ${e.message}", e)
                publish(epoch) {
                    it.copy(
                        isRunning = false,
                        aToB = GrantDirectionState.FAILED,
                        bToA = GrantDirectionState.FAILED,
                        error = "Peering failed: ${e.message}",
                    )
                }
            }
        }
    }
}
