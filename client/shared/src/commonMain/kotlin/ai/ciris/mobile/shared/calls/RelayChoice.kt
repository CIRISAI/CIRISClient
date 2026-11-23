package ai.ciris.mobile.shared.calls

import ai.ciris.mobile.shared.platform.createSecureStorage

/**
 * Whether this device carries others' call media (CSD-134 §2.1, the relay
 * switch; Eric's ruling relayed 2026-11-22):
 *
 * - The person's own choice wins, and is remembered per device.
 * - With no choice made, a metered or mobile connection sends `relay:false`,
 *   because relaying spends someone's data and battery for others.
 * - Otherwise nothing is sent, and the node decides (CIRISServer 0.5.254: never
 *   on a CIRIS public server; the member default elsewhere).
 */
object RelayChoice {
    /** What a join sends: the choice, else false when metered, else nothing. */
    fun forJoin(choice: Boolean?, metered: Boolean): Boolean? = choice ?: if (metered) false else null
}

/** Where the person's relay choice is kept. A test passes its own. */
interface RelayChoiceStore {
    suspend fun load(): Boolean?
    suspend fun save(on: Boolean)

    companion object {
        private const val KEY = "call_relay_choice"

        /** This device's own storage. A read that fails reads as "no choice made". */
        fun device(): RelayChoiceStore = object : RelayChoiceStore {
            override suspend fun load(): Boolean? = when (createSecureStorage().get(KEY).getOrNull()) {
                "on" -> true
                "off" -> false
                else -> null
            }

            override suspend fun save(on: Boolean) {
                createSecureStorage().save(KEY, if (on) "on" else "off")
            }
        }
    }
}

/**
 * Is this device's active connection metered or mobile data? Android asks the
 * system (`isActiveNetworkMetered`), iOS the current path (`isExpensive` or
 * `isConstrained`), a browser `navigator.connection` where it exists. A desktop
 * has no such signal and reads as not metered. Unknown is false: the node's own
 * default then applies, which never relays on a CIRIS public server.
 */
expect fun networkIsMetered(): Boolean
