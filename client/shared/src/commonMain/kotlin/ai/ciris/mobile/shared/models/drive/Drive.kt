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

    /** The bytes OPENED and are not UTF-8 text: the one note-only fact (0.5.217 `read_notes`, `src/drive.rs:2945`). Not "can't be opened" — they were. */
    UNREADABLE,

    /** Any other reason the server names (`withdrawn`, `evicted`, `seal_mismatch`, …); the row's `detail` says which, in words. */
    UNOPENED;

    companion object {
        /** The server's `bytes` token. An unknown token is UNOPENED, never HERE: a guess that the bytes are here is the one guess that must not be made. */
        fun of(token: String): ByteState = when (token) {
            "here" -> HERE
            "not_fetched" -> NOT_FETCHED
            "not_granted" -> NOT_GRANTED
            "unreadable" -> UNREADABLE
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

/**
 * `GET /v1/files/{attestation_id}` when the bytes open. Since 0.5.217 the node
 * states the PLAINTEXT digest of what it hands over (`content_digest`,
 * `content_digest_alg`; CIRISServer `src/drive.rs:2088-2089`, #641) — the
 * one thing CC 5.3.2.5's "verify the full SHA-256 before handing bytes to any
 * renderer" can be checked against. Null from an older node, and then the
 * client says so rather than pretending to have verified.
 */
@Serializable
data class OpenedFile(
    @SerialName("attestation_id") val attestationId: String,
    @SerialName("media_type") val mediaType: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    @SerialName("content_digest") val contentDigest: String? = null,
    @SerialName("content_digest_alg") val contentDigestAlg: String? = null,
    @SerialName("bytes_base64") val bytesBase64: String,
)

/**
 * What comparing an opened file's bytes to the digest the node stated found.
 * Computed once, before any renderer sees the bytes; only [Verified] and the
 * two honest "could not check" outcomes reach a sheet, and a [Mismatch] is
 * shown as unreadable — never as an empty file, and never as bytes.
 */
sealed interface DigestCheck {
    /** The bytes hash to what the node said. [digest] is lowercase hex SHA-256. */
    data class Verified(val digest: String) : DigestCheck

    /** The node sent no digest: it predates 0.5.217. Nothing was verified, and the sheet says so. */
    data object NotSent : DigestCheck

    /** The node sent a digest in an algorithm this client cannot compute. Nothing was verified. */
    data class UnknownAlgorithm(val alg: String) : DigestCheck

    /** The bytes are NOT what the node said it sent. */
    data class Mismatch(val expected: String, val actual: String) : DigestCheck

    companion object {
        const val SHA_256 = "sha-256"

        /** Check [bytes] against what [file] states. Pure; the hash is [ai.ciris.mobile.shared.platform.util.Sha256]. */
        fun of(file: OpenedFile, bytes: ByteArray): DigestCheck {
            val expected = file.contentDigest?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return NotSent
            val alg = file.contentDigestAlg?.trim()?.lowercase() ?: SHA_256
            if (alg != SHA_256) return UnknownAlgorithm(alg)
            val actual = ai.ciris.mobile.shared.platform.util.Sha256.hex(bytes)
            return if (actual == expected) Verified(actual) else Mismatch(expected, actual)
        }
    }
}

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
