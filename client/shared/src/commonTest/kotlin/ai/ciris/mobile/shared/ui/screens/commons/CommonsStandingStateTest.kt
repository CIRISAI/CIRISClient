package ai.ciris.mobile.shared.ui.screens.commons

import ai.ciris.mobile.shared.models.surfaces.CommonsFold
import ai.ciris.mobile.shared.models.surfaces.CommonsStanding
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CSD-070 §2.3, `StateBlock standing`: eight states, and exactly one of them
 * for any input. The rows here are the card's `states:` guards, one by one.
 */
class CommonsStandingStateTest {
    private val fold = CommonsFold(distinctObjectors = 0, required = 4, rosterSize = 7)
    private fun state(arm: String, fold: CommonsFold? = null, refused: Boolean = false) =
        standingState(loading = false, error = null, standing = CommonsStanding(standing = arm, fold = fold, refused = refused))

    @Test
    fun beforeAnyAnswerTheBlockSaysWhichKindOfNothingItIs() {
        assertEquals(StandingState.IDLE, standingState(false, null, null))
        assertEquals(StandingState.LOADING, standingState(true, null, null))
        assertEquals(StandingState.ERROR, standingState(false, "timeout", null))
    }

    @Test
    fun aRefusalIsItsOwnStateWhateverElseTheBodyCarries() {
        assertEquals(StandingState.REFUSED, state("quiet", fold, refused = true))
        assertEquals(StandingState.REFUSED, state("", refused = true))
    }

    @Test
    fun emptyIsOnlyQuietWithItsFold() {
        assertEquals(StandingState.EMPTY, state("quiet", fold))
        // "Nobody objected" needs the count that says so.
        assertEquals(StandingState.UNKNOWN, state("quiet"))
    }

    @Test
    fun aCountedArmWithoutItsFoldIsUnknownNotPopulated() {
        for (arm in listOf("objected", "stood", "reversed")) {
            assertEquals(StandingState.POPULATED, state(arm, fold), arm)
            assertEquals(StandingState.UNKNOWN, state(arm), arm)
        }
    }

    @Test
    fun theAbsencesEachHaveTheirState() {
        assertEquals(StandingState.UNKNOWN, state("unreadable"))
        assertEquals(StandingState.NOT_ON_THIS_NODE, state("action_unknown"))
        assertEquals(StandingState.NOT_ON_THIS_NODE, state("cohort_unknown"))
        // The node answered and the answer is "this cohort has no reverse quorum".
        assertEquals(StandingState.POPULATED, state("not_governed"))
    }

    @Test
    fun anArmThisClientHasNoNameForIsUnknownAndNeverEmpty() {
        assertEquals(StandingState.UNKNOWN, state("suspended", fold))
        assertEquals(StandingState.UNKNOWN, state(""))
    }

    @Test
    fun theTokensAreTheCardsStateNames() {
        assertEquals(
            listOf("idle", "loading", "error", "refused", "unknown", "not_on_this_node", "empty", "populated"),
            StandingState.entries.map { it.token },
        )
    }
}
