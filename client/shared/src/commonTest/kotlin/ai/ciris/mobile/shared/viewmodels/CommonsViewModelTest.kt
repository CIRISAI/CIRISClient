package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.api.CommonsApi
import ai.ciris.mobile.shared.models.surfaces.CommonsBallotRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsDismissalQuorum
import ai.ciris.mobile.shared.models.surfaces.CommonsDismissalRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsFold
import ai.ciris.mobile.shared.models.surfaces.CommonsObjectionRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsScrubSig
import ai.ciris.mobile.shared.models.surfaces.CommonsStanding
import ai.ciris.mobile.shared.models.surfaces.CommonsWriteResult
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OBJ = "obj-1"

private fun objected(vararg ids: String) = CommonsStanding(
    standing = "objected",
    objectionThreshold = 1,
    escalationRespondentFloor = 3,
    fold = CommonsFold(distinctObjectors = ids.size, required = 4, rosterSize = 7, countedObjectionIds = ids.toList()),
)

private val ENVELOPE = buildJsonObject {
    put("row_id", JsonPrimitive("row-7"))
    put("asserted_at", JsonPrimitive("2026-11-10T12:00:00Z"))
}

/** A node that answers what it is told to, and records every request it was sent. */
private class FakeCommons : CommonsApi {
    var standing: suspend () -> CommonsStanding = { objected(OBJ) }
    var objectionAnswer: suspend () -> CommonsWriteResult = { CommonsWriteResult(objectionId = "obj-new", admitted = true) }
    var dismissalAnswer: suspend (CommonsDismissalRequest) -> CommonsWriteResult = { r ->
        if (r.dryRun) CommonsWriteResult(dryRun = true, payloadSha256 = "sha-1", envelope = ENVELOPE)
        else CommonsWriteResult(dismissalId = "dis-1", admitted = true)
    }
    val reads = mutableListOf<Triple<String, String, String>>()
    val objections = mutableListOf<CommonsObjectionRequest>()
    val dismissals = mutableListOf<CommonsDismissalRequest>()

    override suspend fun standing(cohort: String, cohortKeyId: String, actionId: String): CommonsStanding {
        reads += Triple(cohort, cohortKeyId, actionId)
        return standing.invoke()
    }
    override suspend fun objection(request: CommonsObjectionRequest): CommonsWriteResult {
        objections += request
        return objectionAnswer()
    }
    override suspend fun ballot(request: CommonsBallotRequest) = CommonsWriteResult(ballotId = "bal-1", admitted = true)
    override suspend fun dismissal(request: CommonsDismissalRequest): CommonsWriteResult {
        dismissals += request
        return dismissalAnswer(request)
    }
}

/**
 * CSD-070 §2.3: an answer is about its question, and the submission is the dry
 * run. Each test here is one law of `packaging/csd5/ui_primitives.json` on the
 * Commons view model, driven against a scripted node.
 */
class CommonsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val made = mutableListOf<ViewModel>()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { stopViewModels(made); Dispatchers.resetMain() }

    private fun asked(api: FakeCommons): CommonsViewModel = CommonsViewModel(api).also { made += it }.also {
        it.setCohortKeyId("cohort-key")
        it.setActionId("act-1")
        it.readStanding()
    }

    // ── L7: the submission is the dry run ────────────────────────────────

    @Test
    fun theSubmissionIsTheDryRunWithItsEnvelopeAndTheCosignatures() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.dismissDryRun(OBJ, "  settled out of band ")
        assertEquals("sha-1", vm.dismissalDraft.value?.payloadSha256)
        vm.addCosigner("key-b", "sig-b", null)
        vm.submitDismissal(OBJ)

        val (dry, sent) = api.dismissals
        assertTrue(dry.dryRun)
        assertNull(dry.stampedEnvelope, "the dry run sends no envelope back")
        assertEquals(
            dry.copy(
                dryRun = false,
                additionalScrubs = listOf(CommonsScrubSig("key-b", "sig-b")),
                stampedEnvelope = ENVELOPE,
            ),
            sent,
        )
        assertEquals("settled out of band", sent.grounds)
    }

    @Test
    fun editedGroundsLetTheDryRunGoSoNothingElseCanBeSubmittedUnderItsHash() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.dismissDryRun(OBJ, "first reason")
        vm.addCosigner("key-b", "sig-b", null)
        vm.dismissalGroundsEdited(OBJ, "first reason ")
        assertNotNull(vm.dismissalDraft.value, "trailing space is not a different sentence")
        vm.dismissalGroundsEdited(OBJ, "another reason")
        assertNull(vm.dismissalDraft.value)
        assertTrue(vm.cosigners.value.isEmpty())
        assertNull(vm.writeResult.value, "the card that showed the hash goes with it")
        vm.submitDismissal(OBJ)
        assertEquals(1, api.dismissals.size, "nothing is submitted without a held dry run")
    }

    @Test
    fun aSecondDryRunDropsTheSignaturesOverTheFirst() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.dismissDryRun(OBJ, "reason")
        vm.addCosigner("key-b", "sig-b", null)
        vm.dismissDryRun(OBJ, "reason")
        assertTrue(vm.cosigners.value.isEmpty(), "each dry run is stamped anew; old signatures verify against nothing")
        assertNotNull(vm.dismissalDraft.value)
    }

    @Test
    fun aCosignatureNeedsAHeldDryRunAndASubmitNamesItsObjection() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.addCosigner("key-b", "sig-b", null)
        assertTrue(vm.cosigners.value.isEmpty())
        vm.dismissDryRun(OBJ, "reason")
        vm.submitDismissal("obj-other")
        assertEquals(1, api.dismissals.size, "a draft for one objection is not submitted from another's card")
    }

    @Test
    fun aRefusedSubmissionKeepsTheDryRunAndEverySignature() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.dismissDryRun(OBJ, "reason")
        vm.addCosigner("key-b", "sig-b", null)
        val readsBefore = api.reads.size
        api.dismissalAnswer = {
            CommonsWriteResult(refused = true, refusal = "dismissal_quorum_not_met", quorum = CommonsDismissalQuorum(2, 4, 7))
        }
        vm.submitDismissal(OBJ)
        assertEquals("dismissal_quorum_not_met", vm.writeResult.value?.refusal)
        assertEquals("sha-1", vm.dismissalDraft.value?.payloadSha256, "a shortfall is answered by one more signature over the same bytes")
        assertEquals(1, vm.cosigners.value.size)
        assertEquals(readsBefore, api.reads.size, "a refusal changed nothing, so nothing is re-read")
    }

    @Test
    fun aSubmissionThatNeverArrivedKeepsTheDryRunAndTheStanding() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.dismissDryRun(OBJ, "reason")
        api.dismissalAnswer = { throw IllegalStateException("connection reset") }
        vm.submitDismissal(OBJ)
        assertEquals("connection reset", vm.writeError.value)
        assertNull(vm.error.value, "a failed write is not a failed read")
        assertNotNull(vm.standing.value)
        assertNotNull(vm.dismissalDraft.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun anAdmittedDismissalEndsTheCeremonyAndReadsAgain() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.dismissDryRun(OBJ, "reason")
        val readsBefore = api.reads.size
        vm.submitDismissal(OBJ)
        assertNull(vm.dismissalDraft.value)
        assertEquals("dis-1", vm.writeResult.value?.dismissalId)
        assertEquals(readsBefore + 1, api.reads.size)
    }

    // ── L2 / L3 / L11: an answer is about its question ───────────────────

    @Test
    fun changingTheQuestionLetsTheAnswerAndTheDryRunGo() {
        for (change in listOf<(CommonsViewModel) -> Unit>(
            { it.setCohort("community") },
            { it.setCohortKeyId("another-key") },
            { it.setActionId("act-2") },
        )) {
            val vm = asked(FakeCommons())
            vm.setRaiseGrounds("typed under act-1")
            vm.dismissDryRun(OBJ, "reason")
            vm.addCosigner("key-b", "sig-b", null)
            change(vm)
            assertNull(vm.standing.value)
            assertNull(vm.dismissalDraft.value)
            assertTrue(vm.cosigners.value.isEmpty())
            assertEquals("", vm.raiseGrounds.value)
        }
    }

    @Test
    fun settingAFieldToWhatItAlreadyHoldsChangesNothing() {
        val vm = asked(FakeCommons())
        vm.setCohortKeyId("cohort-key")
        vm.setCohort("family")
        assertNotNull(vm.standing.value)
    }

    @Test
    fun aReadInFlightAndAReadThatFailedDrawNothingFromTheReadBefore() {
        val api = FakeCommons()
        val vm = asked(api)
        assertNotNull(vm.standing.value)

        val pending = CompletableDeferred<CommonsStanding>()
        api.standing = { pending.await() }
        vm.readStanding()
        assertNull(vm.standing.value, "in flight")
        assertTrue(vm.loading.value)
        pending.completeExceptionally(IllegalStateException("timeout"))
        assertNull(vm.standing.value, "failed")
        assertEquals("timeout", vm.error.value)
        assertFalse(vm.loading.value)
    }

    @Test
    fun anAnswerToAnOlderQuestionIsDropped() {
        val api = FakeCommons()
        val vm = asked(api)
        val late = CompletableDeferred<CommonsStanding>()
        api.standing = { late.await() }
        vm.readStanding()
        vm.setActionId("act-2")
        assertFalse(vm.loading.value)
        late.complete(objected(OBJ))
        assertNull(vm.standing.value, "the answer was about act-1 and the screen now asks about act-2")
        assertFalse(vm.loading.value)
    }

    @Test
    fun aDryRunAnsweredAfterTheQuestionChangedIsNotKept() {
        val api = FakeCommons()
        val vm = asked(api)
        val late = CompletableDeferred<CommonsWriteResult>()
        api.dismissalAnswer = { late.await() }
        vm.dismissDryRun(OBJ, "reason")
        vm.setActionId("act-2")
        late.complete(CommonsWriteResult(dryRun = true, payloadSha256 = "sha-late", envelope = ENVELOPE))
        assertNull(vm.dismissalDraft.value)
        assertNull(vm.writeResult.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun aHeldDryRunSurvivesAReReadThatStillNamesItsObjectionAndEndsWhenItDoesNot() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.dismissDryRun(OBJ, "reason")
        vm.addCosigner("key-b", "sig-b", null)

        vm.readStanding()
        assertEquals("sha-1", vm.dismissalDraft.value?.payloadSha256)
        assertEquals(1, vm.cosigners.value.size)

        // A refusal and a failure say nothing about the objection.
        api.standing = { CommonsStanding(refused = true, refusal = "objection_roster_unresolved") }
        vm.readStanding()
        assertNotNull(vm.dismissalDraft.value)
        api.standing = { throw IllegalStateException("timeout") }
        vm.readStanding()
        assertNotNull(vm.dismissalDraft.value)

        // An answer that no longer names it does.
        api.standing = { objected("obj-2") }
        vm.readStanding()
        assertNull(vm.dismissalDraft.value)
        assertTrue(vm.cosigners.value.isEmpty())
    }

    // ── Raising ──────────────────────────────────────────────────────────

    @Test
    fun theGroundsAreKeptUntilTheNodeHasTakenThem() {
        val api = FakeCommons()
        val vm = asked(api)
        vm.setRaiseGrounds("this is unsafe")

        api.objectionAnswer = { CommonsWriteResult(refused = true, refusal = "objector_not_on_roster") }
        vm.raiseObjection()
        assertEquals("this is unsafe", vm.raiseGrounds.value, "refused")

        api.objectionAnswer = { throw IllegalStateException("connection reset") }
        vm.raiseObjection()
        assertEquals("this is unsafe", vm.raiseGrounds.value, "never arrived")
        assertEquals("connection reset", vm.writeError.value)

        api.objectionAnswer = { CommonsWriteResult(objectionId = "obj-new", admitted = true) }
        vm.raiseObjection()
        assertEquals("", vm.raiseGrounds.value, "taken")
        assertNull(vm.writeError.value)
        assertEquals(3, api.objections.size)
        assertEquals("this is unsafe", api.objections.last().grounds)
    }

    // ── The wire ─────────────────────────────────────────────────────────

    /** The client's own configuration for these bodies: defaults are not encoded. */
    private val wire = Json { ignoreUnknownKeys = true }

    @Test
    fun theStampedEnvelopeIsOnTheWireOnASubmissionAndAbsentOnADryRun() {
        val dry = CommonsDismissalRequest("family", "cohort-key", "act-1", "reason", OBJ, dryRun = true)
        val dryBody = wire.encodeToJsonElement(CommonsDismissalRequest.serializer(), dry).jsonObject
        assertFalse("stamped_envelope" in dryBody)

        val sent = dismissalSubmission(DismissalDraft(dry, "sha-1", ENVELOPE), emptyList())
        val body = wire.encodeToJsonElement(CommonsDismissalRequest.serializer(), sent).jsonObject
        assertEquals(ENVELOPE, body["stamped_envelope"])
        assertFalse("dry_run" in body, "a submission is not a dry run")
    }

    @Test
    fun aRefusalBodyCarriesNoPricesAndNoneAreInvented() {
        // `err()` in commons_surface.rs: the token, the sentence, and nothing else.
        val raw = """{"refused":true,"refusal":"self_is_not_a_commons","source_locale":"en","message":null}"""
        val s = wire.decodeFromString(CommonsStanding.serializer(), raw)
        assertTrue(s.refused)
        assertNull(s.objectionThreshold)
        assertNull(s.escalationRespondentFloor)
    }
}
