package ai.ciris.mobile.shared.models.federation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * `GET /v1/accord/halt-status` as CIRISServer 0.5.225+ serves it.
 *
 * `halted` is JSON `null` when the latch exists but cannot be read. With a
 * non-nullable Boolean that body threw on decode, and the view model kept the
 * last value it had — a kill-switch card could go on saying "disarmed" while
 * the node could not read its own latch. These pin that every shape the server
 * emits decodes, and that unreadable stays distinguishable from not-halted.
 */
class AccordHaltStatusDecodeTest {
    // The same settings CIRISApiClient decodes with.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(raw: String) = json.decodeFromString(AccordHaltStatusResponse.serializer(), raw)

    @Test
    fun unreadable_latch_decodes_as_unknown_not_as_false() {
        val s = decode(
            """{"halted":null,"lapsed":false,"halt":null,"latch_path":"/h/accord_halt.json",
               "record":null,"releases":[],"release_token_path":"/h/r","error":"parse: eof"}""",
        )
        assertNull(s.halted)
        assertEquals("parse: eof", s.error)
    }

    @Test
    fun in_effect_halt_decodes_with_its_fuse() {
        val s = decode(
            """{"halted":true,"lapsed":false,
               "halt":{"invocation_id":"inv-1","received_at":"2026-10-15T00:00:00Z","fuse_secs":86400,
                       "lapses_at":"2026-10-16T00:00:00Z","confirmed":null,"valid_signers":["a"]},
               "latch_path":"/h/l","record":{"invocation_kind":"constitutional","invocation_id":"inv-1",
               "valid_signers":["a"],"quorum_threshold":1,"latched_at":"2026-10-15T00:00:00Z",
               "received_at":"2026-10-15T00:00:00Z","fuse_secs":86400},"releases":[],"error":null}""",
        )
        assertEquals(true, s.halted)
        assertEquals("inv-1", s.record?.invocationId)
        assertTrue(s.halt != null)
    }

    @Test
    fun lapsed_latch_is_not_halted() {
        val s = decode("""{"halted":false,"lapsed":true,"halt":{"invocation_id":"inv-1"},"record":null}""")
        assertEquals(false, s.halted)
        assertTrue(s.lapsed)
    }

    @Test
    fun pre_225_shape_still_decodes() {
        val s = decode("""{"halted":false,"record":null,"latch_path":"/h/l"}""")
        assertEquals(false, s.halted)
        assertFalse(s.lapsed)
    }
}
