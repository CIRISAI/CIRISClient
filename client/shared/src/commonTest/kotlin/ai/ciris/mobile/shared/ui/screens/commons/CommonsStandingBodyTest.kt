package ai.ciris.mobile.shared.ui.screens.commons

import ai.ciris.mobile.shared.models.surfaces.CommonsFold
import ai.ciris.mobile.shared.models.surfaces.CommonsStanding
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-070: the eight zeroes. "The plane could not be read" and "nobody
 * objected" are opposite facts, and only one of them may print counts.
 */
class CommonsStandingBodyTest {
    private val fold = CommonsFold(distinctObjectors = 0, required = 4, rosterSize = 7)

    @Test
    fun the_four_absence_arms_print_no_counts() {
        for (arm in listOf("unreadable", "action_unknown", "cohort_unknown", "not_governed")) {
            assertEquals(StandingBody.NO_COUNTS, standingBody(CommonsStanding(standing = arm)), arm)
        }
    }

    @Test
    fun quiet_is_read_and_clear_not_an_absence() {
        assertEquals(StandingBody.QUIET, standingBody(CommonsStanding(standing = "quiet", fold = fold)))
    }

    @Test
    fun an_arm_without_a_fold_never_prints_counts() {
        // A `quiet` whose fold did not arrive has no counts to show, so it may not show zeroes.
        assertEquals(StandingBody.NO_COUNTS, standingBody(CommonsStanding(standing = "quiet", fold = null)))
    }

    @Test
    fun the_counted_arms_print_counts_and_a_refusal_returns_first() {
        for (arm in listOf("objected", "stood", "reversed")) {
            assertEquals(StandingBody.COUNTED, standingBody(CommonsStanding(standing = arm, fold = fold)), arm)
        }
        assertEquals(
            StandingBody.REFUSED,
            standingBody(CommonsStanding(standing = "quiet", fold = fold, refused = true, refusal = "objection_roster_unresolved")),
        )
    }

    @Test
    fun a_503_unreadable_body_decodes_as_an_answer() {
        // CSD-070 §3: `unreadable` arrives on a 503 WITH a full body. The body is
        // the answer, so it must decode with `fold: null`, not fail.
        val raw = """{"standing":"unreadable","objection_threshold":1,"escalation_respondent_floor":3,"fold":null,"escalation":null,"error":"plane read failed"}"""
        val s = Json { ignoreUnknownKeys = true }.decodeFromString(CommonsStanding.serializer(), raw)
        assertEquals(StandingBody.NO_COUNTS, standingBody(s))
        assertEquals(1, s.objectionThreshold)
    }
}
