package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.federation.FederationConsentScopes

import ai.ciris.mobile.shared.api.CIRISApiClient
import ai.ciris.mobile.shared.api.ClientConsentWithdraw
import ai.ciris.mobile.shared.api.ConsentWithdrawApi
import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.api.isRouteMissing
import ai.ciris.mobile.shared.models.federation.OwnedNodesDto
import ai.ciris.mobile.shared.models.NodeProfile
import ai.ciris.mobile.shared.models.federation.PeeringRequest
import ai.ciris.mobile.shared.platform.PlatformLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Each node is addressed by its own [NodeProfile.baseUrl] + session token via
 * the nodeUrl/token overloads on [CIRISApiClient].
 */
class ConsentObjectsViewModel(
    private val apiClient: CIRISApiClient,
    private val withdraw: ConsentWithdrawApi = ClientConsentWithdraw(apiClient),
    /** The owned-nodes read. The real client's by default; a test's fake counts re-reads. */
    private val readOwnedNodes: suspend () -> OwnedNodesDto = { apiClient.getOwnedNodes() },
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

    init {
        viewModelScope.launch { loadNodes() }
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
     */
    suspend fun loadNodes() {
        val owned = try {
            readOwnedNodes()
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[loadNodes] owned-nodes unavailable (${e.message})")
            null
        }
        val a = NodeProfile(
            id = NodeProfile.idFor(CIRISApiClient.LOCAL_NODE_URL),
            name = "This device",
            baseUrl = CIRISApiClient.LOCAL_NODE_URL,
            sessionToken = apiClient.getAccessToken(),
            pinnedKeyId = owned?.nodes?.firstOrNull { it.isSelf }?.keyId,
            isLocal = true,
            isOwned = true,
        )
        val b = owned?.nodes?.firstOrNull { !it.isSelf }?.let { on ->
            NodeProfile(id = on.keyId, name = on.keyId, baseUrl = "", pinnedKeyId = on.keyId, isOwned = true)
        }
        _state.value = _state.value.copy(nodeA = a, nodeB = b)
        probeRevokeRoute(a.baseUrl)
    }

    /**
     * Ask node A whether it mounts the revoke route. A "could not tell" never
     * overwrites a definite answer — in particular not a MISSING the POST
     * itself established.
     */
    private suspend fun probeRevokeRoute(nodeUrl: String) {
        val mounted = try {
            withdraw.revokeRouteMounted(nodeUrl)
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[probeRevokeRoute] ${e.message}")
            null
        }
        val route = when (mounted) {
            true -> RevokeRoute.MOUNTED
            false -> RevokeRoute.MISSING
            null -> return
        }
        PlatformLogger.i(TAG, "[probeRevokeRoute] $nodeUrl revoke route: $route")
        _state.value = _state.value.copy(revokeRoute = route)
    }

    /**
     * Withdraw node A's grant to B — `POST {A}/v1/federation/peering/revoke`,
     * signed by the PERSON (the node wields the owner's pen; it never withdraws
     * consent as itself).
     *
     * On any answer the screen RE-READS ([loadNodes] and the route probe) rather
     * than flipping a row on the strength of the click. A direction leaves
     * GRANTED only when the node says it withdrew that grant and signed the
     * `withdraws`; a node-authored grant comes back in [ConsentObjectsState.remainingGrants]
     * and its direction stays GRANTED, because it is.
     */
    fun revokeAToB() {
        val s = _state.value
        val nodeA = s.nodeA
        val grantId = s.aToBGrantId
        if (!s.canRevoke || nodeA == null || grantId.isNullOrBlank()) {
            PlatformLogger.w(TAG, "[revokeAToB] nothing to revoke (grant=$grantId route=${s.revokeRoute} revoking=${s.isRevoking})")
            return
        }
        _state.value = s.copy(
            isRevoking = true,
            error = null,
            message = null,
            remainingGrants = emptyList(),
            withdrawnBy = null,
            revokeRefusalId = null,
            revokeRefusalDetail = null,
        )
        viewModelScope.launch {
            try {
                val resp = withdraw.revokeGrant(nodeA.baseUrl, grantId)
                _state.value = if (resp.complete && resp.attestationId == grantId) {
                    PlatformLogger.i(TAG, "[revokeAToB] withdrawn grant=${grantId.take(16)}… withdraws=${resp.withdraws?.take(16)}")
                    _state.value.copy(
                        revokeRoute = RevokeRoute.MOUNTED,
                        aToB = GrantDirectionState.IDLE,
                        aToBGrantId = null,
                        withdrawnBy = resp.withdraws,
                    )
                } else {
                    PlatformLogger.w(TAG, "[revokeAToB] NOT withdrawn — still active: ${resp.remainingGrants}")
                    _state.value.copy(
                        revokeRoute = RevokeRoute.MOUNTED,
                        remainingGrants = resp.remainingGrants.ifEmpty { listOf(grantId) },
                    )
                }
            } catch (e: NodeRefusal) {
                _state.value = if (e.isRouteMissing()) {
                    PlatformLogger.i(TAG, "[revokeAToB] node predates the revoke route (bare 404)")
                    _state.value.copy(revokeRoute = RevokeRoute.MISSING)
                } else {
                    PlatformLogger.w(TAG, "[revokeAToB] refused reason_id=${e.reasonId ?: "<none>"} status=${e.statusCode}")
                    _state.value.copy(revokeRefusalId = e.reasonId, revokeRefusalDetail = e.detail)
                }
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "[revokeAToB] ${e.message}", e)
                _state.value = _state.value.copy(revokeRefusalDetail = e.message ?: e::class.simpleName)
            } finally {
                _state.value = _state.value.copy(isRevoking = false)
            }
            // THE RE-READ. Whatever the node said, what the screen shows next
            // comes from asking again, not from the click.
            loadNodes()
        }
    }

    fun setNodes(a: NodeProfile?, b: NodeProfile?) {
        _state.value = _state.value.copy(nodeA = a, nodeB = b, aToB = GrantDirectionState.IDLE, bToA = GrantDirectionState.IDLE)
    }

    fun clearMessages() {
        _state.value = _state.value.copy(error = null, message = null)
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

        _state.value = s.copy(
            isRunning = true,
            error = null,
            message = null,
            aToB = GrantDirectionState.IN_PROGRESS,
            bToA = GrantDirectionState.IN_PROGRESS,
        )

        viewModelScope.launch {
            try {
                // 1 + 2: fetch each node's self-key-record.
                PlatformLogger.i(TAG, "[runBilateralPeering] fetching self-key-records A=${nodeA.baseUrl} B=${nodeB.baseUrl}")
                val recordA = apiClient.getSelfKeyRecord(nodeA.baseUrl, nodeA.sessionToken)
                val recordB = apiClient.getSelfKeyRecord(nodeB.baseUrl, nodeB.sessionToken)

                // 3: POST peering to A with peer = B. Keep the grant row id the
                // node names: it is the only handle the revoke route takes.
                val aGrant = try {
                    apiClient.postPeering(
                        request = PeeringRequest(
                            peerKeyId = recordB.keyId,
                            peerKeyRecord = recordB,
                            attestationPrefixes = aToBPrefixes,
                        ),
                        nodeUrl = nodeA.baseUrl,
                        token = nodeA.sessionToken,
                    )
                } catch (e: Exception) {
                    PlatformLogger.e(TAG, "[runBilateralPeering] A→B failed: ${e.message}", e)
                    null
                }
                val aGranted = aGrant?.isGranted == true
                _state.value = _state.value.copy(
                    aToB = if (aGranted) GrantDirectionState.GRANTED else GrantDirectionState.FAILED,
                    aToBGrantId = aGrant?.grantRowId,
                )

                // 4: POST peering to B with peer = A.
                val bGranted = try {
                    val resp = apiClient.postPeering(
                        request = PeeringRequest(
                            peerKeyId = recordA.keyId,
                            peerKeyRecord = recordA,
                            attestationPrefixes = bToAPrefixes,
                        ),
                        nodeUrl = nodeB.baseUrl,
                        token = nodeB.sessionToken,
                    )
                    resp.isGranted
                } catch (e: Exception) {
                    PlatformLogger.e(TAG, "[runBilateralPeering] B→A failed: ${e.message}", e)
                    false
                }
                _state.value = _state.value.copy(
                    bToA = if (bGranted) GrantDirectionState.GRANTED else GrantDirectionState.FAILED,
                )

                val ratified = aGranted && bGranted
                _state.value = _state.value.copy(
                    isRunning = false,
                    message = if (ratified) "Bilateral consent:replication ratified"
                    else "Partial: A→B=${aGranted}, B→A=${bGranted}",
                )
            } catch (e: Exception) {
                PlatformLogger.e(TAG, "[runBilateralPeering] failed before grants: ${e.message}", e)
                _state.value = _state.value.copy(
                    isRunning = false,
                    aToB = GrantDirectionState.FAILED,
                    bToA = GrantDirectionState.FAILED,
                    error = "Peering failed: ${e.message}",
                )
            }
        }
    }
}
