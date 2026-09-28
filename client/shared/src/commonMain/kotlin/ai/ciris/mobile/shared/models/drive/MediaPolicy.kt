package ai.ciris.mobile.shared.models.drive

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The receiver's media policy: which sniffed formats are Tier A here, and
 * their caps.
 *
 * [RECOMMENDED] is CC 5.3.2.6's recommended table with the caps in
 * `FSD/MEDIA_EDGE.md` §3. The constitution makes the table recommended, not
 * normative, and the node publishes its own (`GET /v1/media/policy`,
 * CIRISServer 0.5.217 `src/drive.rs:2978`, body `src/media_gate.rs:294`).
 * A node may NARROW the table and the client must not widen it, so what the
 * sheet renders with is [narrowedBy]: the formats both tables allow, at the
 * smaller of the two caps. Nothing the node says can make this client
 * decode something the recommended set does not.
 */
data class MediaPolicy(
    /** Tier A essences and the byte cap for each. */
    val tierA: Map<String, Long>,
    /** `inline_max_bytes`: the edge's inline storage cap (above it a file is chunked, not refused). Null where the node did not say. */
    val inlineMaxBytes: Long? = null,
    /** `whole_read_max_bytes`: the largest file this node takes whole — the write door's refusal (`drive.too_large`, `src/drive.rs:728`). Null where the node did not say. */
    val wholeReadMaxBytes: Long? = null,
    /** Whether this node makes renditions (CIRISServer#614). `false` means no preview of Tier A media is coming; null where the node did not say. */
    val renditions: Boolean? = null,
    /** `policy_version` off the wire; null for the compiled-in table. */
    val policyVersion: Int? = null,
) {
    /**
     * This table, narrowed to what [node] also allows. A format the node
     * dropped is gone; a cap the node lowered is lowered; a cap the node raised
     * stays at this table's. The node's `inline_max_bytes` and `renditions`
     * are its facts about itself and are taken as given.
     */
    fun narrowedBy(node: MediaPolicy): MediaPolicy = MediaPolicy(
        tierA = tierA.filterKeys { it in node.tierA }.mapValues { (k, cap) -> minOf(cap, node.tierA.getValue(k)) },
        inlineMaxBytes = node.inlineMaxBytes ?: inlineMaxBytes,
        wholeReadMaxBytes = node.wholeReadMaxBytes ?: wholeReadMaxBytes,
        renditions = node.renditions ?: renditions,
        policyVersion = node.policyVersion,
    )

    /**
     * The size an upload is refused BEFORE it leaves this device: the node's
     * whole-read cap when it published one (that is the write door's own
     * gate), else its inline cap, else the compiled-in 1 MiB of a node that
     * publishes nothing.
     */
    val uploadCap: Long get() = wholeReadMaxBytes ?: inlineMaxBytes ?: FileWrite.MAX_INLINE_BYTES

    companion object {
        private const val MB = 1_048_576L

        val RECOMMENDED = MediaPolicy(
            tierA = mapOf(
                "text/plain" to 1 * MB,
                "image/jpeg" to 16 * MB,
                "image/png" to 16 * MB,
                "image/gif" to 25 * MB,
                "image/webp" to 10 * MB,
                "video/mp4" to 100 * MB,
                "audio/mp4" to 16 * MB,
                "audio/mpeg" to 16 * MB,
            ),
            inlineMaxBytes = FileWrite.MAX_INLINE_BYTES,
        )

        /**
         * `GET /v1/media/policy` as 0.5.217 sends it: `tier_a` maps an essence
         * to `{max_bytes, …}`. An entry with no `max_bytes` (audio/mpeg carries
         * only a `conditional`) is allowed at no cap of its own, so under
         * [narrowedBy] the recommended cap stands. Anything unparseable is
         * absent, never guessed — and an absent `tier_a` is an EMPTY table,
         * which renders nothing, because a node that publishes a policy with
         * no Tier A has said so.
         */
        fun fromWire(body: JsonObject): MediaPolicy {
            val tierA = body["tier_a"]?.let { it as? JsonObject }?.mapValues { (_, v) ->
                (v as? JsonObject)?.get("max_bytes")?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE
            } ?: emptyMap()
            return MediaPolicy(
                tierA = tierA,
                inlineMaxBytes = body["inline_max_bytes"]?.jsonPrimitive?.longOrNull,
                wholeReadMaxBytes = body["whole_read_max_bytes"]?.jsonPrimitive?.longOrNull,
                renditions = body["renditions"]?.jsonPrimitive?.booleanOrNull,
                policyVersion = body["policy_version"]?.jsonPrimitive?.longOrNull?.toInt(),
            )
        }
    }
}

/** Where the render policy in force came from — said on the sheet, so a built-in table is never mistaken for the node's word. */
sealed interface PolicySource {
    val policy: MediaPolicy

    /** The node's `GET /v1/media/policy`, narrowed by [MediaPolicy.RECOMMENDED]. */
    data class FromNode(override val policy: MediaPolicy) : PolicySource

    /** The compiled-in table: the node publishes none (predates 0.5.217), or the read failed. [reason] says which. */
    data class BuiltIn(val reason: String?) : PolicySource {
        override val policy: MediaPolicy get() = MediaPolicy.RECOMMENDED
    }
}
