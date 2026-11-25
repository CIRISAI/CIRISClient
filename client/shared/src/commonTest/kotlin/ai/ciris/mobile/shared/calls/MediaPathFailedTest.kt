package ai.ciris.mobile.shared.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-134 §2.1: a call that can't connect says why (Eric's ruling for the LTS
 * calls cut). The shapes are edge 45.2.0's frozen tokens as CIRISServer 0.5.254
 * puts them on the wire, and 45.1.11's top-level form, which is still read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaPathFailedTest {

    private class FakeSocket : CallSocket {
        val inbox = Channel<CallSocketMessage>(Channel.UNLIMITED)
        override val incoming: Flow<CallSocketMessage> = inbox.receiveAsFlow()
        override fun send(bytes: ByteArray) {}
        override fun close() { inbox.close() }
    }

    private class FakeTransport : CallTransport {
        val socket = FakeSocket()
        override suspend fun join(room: String, video: Boolean) = CallJoinAnswer("c1", "s-me", 1, 244, "full", listOf("alice"))
        override suspend fun leave(room: String) {}
        override suspend fun openFrames(room: String): CallSocket = socket
    }

    private val frozen = """
        {"type":"media_path_failed","member":"alice","reason_id":"call.media_path_failed.both_nats_random",
         "cause":"both_nats_random","retryable":false,"retry_after_s":null,"detail":null,
         "tried":[{"kind":"direct_reflexive","outcome":"no_answer","attempts":3,"elapsed_ms":4200,"knocks_sent":12,"sockets":1},
                  {"kind":"birthday","outcome":"not_tried_no_evidence"}],
         "local_nat":{"mapping":"address_and_port_dependent","allocation":"random","filtering":"address_and_port_dependent",
                      "port_preserving":false,"router_mapping":"not_tried","family":"v4","evidence":"two_observer",
                      "legacy_class":"symmetric","measured_at":1795400000000},
         "peer_nat":{"report":{"legacy_class":"symmetric","allocation":"random","router_mapping":"not_tried"},"source":"self_reported"},
         "relay":"refused_by_policy"}
    """.trimIndent()

    @Test fun theFrozenShapeIsReadWhole() {
        val f = assertIs<CallEvent.MediaPathFailed>(CallEvent.parse(frozen)).failure
        assertEquals("alice", f.member)
        assertEquals("both_nats_random", f.cause)
        assertFalse(f.retryable)
        assertNull(f.retryAfterS)
        assertEquals("symmetric", f.localNat?.legacyClass)
        assertEquals("random", f.localNat?.allocation)
        assertEquals(false, f.localNat?.portPreserving)
        assertEquals(1795400000000, f.localNat?.measuredAt)
        assertEquals("symmetric", f.peerNat?.legacyClass)
        assertEquals("refused_by_policy", f.relay)
        assertEquals(listOf("direct_reflexive" to "no_answer", "birthday" to "not_tried_no_evidence"), f.tried.map { it.kind to it.outcome })
        assertEquals(12, f.tried[0].knocksSent)
    }

    @Test fun aSparseEventIsStillShownNeverDropped() {
        val f = assertIs<CallEvent.MediaPathFailed>(CallEvent.parse("""{"type":"media_path_failed","member":"bob"}""")).failure
        assertEquals("other", f.cause)
        // Without edge saying so, nothing offers a retry.
        assertFalse(f.retryable)
        assertNull(f.peerNat)
        assertEquals("mobile.call_path_other", MediaPathText.headline(f, "Bob").key)
    }

    @Test fun theFailureIsHeldPerMemberAndARestoreClearsIt() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text(frozen.replace("\n", "")))
        runCurrent()
        var call = assertIs<CallState.InCall>(s.state.value)
        assertEquals(setOf("alice"), call.pathFailures.keys)
        // Nobody is removed for it.
        assertEquals(setOf("alice"), call.attendees.keys)
        // An update with changed facts replaces the earlier report.
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"media_path_failed","member":"alice","cause":"punch_no_answer","retryable":true}"""))
        runCurrent()
        call = assertIs<CallState.InCall>(s.state.value)
        assertEquals("punch_no_answer", call.pathFailures.getValue("alice").cause)
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"media_path_restored","member":"alice","via":"direct_lan"}"""))
        runCurrent()
        call = assertIs<CallState.InCall>(s.state.value)
        assertTrue(call.pathFailures.isEmpty())
        assertEquals("direct_lan", call.pathRestored["alice"]?.via)
    }

    private fun failure(cause: String, local: String? = null, peer: String? = null, retryable: Boolean = false, after: Int? = null, relay: String? = null) =
        MediaPathFailure(
            "m", cause, null, retryable, after, null,
            local?.let { NatReport(it) }, peer?.let { NatReport(it) }, relay, emptyList(),
        )

    @Test fun everyCauseHasItsOwnSentenceAndAnUnknownOneIsGeneric() {
        val keys = MediaPathText.CAUSES.map { MediaPathText.headline(failure(it), "Ada").key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("mobile.call_path_other", MediaPathText.headline(failure("something_new"), "Ada").key)
    }

    @Test fun aRateLimitSaysHowLongWhenEdgeDoes() {
        assertEquals(
            MediaPathText.Line("mobile.call_path_budget_refused", mapOf("seconds" to "30")),
            MediaPathText.headline(failure("budget_refused", after = 30), "Ada"),
        )
        assertEquals("mobile.call_path_budget_refused_shortly", MediaPathText.headline(failure("budget_refused"), "Ada").key)
    }

    @Test fun tryAgainIsOfferedOnlyWhereEdgeSaysItCouldHelp() {
        assertTrue(MediaPathText.offersRetry(failure("punch_no_answer", retryable = true)))
        assertFalse(MediaPathText.offersRetry(failure("both_nats_random", retryable = false)))
    }

    @Test fun theAdviceIsAboutTheSideThatBlocks() {
        assertEquals("mobile.call_path_action_local", MediaPathText.advice(failure("other", local = "symmetric", peer = "open"), "Ada")?.key)
        assertEquals("mobile.call_path_action_peer", MediaPathText.advice(failure("other", local = "open", peer = "port_restricted"), "Ada")?.key)
        assertNull(MediaPathText.advice(failure("other", local = "full_cone", peer = null), "Ada"))
    }

    @Test fun thePeersNetworkIsTheirClaimAndAMissingOneIsUnknownNotGuessed() {
        assertEquals("mobile.call_path_networks", MediaPathText.networks(failure("other", "open", "symmetric"), "Ada", "x", "y").key)
        assertEquals("mobile.call_path_networks_peer_unknown", MediaPathText.networks(failure("other", "open", null), "Ada", "x", null).key)
        assertEquals("mobile.call_nat_unknown", MediaPathText.natKey("carrier_grade"))
    }

    @Test fun theRelayClauseNamesTheCanonical() {
        assertEquals("mobile.call_relay_refused_by_policy", MediaPathText.relay(failure("other", relay = "refused_by_policy"))?.key)
        assertNull(MediaPathText.relay(failure("other")))
        // Reserved, and never on a failure; an unknown standing says nothing rather than guess.
        assertNull(MediaPathText.relay(failure("other", relay = "peer_relay_in_use")))
        assertNull(MediaPathText.relay(failure("other", relay = "something_newer")))
    }

    private val v254 = """
        {"type":"media_path_failed","member":"alice","person":"p-alice","cause":"no_member_could_relay",
         "reason_id":"call.media_path_failed.no_member_could_relay","retryable":true,"retry_after_s":null,
         "relay":"none_available","relay_reason_id":"call.relay_standing.none_available",
         "diag":{"detail":"punch_silent","tried":[{"kind":"direct_reflexive","outcome":"no_answer"}],
                 "local_nat":{"legacy_class":"port_restricted"},"peer_nat":{"report":{"legacy_class":"symmetric"},"source":"self_reported"}}}
    """.trimIndent()

    @Test fun the0254ShapeReadsItsDiagnosticsFromDiagAndNamesThePerson() {
        val f = assertIs<CallEvent.MediaPathFailed>(CallEvent.parse(v254)).failure
        assertEquals("no_member_could_relay", f.cause)
        assertEquals("p-alice", f.person)
        assertEquals("punch_silent", f.detail)
        assertEquals("port_restricted", f.localNat?.legacyClass)
        assertEquals("symmetric", f.peerNat?.legacyClass)
        assertEquals(listOf("direct_reflexive"), f.tried.map { it.kind })
        assertEquals("mobile.call_path_no_member_could_relay", MediaPathText.headline(f, "Ada").key)
        assertEquals("mobile.call_relay_none_available", MediaPathText.relay(f)?.key)
    }

    @Test fun anonymousModeIsTheReasonSoNoNetworkAdvice() {
        val f = failure("anonymous_no_direct", local = "symmetric", peer = "symmetric")
        assertEquals("mobile.call_path_anonymous_no_direct", MediaPathText.headline(f, "Ada").key)
        assertNull(MediaPathText.advice(f, "Ada"))
    }

    @Test fun aRelayedRestoreNamesWhoCarriesItAndEveryOtherPathReadsDirect() {
        val r = assertIs<CallEvent.MediaPathRestored>(
            CallEvent.parse("""{"type":"media_path_restored","member":"alice","person":"p-a","via":"peer_relay","direct":false,"through":"bob","through_person":"p-b"}"""),
        ).restored
        assertEquals("bob", r.through)
        assertEquals("p-b", r.throughPerson)
        assertEquals(
            MediaPathText.Line("mobile.call_path_restored_relay", mapOf("person" to "Ada", "relayer" to "Bob")),
            MediaPathText.restored(r, "Ada", "Bob"),
        )
        assertEquals("mobile.call_path_restored", MediaPathText.restored(PathRestored("alice", "birthday"), "Ada", "x").key)
        assertEquals("mobile.call_path_restored_lan", MediaPathText.restored(PathRestored("alice", "direct_lan"), "Ada", "x").key)
    }

    @Test fun relayStatusCountsPeopleAndIsHeldOnTheCall() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"relay_status","relaying":true,"relaying_for":2,"relaying_for_nodes":3}"""))
        runCurrent()
        val call = assertIs<CallState.InCall>(s.state.value)
        assertEquals(true, call.relaying)
        assertEquals(2, call.relayingFor)
        // Not a JSON boolean: not a ruling either way.
        assertNull(CallEvent.parse("""{"type":"relay_status","relaying":"yes"}"""))
    }

    @Test fun aSettingThatEndsTheCallIsSaidAndTheCloseDoesNotBlurIt() = runTest {
        val t = FakeTransport()
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false); runCurrent()
        t.socket.inbox.send(CallSocketMessage.Text("""{"type":"call_ended","ended":"shaped_only_no_media","reason_id":"call.shaped_only"}"""))
        runCurrent()
        assertEquals(LeaveReason.SHAPED_ONLY, assertIs<CallState.Left>(s.state.value).reason)
        s.onMessage(CallSocketMessage.Closed("shaped_only_no_media"))
        assertEquals(LeaveReason.SHAPED_ONLY, assertIs<CallState.Left>(s.state.value).reason)
    }

    @Test fun edgesEndTokensMapOntoTheLeaveReasons() {
        assertEquals(LeaveReason.LEFT, LeaveReason.of("ended:hung_up"))
        assertEquals(LeaveReason.REMOVED, LeaveReason.of("revoked"))
        assertEquals(LeaveReason.EXPIRED, LeaveReason.of("expired"))
        assertEquals(LeaveReason.ANONYMOUS, LeaveReason.of("ended:onion_anonymous_no_media"))
        assertEquals(LeaveReason.LOST, LeaveReason.of("something_newer"))
    }

    @Test fun theSwitchAsksTheNodeAndShowsItsAnswer() = runTest {
        var asked: Boolean? = null
        val t = object : CallTransport by FakeTransport() {
            val inner = FakeTransport()
            override suspend fun join(room: String, video: Boolean) = inner.join(room, video)
            override suspend fun openFrames(room: String): CallSocket = inner.socket
            override suspend fun setRelay(room: String, relay: Boolean): RelayAnswer {
                asked = relay
                return RelayAnswer(relaying = false, relayingFor = 0, serveSeat = true)
            }
        }
        val s = CallSession("r", t, backgroundScope)
        s.join(video = false, relay = null); runCurrent()
        assertEquals(RelayAsk.ANSWERED, s.setRelay(true))
        assertEquals(true, asked)
        val call = assertIs<CallState.InCall>(s.state.value)
        // A CIRIS public server never relays: the answer, not the ask, is what shows.
        assertEquals(false, call.relaying)
        assertTrue(call.relaySeat)
    }

    @Test fun detailsCarryTheRawTokens() {
        val f = assertIs<CallEvent.MediaPathFailed>(CallEvent.parse(frozen)).failure
        val d = MediaPathText.details(f)
        assertTrue(d.first().startsWith("cause=both_nats_random"))
        assertTrue(d.any { it.startsWith("peer (self-reported): class=symmetric") })
        assertTrue(d.any { it == "tried direct_reflexive: no_answer attempts=3 elapsed_ms=4200 knocks=12 sockets=1" })
    }
}
