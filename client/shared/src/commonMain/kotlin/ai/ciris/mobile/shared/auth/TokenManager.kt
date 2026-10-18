package ai.ciris.mobile.shared.auth

import ai.ciris.mobile.shared.platform.PlatformLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Token Manager for handling Google ID token refresh.
 *
 * Google ID tokens expire in ~1 hour. This manager:
 * 1. Checks token expiry on startup
 * 2. Triggers silent sign-in when token is stale (< 5 minutes remaining)
 * 3. Periodically refreshes tokens (every 45 minutes)
 * 4. Handles 401 errors by triggering token refresh
 *
 * It keeps TWO conclusions apart (CIRISClient#171): [needsInteractiveLogin]
 * is about the session, [billingCredentialStale] is about the token the
 * agent spends at the hosted proxy. On desktop there is no silent path at
 * all — the node's OAuth exchange discards Google's refresh_token
 * (CIRISServer `src/auth/oauth.rs`, exchange_google reads only access_token
 * and id_token) — so both end in a person signing in again, but through
 * different doors: the session's is Login, billing's is a browser hand-off
 * that leaves the session alone (`BillingCredentialRefresh.kt`).
 *
 * Ported from android/app/.../auth/TokenRefreshManager.kt
 */
class TokenManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "TokenManager"

        // Minimum token validity in seconds - if token expires sooner, force refresh
        private const val MIN_TOKEN_VALIDITY_SECONDS = 300L // 5 minutes

        // Refresh interval: 45 minutes (before 1-hour expiry)
        private const val REFRESH_INTERVAL_MS = 45L * 60L * 1000L

        // Shared instance for global access (set by CIRISApp)
        @Volatile
        private var _shared: TokenManager? = null

        /**
         * Get the shared TokenManager instance.
         * Returns null if not yet initialized by CIRISApp.
         */
        val shared: TokenManager?
            get() = _shared

        /**
         * Set the shared instance. Called by CIRISApp when creating the TokenManager.
         */
        fun setShared(instance: TokenManager) {
            _shared = instance
            PlatformLogger.i(TAG, "[setShared] Shared TokenManager instance set")
        }
    }

    private fun log(level: String, method: String, message: String) {
        val fullMessage = "[$method] $message"
        when (level) {
            "DEBUG" -> PlatformLogger.d(TAG, fullMessage)
            "INFO" -> PlatformLogger.i(TAG, fullMessage)
            "WARN" -> PlatformLogger.w(TAG, fullMessage)
            "ERROR" -> PlatformLogger.e(TAG, fullMessage)
            else -> PlatformLogger.i(TAG, fullMessage)
        }
    }

    private fun logDebug(method: String, message: String) = log("DEBUG", method, message)
    private fun logInfo(method: String, message: String) = log("INFO", method, message)
    private fun logWarn(method: String, message: String) = log("WARN", method, message)
    private fun logError(method: String, message: String) = log("ERROR", method, message)

    // Current token state
    private val _currentToken = MutableStateFlow<String?>(null)
    val currentToken: StateFlow<String?> = _currentToken.asStateFlow()

    private val _tokenState = MutableStateFlow(TokenState.UNKNOWN)
    val tokenState: StateFlow<TokenState> = _tokenState.asStateFlow()

    /**
     * The SESSION needs a person: the CIRIS access token could not be renewed
     * silently. Raised by [on401Error] and the startup check, never by billing.
     */
    private val _needsInteractiveLogin = MutableStateFlow(false)
    val needsInteractiveLogin: StateFlow<Boolean> = _needsInteractiveLogin.asStateFlow()

    /**
     * The BILLING credential needs a person: the provider ID token the agent
     * sends to the hosted proxy is stale and there is no silent path to a new
     * one. Raised by [onBillingCredentialRejected], the periodic refresh and
     * [ensureValidToken]; retired by any fresh token.
     *
     * Two credentials, two flags (CIRISClient#171). They were one: the agent's
     * `.token_refresh_needed` — a billing 401, `billing_auth_expired` — came
     * in through [on401Error], landed on [needsInteractiveLogin], and the
     * client told a person whose own agent was answering them that their
     * session was no longer valid. The session was fine; the Google token
     * issued at setup had passed its hour.
     */
    private val _billingCredentialStale = MutableStateFlow(false)
    val billingCredentialStale: StateFlow<Boolean> = _billingCredentialStale.asStateFlow()

    /** Which credential a refresh is on behalf of — decides which flag a failure raises. */
    private enum class CredentialUse { SESSION, BILLING }

    // Callback for silent sign-in
    private var silentSignInCallback: (suspend () -> SilentSignInResult)? = null

    // Callback for when token is refreshed - receives (idToken, provider)
    private var onTokenRefreshed: ((String, String) -> Unit)? = null

    // Current OAuth provider (google or apple)
    private var currentProvider: String = "google"

    // Periodic refresh job
    private var refreshJob: Job? = null

    /**
     * Set the callback for performing silent sign-in.
     * This should be provided by the platform-specific code.
     */
    fun setSilentSignInCallback(callback: suspend () -> SilentSignInResult) {
        silentSignInCallback = callback
    }

    /**
     * Set the callback for when a token is successfully refreshed.
     * Callback receives (idToken, provider) where provider is "google" or "apple".
     */
    fun setOnTokenRefreshed(callback: (String, String) -> Unit) {
        onTokenRefreshed = callback
    }

    /**
     * Set the current OAuth provider.
     * Call this when user completes interactive sign-in.
     */
    fun setCurrentProvider(provider: String) {
        currentProvider = provider
        logInfo("setCurrentProvider", "Provider set to: $provider")
    }

    /**
     * Check and refresh token if needed.
     * Call this on app startup.
     *
     * @param storedToken The token from secure storage
     * @return true if token is valid (or was refreshed), false if interactive login needed
     */
    suspend fun checkAndRefreshToken(storedToken: String?): Boolean {
        val method = "checkAndRefreshToken"

        if (storedToken == null) {
            logInfo(method, "No stored token - interactive login required")
            _tokenState.value = TokenState.MISSING
            _needsInteractiveLogin.value = true
            return false
        }

        // Check token validity
        val expiry = getTokenExpiry(storedToken)
        val nowSeconds = currentTimeSeconds()
        val remainingSeconds = expiry?.let { it - nowSeconds }

        logInfo(method, "Token check: expiry=$expiry, remaining=${remainingSeconds}s, " +
                "minValid=${MIN_TOKEN_VALIDITY_SECONDS}s")

        if (remainingSeconds == null) {
            logWarn(method, "Could not parse token expiry - treating as expired")
            return attemptSilentRefresh(CredentialUse.SESSION)
        }

        if (remainingSeconds <= 0) {
            logInfo(method, "Token is EXPIRED - attempting silent refresh")
            _tokenState.value = TokenState.EXPIRED
            return attemptSilentRefresh(CredentialUse.SESSION)
        }

        if (remainingSeconds < MIN_TOKEN_VALIDITY_SECONDS) {
            logInfo(method, "Token expires soon (${remainingSeconds}s) - proactive refresh")
            _tokenState.value = TokenState.EXPIRING_SOON
            return attemptSilentRefresh(CredentialUse.SESSION)
        }

        // Token is valid
        logInfo(method, "Token is VALID - ${remainingSeconds}s remaining")
        _tokenState.value = TokenState.VALID
        _currentToken.value = storedToken
        _needsInteractiveLogin.value = false

        // Start periodic refresh
        startPeriodicRefresh()

        return true
    }

    /**
     * Attempt silent sign-in to refresh the token.
     *
     * The SAME mechanism serves both credentials — a fresh provider ID token
     * renews the session (by exchange) and the billing token (by `.env`) — so
     * success clears both flags. Only FAILURE is per-credential: it raises the
     * flag for the [use] that asked, and leaves the other alone.
     */
    private suspend fun attemptSilentRefresh(use: CredentialUse): Boolean {
        val method = "attemptSilentRefresh"
        val callback = silentSignInCallback

        if (callback == null) {
            logWarn(method, "No silent sign-in callback set - a person must sign in again (for: $use)")
            needsPerson(use)
            return false
        }

        logInfo(method, "Attempting silent sign-in (for: $use)...")
        _tokenState.value = TokenState.REFRESHING

        return try {
            when (val result = callback()) {
                is SilentSignInResult.Success -> {
                    logInfo(method, "Silent sign-in successful! provider=${result.provider}")
                    // A fresh credential retires the "sign in again" state; it is
                    // now collected and rendered (CIRISClient#59), so it must also
                    // be cleared, or the banner outlives the problem.
                    _needsInteractiveLogin.value = false
                    _billingCredentialStale.value = false
                    currentProvider = result.provider
                    handleNewToken(result.idToken, result.provider)
                    true
                }
                is SilentSignInResult.NeedsInteractiveLogin -> {
                    logInfo(method, "Silent sign-in requires interactive login (code: ${result.errorCode}, for: $use)")
                    _tokenState.value = TokenState.NEEDS_INTERACTIVE
                    needsPerson(use)
                    false
                }
                is SilentSignInResult.Error -> {
                    logError(method, "Silent sign-in failed: ${result.message} (for: $use)")
                    _tokenState.value = TokenState.ERROR
                    needsPerson(use)
                    false
                }
            }
        } catch (e: Exception) {
            logError(method, "Silent sign-in exception: ${e::class.simpleName}: ${e.message} (for: $use)")
            _tokenState.value = TokenState.ERROR
            needsPerson(use)
            false
        }
    }

    /** Raise the flag for the credential that could not be renewed — and only that one. */
    private fun needsPerson(use: CredentialUse) {
        when (use) {
            CredentialUse.SESSION -> _needsInteractiveLogin.value = true
            CredentialUse.BILLING -> _billingCredentialStale.value = true
        }
    }

    /**
     * Handle a newly obtained token (from silent or interactive sign-in).
     * @param idToken The OAuth ID token
     * @param provider The OAuth provider ("google" or "apple")
     */
    fun handleNewToken(idToken: String, provider: String = currentProvider) {
        val method = "handleNewToken"
        logInfo(method, "Processing new token (length: ${idToken.length}, provider: $provider)")

        currentProvider = provider
        _currentToken.value = idToken
        _tokenState.value = TokenState.VALID
        _needsInteractiveLogin.value = false
        _billingCredentialStale.value = false

        // Log token diagnostics
        val expiry = getTokenExpiry(idToken)
        val remaining = expiry?.let { it - currentTimeSeconds() }
        logInfo(method, "New token expiry: $expiry (${remaining}s remaining)")

        // Notify callback with provider
        onTokenRefreshed?.invoke(idToken, provider)

        // Start/restart periodic refresh
        startPeriodicRefresh()
    }

    /**
     * Handle a 401 from the SESSION — the CIRIS access token was rejected by
     * the node or agent the person is signed in to. Triggers a silent refresh;
     * if none is possible, [needsInteractiveLogin] is raised.
     *
     * NOT for billing. The agent's `.token_refresh_needed` and the credits
     * route's `billing_auth_expired` go through [onBillingCredentialRejected].
     */
    fun on401Error() {
        val method = "on401Error"
        logWarn(method, "401 error received - triggering token refresh")
        _tokenState.value = TokenState.EXPIRED

        scope.launch {
            attemptSilentRefresh(CredentialUse.SESSION)
        }
    }

    /**
     * The hosted proxy rejected the BILLING credential (the agent wrote
     * `.token_refresh_needed`, or `GET /v1/api/billing/credits` answered 401
     * `billing_auth_expired`). Same silent refresh as a session 401 — a fresh
     * ID token fixes both — but when no silent path exists this raises
     * [billingCredentialStale], a credits state, and never
     * [needsInteractiveLogin]: the person is still signed in to their agent
     * (CIRISClient#171).
     */
    fun onBillingCredentialRejected() {
        val method = "onBillingCredentialRejected"
        logWarn(method, "billing credential rejected - triggering token refresh")
        _tokenState.value = TokenState.EXPIRED

        scope.launch {
            attemptSilentRefresh(CredentialUse.BILLING)
        }
    }

    /**
     * Ensure a valid token is available, refreshing silently if needed.
     * Returns the valid token, or null if refresh fails and interactive login is required.
     *
     * Use this before operations that require a token (e.g., purchases).
     */
    suspend fun ensureValidToken(): String? {
        val method = "ensureValidToken"

        // Fast path: token exists and is valid
        val current = _currentToken.value
        if (current != null) {
            val expiry = getTokenExpiry(current)
            val remaining = expiry?.let { it - currentTimeSeconds() }
            if (remaining != null && remaining > MIN_TOKEN_VALIDITY_SECONDS) {
                logDebug(method, "Token valid (${remaining}s remaining)")
                return current
            }
            logInfo(method, "Token expired or expiring soon (${remaining}s remaining), refreshing...")
        } else {
            logInfo(method, "No token available, attempting silent refresh...")
        }

        // Attempt silent refresh. A purchase is a billing use of the token.
        val refreshed = attemptSilentRefresh(CredentialUse.BILLING)
        if (refreshed) {
            val newToken = _currentToken.value
            logInfo(method, "Silent refresh succeeded, token available=${newToken != null}")
            return newToken
        }

        logWarn(method, "Silent refresh failed - interactive login required")
        return null
    }

    /**
     * Start periodic token refresh.
     */
    private fun startPeriodicRefresh() {
        val method = "startPeriodicRefresh"

        refreshJob?.cancel()
        refreshJob = scope.launch {
            logInfo(method, "Starting periodic refresh (interval: ${REFRESH_INTERVAL_MS / 1000 / 60} minutes)")

            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                logInfo(method, "Periodic refresh triggered")
                // The hourly clock is the provider ID token's — the credential
                // billing spends. The session has its own 401 path.
                attemptSilentRefresh(CredentialUse.BILLING)
            }
        }
    }

    /**
     * Stop periodic refresh.
     */
    fun stopPeriodicRefresh() {
        logInfo("stopPeriodicRefresh", "Stopping periodic refresh")
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * Clear the interactive login flag after user completes login.
     */
    fun clearInteractiveLoginNeeded() {
        _needsInteractiveLogin.value = false
    }

    /**
     * Get the expiry time (in seconds since epoch) from a JWT token.
     * Returns null if parsing fails.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun getTokenExpiry(idToken: String): Long? {
        val method = "getTokenExpiry"
        return try {
            // JWT has 3 parts: header.payload.signature
            val parts = idToken.split(".")
            if (parts.size != 3) {
                logWarn(method, "Invalid JWT format: expected 3 parts, got ${parts.size}")
                return null
            }

            // Decode the payload (second part) - use URL-safe base64
            val payloadBase64 = parts[1]
                .replace('-', '+')
                .replace('_', '/')

            // Add padding if needed
            val paddedPayload = when (payloadBase64.length % 4) {
                2 -> "$payloadBase64=="
                3 -> "$payloadBase64="
                else -> payloadBase64
            }

            val payloadBytes = Base64.decode(paddedPayload)
            val payload = payloadBytes.decodeToString()

            val json = Json { ignoreUnknownKeys = true }
            val jsonObj = json.parseToJsonElement(payload).jsonObject
            val exp = jsonObj["exp"]?.jsonPrimitive?.long

            if (exp != null && exp > 0) {
                logDebug(method, "Token expiry: $exp (${exp - currentTimeSeconds()}s remaining)")
                exp
            } else {
                logWarn(method, "No 'exp' claim in JWT payload")
                null
            }
        } catch (e: Exception) {
            logError(method, "Failed to parse JWT: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Check if a token is valid (has sufficient time remaining).
     */
    fun isTokenValid(idToken: String?): Boolean {
        if (idToken == null) return false

        val expiry = getTokenExpiry(idToken) ?: return false
        val remainingSeconds = expiry - currentTimeSeconds()

        logDebug("isTokenValid", "Token validity: ${remainingSeconds}s remaining, need ${MIN_TOKEN_VALIDITY_SECONDS}s")
        return remainingSeconds > MIN_TOKEN_VALIDITY_SECONDS
    }

    private fun currentTimeSeconds(): Long = Clock.System.now().epochSeconds

    /**
     * Clean up resources.
     */
    fun cleanup() {
        logInfo("cleanup", "Cleaning up TokenManager")
        stopPeriodicRefresh()
        scope.cancel()
    }
}

/**
 * Token validity state.
 */
enum class TokenState {
    UNKNOWN,
    VALID,
    EXPIRING_SOON,
    EXPIRED,
    REFRESHING,
    NEEDS_INTERACTIVE,
    MISSING,
    ERROR
}

/**
 * Result of silent sign-in attempt.
 */
sealed class SilentSignInResult {
    data class Success(val idToken: String, val email: String? = null, val provider: String = "google") : SilentSignInResult()
    data class NeedsInteractiveLogin(val errorCode: Int) : SilentSignInResult()
    data class Error(val message: String, val errorCode: Int = -1) : SilentSignInResult()
}
