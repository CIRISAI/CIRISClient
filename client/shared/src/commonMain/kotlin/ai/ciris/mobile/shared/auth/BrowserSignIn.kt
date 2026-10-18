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
}

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
 */
suspend fun collectBrowserSignIn(
    poll: suspend (nonce: String, allowUnbound: Boolean) -> OAuthHandoffPoll,
    nonce: String = newAppNonce(),
    maxAttempts: Int = 90,
    intervalMs: Long = 2000,
    unboundAfterAttempts: Int = 10,
    log: (String) -> Unit = {},
): BrowserSignInResult {
    var attempts = 0
    while (attempts < maxAttempts) {
        delay(intervalMs)
        attempts++
        when (val answer = poll(nonce, attempts > unboundAfterAttempts)) {
            is OAuthHandoffPoll.Ready -> return BrowserSignInResult.Collected(answer.handoff)
            // A 410 with `reason_id` is the server saying STOP; the old loop
            // polled through three of them (CIRISClient#57).
            is OAuthHandoffPoll.Failed -> return BrowserSignInResult.Refused(answer.reasonId ?: "auth.oauth.flow_expired")
            OAuthHandoffPoll.Pending -> Unit
        }
        // Heartbeat every ~20s: "still waiting" must be visible, or a stalled
        // flow looks like a crashed one.
        if (attempts % unboundAfterAttempts == 0) {
            log("still waiting for the browser (${attempts * intervalMs / 1000}s)")
        }
    }
    return BrowserSignInResult.TimedOut((attempts * intervalMs / 1000).toInt())
}
