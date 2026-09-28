package ai.ciris.mobile.shared.viewmodels

import ai.ciris.mobile.shared.models.federation.ContactCodeResponse

/**
 * Which devices a contact code names — the picker on the Share my contact code
 * card (CSD-092). Each maps to one `nodes` query ([contactCodeNodesQuery]).
 */
enum class ContactCodeNodes {
    /** `nodes` absent: every announced device. The default (answered on the 0.5.218 brief). */
    ALL,

    /** `nodes` = the ticked devices, as a comma list. */
    LIST,

    /** `nodes=none`: the fed-ID only; the adder resolves the person through the directory. */
    NONE,
}

/** CC 2.6.8 constraint 3: a code names at most 16 devices. The picker holds the line either way. */
const val CONTACT_CODE_MAX_NODES = 16

/**
 * The `nodes` query for a picker choice. Null means "leave it absent".
 *
 * An empty tick list is sent as `none`, which is what it means: the server
 * refuses an empty comma list as malformed, and a code with no ticked devices
 * IS a code with no devices.
 */
fun contactCodeNodesQuery(mode: ContactCodeNodes, ticked: Set<String>): String? = when (mode) {
    ContactCodeNodes.ALL -> null
    ContactCodeNodes.NONE -> "none"
    ContactCodeNodes.LIST -> if (ticked.isEmpty()) "none" else ticked.sorted().joinToString(",")
}

/** Whether a device row shows ticked: what the current choice will put in the code. */
fun contactCodeRowTicked(mode: ContactCodeNodes, ticked: Set<String>, nodeKeyId: String): Boolean = when (mode) {
    ContactCodeNodes.ALL -> true
    ContactCodeNodes.NONE -> false
    ContactCodeNodes.LIST -> nodeKeyId in ticked
}

/**
 * WHERE People's node calls go (add a contact, the contact code, announce).
 *
 * On a node client the api base IS the node — the local one, or the one the
 * switcher moved to — so it is followed, as the contact list already is (a
 * grant written on the wrong node is irreversible). With an agent in front
 * (with-AI installs, or before the mode is probed) the api base is the AGENT,
 * which does not serve `/v1/self/contact-code` (CIRISAgent#1213); those calls
 * go to the node's own address instead.
 */
fun contactsNodeUrl(isNodeMode: Boolean, baseUrl: String, nodeBaseUrl: String): String =
    if (isNodeMode && baseUrl.isNotBlank()) baseUrl else nodeBaseUrl

/** The Share my contact code card. One of these at a time; the tags are CSD-092's states. */
sealed interface ContactCodeState {
    /** The card is not open; nothing has been asked. */
    data object Closed : ContactCodeState

    /** `contact_code_loading`: the frame and a progress affordance, no QR placeholder. */
    data object Loading : ContactCodeState

    /** `card_contact_code`: a code that reaches the person — QR, text, Copy, picker. */
    data class Ready(val code: ContactCodeResponse) : ContactCodeState

    /**
     * `contact_code_unreachable`: nothing is announced, so any code the node
     * could mint names no device AND resolves through a directory that does
     * not list this person. It reaches no one, so it is not shown.
     */
    data object Unreachable : ContactCodeState

    /** `contact_code_error`: the route is not mounted (bare 404) — a node older than 0.5.218. */
    data object NodeTooOld : ContactCodeState

    /** `contact_code_error`: the node refused or could not answer; by id when it gave one. */
    data class Failed(val reasonId: String?, val detail: String?) : ContactCodeState
}

/** `btn_contact_code_make_reachable` — the announce act from the unreachable card. */
sealed interface MakeReachableState {
    data object Idle : MakeReachableState
    data object Busy : MakeReachableState

    /** Announced. The owner-binding is federation-wide now; the network announce follows at next boot. */
    data class Done(val takesEffect: String?) : MakeReachableState
    data class Failed(val detail: String?) : MakeReachableState
}

/** The last add's outcome, for the add card's success line. */
enum class AddContactOutcome {
    /** A grant row was written: a new contact (or a widened grant). */
    ADDED,

    /** `freshly_emitted: false` — the standing grant already covered it. Not an error. */
    ALREADY,
}
