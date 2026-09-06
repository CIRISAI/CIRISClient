package ai.ciris.mobile.shared.viewmodels

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wizard's step graph — three screens, and no way to declare a fourth that
 * nobody can reach.
 *
 * 2.9.13 declared eleven steps of which FIVE were never a `nextStep()` target:
 * PREFERENCES, OPTIONAL_FEATURES, QUICK_SETUP, NODE_AUTH, VERIFY_SETUP. That was
 * not a tidiness problem — the trace-consent checkbox lived on one of them, so
 * NO production node could ever express trace consent. An unreachable step is a
 * feature that silently does not exist, so [everyStepIsReachable] fails CI
 * rather than letting the next one ship.
 */
class SetupStepFlowTest {

    //: Whether an LLM screen exists. TWO different reasons it might not, and the
    //: step machine must behave identically for both: a node client has no brain
    //: (structural), and an agent build whose user chose to run without an AI
    //: assistant on screen 1 (a choice). See `hasAiStep`.
    private val builds = listOf(true, false)

    @Test
    fun everyStepIsReachable() {
        // Walk the graph from the entry step in every build configuration and
        // collect what is actually visitable.
        val reached = mutableSetOf(SetupStep.YOU)
        for (hasAiStep in builds) {
            var step = SetupStep.YOU
            var guard = 0
            while (step != SetupStep.COMPLETE && guard++ < SetupStep.entries.size + 1) {
                step = nextSetupStep(step, hasAiStep)
                reached += step
            }
        }
        val unreachable = SetupStep.entries.toSet() - reached
        assertTrue(
            unreachable.isEmpty(),
            "these steps are declared but no nextStep() path reaches them, so anything " +
                "on them cannot be used: $unreachable",
        )
    }

    // ── the AI question moved to screen 1 ────────────────────────────────

    @Test
    fun choosingToRunWithoutAiSkipsTheLlmScreenOnAnAgentBuild() {
        // The whole point of moving the question to screen 1: the LLM screen is
        // not merely defaulted past, it is never shown.
        assertFalse(hasAiStep(hasAgent = true, runWithoutAi = true))
        assertEquals(
            SetupStep.COMPLETE,
            nextSetupStep(SetupStep.JOIN_FEDERATION, hasAiStep(true, runWithoutAi = true)),
        )
    }

    @Test
    fun consentBecomesTheFinalStepWhenThereIsNoAiScreen() {
        // If this disagreed with nextSetupStep, Next would advance to a screen
        // that is never rendered and the user would be stranded with no
        // Complete button — the failure mode that makes the two functions one
        // predicate rather than two.
        assertTrue(isFinalSetupStep(SetupStep.JOIN_FEDERATION, hasAiStep(true, runWithoutAi = true)))
        assertFalse(isFinalSetupStep(SetupStep.AI, hasAiStep(true, runWithoutAi = true)))
    }

    @Test
    fun backFromCompleteSkipsTheLlmScreenToo() {
        // The mirror has to skip what the forward walk skipped, or Back lands on
        // a screen the user never saw.
        assertEquals(
            SetupStep.JOIN_FEDERATION,
            previousSetupStep(SetupStep.COMPLETE, hasAiStep(true, runWithoutAi = true)),
        )
    }

    @Test
    fun aNodeClientIsUnaffectedByTheAiAnswer() {
        // It has no brain either way, so the answer cannot change its flow —
        // and the question is not asked there at all.
        assertFalse(hasAiStep(hasAgent = false, runWithoutAi = false))
        assertFalse(hasAiStep(hasAgent = false, runWithoutAi = true))
    }

    @Test
    fun wantingAnAiOnAnAgentBuildStillVisitsTheLlmScreen() {
        assertTrue(hasAiStep(hasAgent = true, runWithoutAi = false))
        assertEquals(
            SetupStep.AI,
            nextSetupStep(SetupStep.JOIN_FEDERATION, hasAiStep(true, runWithoutAi = false)),
        )
    }

    @Test
    fun theAgentBuildVisitsAllThreeScreens() {
        assertEquals(SetupStep.JOIN_FEDERATION, nextSetupStep(SetupStep.YOU, hasAiStep = true))
        assertEquals(SetupStep.AI, nextSetupStep(SetupStep.JOIN_FEDERATION, hasAiStep = true))
        assertEquals(SetupStep.COMPLETE, nextSetupStep(SetupStep.AI, hasAiStep = true))
    }

    @Test
    fun theNodeClientSkipsTheAiScreen() {
        // The node client has no brain to configure — but it must still pass
        // through the federation consent screen.
        assertEquals(SetupStep.JOIN_FEDERATION, nextSetupStep(SetupStep.YOU, hasAiStep = false))
        assertEquals(SetupStep.COMPLETE, nextSetupStep(SetupStep.JOIN_FEDERATION, hasAiStep = false))
    }

    @Test
    fun consentIsOnThePathInEveryBuild() {
        // The 0.3 regression in one assertion: the consent screen must not be
        // skippable by any build configuration.
        for (hasAiStep in builds) {
            var step = SetupStep.YOU
            val path = mutableListOf(step)
            while (step != SetupStep.COMPLETE) {
                step = nextSetupStep(step, hasAiStep)
                path += step
            }
            assertTrue(
                SetupStep.JOIN_FEDERATION in path,
                "hasAiStep=$hasAiStep never reaches the consent screen: $path",
            )
        }
    }

    @Test
    fun backMirrorsForwardExactly() {
        for (hasAiStep in builds) {
            var step = SetupStep.YOU
            while (step != SetupStep.COMPLETE) {
                val forward = nextSetupStep(step, hasAiStep)
                assertEquals(
                    step,
                    previousSetupStep(forward, hasAiStep),
                    "back from $forward must return to $step (hasAiStep=$hasAiStep)",
                )
                step = forward
            }
        }
    }

    @Test
    fun theFirstStepHasNoBack() {
        for (hasAiStep in builds) {
            assertEquals(SetupStep.YOU, previousSetupStep(SetupStep.YOU, hasAiStep))
        }
    }

    @Test
    fun completeIsTerminal() {
        for (hasAiStep in builds) {
            assertEquals(SetupStep.COMPLETE, nextSetupStep(SetupStep.COMPLETE, hasAiStep))
        }
    }

    @Test
    fun theFinalStepIsTheOneBeforeComplete() {
        for (hasAiStep in builds) {
            val finals = SetupStep.entries.filter { isFinalSetupStep(it, hasAiStep) }
            assertEquals(1, finals.size, "exactly one step may be final (hasAiStep=$hasAiStep)")
            assertEquals(SetupStep.COMPLETE, nextSetupStep(finals.single(), hasAiStep))
        }
    }

    @Test
    fun theStepGraphHasNoBranches() {
        // §7: one `when`, not two. The old isNodeFlow fork produced
        // byte-identical transitions on both sides — a duplicate pretending to
        // be a choice. The ONLY legitimate difference between builds is the AI
        // screen, so every other transition must agree.
        for (step in SetupStep.entries) {
            if (step == SetupStep.JOIN_FEDERATION) continue // the one real difference
            assertEquals(
                nextSetupStep(step, hasAiStep = true),
                nextSetupStep(step, hasAiStep = false),
                "$step must transition identically in both builds",
            )
        }
    }
}

/**
 * What blocks Next on each screen, and — as importantly — what does not.
 */
class SetupStepValidationTest {

    /** A YOU screen with every blocking field satisfied, age included. */
    private fun filledOutYouScreen() = SetupFormState(
        currentStep = SetupStep.YOU,
        username = "founder",
        userPassword = "correct-horse",
        userPasswordConfirm = "correct-horse",
        federationIdentity = FederationIdentitySetupState(label = "eric-moore"),
        // The age question is REQUIRED, so a "nothing else blocks" baseline has to
        // answer it. Adult, because that is the branch with no further obligation:
        // minor and declined both pull in the §2580 stewardship rule.
        ageRange = AgeRangeSetupState(selectedBandToken = "adult"),
    )

    @Test
    fun screenOneNeedsAFedIdNameAnAccountAndNothingElse() {
        assertTrue(filledOutYouScreen().canProceedFromCurrentStep())
    }

    @Test
    fun screenOneBlocksOnAMissingFedIdName() {
        val state = filledOutYouScreen().copy(federationIdentity = FederationIdentitySetupState())
        assertFalse(state.canProceedFromCurrentStep())
        assertTrue(state.getStepValidationError() != null)
    }

    @Test
    fun screenOneBlocksOnMismatchedPasswords() {
        val state = filledOutYouScreen().copy(userPasswordConfirm = "something-else")
        assertFalse(state.canProceedFromCurrentStep())
    }

    // ── The age question: required to ANSWER, free to DECLINE ────────────────
    //
    // This replaces screenOneDoesNotRequireAnAgeBand, which asserted that a null
    // band must not block. That test and the required-age branch added in 2.9.22
    // contradicted each other for three releases without anyone noticing, because
    // CI does not run this suite (#1074). The contradiction was real and not just
    // a stale test: AgeRangeSetupState had no way to say "declined", so declining
    // and never-asked were the same value and no rule could tell them apart.
    //
    // Settled policy: the question must be ANSWERED, and "prefer not to say" is a
    // valid answer that costs the subject nothing. It is not treated as adulthood
    // — silence never buys adult privileges — so a declining subject gets exactly
    // the treatment a declared minor gets, stewardship included.

    @Test
    fun theAgeQuestionMustBeAnswered() {
        val notAsked = filledOutYouScreen().copy(ageRange = AgeRangeSetupState())
        assertFalse(
            notAsked.canProceedFromCurrentStep(),
            "an unanswered age question must block — it seeds the fed-ID and the stewardship gate",
        )
        assertTrue(notAsked.getStepValidationError() != null)
    }

    @Test
    fun decliningIsAnAnswerAndDoesNotTrap() {
        // The whole point of the `declined` flag: this state must be reachable and
        // must not dead-end. Stewardship is requested because declining is treated
        // as under-18 — see the next test.
        val declined = filledOutYouScreen().copy(
            ageRange = AgeRangeSetupState(declined = true),
            minorStewardship = MinorStewardshipState(requested = true),
        )
        assertTrue(
            declined.canProceedFromCurrentStep(),
            "declining to state an age is a right; it must never be a dead end",
        )
    }

    @Test
    fun decliningIsTreatedAsAChild() {
        val declined = filledOutYouScreen().copy(ageRange = AgeRangeSetupState(declined = true))
        assertTrue(
            declined.isMinorBand(),
            "a subject who has not stated an age is treated as a child — silence must never " +
                "yield adult treatment",
        )
    }

    @Test
    fun decliningCarriesTheSameStewardshipDutyAsADeclaredMinor() {
        // If declining skipped §2580 it would be a cheaper route to self-claiming
        // ownership than declaring adult — declining must not be an escape hatch.
        val declined = filledOutYouScreen().copy(ageRange = AgeRangeSetupState(declined = true))
        assertFalse(declined.canProceedFromCurrentStep())
        assertTrue(declined.getStepValidationError() != null)
    }

    @Test
    fun declinedIsDistinctFromNotYetAsked() {
        // The distinction the old model could not express, pinned so it cannot be
        // collapsed back into a single nullable.
        assertFalse(AgeRangeSetupState().declined)
        assertTrue(AgeRangeSetupState(declined = true).declined)
        assertEquals(null, AgeRangeSetupState(declined = true).selectedBandToken,
            "declining must NOT be recorded as a self-declared band — the subject never said it")
    }

    @Test
    fun aStatedAdultBandIsNotTreatedAsAChild() {
        assertFalse(filledOutYouScreen().isMinorBand())
    }

    @Test
    fun consentScreenNeverBlocks() {
        // Every toggle has a stated default and declining all of them is valid.
        val state = SetupFormState(
            currentStep = SetupStep.JOIN_FEDERATION,
            announceOwnership = false,
            accordMetricsConsent = false,
            traceAnalyze = false,
            shareLocationInTraces = false,
        )
        assertTrue(state.canProceedFromCurrentStep())
        assertEquals(null, state.getStepValidationError())
    }

    @Test
    fun theUntouchedAiDefaultDoesNotProceed() {
        // provider "OpenAI" with no key is the dead end §0.4 describes.
        val state = SetupFormState(currentStep = SetupStep.AI)
        assertFalse(state.canProceedFromCurrentStep())
        assertTrue(state.getStepValidationError() != null)
    }

    @Test
    fun keylessProvidersProceedWithoutAKey() {
        for (provider in listOf("local", "local_inference", "mobile_local", "localai")) {
            val state = SetupFormState(currentStep = SetupStep.AI, llmProvider = provider)
            assertTrue(state.canProceedFromCurrentStep(), "$provider must not require a key")
        }
    }

    @Test
    fun runWithoutAiIsACompleteAnswer() {
        val state = SetupFormState(currentStep = SetupStep.AI, runWithoutAi = true)
        assertTrue(state.canProceedFromCurrentStep())
        assertEquals(null, state.getStepValidationError())
    }

    @Test
    fun aiIsNotOffByDefault() {
        // §3 CRITICAL: an option, never a default.
        assertFalse(SetupFormState().runWithoutAi)
    }

    @Test
    fun announcingAndSharingAreOnByDefault() {
        // Announce is the floor for service; declining it silently unserves the
        // node, so it must not be the default. `analyze` is on but declinable.
        val fresh = SetupFormState()
        assertTrue(fresh.announceOwnership)
        assertTrue(fresh.accordMetricsConsent)
        assertTrue(fresh.traceAnalyze)
        assertFalse(fresh.shareLocationInTraces, "location is required:false and defaults off")
    }

    @Test
    fun theWizardStartsOnScreenOne() {
        assertEquals(SetupStep.YOU, SetupFormState().currentStep)
    }
}
