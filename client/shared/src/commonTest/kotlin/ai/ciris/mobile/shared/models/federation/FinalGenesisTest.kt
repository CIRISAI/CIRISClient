package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.api.NodeRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the final-genesis sheet: which re-mint, what a refusal asks, the grid. */
class FinalGenesisTest {

    @Test
    fun onlyABare404IsAnOldNode() {
        assertEquals(FinalGenesisProbe.LEGACY, finalGenesisProbe(404, null))
        assertEquals(FinalGenesisProbe.NOT_PLANNED, finalGenesisProbe(404, "final_genesis.not_planned"))
        assertEquals(FinalGenesisProbe.REFUSED, finalGenesisProbe(403, null), "off the node's machine is not an old node")
        assertEquals(FinalGenesisProbe.REFUSED, finalGenesisProbe(404, "something.else"))
        assertEquals(FinalGenesisProbe.REFUSED, finalGenesisProbe(500, null))
    }

    @Test
    fun onlyTheClockAndAPlannedCeremonyAreQuestions() {
        assertEquals(PlanConfirm.CLOCK, planConfirmFor("final_genesis.clock_unverified"))
        assertEquals(PlanConfirm.REPLACE, planConfirmFor("final_genesis.already_planned"))
        assertNull(planConfirmFor("final_genesis.clock_not_synchronized"), "a clock the node KNOWS is wrong is not confirmable")
        assertNull(planConfirmFor(null))
    }

    @Test
    fun itemsSortIntoBundleOrderAndTwoRounds() {
        val owed = listOf("authz", "community:ciris-canonical", "family:humanity-accord", "record:c1", "row:genesis-charter",
            "row:genesis-grant:c1", "row:genesis-lifecycle")
        assertEquals(
            listOf("record:c1", "row:genesis-charter", "row:genesis-grant:c1", "row:genesis-lifecycle",
                "family:humanity-accord", "community:ciris-canonical", "authz"),
            FinalGenesisItems.sorted(owed),
        )
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 2), FinalGenesisItems.sorted(owed).map { FinalGenesisItems.round(it) })
    }

    @Test
    fun aHolderOwingOnlyWaitingItemsWaitsOnTheOthersCharter() {
        val s = FinalGenesisStatusDto(
            signableNow = listOf("row:genesis-charter"),
            owed = mapOf("row:genesis-charter" to listOf("B1"), "authz" to listOf("A1", "B1", "C1")),
        )
        val g = finalGenesisGrid(listOf("A1", "B1", "C1"), s)
        assertEquals(HolderGenesisState.Waiting(listOf("B1")), g.holderState("A1"))
        assertEquals(HolderGenesisState.SignNow, g.holderState("B1"))
        assertEquals(GenesisCell.WAITING, g.cell("C1", "authz"))
        assertFalse(g.roundTwoOpen)
    }

    @Test
    fun aCompletedItemStaysOnTheGridAsSigned() {
        val s = FinalGenesisStatusDto(complete = true)
        val g = finalGenesisGrid(listOf("A1", "B1", "C1"), s, known = listOf("authz"))
        assertEquals(listOf("authz"), g.items)
        assertEquals(GenesisCell.SIGNED, g.cell("A1", "authz"))
        assertEquals(HolderGenesisState.Done, g.holderState("A1"))
        assertTrue(g.roundTwoOpen)
    }

    @Test
    fun aCommitmentReadsAsItsFirstSixteenHexInFours() {
        assertEquals("3f2a 9c01 77de 0b4e", shortCommitment("3f2a9c0177de0b4e55aa66bb77cc88dd99ee00ff11223344556677889900aabb"))
        assertEquals("3f2a 9c01", shortCommitment("sha256:3f2a9c01"))
        assertNull(shortCommitment(null))
        assertNull(shortCommitment(" "))
    }

    @Test
    fun theFingerprintIsSha256OfThePublicBytesInFours() {
        // SHA-256("abc") = ba7816bf 8f01cfea …
        assertEquals("ba78 16bf 8f01 cfea", shortKeyFingerprint("YWJj"))
        assertNull(shortKeyFingerprint("not base64!"))
        assertNull(shortKeyFingerprint(""))
    }

    @Test
    fun aCeremonyIdInBothErrorAndReasonIdIsNotReadAsTheSentence() {
        val e = NodeRefusal.fromBody(409, """{"error":"ceremony_incomplete","reason_id":"ceremony_incomplete","detail":"authz is owed by C1"}""")
        assertEquals("ceremony_incomplete", e.reasonId)
        assertEquals("authz is owed by C1", e.detail)
        // An English `error` beside a different id stays the sentence.
        val f = NodeRefusal.fromBody(400, """{"error":"Bad thing happened","reason_id":"x.y"}""")
        assertEquals("Bad thing happened", f.detail)
    }
}
