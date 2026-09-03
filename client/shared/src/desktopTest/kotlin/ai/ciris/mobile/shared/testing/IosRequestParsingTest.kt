package ai.ciris.mobile.shared.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The framing rule the iOS test server got wrong (CIRISClient#35).
 *
 * `TestAutomationServer.ios.kt` read once into a buffer and parsed whatever had
 * arrived. TCP does not promise that headers and body land in the same segment,
 * and httpx — which the QA gate uses — writes them separately. So every GET
 * worked, every POST decoded an empty body, and CIRISAgent had to route iOS
 * through a one-segment transport to get the wizard running at all.
 *
 * The fix is a read loop, and the two rules it depends on are pure: find the
 * header terminator by BYTE offset, and take exactly Content-Length bytes after
 * it. Those are asserted here, on the desktop JVM, because iosMain has no host
 * that can run a test — the algorithm is what is portable, so the algorithm is
 * what is pinned.
 */
class IosRequestParsingTest {

    /** Byte-offset search for CRLFCRLF — the iOS server's `indexOfHeaderEnd`. */
    private fun indexOfHeaderEnd(buf: ByteArray, len: Int): Int {
        var i = 0
        while (i + 3 < len) {
            if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte() &&
                buf[i + 2] == 13.toByte() && buf[i + 3] == 10.toByte()
            ) return i
            i++
        }
        return -1
    }

    private fun contentLengthOf(headerText: String): Int =
        headerText.split("\r\n").drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
            ?: 0

    /** Feed bytes one segment at a time, exactly as the server's loop consumes them. */
    private fun parse(segments: List<String>): Pair<String, String>? {
        val buffer = ByteArray(65536)
        var filled = 0
        var headerEnd = -1
        val feed = segments.map { it.encodeToByteArray() }.iterator()

        while (headerEnd < 0 && feed.hasNext()) {
            val seg = feed.next()
            seg.copyInto(buffer, filled)
            filled += seg.size
            headerEnd = indexOfHeaderEnd(buffer, filled)
        }
        if (headerEnd < 0) return null

        val headerText = buffer.decodeToString(0, headerEnd)
        val path = headerText.split("\r\n")[0].split(" ").getOrNull(1) ?: return null
        val contentLength = contentLengthOf(headerText)
        val bodyStart = headerEnd + 4

        while (filled - bodyStart < contentLength && feed.hasNext()) {
            val seg = feed.next()
            seg.copyInto(buffer, filled)
            filled += seg.size
        }
        val body = if (filled > bodyStart) {
            buffer.decodeToString(bodyStart, minOf(filled, bodyStart + contentLength).coerceAtLeast(bodyStart))
        } else ""
        return path to body
    }

    @Test
    fun a_body_arriving_in_a_second_segment_is_read() {
        // The exact failure: httpx writes headers, then the body.
        val r = parse(listOf(
            "POST /click HTTP/1.1\r\nHost: localhost\r\nContent-Length: 28\r\n\r\n",
            """{"testTag":"btn_login_submit"}""".let { it.substring(0, 28) },
        ))
        assertEquals("/click", r?.first)
        assertTrue(r?.second?.startsWith("{") == true, "body was empty — the #35 defect: ${r?.second}")
    }

    @Test
    fun a_single_segment_request_still_works() {
        // The workaround transport CIRISAgent added must keep working after the
        // fix, or removing it becomes a flag day.
        val body = """{"testTag":"btn_x"}"""
        val r = parse(listOf("POST /click HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body"))
        assertEquals(body, r?.second)
    }

    @Test
    fun a_body_split_across_three_segments_is_reassembled() {
        val body = """{"testTag":"input_username","text":"qaadmin"}"""
        val r = parse(listOf(
            "POST /input HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n",
            body.substring(0, 10),
            body.substring(10),
        ))
        assertEquals(body, r?.second)
    }

    @Test
    fun a_get_with_no_body_reads_empty_not_garbage() {
        val r = parse(listOf("GET /tree HTTP/1.1\r\nHost: localhost\r\n\r\n"))
        assertEquals("/tree", r?.first)
        assertEquals("", r?.second)
    }

    @Test
    fun trailing_bytes_beyond_content_length_are_not_swallowed_into_the_body() {
        // A pipelined or padded write must not corrupt the JSON we decode.
        val body = """{"a":1}"""
        val r = parse(listOf(
            "POST /click HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n",
            body + "GARBAGE",
        ))
        assertEquals(body, r?.second)
    }

    @Test
    fun the_header_terminator_is_found_by_byte_not_by_character() {
        // A non-ASCII header value makes a character index and a byte offset
        // disagree; slicing the body at the character index would cut
        // mid-character. Headers are ASCII in practice — which is exactly the
        // kind of "in practice" that stops being true quietly.
        val body = """{"ok":true}"""
        val headers = "POST /click HTTP/1.1\r\nX-Note: café — dash\r\nContent-Length: ${body.length}\r\n\r\n"
        val r = parse(listOf(headers, body))
        assertEquals(body, r?.second, "byte vs character offset diverged")
    }

    @Test
    fun a_content_length_header_is_matched_case_insensitively() {
        val body = """{"a":1}"""
        val r = parse(listOf("POST /click HTTP/1.1\r\ncontent-length: ${body.length}\r\n\r\n", body))
        assertEquals(body, r?.second)
    }
}
