package ai.ciris.mobile.shared.platform

import ai.ciris.mobile.shared.models.capability.BackendOwnership

/**
 * Protocol/interface for Python runtime operations.
 * Allows dependency injection and testability without subclassing expect classes.
 *
 * Implementations:
 * - PythonRuntime (actual platform implementations)
 * - Test fakes in commonTest
 */
interface PythonRuntimeProtocol {
    /**
     * Initialize Python interpreter
     * @param pythonHome Path to Python installation (platform-specific)
     * @return Result with Unit on success, exception on failure
     */
    suspend fun initialize(pythonHome: String): Result<Unit>

    /**
     * Start CIRIS FastAPI server on localhost:8080
     * Calls mobile_main.py to launch the CIRIS engine
     * @return Result with server URL on success
     */
    suspend fun startServer(): Result<String>

    /**
     * Start Python server with full lifecycle management
     *
     * This handles:
     * - SmartStartup detection (reconnect vs restart)
     * - Orphan server shutdown
     * - Python mobile_main.main() invocation
     * - Health check polling
     *
     * @param onStatus Callback for status updates (for UI)
     * @return Result with server URL on success
     */
    suspend fun startPythonServer(onStatus: ((String) -> Unit)?): Result<String>

    /**
     * Inject Python configuration from encrypted preferences
     *
     * Sets environment variables:
     * - OPENAI_API_BASE
     * - OPENAI_API_KEY
     *
     * @param config Map of config key-value pairs
     */
    fun injectPythonConfig(config: Map<String, String>)

    /**
     * Check if CIRIS server is responding to health checks
     * Polls http://localhost:8080/v1/system/health
     * @return Result with true if healthy, false if not yet ready
     */
    suspend fun checkHealth(): Result<Boolean>

    /**
     * Get number of services online
     * Parses telemetry from /v1/telemetry/unified
     * @return Result with (servicesOnline, servicesTotal)
     */
    suspend fun getServicesStatus(): Result<Pair<Int, Int>>

    /**
     * Get prep step status (pydantic setup + code integrity)
     * @return Result with (prepStepsCompleted, totalPrepSteps)
     */
    suspend fun getPrepStatus(): Result<Pair<Int, Int>>

    /**
     * Shutdown Python runtime gracefully
     * Note: On Android (Chaquopy), Python persists for app lifetime
     * On iOS, this calls Py_Finalize()
     */
    fun shutdown()

    /**
     * Whether the backend is one this runtime launched, or one it attached to.
     *
     * A typed fact, not a nullable handle. [shutdown] can only stop a process it
     * holds; a caller about to delete that process's files needs to know whether
     * shutdown() will actually stop anything BEFORE it deletes, and the only
     * prior signal was `_serverProcess == null` — which also means "nothing is
     * running" (CIRISClient#55). Defaults to UNDETERMINED so runtimes that never
     * launch anything (mobile) do not have to say anything.
     */
    val backendOwnership: BackendOwnership get() = BackendOwnership.UNDETERMINED

    /**
     * Check if Python is already initialized
     */
    fun isInitialized(): Boolean

    /**
     * Check if server has been started
     */
    fun isServerStarted(): Boolean

    /**
     * Get the server URL
     * Default: http://localhost:8080
     */
    val serverUrl: String

    /**
     * Set a callback for console output lines from the Python backend.
     * Used to drive startup light animations from structured console output.
     * Default implementation is a no-op.
     */
    fun setOutputLineCallback(callback: ((String) -> Unit)?) {
        // Default no-op — platforms override as needed
    }

    /**
     * ONE read of the node's one-time CLAIM PIN from its durable 0600 file — the
     * path the node declares as `claim_pin_file` in `GET /v1/setup/status`, else
     * `<CIRIS_HOME>/claim_pin`. `null` when the file is absent/empty/unreadable
     * or on platforms that do not drive a local node.
     *
     * The FILE is the only source (CIRISClient#184: "the claim PIN is a file on
     * disk, never the log"). Since ciris-server 0.5.227 the boot banner names the
     * file instead of printing the PIN, so no platform parses any log for it —
     * and the NodeCode is read over HTTP (`GET /v1/federation/node-code`), not
     * from the banner either. Single-shot on purpose: the bounded wait for a
     * file that appears a few seconds after health answers is
     * [ClaimPinFile.await], in one place for every platform. Same-uid file
     * access is first-run operator-level access, and `setup/root` still
     * verifies the PIN before granting ownership.
     */
    suspend fun readLocalClaimPin(): String? = null
}
