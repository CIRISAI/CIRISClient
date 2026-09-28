package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The scan control's behaviour, without a camera. What it pins: a platform
 * with no camera shows a sentence and never a button; a refusal is its own
 * state with its own sentence, distinct from "no camera here"; and nothing
 * but a granted camera opens the preview.
 */
class QrScanStateTest {

    private val everyUnavailable = QrScanUnavailable.entries.map { QrCameraAccess.Unavailable(it) }

    @Test
    fun desktop_and_web_start_unavailable_and_stay_there() {
        val s = qrScanInitial(NoCameraQrScanPlatform.access())
        assertEquals(QrScanState.Unavailable(QrScanUnavailable.PLATFORM), s)
        val events = listOf(
            QrScanEvent.Tap(QrCameraAccess.Granted),
            QrScanEvent.PermissionResult(true),
            QrScanEvent.Scanned,
            QrScanEvent.Cancel,
            QrScanEvent.CameraFailed,
        )
        for (e in events) assertEquals(s, s.on(e), "Unavailable moved on $e")
        assertEquals("mobile.qr_scan_unavailable_platform", s.messageKey())
    }

    @Test
    fun a_device_with_a_camera_starts_on_the_button() {
        for (a in listOf(QrCameraAccess.Granted, QrCameraAccess.Askable, QrCameraAccess.Denied)) {
            assertEquals(QrScanState.Idle, qrScanInitial(a))
        }
        assertNull(QrScanState.Idle.messageKey(), "Idle's button speaks for itself")
    }

    @Test
    fun tap_goes_where_access_says() {
        val idle = QrScanState.Idle
        assertEquals(QrScanState.Scanning, idle.on(QrScanEvent.Tap(QrCameraAccess.Granted)))
        assertEquals(QrScanState.Asking, idle.on(QrScanEvent.Tap(QrCameraAccess.Askable)))
        assertEquals(QrScanState.PermissionDenied, idle.on(QrScanEvent.Tap(QrCameraAccess.Denied)))
        for (u in everyUnavailable) {
            assertEquals(QrScanState.Unavailable(u.reason), idle.on(QrScanEvent.Tap(u)))
        }
    }

    @Test
    fun the_permission_answer_decides_between_preview_and_refusal() {
        assertEquals(QrScanState.Scanning, QrScanState.Asking.on(QrScanEvent.PermissionResult(true)))
        assertEquals(QrScanState.PermissionDenied, QrScanState.Asking.on(QrScanEvent.PermissionResult(false)))
        // A stray tap while the OS prompt is up does not open the camera.
        assertEquals(QrScanState.Asking, QrScanState.Asking.on(QrScanEvent.Tap(QrCameraAccess.Granted)))
    }

    @Test
    fun only_a_granted_camera_opens_the_preview() {
        val states = listOf(QrScanState.Idle, QrScanState.Asking, QrScanState.PermissionDenied, QrScanState.CameraFailed) +
            QrScanUnavailable.entries.map { QrScanState.Unavailable(it) }
        val notGranted = listOf(QrCameraAccess.Askable, QrCameraAccess.Denied) + everyUnavailable
        for (s in states) {
            for (a in notGranted) {
                assertNotEquals(QrScanState.Scanning, s.on(QrScanEvent.Tap(a)), "$s + tap($a)")
            }
            assertNotEquals(QrScanState.Scanning, s.on(QrScanEvent.PermissionResult(false)), "$s + denied")
        }
    }

    @Test
    fun scanning_ends_on_a_read_a_stop_or_a_failure() {
        assertEquals(QrScanState.Idle, QrScanState.Scanning.on(QrScanEvent.Scanned))
        assertEquals(QrScanState.Idle, QrScanState.Scanning.on(QrScanEvent.Cancel))
        assertEquals(QrScanState.CameraFailed, QrScanState.Scanning.on(QrScanEvent.CameraFailed))
    }

    @Test
    fun a_refusal_and_a_failure_can_be_retried() {
        for (s in listOf(QrScanState.PermissionDenied, QrScanState.CameraFailed)) {
            assertEquals(QrScanState.Scanning, s.on(QrScanEvent.Tap(QrCameraAccess.Granted)))
        }
    }

    @Test
    fun every_state_that_is_not_a_button_says_something_and_no_two_say_the_same() {
        val states = listOf(QrScanState.Scanning, QrScanState.Asking, QrScanState.PermissionDenied, QrScanState.CameraFailed) +
            QrScanUnavailable.entries.map { QrScanState.Unavailable(it) }
        val keys = states.map { assertNotNull(it.messageKey(), "$it has no sentence") }
        assertEquals(keys.size, keys.toSet().size, "two states share a sentence: $keys")
    }
}
