package ai.ciris.mobile.shared.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What `POST /v1/mesh/relay` passed back from the target: its status and body, verbatim. */
data class RelayAnswer(val status: Int, val body: String)

/**
 * The relay itself refused, before or instead of the target answering. [token] is
 * the node's stable word (`mesh_target_unreachable`, `mesh_relay_timeout`,
 * `mesh_send_failed`, `mesh_send_unwired`) or null; [detail] is its sentence.
 */
class RelayRefusal(val status: Int, val token: String?, val detail: String) : Exception(detail)

/** One read through the person's own node to another node they own. */
fun interface RemoteNodeApi {
    suspend fun relay(targetKeyId: String, method: String, path: String): RelayAnswer
}

/** Why a remote view shows nothing, each with its own sentence. */
enum class RemoteFailure { UNREACHABLE, TIMEOUT, SEND_FAILED, NO_TRANSPORT, NEEDS_UPDATE, NOT_OWNER, OTHER }

sealed interface RemoteNodeState {
    data object Loading : RemoteNodeState

    /**
     * A snapshot, not a live view: event streams cannot cross the relay
     * (CIRISServer `mesh_relay.rs`), so it is read when asked and says when.
     */
    data class Loaded(
        val at: Instant,
        val metrics: List<Pair<String, String>>,
        val logs: List<String>,
        /** Each half can fail alone: a node that relays metrics may still refuse logs. */
        val metricsFailure: RemoteFailure? = null,
        val logsFailure: RemoteFailure? = null,
    ) : RemoteNodeState

    data class Failed(val failure: RemoteFailure) : RemoteNodeState
}

/**
 * Administer a node the person owns from their own client, through their own
 * node (`POST /v1/mesh/relay`, CIRISServer 0.5.245). The target checks the
 * signer against the owner's self-key roster, so any device on it works.
 */
class RemoteNodeViewModel(private val api: RemoteNodeApi) : ViewModel() {
    private val _state = MutableStateFlow<RemoteNodeState>(RemoteNodeState.Loading)
    val state: StateFlow<RemoteNodeState> = _state.asStateFlow()

    fun load(targetKeyId: String) {
        _state.value = RemoteNodeState.Loading
        viewModelScope.launch {
            val metrics = async { read(targetKeyId, METRICS_PATH) }
            val logs = async { read(targetKeyId, LOGS_PATH) }
            val m = metrics.await()
            val l = logs.await()
            _state.value = if (m.isFailure && l.isFailure) {
                RemoteNodeState.Failed(failureOf(m.exceptionOrNull()))
            } else {
                RemoteNodeState.Loaded(
                    at = Clock.System.now(),
                    metrics = m.getOrNull()?.let(::metricLines).orEmpty(),
                    logs = l.getOrNull()?.let(::logLines).orEmpty(),
                    metricsFailure = m.exceptionOrNull()?.let(::failureOf),
                    logsFailure = l.exceptionOrNull()?.let(::failureOf),
                )
            }
        }
    }

    private suspend fun read(target: String, path: String): Result<JsonElement> = try {
        val answer = api.relay(target, "GET", path)
        if (answer.status in 200..299) Result.success(json.parseToJsonElement(answer.body))
        else Result.failure(RelayRefusal(answer.status, null, answer.body.take(200)))
    } catch (e: RelayRefusal) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(RelayRefusal(0, null, e.message ?: e::class.simpleName.orEmpty()))
    }

    companion object {
        const val METRICS_PATH = "/v1/federation/metrics"
        /** A modest page: the relayed answer is capped near 2 MiB. */
        const val LOGS_PATH = "/v1/telemetry/logs?limit=100"
        private val json = Json { ignoreUnknownKeys = true }

        /** The relay's refusal, in one of the screen's words. */
        fun failureOf(e: Throwable?): RemoteFailure {
            val r = e as? RelayRefusal ?: return RemoteFailure.OTHER
            val text = (r.token ?: "") + " " + r.detail
            return when {
                "mesh_target_unreachable" in text -> RemoteFailure.UNREACHABLE
                "mesh_relay_timeout" in text -> RemoteFailure.TIMEOUT
                "mesh_send_unwired" in text -> RemoteFailure.NO_TRANSPORT
                "mesh_send_failed" in text -> RemoteFailure.SEND_FAILED
                // An older target does not relay these reads, and says so.
                r.status == 403 && "not a relayable" in text -> RemoteFailure.NEEDS_UPDATE
                r.status == 403 -> RemoteFailure.NOT_OWNER
                else -> RemoteFailure.OTHER
            }
        }

        /** The metrics document's scalar fields, flattened, at most [MAX_LINES]. */
        fun metricLines(doc: JsonElement): List<Pair<String, String>> {
            val root = (doc as? JsonObject)?.let { it["data"] as? JsonObject ?: it } ?: return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            fun walk(prefix: String, e: JsonElement) {
                if (out.size >= MAX_LINES) return
                when (e) {
                    is JsonObject -> e.forEach { (k, v) -> walk(if (prefix.isEmpty()) k else "$prefix.$k", v) }
                    is JsonArray -> out += prefix to "${e.size}"
                    is JsonPrimitive -> out += prefix to e.content
                }
            }
            walk("", root)
            return out
        }

        /** Newest first, one line per entry: "time LEVEL message". */
        fun logLines(doc: JsonElement): List<String> {
            val data = (doc as? JsonObject)?.get("data") as? JsonObject ?: doc as? JsonObject ?: return emptyList()
            val logs = data["logs"] as? JsonArray ?: return emptyList()
            return logs.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun f(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
                "${f("timestamp")} ${f("level")} ${f("message")}".trim()
            }
        }

        private const val MAX_LINES = 60
    }
}
