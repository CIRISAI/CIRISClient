package ai.ciris.mobile.shared.auth

import ai.ciris.mobile.shared.models.OAuthHandoff
import ai.ciris.mobile.shared.models.OAuthHandoffPoll
import kotlinx.coroutines.delay

/**
 * The desktop browser sign-in, collected over `GET /v1/auth/oauth/handoff`.
 *
 * Desktop has no native Google SDK. Sign-in happens in the browser against the
 * node's OAuth front door; the app opens the browser with a nonce and polls the
 * node until it hands the session over (ciris-server 0.5.165+, CIRISAgent#1028).
 *
 * ONE LOOP, TWO DOORS. The Login screen's Google button and the billing
 * credential refresh (CIRISClient#172) both need exactly this conversation, so
 * it is one function with the I/O injected: `poll` is the node, and the caller
 * has already opened the browser. The decisions a test can pin — 204 is NOT
 * YET, a `reason_id` is STOP, a stray tab is accepted only after our own had
 * its chance, silence for the whole budget is a timeout — live here and not
 * twice.
 */
sealed class BrowserSignInResult {
    /** The node handed the session over; [OAuthHandoff.idToken] is the provider's ID token when it issued one. */
    data class Collected(val handoff: OAuthHandoff) : BrowserSignInResult()

    /** The node said STOP — `reason_id` is a localisation key (e.g. `auth.oauth.no_local_identity`). */
    data class Refused(val reasonId: String) : BrowserSignInResult()

    /** Nothing arrived in the whole budget: the tab was closed, or an old tab without the nonce was used. */
    data class TimedOut(val waitedSeconds: Int) : BrowserSignInResult()

    /**
     * The node never heard of this sign-in for the whole grace window: every
     * answer was "unknown or expired" and none was "waiting". So the browser
     * never reached the node's sign-in page — it did not open, or opened
     * somewhere else (CIRISClient#284). Not an expiry, and not the person's doing.
     */
    data class BrowserNeverArrived(val waitedSeconds: Int) : BrowserSignInResult()
}

private const val FLOW_EXPIRED = "auth.oauth.flow_expired"

/**
 * CIRISServer#868 (0.5.246): `410 {status: "unknown"}` once the node's own 120 s
 * grace for a nonce it never saw has run out. The browser never reached /login.
 */
private const val FLOW_UNKNOWN = "auth.oauth.flow_unknown"

/** `status` of a flow the node ran and parked as failed (a 200), always final. */
private const val PARKED_FAILURE = "failed"

/** A 32-character app nonce for `?app_nonce=` — what binds the browser tab to this process. */
fun newAppNonce(): String = buildString {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    repeat(32) { append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) }
}

/**
 * Poll the node for the hand-off that [nonce] names until it is READY, REFUSED
 * or the budget is spent.
 *
 * @param poll one `GET /v1/auth/oauth/handoff?app_nonce=&allow_unbound=` — `CIRISApiClient.pollOAuthHandoff`.
 * @param maxAttempts × [intervalMs] is the budget: the default ~3 minutes is
 *   long enough for a real sign-in with a password manager and 2FA, short
 *   enough that an abandoned attempt does not spin forever.
 * @param unboundAfterAttempts after ~20s our own tab has had its chance;
 *   accept a sign-in that completed in a stray tab rather than spin while the
 *   node holds a perfectly good session.
 * @param unseenGraceAttempts how long (~60s) the node may answer "unknown or
 *   expired" before it has ever said "waiting". The node learns the nonce only
 *   when the BROWSER loads `/login?app_nonce=`, and a cold browser start takes
 *   longer than one poll interval (CIRISClient#284), so until then those answers
 *   are NOT YET. Once the node has said "waiting" (204) the flow is real, and an
 *   expiry after that is the refusal it says it is (CIRISClient#57).
 */
suspend fun collectBrowserSignIn(
    poll: suspend (nonce: String, allowUnbound: Boolean) -> OAuthHandoffPoll,
    nonce: String = newAppNonce(),
    maxAttempts: Int = 90,
    intervalMs: Long = 2000,
    unboundAfterAttempts: Int = 10,
    unseenGraceAttempts: Int = 30,
    log: (String) -> Unit = {},
): BrowserSignInResult {
    var attempts = 0
    // The node has said 204 for this nonce: it knows the flow, so the browser got there.
    var acknowledged = false
    while (attempts < maxAttempts) {
        delay(intervalMs)
        attempts++
        val answer = poll(nonce, attempts > unboundAfterAttempts)
        // Every answer, every time: in CIRISClient#284 the app log said nothing
        // and the diagnosis had to come from the node's log alone.
        log("poll n=$attempts ${describe(answer)}")
        when (answer) {
            is OAuthHandoffPoll.Ready -> return BrowserSignInResult.Collected(answer.handoff)
            // A `reason_id` is the server saying STOP; the old loop polled
            // through three of them (CIRISClient#57). The one exception is
            // "unknown or expired" for a flow the node has never acknowledged:
            // the browser may not have reached /login yet.
            is OAuthHandoffPoll.Failed -> {
                // The node's own verdict that the browser never came (0.5.246+, after
                // its 120 s grace). Final, and it is not an expiry.
                if (answer.reasonId == FLOW_UNKNOWN && answer.status != PARKED_FAILURE) {
                    return BrowserSignInResult.BrowserNeverArrived((attempts * intervalMs / 1000).toInt())
                }
                // A 410 expiry carries `status: "expired"` on every node that sends
                // one (0.5.244 and on). Only a flow parked as FAILED (a 200 with
                // `status: "failed"`) is the node's verdict on a flow it ran. The
                // 0.5.238 check read a missing status as "unseen", which no node
                // sends, so the grace never applied (CIRISClient#284).
                val unseen = answer.reasonId == FLOW_EXPIRED && answer.status != PARKED_FAILURE
                when {
                    !unseen || acknowledged -> return BrowserSignInResult.Refused(answer.reasonId ?: FLOW_EXPIRED)
                    attempts >= unseenGraceAttempts ->
                        return BrowserSignInResult.BrowserNeverArrived((attempts * intervalMs / 1000).toInt())
                }
            }
            OAuthHandoffPoll.Pending -> acknowledged = true
            OAuthHandoffPoll.Unclear -> Unit
        }
        // Heartbeat every ~20s: "still waiting" must be visible, or a stalled
        // flow looks like a crashed one.
        if (attempts % unboundAfterAttempts == 0) {
            log("still waiting for the browser (${attempts * intervalMs / 1000}s)")
        }
    }
    return BrowserSignInResult.TimedOut((attempts * intervalMs / 1000).toInt())
}

private fun describe(answer: OAuthHandoffPoll): String = when (answer) {
    OAuthHandoffPoll.Pending -> "status=204 waiting"
    OAuthHandoffPoll.Unclear -> "status=unreadable"
    is OAuthHandoffPoll.Ready -> "status=ready"
    is OAuthHandoffPoll.Failed -> "status=${answer.status ?: "refused"} reason=${answer.reasonId}"
}
