package ai.ciris.mobile.shared.calls

import ai.ciris.mobile.shared.calls.media.androidCallMediaContext
import android.content.Context
import android.net.ConnectivityManager

/** Mobile data, and Wi-Fi the person marked as metered, both read as metered. */
actual fun networkIsMetered(): Boolean {
    val ctx = androidCallMediaContext?.applicationContext ?: return false
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
}
