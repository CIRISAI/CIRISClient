package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.AttachmentOutcome
import ai.ciris.mobile.shared.models.AttachmentPolicy
import ai.ciris.mobile.shared.models.InteractResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `/v1/agent/interact` body and the attachment policy, read from the raw
 * text (CIRISClient#217). The generated SDK model has no `attachments`, so
 * decoding through it would drop every per-file verdict the agent sends.
 */
internal object InteractWire {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private data class Data(
        @SerialName("message_id") val messageId: String,
        val response: String,
        val outcome: String? = null,
        val attachments: List<AttachmentOutcome> = emptyList(),
    )

    @Serializable
    private data class Envelope(val data: Data? = null)

    @Serializable
    private data class PolicyEnvelope(val data: AttachmentPolicy? = null)

    /** `SuccessResponse[InteractResponse]` → [InteractResponse]; `attachments` on complete AND timeout. */
    fun parseInteract(raw: String): InteractResponse {
        val data = json.decodeFromString(Envelope.serializer(), raw).data
            ?: throw RuntimeException("API returned null data")
        return InteractResponse(
            response = data.response,
            message_id = data.messageId,
            reasoning = null,
            outcome = data.outcome,
            attachments = data.attachments,
        )
    }

    /** `SuccessResponse[AttachmentPolicy]` → [AttachmentPolicy], or null when the body carries none. */
    fun parsePolicy(raw: String): AttachmentPolicy? = json.decodeFromString(PolicyEnvelope.serializer(), raw).data
}

/** The outcome of reading `GET /v1/agent/attachments/policy`. */
sealed interface AttachmentPolicyRead {
    data class Live(val policy: AttachmentPolicy) : AttachmentPolicyRead
    /** A bare 404: the agent is older than the route. */
    data object AgentTooOld : AttachmentPolicyRead
    data class Failed(val why: String) : AttachmentPolicyRead
}
