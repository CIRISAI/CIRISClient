package ai.ciris.mobile.shared.models.capability

/**
 * What THIS host can do, declared once and asked by name.
 *
 * [NodeCapabilities] answers "what can the node I am attached to do", and is
 * careful to keep four answers apart — present, absent, undeclared, could not
 * tell. Nothing answered the same question about the host itself. Instead each
 * caller inferred a capability from whether some object happened to be null:
 *
 *   * "can I silently refresh a token?"  was  `googleSignInCallback != null`
 *   * "do I own the backend process?"     was  `_serverProcess != null`
 *   * "is this sign-in flow finished?"    was  `(poll as? Ready)?.handoff == null`
 *
 * Each of those is a typed answer collapsed into a nullable, and then the
 * nullable read as a fact about the world. Three shipped bugs are that
 * collapse (CIRISClient#55, #57, #59). This is the typed answer, kept.
 */
enum class HostCapability {
    /** A token can be refreshed without the person doing anything. */
    SILENT_REFRESH,

    /** The OAuth flow completes inside the app, not in a separate browser window. */
    OAUTH_IN_APP,
}

/**
 * Whether the backend this client talks to is one it started.
 *
 * Decided ONCE, when the runtime probes the port at startup, and kept — it
 * used to be decided at that moment too, as `ExistingServerState`, and then
 * thrown away, leaving `_serverProcess == null` to mean both "we attached to
 * a running node" and "nothing is running". `shutdown()` cannot tell those
 * apart, so a Reset on an attached node reported success having stopped
 * nothing, and deleted the files out from under a process that kept serving
 * them (CIRISClient#55).
 */
enum class BackendOwnership {
    /** This runtime launched the process and holds its handle. It can stop it. */
    LAUNCHED,

    /**
     * A backend was already serving when we started. We attached; it is not
     * ours to stop. On a launcher-started desktop this is EVERY run — the
     * agent's desktop_launcher brings the API up and then spawns the client
     * against it — so nothing may refuse on ATTACHED alone (CIRISClient#61,
     * where 0.5.220's Reset did and refused on the whole product). Reset
     * wipes regardless and names the running node it could not stop.
     */
    ATTACHED,

    /** No backend: never started, or ours was stopped. */
    NONE,

    /** Not yet probed. */
    UNDETERMINED,
}

/**
 * The host's declared capabilities. Platform entry points declare; callers ask.
 *
 * Reuses [CapabilityState] so "absent" and "not yet known" stay distinct here
 * too — a desktop that has not declared is not the same as a desktop that has
 * said no.
 */
object HostCapabilities {
    private val declared = mutableMapOf<HostCapability, CapabilityState>()

    fun declare(capability: HostCapability, state: CapabilityState) {
        declared[capability] = state
    }

    fun state(capability: HostCapability): CapabilityState =
        declared[capability] ?: CapabilityState.UNDETERMINED

    fun has(capability: HostCapability): Boolean = state(capability).isUsable
}
