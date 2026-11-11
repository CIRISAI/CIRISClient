package ai.ciris.mobile.shared.calls.media

import kotlinx.coroutines.flow.StateFlow

/**
 * H.264 that is not part of this app (CSD-134).
 *
 * Android, iOS and the browser encode and decode H.264 with the system's own
 * codec. A desktop has none this app may use, and H.264 is patented, so the app
 * carries no H.264 of its own that it calls. It uses the library Cisco publishes
 * (OpenH264), which Cisco licenses for everyone on four conditions:
 *
 * 1. the person's own device downloads it from Cisco, apart from this app;
 * 2. the person can turn it on, off and on again;
 * 3. the screen that has that control shows [ATTRIBUTION], word for word;
 * 4. Cisco's licence text ([licence]) is where the person can read it.
 *
 * So H.264 on desktop is off until the person turns it on, and nothing is
 * fetched before that. Off, a desktop still sends and shows AV1.
 */
interface H264Download {
    enum class State {
        /** Not here. Nothing has been downloaded, or it was turned off and deleted. */
        OFF,
        DOWNLOADING,
        /** Downloaded, checked against the hash Cisco publishes, and loaded. */
        ON,
        /** Cisco's server could not be reached, or the download stopped. Nothing was kept. */
        FAILED_DOWNLOAD,
        /** What arrived was not the file Cisco published. It was deleted. */
        FAILED_CHECK,
        /** The right file, and this machine would not load it. */
        FAILED_LOAD,
    }

    val state: StateFlow<State>

    /** Cisco's licence for the library, word for word, in English as Cisco publishes it. */
    val licence: String

    /** Download (if it is not here), check, and use it. Does nothing while downloading or on. */
    fun turnOn()

    /** Stop using it and delete it. Encoders and decoders already made stop doing anything. */
    fun turnOff()

    companion object {
        /** Cisco's condition 3 gives this sentence exactly. It is not translated and not reworded. */
        const val ATTRIBUTION = "OpenH264 Video Codec provided by Cisco Systems, Inc."
    }
}

/**
 * The control for this device's downloaded H.264, or null where there is none to
 * control: the system supplies H.264 (Android, iOS, the browser), or Cisco
 * publishes no library for this kind of computer.
 */
expect fun h264Download(): H264Download?
