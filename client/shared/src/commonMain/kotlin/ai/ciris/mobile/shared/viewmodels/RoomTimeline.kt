package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.drive.DriveEntry
import kotlinx.datetime.Instant

/**
 * One row of a room's chat: a message (or the node's own note), or a file
 * someone added to the room.
 *
 * The node sends no "file added" event; the room's drive listing is the
 * record. Each [FileAdded] row is a drive entry of this room, signed by its
 * author at its `asserted_at`, so the row states only what that entry says.
 */
sealed interface RoomRow {
    val key: String

    data class Message(val message: CegChatMessage) : RoomRow {
        override val key: String get() = message.attestationId
    }

    /**
     * [entry] was added to the room. [mine] is true only when one of this
     * person's own messages in the same transcript names the same author key;
     * otherwise the row names the key.
     */
    data class FileAdded(val entry: DriveEntry, val mine: Boolean) : RoomRow {
        override val key: String get() = "file:" + entry.attestationId
    }
}

/**
 * The room's chat with its added files placed among the messages.
 *
 * - Messages keep the node's order (oldest first, by signed `asserted_at`).
 * - A file a message already carries as its attachment is NOT repeated: that
 *   message is where it was added, and it opens from there.
 * - Notes are not files (they live in Chats); entries of another room are
 *   ignored.
 * - A file goes after every message whose time is at or before its own. One
 *   whose time does not parse goes last rather than being guessed into place.
 */
fun roomTimeline(
    roomId: String,
    messages: List<CegChatMessage>,
    files: List<DriveEntry>,
): List<RoomRow> {
    val carried = messages.mapNotNull { it.attachment }.toSet()
    val myKeys = messages.filter { it.mine }.map { it.speakerKeyId }.filter { it.isNotBlank() }.toSet()
    val added = files
        .asSequence()
        .filter { it.roomId == roomId && !it.isNote && it.attestationId !in carried }
        .distinctBy { it.attestationId }
        .map { RoomRow.FileAdded(it, mine = it.authorKeyId in myKeys) to instantOf(it.assertedAt) }
        .sortedWith(compareBy(nullsLast()) { it.second })
        .toList()
    if (added.isEmpty()) return messages.map { RoomRow.Message(it) }

    val out = ArrayList<RoomRow>(messages.size + added.size)
    var i = 0
    for (m in messages) {
        val at = instantOf(m.assertedAt)
        if (at != null) {
            while (i < added.size && added[i].second.let { it != null && it < at }) {
                out += added[i].first
                i++
            }
        }
        out += RoomRow.Message(m)
    }
    while (i < added.size) out += added[i++].first
    return out
}

private fun instantOf(s: String?): Instant? =
    s?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
