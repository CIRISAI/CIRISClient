package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.platform.AttachmentTypes
import ai.ciris.mobile.shared.platform.PickedFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the agent did with one attached file — `InteractResponse.attachments[]`
 * (CIRISAgent `api_attachments.AttachmentOutcome`, branch
 * `fix/interact-attachments` @ 380455879, api_attachments.py:108-120). Present
 * on the complete AND the timeout response; empty when nothing was attached
 * or the agent predates the field.
 */
@Serializable
data class AttachmentOutcome(
    /** Position within images[] or documents[], whichever [kind] names. */
    val index: Int,
    /** "image" | "document". */
    val kind: String,
    val filename: String? = null,
    /** The type the agent decided on. */
    @SerialName("media_type") val mediaType: String = "",
    /** "accepted" | "refused". */
    val status: String,
    /** A stable refusal id ([AttachmentReason]) when refused, else null. */
    val reason: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("limit_bytes") val limitBytes: Long? = null,
    @SerialName("extracted_chars") val extractedChars: Int? = null,
    val truncated: Boolean = false,
) {
    val refused: Boolean get() = status == "refused"

    /** The localized note shown on this file's chip, or null when there is nothing to say. */
    fun noteKey(): String? = when {
        refused -> "mobile.interact_attachment_reason_" + (reason?.takeIf { it in AttachmentReason.ALL } ?: "other")
        truncated -> "mobile.interact_attachment_truncated"
        else -> null
    }
}

/** The agent's refusal ids (api_attachments.py:87-105); each is localized as `mobile.interact_attachment_reason_<id>`. */
object AttachmentReason {
    const val TOO_LARGE = "too_large"
    const val TOO_MANY = "too_many"
    const val UNSUPPORTED_TYPE = "unsupported_type"
    const val TYPE_MISMATCH = "type_mismatch"
    const val UNREADABLE = "unreadable"
    const val NO_TEXT = "no_text"
    const val PARSER_UNAVAILABLE = "parser_unavailable"
    const val TEXT_LIMIT_REACHED = "text_limit_reached"

    val ALL = listOf(TOO_LARGE, TOO_MANY, UNSUPPORTED_TYPE, TYPE_MISMATCH, UNREADABLE, NO_TEXT, PARSER_UNAVAILABLE, TEXT_LIMIT_REACHED)
}

/**
 * `GET /v1/agent/attachments/policy` (api_attachments.py:123-132, routes/agent.py:1350-1357):
 * the limits `/interact` applies. The chat paperclip's picker types, its
 * re-check before send and its count/size pre-refusal all come from this.
 *
 * [maxTotal] is client-side only: the live policy counts per kind, so it is
 * null there; [CLIENT_FALLBACK] keeps the client's old total of
 * [PickedFile.MAX_ATTACHMENTS].
 */
@Serializable
data class AttachmentPolicy(
    @SerialName("image_types") val imageTypes: List<String>,
    @SerialName("image_max_bytes") val imageMaxBytes: Long,
    @SerialName("max_images") val maxImages: Int,
    @SerialName("document_types") val documentTypes: List<String>,
    @SerialName("document_max_bytes") val documentMaxBytes: Long,
    @SerialName("max_documents") val maxDocuments: Int,
    @SerialName("max_extracted_chars") val maxExtractedChars: Int = 0,
    @kotlinx.serialization.Transient val maxTotal: Int? = null,
) {
    /** Every type the picker offers, images first. */
    val allTypes: List<String> get() = (imageTypes + documentTypes).map { AttachmentTypes.essence(it) }.distinct()

    fun isImageType(mediaType: String): Boolean = AttachmentTypes.essence(mediaType) in imageTypes.map { AttachmentTypes.essence(it) }

    fun accepts(mediaType: String): Boolean = AttachmentTypes.essence(mediaType) in allTypes

    /**
     * The per-file limit for [mediaType]'s kind, never above
     * [PickedFile.MAX_FILE_SIZE_BYTES]: the picker reads no more than that
     * (the body is still one inline base64 request), whatever the agent allows.
     */
    fun maxBytesFor(mediaType: String): Long =
        minOf(if (isImageType(mediaType)) imageMaxBytes else documentMaxBytes, PickedFile.MAX_FILE_SIZE_BYTES)

    /** How many of [mediaType]'s kind one message may carry. */
    fun maxCountFor(mediaType: String): Int = if (isImageType(mediaType)) maxImages else maxDocuments

    /** Whether [attached] leaves room for at least one more file of some kind. */
    fun hasRoom(attached: List<PickedFile>): Boolean {
        if (maxTotal != null && attached.size >= maxTotal) return false
        val images = attached.count { isImageType(it.mediaType) }
        return images < maxImages || attached.size - images < maxDocuments
    }

    companion object {
        /**
         * The client's own constants, used when the agent has no policy route
         * (older than CIRISAgent `fix/interact-attachments`: a bare 404) or it
         * could not be read. Attaching is never blocked on a missing policy.
         */
        val CLIENT_FALLBACK = AttachmentPolicy(
            imageTypes = PickedFile.ALLOWED_MIME_TYPES.filter { it.startsWith("image/") },
            imageMaxBytes = PickedFile.MAX_FILE_SIZE_BYTES,
            maxImages = PickedFile.MAX_ATTACHMENTS,
            documentTypes = PickedFile.ALLOWED_MIME_TYPES.filterNot { it.startsWith("image/") },
            documentMaxBytes = PickedFile.MAX_FILE_SIZE_BYTES,
            maxDocuments = PickedFile.MAX_ATTACHMENTS,
            maxTotal = PickedFile.MAX_ATTACHMENTS,
        )
    }
}

/** Where the policy in force came from. */
enum class AttachmentPolicySource {
    /** Read from the agent. */
    AGENT,
    /** The agent answered a bare 404: older than the route. Client constants. */
    FALLBACK_AGENT_TOO_OLD,
    /** Not read yet, or the read failed. Client constants. */
    FALLBACK_UNAVAILABLE,
}

/**
 * One file as it went out on a SENT message, with the agent's verdict on it
 * once the response names it ([outcome] stays null for an agent that reports
 * none).
 */
@Serializable
data class SentAttachment(
    val name: String,
    val mediaType: String,
    val outcome: AttachmentOutcome? = null,
)
