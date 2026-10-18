package ai.ciris.mobile.shared.auth

import ai.ciris.mobile.shared.platform.EnvFileUpdater

/**
 * Renewing the BILLING credential on a desktop (CIRISClient#172).
 *
 * The agent pays for hosted-proxy use with the user's Google ID token, read
 * from `CIRIS_BILLING_GOOGLE_ID_TOKEN` in `<CIRIS_HOME>/.env`. Google ID
 * tokens last an hour. Setup writes one; nothing on desktop ever wrote
 * another, so every billing call from the second hour on was `401
 * token_expired` — three weeks of it on the reporting machine.
 *
 * WHY NOT A SILENT REFRESH. Android has one: `.token_refresh_needed` → silent
 * Google sign-in → `EnvFileUpdater` → `.config_reload`. Desktop cannot: the
 * node's browser OAuth asks Google for `access_type=offline&prompt=consent`
 * and so RECEIVES a refresh_token, but `exchange_google` reads only
 * `access_token` and `id_token` out of the token response and the
 * refresh_token is dropped on the floor (CIRISServer `src/auth/oauth.rs`).
 * Nothing stores it, no route mints a fresh ID token from it. Until the node
 * keeps it (evidence/blocked_upstream.tsv), the only renewal is the person
 * signing in again in the browser — so that is what this does, as a named,
 * non-blocking credits state with a button, and NEVER as a session failure.
 *
 * The session is not touched. The hand-off does return a new CIRIS access
 * token; it is ignored here on purpose — the one in hand is valid, and
 * swapping it would make a billing renewal look like a re-login to every
 * collector keyed on the token.
 */
sealed class BillingCredentialRefreshOutcome {
    /** A fresh ID token is in `.env`, the agent was told to reload, TokenManager holds it. */
    data object Refreshed : BillingCredentialRefreshOutcome()

    /** The node refused the hand-off; `reasonId` is a localisation key. */
    data class Refused(val reasonId: String) : BillingCredentialRefreshOutcome()

    /** The browser never came back. */
    data class TimedOut(val waitedSeconds: Int) : BillingCredentialRefreshOutcome()

    /** The person dismissed the native picker (Android/iOS). Not an error. */
    data object Cancelled : BillingCredentialRefreshOutcome()

    /**
     * The node handed over a session but no `id_token`: it predates
     * ciris-server 0.5.177 (CIRISServer#434). A named state — not a generic
     * error — because the fix is updating the node, not trying again.
     */
    data object NodeTooOld : BillingCredentialRefreshOutcome()

    /** `.env` could not be rewritten; the agent still holds the old token. */
    data class WriteFailed(val detail: String) : BillingCredentialRefreshOutcome()

    /** The native sign-in SDK failed outright. */
    data class Failed(val detail: String) : BillingCredentialRefreshOutcome()
}

/**
 * Hand a freshly collected provider ID token to billing: write it through to
 * `.env` under the name the agent reads, trip `.config_reload`, and give it to
 * [TokenManager] so the stale state retires and purchases can use it.
 *
 * Called by the billing refresh AND by the existing-user desktop Login path —
 * which collected `id_token` and dropped it, so "sign in again" left `.env`
 * unchanged (CIRISClient#172: re-signing in at 11:47:04 changed nothing).
 */
suspend fun adoptBillingIdToken(
    idToken: String,
    provider: String,
    envFileUpdater: EnvFileUpdater,
    tokenManager: TokenManager,
): Result<Boolean> =
    envFileUpdater.updateEnvWithToken(idToken).onSuccess {
        tokenManager.handleNewToken(idToken, provider.ifBlank { "google" })
    }

/**
 * The desktop renewal: run the browser hand-off, then [adoptBillingIdToken].
 *
 * @param signIn opens the browser and collects the hand-off — see
 *   [collectBrowserSignIn]; injected so the decision below is testable with
 *   a fake node and a temp `.env`.
 */
suspend fun refreshBillingCredentialFromBrowser(
    signIn: suspend () -> BrowserSignInResult,
    envFileUpdater: EnvFileUpdater,
    tokenManager: TokenManager,
    log: (String) -> Unit = {},
): BillingCredentialRefreshOutcome {
    return when (val result = signIn()) {
        is BrowserSignInResult.Refused -> {
            log("node refused the hand-off: ${result.reasonId}")
            BillingCredentialRefreshOutcome.Refused(result.reasonId)
        }
        is BrowserSignInResult.TimedOut -> {
            log("no hand-off after ${result.waitedSeconds}s")
            BillingCredentialRefreshOutcome.TimedOut(result.waitedSeconds)
        }
        is BrowserSignInResult.Collected -> {
            val idToken = result.handoff.idToken
            if (idToken.isNullOrBlank()) {
                log("hand-off carried no id_token — node predates ciris-server 0.5.177")
                return BillingCredentialRefreshOutcome.NodeTooOld
            }
            adoptBillingIdToken(idToken, result.handoff.provider, envFileUpdater, tokenManager).fold(
                onSuccess = {
                    log("fresh ${result.handoff.provider.ifBlank { "google" }} id_token written to .env; .config_reload tripped")
                    BillingCredentialRefreshOutcome.Refreshed
                },
                onFailure = { e ->
                    log("failed to write .env: ${e.message}")
                    BillingCredentialRefreshOutcome.WriteFailed(e.message ?: e::class.simpleName ?: "unknown")
                },
            )
        }
    }
}
