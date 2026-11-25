package ai.ciris.mobile.shared.calls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue

/** The current path, kept by one monitor for the app's life. Null before the first path, and with no network. */
private val meteredNow = MutableStateFlow<Boolean?>(null)

/** Expensive (cellular, a hotspot), Low Data Mode, or a path over cellular. */
@OptIn(ExperimentalForeignApi::class)
private val monitor by lazy {
    nw_path_monitor_create().also { m ->
        nw_path_monitor_set_update_handler(m) { path ->
            meteredNow.value = if (nw_path_get_status(path) != nw_path_status_satisfied) {
                null
            } else {
                nw_path_is_expensive(path) || nw_path_is_constrained(path) ||
                    nw_path_uses_interface_type(path, nw_interface_type_cellular)
            }
        }
        nw_path_monitor_set_queue(m, dispatch_get_main_queue())
        nw_path_monitor_start(m)
    }
}

actual fun networkMetered(): Flow<Boolean?> {
    monitor
    return meteredNow
}
