package ai.ciris.mobile.shared.calls

import ai.ciris.mobile.shared.platform.Platform
import ai.ciris.mobile.shared.platform.createSecureStorage
import ai.ciris.mobile.shared.platform.getPlatform
import ai.ciris.mobile.shared.platform.isLoopbackUrl
import kotlinx.coroutines.flow.Flow

/**
 * The node that carries this call (CSD-134 §2.1). A device is a node, so this is
 * normally the device's own; it is another when the person switched the app to a
 * different node, and always when a browser tab talks to one elsewhere.
 */
data class RelayNode(
    /** Where this node's choice is kept. Every loopback address is this device's one node. */
    val key: String,
    /** The call's media goes through this device. */
    val onThisDevice: Boolean,
    /** This device is a phone carrying its own call, so the phone's network is the one spent. */
    val ownPhone: Boolean,
) {
    companion object {
        private const val HERE = "here"

        fun of(nodeUrl: String, platform: Platform = getPlatform()): RelayNode {
            val here = isLoopbackUrl(nodeUrl)
            return RelayNode(
                key = if (here) HERE else fnv64(normalized(nodeUrl)),
                onThisDevice = here,
                ownPhone = here && (platform == Platform.ANDROID || platform == Platform.IOS),
            )
        }

        /** The address without user-info, a trailing slash or letter case, so one node keeps one choice. */
        private fun normalized(url: String): String {
            val scheme = url.substringBefore("://", "").lowercase()
            val rest = url.substringAfter("://", url).trimEnd('/')
            val authority = rest.substringBefore('/').substringAfterLast('@').lowercase()
            val path = rest.substringAfter('/', "")
            return if (path.isEmpty()) "$scheme://$authority" else "$scheme://$authority/$path"
        }

        /** Short, because desktop preference keys are limited to 80 characters. */
        private fun fnv64(s: String): String {
            var h = 0xcbf29ce484222325uL
            s.encodeToByteArray().forEach { b -> h = (h xor (b.toInt() and 0xFF).toULong()) * 0x100000001b3uL }
            return h.toString(16)
        }
    }
}

/**
 * Whether the node that carries the call carries others' call media too (CSD-134
 * §2.1, the relay switch; Eric's rulings of 2026-11-22 and 2026-11-25):
 *
 * - The person's own choice wins. It is remembered for the node that carries the
 *   call, so a phone's choice and a home node's choice stay apart.
 * - A phone carrying its own call always says: on where its network is known not
 *   to be metered or mobile data, off everywhere else, because relaying spends
 *   someone's data and battery for others. A choice not read yet, and a network
 *   not known yet, are both off.
 * - For any other node nothing is said without a choice, and the node decides
 *   (CIRISServer 0.5.254: never on a CIRIS public server; on elsewhere). This
 *   device's network is not that node's.
 */
object RelayChoice {
    /** [on] is the person's choice, or null where they made none; [known] is false until that has been read. */
    data class Choice(val known: Boolean = false, val on: Boolean? = null)

    /** What to ask of the node now; null leaves it to the node. [metered] null is a network not known. */
    fun wanted(node: RelayNode, choice: Choice, metered: Boolean?): Boolean? = when {
        choice.on != null -> choice.on
        !node.ownPhone -> null
        else -> choice.known && metered == false
    }
}

/** Where the person's relay choice for one node is kept. A test passes its own. */
interface RelayChoiceStore {
    /** The choice, or null where none was made. Throws when it could not be read, which is not "none". */
    suspend fun load(): Boolean?
    suspend fun save(on: Boolean)

    companion object {
        /** This device's own storage, for [node]. */
        fun device(node: RelayNode): RelayChoiceStore = object : RelayChoiceStore {
            private val key = "call_relay_choice.${node.key}"

            override suspend fun load(): Boolean? = when (createSecureStorage().get(key).getOrThrow()) {
                "on" -> true
                "off" -> false
                else -> null
            }

            override suspend fun save(on: Boolean) {
                createSecureStorage().save(key, if (on) "on" else "off").getOrThrow()
            }
        }
    }
}

/**
 * Is this device's active connection metered or mobile data, as it changes?
 * `true` metered or mobile, `false` known to be neither, `null` not known (no
 * network, or the system has not said yet). Only a phone carrying its own call
 * asks. Android: the default network's capabilities. iOS: the current path. A
 * desktop and a browser say nothing (`null`).
 */
expect fun networkMetered(): Flow<Boolean?>
