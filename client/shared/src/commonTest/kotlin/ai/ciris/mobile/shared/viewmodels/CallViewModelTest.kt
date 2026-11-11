package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.calls.CallFrames
import ai.ciris.mobile.shared.calls.CallJoinAnswer
import ai.ciris.mobile.shared.calls.CallSocket
import ai.ciris.mobile.shared.calls.CallSocketMessage
import ai.ciris.mobile.shared.calls.CallState
import ai.ciris.mobile.shared.calls.CallTransport
import ai.ciris.mobile.shared.calls.Layer
import ai.ciris.mobile.shared.calls.media.AudioSink
import ai.ciris.mobile.shared.calls.media.AudioSource
import ai.ciris.mobile.shared.calls.media.CallMediaDevices
import ai.ciris.mobile.shared.calls.media.CallVideoCodec
import ai.ciris.mobile.shared.calls.media.EncodedChunk
import ai.ciris.mobile.shared.calls.media.H264Download
import ai.ciris.mobile.shared.calls.media.VideoEncoder
import ai.ciris.mobile.shared.calls.media.VideoFrame
import ai.ciris.mobile.shared.calls.media.VideoKind
import ai.ciris.mobile.shared.calls.media.VideoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CSD-134: this device's camera is open exactly while its picture is being sent.
 * A camera that is open and unsent is a lit camera light that tells the person
 * something untrue, so every way sending stops is checked to let it go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {

    private class FakeSocket : CallSocket {
        val inbox = Channel<CallSocketMessage>(Channel.UNLIMITED)
        val sent = mutableListOf<ByteArray>()
        override val incoming: Flow<CallSocketMessage> = inbox.receiveAsFlow()
        override fun send(bytes: ByteArray) { sent += bytes }
        override fun close() { inbox.close() }
    }

    private class FakeTransport(val answer: CallJoinAnswer) : CallTransport {
        val socket = FakeSocket()
        override suspend fun join(room: String, video: Boolean) = answer
        override suspend fun leave(room: String) = Unit
        override suspend fun openFrames(room: String): CallSocket = socket
    }

    /**
     * One opening of the camera. As on desktop and in the browser, the device is
     * open, and its light on, from the moment the devices hand it over until
     * [stop], whether or not it was ever started.
     */
    private class FakeCamera : VideoSource {
        override val kind = VideoKind.CAMERA
        var onFrame: ((VideoFrame) -> Unit)? = null
        var everStarted = false
        var stops = 0
        val lit get() = stops == 0
        override fun start(onFrame: (VideoFrame) -> Unit) { this.onFrame = onFrame; everStarted = true }
        override fun stop() { onFrame = null; stops++ }
        fun deliver() = onFrame!!(VideoFrame(2, 2, ByteArray(4), ByteArray(1), ByteArray(1), 0))
    }

    private class FakeDevices : CallMediaDevices {
        val opened = mutableListOf<FakeCamera>()
        /** When set, [camera] waits on it: the browser asking the person. */
        var gate: CompletableDeferred<Unit>? = null
        var asked = 0
        override suspend fun microphone(): AudioSource? = null
        override fun speaker(): AudioSink? = null
        override suspend fun camera(): VideoSource {
            asked++
            gate?.await()
            return FakeCamera().also { opened += it }
        }
        val lit get() = opened.count { it.lit }
    }

    private class FakeEncoder(override val codecId: Int, val log: MutableList<String>) : VideoEncoder {
        override val codecName = if (codecId == CallVideoCodec.AV1_SVC) "av1" else "h264"
        override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
            log += if (forceKeyframe) "key" else "inter"
            onChunk(EncodedChunk(Layer.BASE, byteArrayOf(1, 2, 3), forceKeyframe))
        }
        override fun close() { log += "close" }
    }

    private class FakeH264 : H264Download {
        override val state = MutableStateFlow(H264Download.State.OFF)
        override val licence = ""
        override fun turnOn() { state.value = H264Download.State.ON }
        override fun turnOff() { state.value = H264Download.State.OFF }
    }

    private class Rig(scope: TestScope, answerJson: String = TWO_PEOPLE, h264: H264Download? = null) {
        val transport = FakeTransport(
            CallJoinAnswer.parse(kotlinx.serialization.json.Json.parseToJsonElement(answerJson) as kotlinx.serialization.json.JsonObject),
        )
        val devices = FakeDevices()
        val encoded = mutableListOf<String>()
        /** What this machine can encode right now; null is "nothing". */
        var codec: Int? = CallVideoCodec.AV1_SVC
        val vm = CallViewModel(
            room = "r",
            transport = { transport },
            devices = devices,
            h264 = h264,
            newEncoder = { _, _, _, _ -> codec?.let { FakeEncoder(it, encoded) } },
            scope = scope.backgroundScope,
        )
        suspend fun say(json: String) = transport.socket.inbox.send(CallSocketMessage.Text(json))
        val sentCodecs get() = transport.socket.sent.map { CallFrames.decodeOutgoing(it).codecId }
    }

    private companion object {
        const val TWO_PEOPLE = """{"call_id":"c","stream_id":"s","epoch":1,"frame_budget_bytes":244,"attendees":["alice"]}"""
        const val THREE_PEOPLE = """{"call_id":"c","stream_id":"s","epoch":1,"frame_budget_bytes":244,"attendees":["alice","bob"],
            "video_allowed":false,"video_max_people":2}"""
        const val READY = """{"type":"ready","frame_budget_bytes":244,"fragmenting":true}"""
        const val TOO_MANY = """{"type":"video_allowed","allowed":false,"people":3,"max_people":2,"reason_id":"call.video_two_people_only"}"""
        const val TWO_AGAIN = """{"type":"video_allowed","allowed":true,"people":2,"max_people":2,"reason_id":"call.video_two_people_only"}"""
    }

    @Test fun theCameraIsHeldOnlyWhileItsPictureIsSent() = runTest {
        val r = Rig(this)
        r.vm.join(video = true); runCurrent()
        assertEquals(0, r.devices.asked, "not before the node is ready for frames")
        r.say(READY); runCurrent()
        assertEquals(1, r.devices.lit)
        r.devices.opened[0].deliver(); r.devices.opened[0].deliver()
        assertEquals(listOf("key", "inter"), r.encoded)
        assertEquals(listOf(CallVideoCodec.AV1_SVC, CallVideoCodec.AV1_SVC), r.sentCodecs)

        // The person turns the camera off: the device's camera is let go, not just unsent.
        r.vm.setCameraOff(true); runCurrent()
        assertEquals(0, r.devices.lit, "camera off releases the camera")
        assertEquals(1, r.devices.opened[0].stops)
        assertEquals("close", r.encoded.last())

        // And on again: a new opening, and the others get a picture they can start from.
        r.vm.setCameraOff(false); runCurrent()
        assertEquals(2, r.devices.opened.size)
        assertEquals(1, r.devices.lit)
        r.encoded.clear()
        r.devices.opened[1].deliver()
        assertEquals(listOf("key"), r.encoded, "the first frame after reopening is a keyframe")

        // A third person joins: the node carries no video, so the camera is not held.
        r.say(TOO_MANY); runCurrent()
        assertFalse(assertIs<CallState.InCall>(r.vm.state.value).videoAllowed)
        assertEquals(0, r.devices.lit, "over the limit releases the camera")

        // Back to two, and the person never turned it off: it sends again.
        r.say(TWO_AGAIN); runCurrent()
        assertEquals(3, r.devices.opened.size)
        assertEquals(1, r.devices.lit)

        r.vm.leave(); runCurrent()
        assertEquals(0, r.devices.lit, "leaving releases the camera")
        assertTrue(r.devices.opened.all { it.stops == 1 }, "each opening is closed once")
    }

    @Test fun aCallOverTheLimitDoesNotOpenTheCameraUntilItIsAllowed() = runTest {
        val r = Rig(this, THREE_PEOPLE)
        r.vm.join(video = true); runCurrent()
        r.say(READY); runCurrent()
        assertEquals(0, r.devices.asked, "nothing would be sent, so the camera is not asked for")
        assertFalse(r.vm.videoUnavailable.value, "the call's size is not a fault of this device")
        r.say(TWO_AGAIN); runCurrent()
        assertEquals(1, r.devices.lit)
    }

    @Test fun aVoiceCallNeverOpensTheCamera() = runTest {
        val r = Rig(this)
        r.vm.join(video = false); runCurrent()
        r.say(READY); runCurrent()
        r.vm.setCameraOff(false); runCurrent()
        r.say(TWO_AGAIN); runCurrent()
        assertEquals(0, r.devices.asked)
    }

    /** No encoder: the camera is let go, the screen says so, and it is not opened again on every event. */
    @Test fun withNoEncoderTheCameraIsLetGoUntilSomethingChanges() = runTest {
        val h264 = FakeH264()
        val r = Rig(this, h264 = h264)
        r.codec = null
        r.vm.join(video = true); runCurrent()
        r.say(READY); runCurrent()
        assertEquals(1, r.devices.opened.size)
        assertEquals(0, r.devices.lit, "a camera with nothing to encode it is released")
        assertEquals(1, r.devices.opened[0].stops)
        assertFalse(r.devices.opened[0].everStarted)
        assertTrue(r.vm.videoUnavailable.value)

        r.say("""{"type":"attendee_joined","member":"carol","stream_id":"x"}"""); runCurrent()
        r.say(TWO_AGAIN); runCurrent()
        assertEquals(1, r.devices.opened.size, "not tried again on every event of the call")

        // The person turns H.264 on: now there is an encoder.
        r.codec = CallVideoCodec.TEMPORAL_LAYERED
        h264.turnOn(); runCurrent()
        assertEquals(2, r.devices.opened.size)
        assertEquals(1, r.devices.lit)
        assertFalse(r.vm.videoUnavailable.value)
        r.devices.opened[1].deliver()
        assertEquals(listOf(CallVideoCodec.TEMPORAL_LAYERED), r.sentCodecs)

        // And off again, while H.264 is what is sent: that stops, and AV1 goes if the machine can.
        r.codec = CallVideoCodec.AV1_SVC
        h264.turnOff(); runCurrent()
        assertEquals(1, r.devices.opened[1].stops)
        assertEquals(3, r.devices.opened.size)
        r.devices.opened[2].deliver()
        assertEquals(CallVideoCodec.AV1_SVC, r.sentCodecs.last())

        // Turning the camera on is asking again, too.
        r.codec = null
        r.vm.setCameraOff(true); runCurrent()
        r.vm.setCameraOff(false); runCurrent()
        assertEquals(0, r.devices.lit)
        assertTrue(r.vm.videoUnavailable.value)
        val tries = r.devices.opened.size
        r.codec = CallVideoCodec.AV1_SVC
        r.vm.setCameraOff(true); runCurrent()
        r.vm.setCameraOff(false); runCurrent()
        assertEquals(tries + 1, r.devices.opened.size)
        assertEquals(1, r.devices.lit)
    }

    /** The browser asks the person for the camera; by the time they answer, the call may be over. */
    @Test fun aCameraThatArrivesAfterTheCallEndedIsLetGoUnused() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>()
        r.devices.gate = gate
        r.vm.join(video = true); runCurrent()
        r.say(READY); runCurrent()
        assertEquals(1, r.devices.asked)
        assertEquals(0, r.devices.opened.size, "still waiting on the person")

        r.vm.leave(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(1, r.devices.opened.size)
        assertFalse(r.devices.opened[0].everStarted, "never started")
        assertEquals(1, r.devices.opened[0].stops, "and released")
        assertTrue(r.encoded.isEmpty())
    }

    /** The same wait, and the person turned the camera off while the browser was asking. */
    @Test fun aCameraThatArrivesAfterCameraOffIsLetGoUnused() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>()
        r.devices.gate = gate
        r.vm.join(video = true); runCurrent()
        r.say(READY); runCurrent()
        r.vm.setCameraOff(true); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(0, r.devices.lit)
        assertEquals(1, r.devices.opened[0].stops)
        assertFalse(r.devices.opened[0].everStarted)
    }

    /** The screen going away ends the call and, before the scope ends, the camera. */
    @Test fun closingReleasesTheCamera() = runTest {
        val r = Rig(this)
        r.vm.join(video = true); runCurrent()
        r.say(READY); runCurrent()
        assertEquals(1, r.devices.lit)
        r.vm.close(); runCurrent()
        assertEquals(0, r.devices.lit)
        assertEquals(1, r.devices.opened[0].stops)
    }
}
