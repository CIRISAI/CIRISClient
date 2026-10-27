package ai.ciris.mobile.shared.api

import kotlinx.coroutines.delay

/**
 * **A node that binds late is "not yet", never "absent"** (CIRISClient#254).
 *
 * Eric's second phone (SM-S737TL, Android 8.1, armeabi-v7a, 1.8 GB): Login
 * composed and asked the local node about capabilities and the owner's
 * federation identity at 19:43:28. The embedded Python printed its first line
 * at 19:44:04, and the node bound :4243 some time after the agent's API was up
 * at 19:44:30. Both probes ran exactly once, both answered "unreachable", and
 * nothing asked again. So a healthy, unclaimed node never got its claim section,
 * and the phone could not be claimed. The gate's x86_64 emulator never shows
 * this gap: its node is up before Login composes.
 *
 * Both probes already knew that "could not ask" is not "the answer is no". That
 * is why an unreachable node renders no section, rather than inviting the owner
 * to create an identity they already have. What they lacked was a second
 * question. This is the second question, on a capped backoff, asked until the
 * node answers.
 *
 * Login never waits on it. A node that never comes up leaves agent sign-in
 * exactly as usable as before, with one cheap refused connect every
 * [BACKOFF_MS] cap.
 */
object NodeReprobe {

    /** Delay after the 1st, 2nd, … unanswered probe; the last value repeats. */
    val BACKOFF_MS: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 30_000)

    fun delayAfter(failures: Int): Long = BACKOFF_MS[failures.coerceIn(0, BACKOFF_MS.lastIndex)]

    /**
     * Run [probe] until [answered] holds, passing every result to [onResult] so
     * the UI shows the latest verdict, not the first one. Returns the answering
     * result. Cancellation (the caller's effect restarting or leaving
     * composition) ends the loop, as it should.
     */
    suspend fun <T> untilAnswered(
        answered: (T) -> Boolean,
        onResult: (T) -> Unit = {},
        sleep: suspend (Long) -> Unit = { delay(it) },
        probe: suspend () -> T,
    ): T {
        var failures = 0
        while (true) {
            val result = probe()
            onResult(result)
            if (answered(result)) return result
            sleep(delayAfter(failures++))
        }
    }
}

/**
 * What Login's federation probe learned. [reached] false means the node could
 * not be asked, which renders no section at all. [reached] true with a null
 * [keyId] means the node answered and the owner has no federation identity yet,
 * which renders the create path.
 */
data class FederationProbe(val reached: Boolean, val keyId: String?)

/**
 * THE OWNER'S FED-ID, NOT THE NODE'S SIGNER (Codex, PR #6).
 *
 * getSelfKeyRecord() returns the NODE key (`ciris-client` on a claimed node),
 * which is the node's own signer and NOT the bound owner. Reading it first made
 * the login screen offer "Sign in as ciris-client". Worse, it treated an
 * ordinary node signer as proof that the OWNER already has a federation
 * identity, so the create path could never appear for an owner who genuinely
 * has none. IdentityManagementViewModel.load() documents this exact trap and
 * asks owned-nodes first; this does the same, in the same order.
 *
 * Moved here from CIRISApp's composable unchanged, so a test can drive it
 * against a node that binds late (CIRISClient#254).
 */
suspend fun CIRISApiClient.probeFederationIdentity(
    nodeUrl: String = CIRISApiClient.LOCAL_NODE_URL,
): FederationProbe {
    var reached = false
    val keyId = try {
        val owner = runCatching { getOwnedNodes(nodeUrl).owner }
            .onSuccess { reached = true }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        owner ?: getSelfKeyRecord(nodeUrl).keyId.also { reached = true }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ai.ciris.mobile.shared.platform.PlatformLogger.i(
            "CIRISApp",
            "[INFO][federation] probe failed: ${e.message?.take(80)}",
        )
        null
    }
    return FederationProbe(reached, keyId)
}
