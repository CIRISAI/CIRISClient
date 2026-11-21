package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** CSD-003 §2.1a: armed and the halt's standing are two facts; not halted is never "disarmed". */
class KillSwitchTest {

    private fun status(raw: String) =
        Json { ignoreUnknownKeys = true }.decodeFromString(AccordHaltStatusResponse.serializer(), raw)

    @Test
    fun standingIsReadFromTheLatchAndItsFuse() {
        assertEquals(HaltStanding.Reading, KillSwitch.read(null, loading = true).standing)
        assertEquals(HaltStanding.Unknown, KillSwitch.read(null, loading = false).standing)
        assertEquals(HaltStanding.Unknown, KillSwitch.read(status("""{"halted":null,"error":"unreadable"}"""), false).standing)
        assertEquals(HaltStanding.NotHalted, KillSwitch.read(status("""{"halted":false}"""), false).standing)
        assertEquals(HaltStanding.Lapsed, KillSwitch.read(status("""{"halted":false,"lapsed":true}"""), false).standing)
        assertEquals(
            HaltStanding.Lone("2026-11-22T00:00:00Z"),
            KillSwitch.read(status("""{"halted":true,"halt":{"fuse_secs":86400,"lapses_at":"2026-11-22T00:00:00Z","confirmed":false}}"""), false).standing,
        )
        assertEquals(HaltStanding.Lone(null), KillSwitch.read(status("""{"halted":true}"""), false).standing)
        assertEquals(
            HaltStanding.Confirmed,
            KillSwitch.read(status("""{"halted":true,"halt":{"confirmed":true,"lapses_at":"2026-11-22T00:00:00Z"}}"""), false).standing,
        )
    }

    @Test
    fun armedIsNeverDerivedFromNotHalted() {
        // No node reports armed yet (0.6: pending), so "not halted" says nothing about reach.
        for (raw in listOf("""{"halted":false}""", """{"halted":true}""", """{"halted":false,"lapsed":true}""")) {
            assertEquals(HaltArmed.UNKNOWN, KillSwitch.read(status(raw), false).armed, raw)
        }
        assertEquals(HaltArmed.UNKNOWN, KillSwitch.read(null, false).armed)
    }
}
