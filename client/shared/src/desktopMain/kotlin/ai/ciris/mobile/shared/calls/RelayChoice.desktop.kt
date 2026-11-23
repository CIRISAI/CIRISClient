package ai.ciris.mobile.shared.calls

/** A desktop OS gives no portable "metered" signal; it is treated as unmetered. */
actual fun networkIsMetered(): Boolean = false
