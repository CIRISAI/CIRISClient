package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.ceg.Dim
import ai.ciris.mobile.shared.models.federation.FamilyChangeCarry
import ai.ciris.mobile.shared.models.federation.FamilyDto
import ai.ciris.mobile.shared.models.federation.FamilySignatureDto
import ai.ciris.mobile.shared.ui.primitives.Fact
import ai.ciris.mobile.shared.ui.primitives.Receipt
import ai.ciris.mobile.shared.ui.primitives.SignerState
import kotlinx.serialization.json.Json

/**
 * The pure half of the household cards (CSD-100, the household in the Family
 * hub on Family › Rules; CSD-101, its roster on Family › People): tags, how a family is governed, where an act goes, the
 * signer states of a pending change, and the receipt a household carries.
 * No Compose, so every rule here is tested directly.
 */
object HouseholdTags {
    // ── Shared: which household you are looking at ──
    const val SWITCHER = "households_switcher"
    fun chip(familyId: String) = "chip_household_${slug(familyId)}"
    const val REFRESH = "btn_households_refresh"
    const val REFUSAL = "household_refusal"
    const val NOTICE = "household_notice"
    const val CONFIRM = "confirm_household"

    // ── CSD-100: the household (Family › Rules) ──
    const val LOADING = "households_loading"
    const val EMPTY = "households_empty"
    /** Literal, not a prefix: `test_csd_state_tags` greps for the tag the CSD declares. */
    const val ERROR = "households_error"
    const val NOT_ON_THIS_NODE = "households_not_on_this_node"
    const val CARD = "card_household"
    const val RECORD = "household_record"
    const val NAME = "household_name"
    const val PROTOCOL = "household_protocol"
    const val MY_ROLE = "household_my_role"
    const val FOUNDED = "household_founded"
    const val MEMBER_COUNT = "household_member_count"
    const val GOVERNANCE_NOTE = "household_governance_note"
    const val LIMITS = "household_limits"
    const val FOUNDERS = "household_founders"
    const val LEAVE = "btn_household_leave"
    const val DISSOLVE = "btn_household_dissolve"

    const val CREATE_OPEN = "btn_household_create_open"
    const val CREATE_CARD = "card_household_create"
    const val CREATE_NAME = "input_household_name"
    fun protocolOption(choice: ProtocolChoice) = "opt_household_protocol_${choice.wire}"
    fun founding(keyId: String) = "chk_household_founding_${slug(keyId)}"
    const val CREATE_SUBMIT = "btn_household_create_submit"
    const val CREATE_CANCEL = "btn_household_create_cancel"

    /** CeremonyBlock prefix: `ceremony_household_change`, `household_change_signed_of`, `btn_household_change_sign`. */
    const val CHANGE = "household_change"
    const val CHANGE_COPY = "btn_household_change_copy"
    const val CHANGE_PASTE = "input_household_change_paste"
    const val CHANGE_IMPORT = "btn_household_change_import"
    const val CHANGE_APPLY = "btn_household_change_apply"
    const val CHANGE_NOTE = "household_change_note"

    // ── CSD-101: the roster (Family › People) ──
    const val MEMBERS_LOADING = "household_members_loading"
    const val MEMBERS_ERROR = "household_members_error"
    const val MEMBERS_NOT_ON_THIS_NODE = "household_members_not_on_this_node"
    const val MEMBERS_LIST = "household_members_list"
    const val MEMBERS_NO_HOUSEHOLD = "household_members_no_household"
    const val MEMBERS_GO_RULES = "btn_household_members_go_rules"
    fun member(keyId: String) = "household_member_${slug(keyId)}"
    fun remove(keyId: String) = "btn_household_member_remove_${slug(keyId)}"
    /** Make founder / make member — a role is a fact about one member, so it lives on their row. */
    fun role(keyId: String) = "btn_household_member_role_${slug(keyId)}"
    const val ADD_OPEN = "btn_household_member_add_open"
    const val ADD_CARD = "card_household_member_add"
    fun pick(keyId: String) = "btn_household_member_pick_${slug(keyId)}"
    const val ADD_NO_CONTACTS = "household_member_add_no_contacts"

    /** A key or family id as a tag segment: `family:v1:9f…` → `family_v1_9f…`. */
    fun slug(id: String): String = id.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/** The three protocols a person can choose when forming a household. The node stores the last two as `quorum:M/N`. */
enum class ProtocolChoice(val wire: String) {
    FOUNDER_ONLY("founder_only"),
    MAJORITY("majority"),
    UNANIMOUS("unanimous"),
}

const val ROLE_FOUNDER = "founder"
const val ROLE_MEMBER = "member"

/**
 * How a household is governed, read the way the node reads it
 * (`Protocol::of`, `family_api.rs:416-424`): `founder_only`, or a strict
 * majority `quorum:M/N`. Anything else is a protocol this node refuses to
 * govern (`family.bad_consensus_protocol`), and only leaving still works.
 */
sealed interface Governance {
    data class FounderOnly(val iAmFounder: Boolean) : Governance
    data class Quorum(val m: Int, val n: Int) : Governance
    data class Ungovernable(val protocol: String) : Governance
}

fun governanceOf(protocol: String, myRole: String?): Governance {
    if (protocol == "founder_only") return Governance.FounderOnly(myRole == ROLE_FOUNDER)
    val parts = protocol.removePrefix("quorum:").takeIf { it != protocol }?.split('/')
    val m = parts?.getOrNull(0)?.toIntOrNull()
    val n = parts?.getOrNull(1)?.toIntOrNull()
    if (parts?.size == 2 && m != null && n != null && m >= 1 && m <= n && 2 * m > n) return Governance.Quorum(m, n)
    return Governance.Ungovernable(protocol)
}

/**
 * Something a person asks of a household. Every one but [Leave] is governed by the family's protocol.
 *
 * Two kinds, one door each (CSD-100 §3.1): [OfHousehold] acts are asked on the
 * hub (Family › Rules) and [OfMember] acts on the roster (Family › People), and
 * each screen's confirm sends only its own kind — so no screen can reach a
 * write it has no control for.
 */
sealed interface HouseholdAct {
    /** The envelope `action` a quorum family proposes this as; null for [Leave], which is never proposed. */
    val envelopeAction: String?

    /** Leave, dissolve — the household itself. The hub's acts. */
    sealed interface OfHousehold : HouseholdAct

    /** Add, remove, change a role — one person in it. The roster's acts. */
    sealed interface OfMember : HouseholdAct {
        val keyId: String
        val label: String
    }

    data class Add(override val keyId: String, override val label: String) : OfMember { override val envelopeAction = "add" }
    data class Remove(override val keyId: String, override val label: String) : OfMember { override val envelopeAction = "remove" }
    data class Role(override val keyId: String, override val label: String, val role: String) : OfMember { override val envelopeAction = "role" }
    data object Dissolve : OfHousehold { override val envelopeAction = "dissolve" }
    /** Leaving is always your own act, never subject to quorum (FSD §1 rule 5). */
    data object Leave : OfHousehold { override val envelopeAction: String? = null }
}

/** Where an act goes: one call, a proposal others must sign, or nowhere (the screen says why). */
enum class ActRoute { DIRECT, PROPOSE, NOT_ALLOWED }

/**
 * THE GOVERNANCE RULE, in one place. The node enforces it either way
 * (`require_founder`, `needs_quorum`), so this decides only what the screen
 * offers — and it must never offer a single call to a quorum family, because
 * the node would refuse it with `family.quorum_pending` after the person had
 * already confirmed.
 */
fun routeOf(act: HouseholdAct, governance: Governance): ActRoute = when {
    act is HouseholdAct.Leave -> ActRoute.DIRECT
    governance is Governance.FounderOnly && governance.iAmFounder -> ActRoute.DIRECT
    governance is Governance.Quorum -> ActRoute.PROPOSE
    else -> ActRoute.NOT_ALLOWED
}

/**
 * Each signer's state on a pending change: SIGNED once their signature is in
 * the set, PROPOSED for the member who proposed it and has not signed yet,
 * NOT_YET otherwise. The order is the node's `signers` order.
 */
fun signerStates(
    signers: List<String>,
    signatures: List<FamilySignatureDto>,
    proposer: String?,
): List<Pair<String, SignerState>> {
    val signed = signatures.map { it.memberId }.toSet()
    return signers.map { key ->
        key to when {
            key in signed -> SignerState.SIGNED
            key == proposer -> SignerState.PROPOSED
            else -> SignerState.NOT_YET
        }
    }
}

/** CC 3.3.4 — a household is a `family` record; the section that fixes its shape. */
const val HOUSEHOLD_CC = "CC 3.3.4"

/**
 * The receipt a household carries. The node sends the row's envelope
 * (`family_api.rs:563-569`): the subject (the family id), the key that signed
 * the current record, the cohort scope and the dimension. Each is `Wire` when
 * present and `NotSent` when not — `attester` is null for a row with no signed
 * read, and the sheet must say so rather than guess the founder. No
 * `consent:scope` travels with a family record, so the rule is `NotSent`.
 *
 * [Receipt.dimension] names the registry family the record's changes are
 * recorded under: CC 4.4.3.4.2 emits `hard_case:family_membership_change:{F}`
 * on each admitted change. The record itself has no registry dimension; the
 * sheet shows the wire's own `dimension` value.
 */
fun householdReceipt(family: FamilyDto): Receipt {
    val env = family.envelope
    fun wire(v: String?): Fact = v?.takeIf { it.isNotBlank() }?.let { Fact.Wire(it) } ?: Fact.NotSent
    return Receipt(
        id = HouseholdTags.slug(family.familyId),
        subject = wire(env?.subject),
        attester = wire(env?.attester),
        scope = wire(env?.cohortScope),
        dimension = Dim.hardCaseKind,
        dimensionValue = wire(env?.dimension),
        rule = Fact.NotSent,
        holders = null,
    )
}

private val carryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The text a person copies to the other members: the envelope and the signatures so far. */
fun encodeCarry(carry: FamilyChangeCarry): String = carryJson.encodeToString(FamilyChangeCarry.serializer(), carry)

/** Read a pasted change back, or null for anything that is not one. Never throws on a person's paste. */
fun decodeCarry(text: String): FamilyChangeCarry? = try {
    carryJson.decodeFromString(FamilyChangeCarry.serializer(), text.trim())
} catch (_: Exception) {
    null
}

/** The family a change envelope names (`family_key_id`), or null. */
fun FamilyChangeCarry.familyId(): String? =
    (changeEnvelope["family_key_id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/** The envelope's `action` and `target_key_id`. */
fun FamilyChangeCarry.action(): String? =
    (changeEnvelope["action"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

fun FamilyChangeCarry.targetKeyId(): String? =
    (changeEnvelope["target_key_id"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
