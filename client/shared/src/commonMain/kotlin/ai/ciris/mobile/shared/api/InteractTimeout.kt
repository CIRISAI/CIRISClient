package ai.ciris.mobile.shared.api

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.timeout
import io.ktor.http.encodedPath

/** The agent's own default wait for a reply (`routes/agent.py`, `_get_interaction_timeout`). */
internal const val AGENT_INTERACT_WAIT_MS = 55_000L

/** How long the client holds `POST /v1/agent/interact` open: past the agent's wait, never short of it. */
internal const val INTERACT_TIMEOUT_MS = AGENT_INTERACT_WAIT_MS + 10_000L

private const val INTERACT_PATH = "/v1/agent/interact"

/**
 * **The client must not give up before the agent does.** `interact` answers
 * when the reply is ready, up to [AGENT_INTERACT_WAIT_MS]; every other route
 * keeps the client's 30 s. At 30 s here a reply that took 31.5 s (run
 * 41885890061, windows-ai) lost its POST: the person's bubble kept its local
 * id, so history drew it a second time and the reply could not be paired with
 * the message it answers.
 *
 * The socket timeout moves too, because nothing crosses the socket while the
 * agent thinks: left at 30 s it would end the same wait.
 */
val InteractOutlastsTheAgent = createClientPlugin("InteractOutlastsTheAgent") {
    onRequest { request, _ ->
        if (request.url.encodedPath.endsWith(INTERACT_PATH)) {
            request.timeout {
                requestTimeoutMillis = INTERACT_TIMEOUT_MS
                socketTimeoutMillis = INTERACT_TIMEOUT_MS
            }
        }
    }
}
