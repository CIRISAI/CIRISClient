package ai.ciris.mobile.shared.models.safety

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CIRISServer `src/safety/age.rs` serializes `AssuranceLevel` with
 * `rename_all = "snake_case"`, so a self-declared band reads
 * `{"band":"minor","level":"self_declared"}`. The client read "self" and every
 * recorded self-declaration failed to decode (CSD-066).
 */
class AgeAssuranceWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aSelfDeclaredBandAsTheNodeWritesItDecodes() {
        val a = json.decodeFromString(AgeAssurance.serializer(), """{"band":"minor","level":"self_declared"}""")
        assertEquals(AgeBand.MINOR, a.band)
        assertEquals(AssuranceLevel.SELF_DECLARED, a.level)
    }

    @Test
    fun theLevelIsWrittenTheWayTheNodeReadsIt() {
        val out = json.encodeToString(AgeAssurance.serializer(), AgeAssurance(AgeBand.ADULT, AssuranceLevel.SELF_DECLARED))
        assertEquals("""{"band":"adult","level":"self_declared"}""", out)
    }
}
