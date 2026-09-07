package ai.ciris.mobile.shared.platform

/**
 * WHERE THE BACKEND IS, AND HOW TO ASK IT IF IT IS READY.
 *
 * There are two, and which one serves depends on a choice the person made in the
 * setup wizard:
 *
 *   WITH an AI assistant — the agent runs, serving `:8080`. Readiness is
 *   `/v1/system/health`, whose payload drives the startup screen's per-service
 *   progress.
 *
 *   WITHOUT one (CIRISAgent#1149, from 2.11.0) — the brain shuts down and the
 *   process becomes the node. Only `:4243` serves; `:8080` goes away.
 *
 * ONE PLACE, DELIBERATELY. Both mobile actuals hard-coded the host and the path
 * as constants, and desktop has its own. Three copies of "where is the backend"
 * is how the automation layer ended up with three copies of one rule and a fix
 * landing in two of them (CIRISClient#33). The actuals consult this instead.
 *
 * THIS FILE DOES NOT DECIDE WHICH MODE IS IN EFFECT. It only maps the answer to
 * an address, so it stays a pure function that can be tested without a device —
 * which matters because getting it wrong shows the user a spinner forever, with
 * no way to tell a slow boot from a dead one.
 */
data class BackendEndpoint(
    /** TCP port the backend listens on. */
    val port: Int,
    /** Path to poll for readiness. */
    val healthPath: String,
) {
    /**
     * THE HOST IS THE CALLER'S, NOT OURS.
     *
     * Android must say `localhost` and not `127.0.0.1` — the WebView's
     * Same-Origin Policy treats them as different origins, and the loopback
     * literal breaks it. iOS uses `127.0.0.1`. Baking either into this file
     * would have silently broken the other, so the port and the path are shared
     * and the host stays where the constraint lives.
     */
    fun baseUrl(host: String): String = "http://$host:$port"

    fun healthUrl(host: String): String = baseUrl(host) + healthPath
}

/** The agent, with a brain. Its health payload carries per-service progress. */
val AGENT_ENDPOINT = BackendEndpoint(port = 8080, healthPath = "/v1/system/health")

/**
 * The node alone, after a run-without-AI setup.
 *
 * 200 MEANS SERVING, AND THAT IS SETTLED (CIRISServer#548).
 *
 * This was written provisionally, on the worry that a 200 might also mean "still
 * opening its stores" — the absence-read-as-a-positive that produced
 * CIRISClient#21 and #34. The node team checked and the worry does not apply
 * here: `/health` serves a CONSTANT `"status": "ok"` (`health.rs::plain_health`),
 * and `"starting"` has no producer anywhere in the node. The one place that word
 * appears is the desktop wheel launcher's own allowance, which was a leftover
 * rather than a contract and is being removed. So there is no intermediate state
 * to miss: refused while coming up, 200 once serving.
 *
 * DO NOT key on `"starting"`. If a richer answer is ever wanted,
 * `node.standing == "identified"` is the field, and `/v1/health` — not this
 * route — is where a bare node serves `services`, `warnings`, `degraded_mode`
 * and the rest. `/health` is deliberately the small one, polled by every watcher
 * in the mesh on a timer, which is exactly our use.
 *
 * `degraded` IS a serving state on `/v1/health`, so a future switch to that route
 * must treat `ok|degraded` as ready and must not read `degraded` as down.
 */
val NODE_ONLY_ENDPOINT = BackendEndpoint(port = 4243, healthPath = "/health")

/**
 * Which backend this install talks to.
 *
 * @param runWithoutAi the wizard's answer, read back from `CIRIS_RUN_WITHOUT_AI`
 *        in the home's `.env`. **Absent means false**, and that is correct only
 *        while the legacy default is brain-present: every install predating the
 *        flag serves `:8080`. If that default ever inverts, this has to become
 *        three-state rather than silently reading "unknown" as "with AI".
 */
fun backendEndpoint(runWithoutAi: Boolean): BackendEndpoint =
    if (runWithoutAi) NODE_ONLY_ENDPOINT else AGENT_ENDPOINT

/**
 * Read the wizard's answer out of `.env` content.
 *
 * Tolerant of what a shell-ish file actually contains — quotes, spacing,
 * comments, casing — because a mis-parse here does not fail loudly. It sends the
 * client to a port nothing is listening on, and the user sees a spinner.
 */
fun runWithoutAiFromEnv(envContent: String?): Boolean {
    if (envContent.isNullOrBlank()) return false
    for (raw in envContent.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val key = line.substringBefore('=', "").trim()
        if (!key.equals("CIRIS_RUN_WITHOUT_AI", ignoreCase = true)) continue
        val value = line.substringAfter('=', "").trim()
            .removeSurrounding("\"").removeSurrounding("'").trim()
        // EXACTLY the set the agent's reader accepts — no wider, no narrower.
        //
        // This is not a detail. Reviewing this file, the agent found their own
        // reader also accepted `on`, so `CIRIS_RUN_WITHOUT_AI=on` in a
        // hand-edited .env would have sent the agent to :4243 while we polled
        // :8080 — a dead app, with nothing in either log saying why. They
        // narrowed to match (CIRISAgent da584bc1d). A widening on either side
        // desynchronises the two, so the negative cases are tested here too.
        return value.lowercase() in setOf("true", "1", "yes")
    }
    return false
}

/**
 * The endpoint this process is using, resolved once the home's `.env` has been read.
 *
 * A single holder rather than a constructor parameter threaded through both
 * mobile runtimes: those are `actual` classes built by platform factories, and
 * widening their construction would mean editing four platform files to carry a
 * value that is the same everywhere. It mirrors `TestAutomationState`, which is
 * a singleton for the same reason.
 *
 * DEFAULTS TO THE AGENT, and that default is load-bearing. If the `.env` cannot
 * be read at all — a first run, a wiped home, a platform that has not wired
 * [resolveFrom] yet — the client must go to `:8080`, because that is where every
 * install predating CIRIS_RUN_WITHOUT_AI serves. Defaulting the other way would
 * point a working install at a port nothing is listening on.
 */
object ActiveBackend {
    @kotlin.concurrent.Volatile
    var endpoint: BackendEndpoint = AGENT_ENDPOINT
        private set

    /** Read the wizard's answer out of `.env` content and select the endpoint. */
    fun resolveFrom(envContent: String?) {
        endpoint = backendEndpoint(runWithoutAiFromEnv(envContent))
    }

    /** Back to the default. For tests, and for a wipe that removes the home. */
    fun reset() {
        endpoint = AGENT_ENDPOINT
    }
}

/**
 * THE LOOPBACK NAME THIS PLATFORM MUST USE.
 *
 * Android has to say `localhost`: the WebView's Same-Origin Policy treats it
 * and `127.0.0.1` as different origins, and the literal breaks it. Everywhere
 * else `127.0.0.1` is the honest answer. Both call sites used to hardcode
 * their own, which was fine while only `PythonRuntime` needed one and became a
 * trap the moment a second reader appeared.
 */
expect val LOOPBACK_HOST: String

/**
 * Point the client at the backend the home's `.env` says is running.
 *
 * THE SWITCH WAS BUILT AND NEVER PLUGGED IN (CIRISClient#43). `ActiveBackend`
 * and `runWithoutAiFromEnv` shipped in 0.5.203 with tests that call
 * `resolveFrom` directly — and NOTHING IN PRODUCTION CALLED IT, on any
 * platform. So `ActiveBackend.endpoint` stayed at its `AGENT_ENDPOINT` default
 * forever: a run-without-AI install polled `:8080` after the agent had already
 * handed off to the node on `:4243`, and the user watched "Restarting your
 * node…" for as long as they were willing to.
 *
 * Call this at startup AND when setup completes. The second is not optional:
 * the hand-off replaces the AGENT process, not the client, so a client that
 * only resolves at startup has already decided before the answer exists.
 *
 * THE NODE URL IS ONLY FORCED IN THE RUN-WITHOUT-AI DIRECTION. Anything else
 * would stamp on `CIRIS_NODE_URL` and on operators attached to someone else's
 * node — the exact defect CIRISClient#26 was about. The invariant this
 * enforces is the narrow one: once run-without-AI is recorded, no path may
 * leave the client talking to the agent port.
 *
 * @return the endpoint now in effect.
 */
suspend fun syncBackendFromEnv(
    updater: EnvFileUpdater,
    apiClient: ai.ciris.mobile.shared.api.CIRISApiClient,
): BackendEndpoint =
    syncBackendFrom(
        object : EnvReader {
            override suspend fun readRawEnv(): String? = updater.readRawEnv()
        },
        apiClient,
    )

/**
 * What [syncBackendFromEnv] needs from a home: its `.env`, or null.
 *
 * A one-method seam so the wiring is testable without a device. `EnvFileUpdater`
 * is an expect class with a platform constructor, so a test cannot build one —
 * which is a large part of why this path went unwired and unnoticed.
 */
interface EnvReader {
    suspend fun readRawEnv(): String?
}

/**
 * See [syncBackendFromEnv].
 *
 * [apiClient] is NOT optional, and that is the point. The first cut of this
 * moved `LOCAL_NODE_URL` — which federation call sites read — and left the
 * client every ORDINARY call goes through pinned at whatever it was
 * constructed with, because `CIRISApp` builds it once inside a `remember`.
 * So the node URL moved to :4243 and login still went to :8080. Taking the
 * client as a required argument means the two cannot move apart again;
 * a caller that has one cannot forget to hand it over.
 */
suspend fun syncBackendFrom(
    reader: EnvReader,
    apiClient: ai.ciris.mobile.shared.api.CIRISApiClient,
): BackendEndpoint {
    ActiveBackend.resolveFrom(runCatching { reader.readRawEnv() }.getOrNull())
    val endpoint = ActiveBackend.endpoint
    if (endpoint == NODE_ONLY_ENDPOINT) {
        val url = endpoint.baseUrl(LOOPBACK_HOST)
        ai.ciris.mobile.shared.api.CIRISApiClient.setLocalNodeUrl(url)
        // updateBaseUrl recreates every SDK instance, so this reaches the
        // ~13 generated APIs as well as the direct HTTP calls.
        if (apiClient.baseUrl != url) apiClient.updateBaseUrl(url)
    }
    return endpoint
}
