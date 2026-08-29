package ai.ciris.mobile.shared.models.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * THE CAPABILITY WIRE CONTRACT, in one place.
 *
 * A node — and, once CIRISAgent#-tier declaration lands, a folded agent —
 * publishes what it can do as a list. This is the only reader of that list in
 * the client, and it is written to be the reference the other repos read rather
 * than each coining their own: CIRISServer declares it in
 * `src/conformance.rs`, CIRISAgent proposes an agent-tier field on
 * `/v1/system/health`, and all three of us have to agree on what silence means.
 *
 * # The four wire shapes, and why four
 *
 * ```
 *   field absent        UNDECLARED     an older peer that never had the field
 *   "field": null       UNDETERMINED   it tried and could not read its own record
 *   "field": []         ABSENT         it read, and holds nothing
 *   "field": [ ... ]    membership     the declared set
 * ```
 *
 * These are four different facts with four different remedies — upgrade the
 * peer, retry, use a different peer, proceed — and collapsing any pair produces
 * a confident wrong answer rather than a missing one. CIRISServer's own field
 * documentation draws the middle line and says why:
 *
 * > `null` when this node could not read its own key record, which is NOT the
 * > same fact as `[]` — "no capabilities" and "could not determine" must not
 * > collapse into one answer, or a client renders a transient directory error
 * > as a node with no authority.
 *
 * The first version of this reader matched only the array form, so `null` read
 * identically to a missing field and the UI told a CURRENT node's operator that
 * their node predates the declaration. That is the collapse, committed by the
 * reader whose purpose was to prevent it — which is why the shapes are
 * enumerated here explicitly instead of falling out of a regex.
 *
 * A fifth state, [CapabilityState.UNREACHABLE], is OUR side failing to ask —
 * transport, a non-success status, a document that will not parse. It never
 * comes from the wire; it is what the caller supplies when there is no document
 * to hand this at all.
 *
 * # Provenance is not merged
 *
 * [parse] takes the FIELD NAME because conferred scopes and agent features are
 * different authorities and must not be unioned. A conferred scope is signed by
 * the trust root and enforced by the node; an agent feature is a property of the
 * running brain that nothing attests. A client that cannot tell them apart
 * cannot tell an operator which remedy applies, and CIRISServer explicitly
 * refuses to launder one into the other:
 *
 * > Conferred only — a locally-detected capability is a different authority and
 * > is not laundered through this list.
 *
 * Read each field separately and keep the results separate.
 *
 * # Not a security boundary
 *
 * `TRUST_ROOT_CAPABILITY_GATE.md` §5: "the server enforces the reality whether
 * or not the client showed it (the warning informs; the gate binds)." Everything
 * this produces is for deciding what to SHOW. A reader that is wrong
 * permissively gets refused by the server anyway; wrong restrictively, an
 * operator sees a working feature marked unavailable — which is why UNDECLARED
 * and UNDETERMINED are never rendered as ABSENT.
 */
object CapabilityWire {

    /** The conferred scopes a node holds. CIRISServer#499, `/v1/federation/conformance`. */
    const val FIELD_CONFERRED = "capabilities"

    /**
     * The agent tier's own features, once CIRISAgent lands it on
     * `/v1/system/health`. A DISTINCT NAME on purpose: that document is the
     * node's health merged with the folded brain's, so a bare `capabilities`
     * there could not be attributed to either tier by a reader holding only the
     * parsed set.
     */
    const val FIELD_AGENT = "agent_capabilities"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Read [field] out of [document].
     *
     * @param document the raw response body. An empty or unparseable one is
     *   [CapabilityState.UNREACHABLE] — we did not get a document, which is not
     *   a statement about the peer.
     */
    fun parse(document: String, field: String = FIELD_CONFERRED): NodeCapabilities {
        if (document.isBlank()) return NodeCapabilities.UNREACHABLE

        val root: JsonObject = runCatching {
            val element = json.parseToJsonElement(document)
            // Both envelopes appear in this ecosystem: bare, and `{"data": ...}`.
            // Prefer `data` when it is an object, because that is what the
            // agent's SuccessResponse wraps everything in.
            val obj = element.jsonObject
            (obj["data"] as? JsonObject) ?: obj
        }.getOrElse {
            // A body we cannot parse tells us nothing about the peer's
            // capabilities — only that we could not read it.
            return NodeCapabilities.UNREACHABLE
        }

        // ABSENT KEY: this peer predates the field.
        val value = root[field] ?: return NodeCapabilities.UNDECLARED

        // EXPLICIT NULL: the peer answered, and its answer is "I do not know".
        if (value is JsonNull) return NodeCapabilities.UNDETERMINED

        // A LIST: the declaration, possibly empty.
        if (value is JsonArray) {
            val ids = value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            // A list carrying non-strings is malformed in a way we should not
            // silently narrow: if anything was dropped, we did not read it.
            if (ids.size != value.size) return NodeCapabilities.UNREACHABLE
            return NodeCapabilities(ids.toSet())
        }

        // Present, but neither null nor a list. We cannot read it, and guessing
        // which of the other three it meant would be inventing an answer.
        return NodeCapabilities.UNREACHABLE
    }
}
