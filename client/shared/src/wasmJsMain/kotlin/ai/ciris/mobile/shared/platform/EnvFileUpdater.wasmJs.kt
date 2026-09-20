package ai.ciris.mobile.shared.platform

/**
 * Web implementation of EnvFileUpdater - no-op since web doesn't have .env files.
 * Configuration is handled server-side via API.
 */
actual class EnvFileUpdater {

    actual suspend fun updateEnvWithToken(oauthIdToken: String): Result<Boolean> = Result.success(true)

    actual fun triggerConfigReload() {
        // No-op on web
    }

    /**
     * There is no home directory in a browser, so there is no `.env` to read.
     * Null means "the agent", which is the right answer for the web build: it
     * attaches to a backend over the network rather than hosting one.
     */
    actual suspend fun readRawEnv(): String? = null

    actual suspend fun readLlmConfig(): EnvLlmConfig? = null

    actual suspend fun deleteEnvFile(): Result<Boolean> = Result.success(true)

    actual fun checkTokenRefreshSignal(): Boolean = false

    actual suspend fun clearSigningKey(): Result<Boolean> = Result.success(true)

    actual suspend fun clearDataOnly(): Result<Boolean> = Result.success(true)
}

actual fun createEnvFileUpdater(): EnvFileUpdater = EnvFileUpdater()
