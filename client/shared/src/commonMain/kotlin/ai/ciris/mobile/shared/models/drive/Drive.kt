package ai.ciris.mobile.shared.models.drive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The drive plane (CIRISServer 0.5.215, `src/drive.rs`): one door to write a
 * file at a cohort, one listing of everything this person can reach, and
 * notes to self.
 *
 * Bare JSON on the wire, not the federation `{data: …}` envelope, and every
 * route is owner-session gated.
 */

/** Where a file's bytes are, relative to THIS device. The row can be here when the bytes are not. */
enum class ByteState {
    /** The bytes open on this node. */
    HERE,

    /** The file exists and its bytes are on another device; asking again later may succeed (409). */
    NOT_FETCHED,

    /** This device holds no grant for these bytes (403). A different truth from NOT_FETCHED. */
    NOT_GRANTED,

    /** Any other reason the server names; the row's `detail` says which, in words. */
    UNOPENED;

    companion object {
        /** The server's `bytes` token. An unknown token is UNOPENED, never HERE: a guess that the bytes are here is the one guess that must not be made. */
        fun of(token: String): ByteState = when (token) {
            "here" -> HERE
            "not_fetched" -> NOT_FETCHED
            "not_granted" -> NOT_GRANTED
            else -> UNOPENED
        }
    }
}

/** `GET /v1/drive`: the rooms the listing spans, and the rows. */
@Serializable
data class DriveListing(
    val rooms: List<DriveRoom> = emptyList(),
    val entries: List<DriveEntry> = emptyList(),
)

@Serializable
data class DriveRoom(
    /** `self` | `family` | `community`. */
    val cohort: String,
    val room: String,
)

@Serializable
data class DriveEntry(
    /** The cohort this row was listed from: `self` | `family` | `community`. */
    val cohort: String,
    /** The room id to pass back to [OpenedFile]'s `GET /v1/files/{id}`. */
    @SerialName("room_id") val roomId: String,
    @SerialName("attestation_id") val attestationId: String,
    @SerialName("author_key_id") val authorKeyId: String,
    @SerialName("asserted_at") val assertedAt: String,
    val filename: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    /** `here`, or why not. Read it through [byteState]. */
    val bytes: String,
    /** The plain-words version of [bytes], written by the server for a client that renders state. */
    val detail: String = "",
) {
    val byteState: ByteState get() = ByteState.of(bytes)

    /**
     * A note to self, not a file: an UNNAMED `text/plain` row in the self room.
     * Both conditions, exactly as the node's `GET /v1/notes` reads them — a named
     * `.txt` the person uploaded stays a file, and nothing here guesses.
     */
    val isNote: Boolean
        get() = cohort == "self" && filename == null && mediaType?.startsWith("text/plain") == true
}

/** `GET /v1/files/{attestation_id}` when the bytes open. */
@Serializable
data class OpenedFile(
    @SerialName("attestation_id") val attestationId: String,
    @SerialName("media_type") val mediaType: String? = null,
    val filename: String? = null,
    @SerialName("bytes_base64") val bytesBase64: String,
)

/** `POST /v1/files`. Inline bytes only: the edge caps a write at [MAX_INLINE_BYTES]. */
@Serializable
data class FileWrite(
    /** `self` | `family` | `community`. */
    val cohort: String,
    /** The family or community id; omitted for `self`, where the room IS the owner. */
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("bytes_base64") val bytesBase64: String,
    @SerialName("media_type") val mediaType: String,
    val filename: String? = null,
) {
    companion object {
        /** The edge's inline cap. A larger file is refused BEFORE upload, in words, rather than after, as a 413. */
        const val MAX_INLINE_BYTES: Long = 1L * 1024 * 1024
    }
}

/**
 * `POST /v1/files` answered. Three facts the server keeps apart, and so does
 * the UI: the row reached the audience ([crossed]); the bytes can be fetched
 * ([addressed]); and some devices hold no grant ([excluded]).
 */
@Serializable
data class FileWritten(
    @SerialName("attestation_id") val attestationId: String,
    val addressed: Boolean,
    val cohort: String,
    val room: String,
    val tier: String,
    /** **False means the file reached nobody**: it is local-tier and invisible to other devices AND to the drive. */
    val crossed: Boolean,
    val excluded: List<String> = emptyList(),
    val granted: Int = 0,
)

/** `GET /v1/notes`: notes to self, in the self room. */
@Serializable
data class NoteListing(
    val room: String = "",
    val notes: List<Note> = emptyList(),
)

@Serializable
data class Note(
    @SerialName("attestation_id") val attestationId: String,
    @SerialName("asserted_at") val assertedAt: String,
    @SerialName("author_key_id") val authorKeyId: String,
    /** The note, when this device can open it; null with [state] saying why. */
    val body: String? = null,
    val state: String,
    val detail: String = "",
) {
    /**
     * The notes surface says `open` where the drive listing says `here`: same
     * fact, different word on the wire (CIRISServer `drive.rs`). Every other
     * token is shared, and an unknown one is still never "here".
     */
    val byteState: ByteState get() = ByteState.of(if (state == "open") "here" else state)
}

@Serializable
data class NoteWrite(val body: String)
