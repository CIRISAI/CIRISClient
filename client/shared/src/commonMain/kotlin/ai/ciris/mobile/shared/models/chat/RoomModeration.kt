package ai.ciris.mobile.shared.models.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a room's moderators stand on one item, a message or a room file
 * (`room_moderation::ModerationView`, server 0.5.243, CC 4.5.5). Absent from a
 * row nobody ever flagged. [flag] is null once the flag was cleared or
 * reversed, while the rest stays as history.
 */
@Serializable
data class RoomModeration(
    /** `infohazard` | `reported`, or null once cleared. */
    val flag: String? = null,
    /** What a reveal consents to: this row, or an earlier version of a message. */
    @SerialName("item_attestation_id")
    val itemAttestationId: String,
    @SerialName("flag_attestation_id")
    val flagAttestationId: String = "",
    @SerialName("flagged_by")
    val flaggedBy: String = "",
    /** Flagged and not revealed: the body or bytes are held back from THIS viewer. */
    val withheld: Boolean = false,
    /** This viewer consented to view it. */
    val revealed: Boolean = false,
    /** `filed` | `upheld` | `partial` | `reversed`, when the flag was appealed. */
    val reconsideration: String? = null,
    @SerialName("reconsideration_attestation_id")
    val reconsiderationAttestationId: String? = null,
)
