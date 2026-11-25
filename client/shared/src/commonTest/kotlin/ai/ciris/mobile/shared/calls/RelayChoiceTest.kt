package ai.ciris.mobile.shared.calls

import ai.ciris.mobile.shared.calls.media.AudioSink
import ai.ciris.mobile.shared.calls.media.AudioSource
import ai.ciris.mobile.shared.calls.media.CallMediaDevices
import ai.ciris.mobile.shared.calls.media.VideoSource
import ai.ciris.mobile.shared.platform.Platform
import ai.ciris.mobile.shared.viewmodels.CallViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSD-134 §2.1, the relay switch: whose choice it is, and that a phone on mobile
 * data does not carry other people's calls because nobody asked it to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayChoiceTest {

    private class FakeSocket : CallSocket {
        val inbox = Channel<CallSocketMessage>(Channel.UNLIMITED)
        override val incoming: Flow<CallSocketMessage> = inbox.receiveAsFlow()
        override fun send(bytes: ByteArray) {}
        override fun close() { inbox.close() }
    }

    /** Records what the node was told, in order: `join:…` and then each `set:…`. */
    private class FakeTransport(val answer: CallJoinAnswer = CallJoinAnswer("c1", "s-me", 1, 244, "full", listOf("alice"))) : CallTransport {
        val told = mutableListOf<String>()
        var failures = 0
        var refuse = false
        override suspend fun join(room: String, video: Boolean) = join(room, video, null)
        override suspend fun join(room: String, video: Boolean, relay: Boolean?): CallJoinAnswer {
            told += "join:${relay ?: "-"}"
            return answer
        }
        override suspend fun leave(room: String) {}
        override suspend fun openFrames(room: String): CallSocket = FakeSocket()
        override suspend fun setRelay(room: String, relay: Boolean): RelayAnswer {
            told += "set:$relay"
            if (refuse) throw CallRefusal(CallTransport.RELAY_UNAVAILABLE)
            if (failures > 0) { failures--; throw IllegalStateException("HTTP 503") }
            return RelayAnswer(relaying = relay, relayingFor = 0, serveSeat = false)
        }
    }

    private class NoDevices : CallMediaDevices {
        override suspend fun microphone(): AudioSource? = null
        override fun speaker(): AudioSink? = null
        override suspend fun camera(): VideoSource? = null
    }

    private class FakeStore(var value: Boolean? = null, val gate: CompletableDeferred<Unit>? = null, val broken: Boolean = false) : RelayChoiceStore {
        override suspend fun load(): Boolean? {
            gate?.await()
            check(!broken) { "storage locked" }
            return value
        }
        override suspend fun save(on: Boolean) { value = on }
    }

    private val phone = RelayNode.of("http://127.0.0.1:4243", Platform.ANDROID)
    private val desktop = RelayNode.of("http://127.0.0.1:4243", Platform.DESKTOP)
    private val home = RelayNode.of("https://home.example:4243", Platform.ANDROID)

    private class Rig(
        scope: TestScope, node: RelayNode, val store: FakeStore = FakeStore(), metered: Boolean? = false,
        val transport: FakeTransport = FakeTransport(),
    ) {
        val network = MutableStateFlow(metered)
        var watched = 0
        val vm = CallViewModel(
            room = "r", transport = { transport }, devices = NoDevices(), h264 = null,
            newEncoder = { _, _, _, _ -> null }, scope = scope.backgroundScope,
            relayNode = node, relayStore = store, network = { watched++; network },
        )
        val told get() = transport.told
    }

    @Test fun whichNodeCarriesTheCall() {
        assertTrue(phone.ownPhone)
        assertTrue(RelayNode.of("http://localhost:4243/", Platform.IOS).ownPhone)
        assertTrue(desktop.onThisDevice)
        assertFalse(desktop.ownPhone)
        // A phone driving another node: that node's network carries it, not the phone's.
        assertFalse(home.ownPhone)
        assertFalse(home.onThisDevice)
        // A browser is never a node.
        assertFalse(RelayNode.of("https://home.example:4243", Platform.WEB).onThisDevice)
        assertFalse(RelayNode.of("http://localhost:x@remote.example", Platform.ANDROID).ownPhone)
    }

    @Test fun oneNodeKeepsOneChoice() {
        assertEquals(phone.key, RelayNode.of("http://localhost:4243/", Platform.ANDROID).key)
        assertEquals(home.key, RelayNode.of("HTTPS://Home.Example:4243/", Platform.ANDROID).key)
        assertNotEquals(home.key, RelayNode.of("https://other.example:4243", Platform.ANDROID).key)
        assertNotEquals(home.key, phone.key)
        val long = RelayNode.of("https://${"a".repeat(200)}.example", Platform.DESKTOP)
        assertTrue("call_relay_choice.${long.key}".length <= 80, "desktop preference keys are limited to 80")
    }

    @Test fun whatIsAskedOfWhichNode() {
        val none = RelayChoice.Choice(known = true)
        val unread = RelayChoice.Choice()
        assertEquals(true, RelayChoice.wanted(phone, none, metered = false))
        assertEquals(false, RelayChoice.wanted(phone, none, metered = true))
        assertEquals(false, RelayChoice.wanted(phone, none, metered = null), "an unknown network is not an unmetered one")
        assertEquals(false, RelayChoice.wanted(phone, unread, metered = false), "a choice not read is not 'none made'")
        assertEquals(true, RelayChoice.wanted(phone, none.copy(on = true), metered = true))
        assertEquals(false, RelayChoice.wanted(phone, none.copy(on = false), metered = false))
        listOf(desktop, home).forEach { n ->
            assertNull(RelayChoice.wanted(n, none, metered = true))
            assertNull(RelayChoice.wanted(n, none, metered = false))
            assertEquals(false, RelayChoice.wanted(n, none.copy(on = false), metered = false))
        }
    }

    @Test fun aPhoneOnWifiSaysOnAndMobileDataMidCallSaysOff() = runTest {
        val r = Rig(this, phone)
        r.vm.join(video = false); runCurrent()
        assertEquals(listOf("join:true"), r.told, "the join said it: nothing more to ask")
        r.network.value = true; runCurrent()
        assertEquals(listOf("join:true", "set:false"), r.told)
        r.network.value = false; runCurrent()
        assertEquals("set:true", r.told.last())
        r.network.value = null; runCurrent()
        assertEquals("set:false", r.told.last(), "no known network is off")
    }

    @Test fun aNetworkKnownOnlyAfterTheJoinTurnsItOnThen() = runTest {
        val r = Rig(this, phone, metered = null)
        r.vm.join(video = false); runCurrent()
        r.network.value = false; runCurrent()
        assertEquals(listOf("join:false", "set:true"), r.told)
    }

    @Test fun thePersonsChoiceHoldsWhateverTheNetworkDoes() = runTest {
        val r = Rig(this, phone, FakeStore(false))
        r.vm.join(video = false); runCurrent()
        r.network.value = true; runCurrent()
        r.network.value = false; runCurrent()
        assertEquals(listOf("join:false"), r.told)
    }

    @Test fun theJoinWaitsForTheStoredChoice() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, home, FakeStore(false, gate))
        r.vm.join(video = false); runCurrent()
        assertEquals(emptyList(), r.told, "nothing is said before the choice is read")
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("join:false"), r.told)
    }

    @Test fun aSlowStoreNeverMakesAPhoneRelayAndIsPutRightWhenItAnswers() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, phone, FakeStore(null, gate))
        r.vm.join(video = false)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("join:false"), r.told)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("join:false", "set:true"), r.told)
    }

    @Test fun aStoreThatCannotBeReadLeavesAPhoneOff() = runTest {
        val r = Rig(this, phone, FakeStore(broken = true))
        r.vm.join(video = false); runCurrent()
        assertEquals(listOf("join:false"), r.told)
    }

    @Test fun leavingBeforeTheChoiceIsReadAsksNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, phone, FakeStore(null, gate))
        r.vm.join(video = false); runCurrent()
        r.vm.leave()
        gate.complete(Unit); runCurrent()
        assertEquals(emptyList(), r.told)
        assertIs<CallState.Left>(r.vm.state.value)
    }

    @Test fun anotherNodeIsToldOnlyWhatThePersonChoseForIt() = runTest {
        val r = Rig(this, home)
        r.vm.join(video = false); runCurrent()
        r.network.value = true; runCurrent()
        assertEquals(listOf("join:-"), r.told, "a phone's mobile data is not the home node's")
        assertEquals(0, r.watched)
        r.vm.setRelay(false); runCurrent()
        assertEquals(listOf("join:-", "set:false"), r.told)
        assertEquals(false, r.store.value)
    }

    @Test fun aTapIsAlwaysAskedAndKept() = runTest {
        val r = Rig(this, phone)
        r.vm.join(video = false); runCurrent()
        // The node was told "on" by the join; the person's own "on" is still asked, once.
        r.vm.setRelay(true); runCurrent()
        assertEquals(listOf("join:true", "set:true"), r.told)
        assertEquals(true, r.store.value)
        r.vm.setRelay(false); runCurrent()
        assertEquals("set:false", r.told.last())
        assertEquals(false, r.store.value)
    }

    @Test fun aTapBeforeTheStoreAnswersWins() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = Rig(this, phone, FakeStore(true, gate))
        r.vm.setRelay(false)
        gate.complete(Unit); runCurrent()
        assertEquals(RelayChoice.Choice(known = true, on = false), r.vm.relayChoice.value)
    }

    @Test fun offIsRepeatedUntilTheNodeHearsIt() = runTest {
        val r = Rig(this, phone)
        r.vm.join(video = false); runCurrent()
        r.transport.failures = 2
        r.network.value = true; runCurrent()
        assertEquals(1, r.told.count { it == "set:false" })
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, r.told.count { it == "set:false" }, "two failures, then heard, then quiet")
    }

    @Test fun aNewNetworkDuringARetryReplacesIt() = runTest {
        val r = Rig(this, phone)
        r.vm.join(video = false); runCurrent()
        r.transport.failures = 1
        r.network.value = true; runCurrent()
        r.network.value = false; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        // The failed "off" may have reached the node, so "on" is said and not assumed.
        assertEquals(listOf("join:true", "set:false", "set:true"), r.told)
    }

    @Test fun aLostOnNeverLeavesAPhoneRelayingOnMobileData() = runTest {
        val r = Rig(this, phone, metered = true)
        r.vm.join(video = false); runCurrent()
        r.transport.failures = 1
        r.network.value = false; runCurrent()
        r.network.value = true; runCurrent()
        // The "on" may have landed although its answer did not: "off" is said again.
        assertEquals(listOf("join:false", "set:true", "set:false"), r.told)
    }

    @Test fun aNodeWithoutTheRouteIsAskedOnce() = runTest {
        val r = Rig(this, phone)
        r.transport.refuse = true
        r.vm.join(video = false); runCurrent()
        r.network.value = true; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("join:true", "set:false"), r.told)
    }

    @Test fun rejoiningSaysTheNetworkAsItIsThen() = runTest {
        val r = Rig(this, phone)
        r.vm.join(video = false); runCurrent()
        r.vm.leave(); runCurrent()
        r.network.value = true; runCurrent()
        r.vm.rejoin(); runCurrent()
        assertEquals(listOf("join:true", "join:false"), r.told)
    }

    @Test fun theJoinAnswerSetsTheSwitch() = runTest {
        fun answer(relay: String) = CallJoinAnswer.parse(
            Json.parseToJsonElement("""{"call_id":"c","stream_id":"s","epoch":1,"frame_budget_bytes":244,"attendees":[]$relay}""") as JsonObject,
        )
        assertNull(answer("").relay, "an older node says nothing, which is not 'off'")
        val r = Rig(this, phone, transport = FakeTransport(answer(""","relay":{"relaying":false,"relaying_for":0,"serve_seat":true}""")))
        r.vm.join(video = false); runCurrent()
        val call = assertIs<CallState.InCall>(r.vm.state.value)
        assertEquals(false, call.relaying)
        assertTrue(call.relaySeat, "a CIRIS public server shows no switch")
    }
}
