package ai.ciris.mobile.shared.calls

import ai.ciris.mobile.shared.calls.media.androidCallMediaContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Mobile data (even where the plan calls it unmetered), and Wi-Fi the person marked as metered. */
private fun metered(c: NetworkCapabilities): Boolean =
    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

actual fun networkMetered(): Flow<Boolean?> = callbackFlow<Boolean?> {
    val cm = androidCallMediaContext?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(metered(caps)) }
        override fun onLost(network: Network) { trySend(null) }
    }
    // Unknown until the system's first callback, which comes at once where there is a network.
    trySend(null)
    val watching = cm != null && runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
    awaitClose { if (watching) runCatching { cm?.unregisterNetworkCallback(callback) } }
}.conflate()
