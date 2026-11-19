package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.chat.CegChatMessage
import ai.ciris.mobile.shared.models.drive.DriveEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Added a file to the room" rows (2026-11-18, Eric): the node sends no such
 * event, so the room's drive listing is placed among the messages by signed
 * time. What must hold: order by time across both lists, no duplicate for a
 * file a message already carries, no other room's files, no notes, and "you"
 * only when the transcript itself shows the author key is this person's.
 */
class RoomTimelineTest {

    private val room = "room-1"

    private fun msg(id: String, at: String, author: String = "alice-key", mine: Boolean = false, attachment: String? = null) =
        CegChatMessage(attestationId = id, author = author, assertedAt = at, mine = mine, communityId = room, attachment = attachment)

    private fun file(id: String, at: String, author: String = "bob-key", roomId: String = room, cohort: String = "community", name: String? = "cats.txt") =
        DriveEntry(cohort = cohort, roomId = roomId, attestationId = id, authorKeyId = author, assertedAt = at, filename = name, mediaType = "text/plain", bytes = "here")

    private fun keys(rows: List<RoomRow>) = rows.map { it.key }

    @Test
    fun filesSitAmongMessagesBySignedTime() {
        val rows = roomTimeline(
            room,
            listOf(msg("m1", "2026-11-18T16:40:00Z"), msg("m2", "2026-11-18T16:43:08.289+00:00")),
            listOf(file("f1", "2026-11-18T16:41:00Z"), file("f0", "2026-11-18T16:39:00Z"), file("f2", "2026-11-18T16:50:00Z")),
        )
        assertEquals(listOf("file:f0", "m1", "file:f1", "m2", "file:f2"), keys(rows))
    }

    @Test
    fun aFileAtTheSameMomentAsAMessageComesAfterIt() {
        val rows = roomTimeline(room, listOf(msg("m1", "2026-11-18T16:40:00Z")), listOf(file("f1", "2026-11-18T16:40:00.000+00:00")))
        assertEquals(listOf("m1", "file:f1"), keys(rows))
    }

    @Test
    fun aFileAMessageAlreadyCarriesIsNotRepeated() {
        val rows = roomTimeline(
            room,
            listOf(msg("m1", "2026-11-18T16:40:00Z", attachment = "f1")),
            listOf(file("f1", "2026-11-18T16:39:00Z"), file("f2", "2026-11-18T16:41:00Z")),
        )
        assertEquals(listOf("m1", "file:f2"), keys(rows))
    }

    @Test
    fun otherRoomsAndNotesAreLeftOut() {
        val rows = roomTimeline(
            room,
            listOf(msg("m1", "2026-11-18T16:40:00Z")),
            listOf(
                file("elsewhere", "2026-11-18T16:41:00Z", roomId = "room-2"),
                file("note", "2026-11-18T16:41:00Z", cohort = "self", name = null),
            ),
        )
        assertEquals(listOf("m1"), keys(rows))
    }

    @Test
    fun youOnlyWhenTheTranscriptShowsTheKeyIsYours() {
        val rows = roomTimeline(
            room,
            listOf(msg("m1", "2026-11-18T16:40:00Z", author = "me-key", mine = true)),
            listOf(file("mine", "2026-11-18T16:41:00Z", author = "me-key"), file("theirs", "2026-11-18T16:42:00Z", author = "bob-key")),
        )
        val added = rows.filterIsInstance<RoomRow.FileAdded>().associate { it.entry.attestationId to it.mine }
        assertEquals(mapOf("mine" to true, "theirs" to false), added)
    }

    @Test
    fun aTimeThatDoesNotParseGoesLastNotGuessedIntoPlace() {
        val rows = roomTimeline(
            room,
            listOf(msg("m1", "2026-11-18T16:40:00Z"), msg("m2", "2026-11-18T16:45:00Z")),
            listOf(file("bad", "yesterday"), file("ok", "2026-11-18T16:41:00Z")),
        )
        assertEquals(listOf("m1", "file:ok", "m2", "file:bad"), keys(rows))
    }

    @Test
    fun noFilesLeavesTheTranscriptAsTheNodeSentIt() {
        val ms = listOf(msg("m2", "2026-11-18T16:45:00Z"), msg("m1", "2026-11-18T16:40:00Z"))
        assertEquals(listOf("m2", "m1"), keys(roomTimeline(room, ms, emptyList())))
    }
}
