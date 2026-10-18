package ai.ciris.mobile.shared.auth

import ai.ciris.mobile.shared.models.NodeProfile
import ai.ciris.mobile.shared.platform.PlatformLogger

/**
 * A node whose home a Data Management wipe just destroyed (CIRISClient#87).
 *
 * @param baseUrl the endpoint the wipe was sent to.
 * @param keyId the node's federation `key_id`, read from its public NodeCode
 *   BEFORE the wipe (afterwards the key is gone). Null when the read failed: the
 *   identity is then unknown, and nothing is matched by it.
 * @param identityDestroyed true for `wipe-signing-key` (the key_id dies with the
 *   home, so any profile pinned to it names a node that no longer exists); false
 *   for `reset-account` (the key survives, the auth database does not — the node
 *   is the same node, but every session it issued is dead).
 */
data class WipedNode(
    val baseUrl: String,
    val keyId: String?,
    val identityDestroyed: Boolean,
)

/**
 * The switcher's profiles after [wiped] lost its home. Pure, so the rule is
 * pinned in a test.
 *
 * Matching is by IDENTITY, not by URL alone: a URL is where a node answers, not
 * which node it is, and two homes can serve the same loopback port in turn.
 * - pinned to the wiped key_id: dropped when the identity was destroyed, else
 *   kept with its session token cleared;
 * - unpinned, at the wiped endpoint: kept (its identity is unproven) but its
 *   session token is cleared — the auth database that minted it is gone;
 * - pinned to any OTHER key_id, or at any other endpoint: untouched.
 */
fun profilesAfterWipe(profiles: List<NodeProfile>, wiped: WipedNode): List<NodeProfile> {
    val wipedEndpoint = NodeProfile.idFor(wiped.baseUrl)
    return profiles.mapNotNull { p ->
        val sameNode = wiped.keyId != null && p.pinnedKeyId == wiped.keyId
        val sameEndpointUnpinned = p.pinnedKeyId == null &&
            p.baseUrl.isNotBlank() && NodeProfile.idFor(p.baseUrl) == wipedEndpoint
        when {
            sameNode && wiped.identityDestroyed -> null
            sameNode || sameEndpointUnpinned -> p.copy(sessionToken = null)
            else -> p
        }
    }
}

/**
 * What the client does after a SUCCESSFUL wipe, on every platform — the one path
 * desktop, Android and iOS share. Each step runs even when an earlier one
 * failed: a token left behind because the profile step threw is the bug this
 * exists to close. Returns the steps that failed (empty = all done).
 *
 * The steps are injected rather than bound to [ai.ciris.mobile.shared.platform.SecureStorage]
 * so the order and independence are testable without touching a real keychain.
 *
 * Local only: nothing here reaches a file the user chose (a USB node-list or
 * fed-ID backup is theirs, not the wiped node's) or any other device.
 */
suspend fun forgetWipedNode(
    wiped: WipedNode,
    deleteStoredToken: suspend () -> Result<Unit>,
    clearLiveToken: () -> Unit,
    forgetProfiles: (WipedNode) -> Unit,
): List<String> {
    val failed = mutableListOf<String>()
    fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            PlatformLogger.w(TAG, "[forgetWipedNode] $name failed: ${e.message}")
            failed += name
        }
    }
    val stored = try {
        deleteStoredToken()
    } catch (e: Exception) {
        Result.failure(e)
    }
    stored.exceptionOrNull()?.let {
        PlatformLogger.w(TAG, "[forgetWipedNode] stored_token failed: ${it.message}")
        failed += "stored_token"
    }
    step("live_token") { clearLiveToken() }
    step("profiles") { forgetProfiles(wiped) }
    PlatformLogger.i(
        TAG,
        "[forgetWipedNode] ${wiped.baseUrl} key=${wiped.keyId ?: "<unknown>"} " +
            "identityDestroyed=${wiped.identityDestroyed} — " +
            if (failed.isEmpty()) "token + profiles forgotten" else "FAILED: $failed",
    )
    return failed
}

private const val TAG = "WipedNode"
