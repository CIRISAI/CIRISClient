package ai.ciris.mobile.shared.calls

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.darwin.dispatch_get_main_queue

/** The current path, kept by one monitor for the app's life: expensive (cellular, hotspot) or Low Data Mode. */
@kotlin.concurrent.Volatile
private var meteredNow = false

@OptIn(ExperimentalForeignApi::class)
private val monitor by lazy {
    nw_path_monitor_create().also { m ->
        nw_path_monitor_set_update_handler(m) { path ->
            meteredNow = nw_path_is_expensive(path) || nw_path_is_constrained(path)
        }
        nw_path_monitor_set_queue(m, dispatch_get_main_queue())
        nw_path_monitor_start(m)
    }
}

/** Before the first path arrives this reads false, and the node's default applies. */
actual fun networkIsMetered(): Boolean {
    monitor
    return meteredNow
}
