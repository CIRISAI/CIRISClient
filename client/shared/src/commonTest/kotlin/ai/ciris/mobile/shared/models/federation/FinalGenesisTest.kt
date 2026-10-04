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
        assertEquals(FinalGenesisProbe.REFUSED, finalGenesisProbe(404, null, """{"error":"Not Found"}"""), "a 404 with a body is not the missing route")
        assertEquals(FinalGenesisProbe.REFUSED, finalGenesisProbe(404, null, "<html>404</html>"))
        assertEquals(FinalGenesisProbe.LEGACY, finalGenesisProbe(404, null, "  "))
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
    fun aBundleMatchesItsFingerprintOnlyByteForByte() {
        // SHA-256("abc")
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(bundleMatchesSha256("abc", "sha256:$abc"))
        assertTrue(bundleMatchesSha256("abc", abc.uppercase()))
        assertFalse(bundleMatchesSha256("abc ", "sha256:$abc"))
    }

    @Test
    fun theBundleMemberIsFoundAndAStringMemberIsUnquoted() {
        assertEquals("""{"a" : [1,"}"]}""", rawJsonMember("""{"x":"{","bundle":{"a" : [1,"}"]},"y":2}""", "bundle"))
        assertEquals("line1\nline2", finalGenesisBundleText("""{"bundle":"line1\nline2"}"""))
        assertNull(finalGenesisBundleText("""{"bundle":null}"""))
        assertNull(finalGenesisBundleText("""{"other":1}"""))
        assertNull(rawJsonMember("[1]", "bundle"))
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

    @Test
    fun aDialHintIsAnIpLiteralAndAPortAsTheServersDialerParsesIt() {
        // CIRISServer 814dd7c6 `require_dial_hint` → `compose::ip_addrs_from_hints`:
        // `destination.parse::<std::net::SocketAddr>()` — an IP literal, never a name.
        for (ok in listOf("108.61.242.236:4242", "203.0.113.7:1", "0.0.0.0:65535", "[2001:db8::1]:4242", "[::1]:4242",
                "[::ffff:192.0.2.1]:4242", "[2001:db8:0:0:0:0:0:1]:80")) {
            assertTrue(isDialHint(ok), ok)
        }
        for (bad in listOf("canon2.example.net:4242", "localhost:4242", "108.61.242.236", "108.61.242.236:", "108.61.242.236:0",
                "108.61.242.236:65536", "108.61.242.236:+4242", "256.1.1.1:4242", "01.2.3.4:4242", "1.2.3:4242",
                "2001:db8::1:4242", "[2001:db8::1]", "[2001:db8:::1]:4242", "[1:2:3:4:5:6:7:8:9]:4242", "[g::1]:4242",
                " 108.61.242.236 :4242", "")) {
            assertFalse(isDialHint(bad), bad)
        }
    }
}
