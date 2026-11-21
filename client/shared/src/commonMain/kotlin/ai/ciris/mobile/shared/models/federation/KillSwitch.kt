package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * THE KILL SWITCH AS TWO FACTS, NOT ONE (CSD-003 §2.1a; CC 4.2.1.1, 4.2.1.3).
 *
 * ARMED: a genesis is in force, it seats N holders, and this node holds an
 * acceptance edge to that root, so a holder's halt would reach it.
 * STANDING: whether a halt is in force now, and which kind.
 *
 * They were drawn as one line, so "no halt in force" was printed as "disarmed",
 * a claim about the halt's reach the node never made. A node that is not
 * halted may be fully armed.
 */
enum class HaltArmed { ARMED, NOT_ARMED, UNKNOWN }

sealed interface HaltStanding {
    /** Nothing read yet, or the read failed. */
    data object Reading : HaltStanding
    /** The node answered and cannot read its own latch, or did not answer. Never "safe". */
    data object Unknown : HaltStanding
    data object NotHalted : HaltStanding
    /** One holder's halt: it lapses at [lapsesAt] (if the node said) unless a strict majority confirms. */
    data class Lone(val lapsesAt: String?) : HaltStanding
    /** A strict majority confirmed it; it stands until resumed. */
    data object Confirmed : HaltStanding
    /** The last halt's fuse ran out with no majority confirmation: history, not a halt. */
    data object Lapsed : HaltStanding
}

data class KillSwitchReading(val armed: HaltArmed, val standing: HaltStanding)

object KillSwitch {
    /**
     * Read the two facts from `GET /v1/accord/halt-status`.
     *
     * Armed: 0.5.x's halt-status does not report it, and v62's halt-status shape
     * (armed vs standing) is 0.6: pending (CIRISServer), so it is UNKNOWN on
     * every edition today. It is never derived from "not halted".
     */
    fun read(status: AccordHaltStatusResponse?, loading: Boolean): KillSwitchReading {
        val armed = HaltArmed.UNKNOWN // 0.6: pending (CIRISServer): no node reports armed yet.
        val standing = when {
            status == null -> if (loading) HaltStanding.Reading else HaltStanding.Unknown
            status.halted == null -> HaltStanding.Unknown
            status.halted == true -> {
                val h = status.halt
                val confirmed = (h?.get("confirmed") as? JsonPrimitive)?.booleanOrNull
                if (confirmed == true) HaltStanding.Confirmed
                else HaltStanding.Lone((h?.get("lapses_at") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
            }
            status.lapsed -> HaltStanding.Lapsed
            else -> HaltStanding.NotHalted
        }
        return KillSwitchReading(armed, standing)
    }
}
