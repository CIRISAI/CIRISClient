package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
// Wildcards on purpose, as in QrScanner.ios.kt: some AVCaptureDevice class
// methods come from Objective-C categories.
import platform.AVFoundation.*
import platform.darwin.*

/**
 * iOS: AVCaptureDevice authorisation for audio and video. Without
 * NSMicrophoneUsageDescription and NSCameraUsageDescription in the host's
 * Info.plist, iOS kills the app on first use.
 */
@Composable
actual fun rememberCallMediaPlatform(): CallMediaPlatform = remember { IosCallMediaPlatform }

private object IosCallMediaPlatform : CallMediaPlatform {
    private fun mediaType(source: MediaSource) = when (source) {
        MediaSource.MICROPHONE -> AVMediaTypeAudio
        MediaSource.CAMERA -> AVMediaTypeVideo
    }

    override fun access(source: MediaSource): MediaAccess {
        // The simulator has no camera; a device without one has no video device.
        if (AVCaptureDevice.defaultDeviceWithMediaType(mediaType(source)) == null) {
            return MediaAccess.Unavailable(MediaUnavailable.NO_DEVICE)
        }
        return when (AVCaptureDevice.authorizationStatusForMediaType(mediaType(source))) {
            AVAuthorizationStatusAuthorized -> MediaAccess.Granted
            AVAuthorizationStatusNotDetermined -> MediaAccess.Askable
            // Denied or Restricted: iOS asks once, ever. Settings is the way back.
            else -> MediaAccess.Denied
        }
    }

    override fun requestAccess(source: MediaSource, onResult: (granted: Boolean) -> Unit) {
        AVCaptureDevice.requestAccessForMediaType(mediaType(source)) { granted ->
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }
}
