package ai.ciris.mobile.shared.approvals

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * IDENTITY CHANGES A WISE AUTHORITY APPROVES (CIRISAgent v2.23.1-stable, 55a2447a6;
 * 2.23.2 #1373).
 *
 * The agent proposes a wording for its own identity, with an M-1 (Adaptive
 * Coherence) justification, as a deferral. A Wise Authority approves those EXACT
 * bytes; the agent's consciences then review the write, and the node signs it. A
 * fresh agent first asks for its "base link": approval of the shipped template it
 * runs on. Until that is approved no amendment counts.
 *
 * Eric (2026-11-20): the client must show this accurately and robustly, as a
 * request to change the agent's template, with the justification clear. So
 * everything here is parsed leniently and decided in one place: a proposal that
 * cannot be read is shown read-only, never approved on a guess.
 */

/** `GET /v1/wa/deferrals` → `profile.status`. Anything unknown is [UNKNOWN], never anchored. */
enum class IdentityProfileStatus { ANCHORED, UNANCHORED, MISMATCH, CONTESTED, UNKNOWN;
    companion object {
        fun of(wire: String?): IdentityProfileStatus = when (wire?.lowercase()) {
            "anchored" -> ANCHORED
            "unanchored" -> UNANCHORED
            "mismatch" -> MISMATCH
            "contested" -> CONTESTED
            else -> UNKNOWN
        }
    }
}

data class IdentityProfile(
    val status: IdentityProfileStatus,
    /** The base-link deferral's id, or whatever the agent named as the proposed base link. */
    val proposedBaseLink: String?,
)

/**
 * `capabilities` on the deferral list. Each member is a bool that defaults to true
 * in the agent's schema; a missing object or member is an OLDER agent, so false.
 */
data class IdentityCapabilities(
    val identityChangeReview: Boolean = false,
    val exactAmendmentText: Boolean = false,
    val baseLinkApproval: Boolean = false,
) {
    companion object { val NONE = IdentityCapabilities() }
}

/** What the list carries besides the deferrals. */
data class DeferralListMeta(
    val profile: IdentityProfile? = null,
    val capabilities: IdentityCapabilities = IdentityCapabilities.NONE,
) {
    companion object { val NONE = DeferralListMeta() }
}

/** The deferrals and their [meta], from one read. */
data class DeferralList(
    val deferrals: List<ai.ciris.mobile.shared.api.DeferralData>,
    val meta: DeferralListMeta = DeferralListMeta.NONE,
)

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/**
 * Read `profile` and `capabilities` from the raw list body. The body may be the
 * `SuccessResponse` wrapper (`{data: {...}}`) or the bare list. Any failure is
 * [DeferralListMeta.NONE]: the plain deferral view, never a crash.
 */
fun parseDeferralListMeta(raw: String?): DeferralListMeta {
    if (raw.isNullOrBlank()) return DeferralListMeta.NONE
    val root = runCatching { lenient.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return DeferralListMeta.NONE
    val body = root["data"].obj() ?: root
    val profile = body["profile"].obj()?.let {
        IdentityProfile(IdentityProfileStatus.of(it["status"].str()), it["proposed_base_link"].str())
    }
    val caps = body["capabilities"].obj()?.let {
        IdentityCapabilities(
            identityChangeReview = it["identity_change_review"].bool() == true,
            exactAmendmentText = it["exact_amendment_text"].bool() == true,
            baseLinkApproval = it["base_link_approval"].bool() == true,
        )
    } ?: IdentityCapabilities.NONE
    return DeferralListMeta(profile, caps)
}

/** The agent's template, for a base-link request. */
data class IdentityTemplate(val name: String?, val version: String?, val manifestDigest: String?)

/** A conscience's objection on a re-deferral. */
data class ConscienceObjection(val conscience: String?, val type: String?, val reason: String?)

/** `context.identity_change`, by kind. */
sealed interface IdentityChange {
    val summary: String?

    /** "Request to change this agent's identity". */
    data class Amendment(
        override val summary: String?,
        val proposedText: String,
        val m1Justification: String?,
        val waEdited: Boolean,
        val objection: ConscienceObjection?,
    ) : IdentityChange

    /** "Re-approve an existing identity amendment": it predates exact-text approval. */
    data class Reapproval(
        override val summary: String?,
        val proposedText: String,
        val m1Justification: String?,
        val supersedesNodeId: String?,
        val waEdited: Boolean,
        val objection: ConscienceObjection?,
    ) : IdentityChange

    /** "Anchor this agent's identity to its template". */
    data class BaseLink(override val summary: String?, val template: IdentityTemplate) : IdentityChange

    /**
     * A kind this client does not know, or a proposal it could not read whole: the
     * summary and context are shown read-only and nothing can be approved.
     */
    data class Unreadable(override val summary: String?, val kind: String?) : IdentityChange
}

/** The context keys an identity card shows itself; the raw context lines leave them out. */
val IDENTITY_CHANGE_RENDERED_KEYS = setOf(
    "identity_change", "proposed_amendment", "m1_justification", "proposed_base_link",
    "conscience_objection", "conscience_objection_type", "conscience",
)

/**
 * The identity change a deferral carries, or null when it carries none.
 *
 * `context.identity_change` (a JSON string) is preferred. Without it, the flat
 * `proposed_amendment` (+ `m1_justification`, and a re-deferral's
 * `conscience_objection*`) is read as an amendment, as an agent before 2.23.1
 * sent it. The proposed text is kept BYTE FOR BYTE: it is what will be signed.
 */
fun parseIdentityChange(context: Map<String, String>?): IdentityChange? {
    if (context == null) return null
    val rawChange = context["identity_change"]
    if (rawChange == null) {
        val text = context["proposed_amendment"] ?: return null
        return IdentityChange.Amendment(
            summary = null,
            proposedText = text,
            m1Justification = context["m1_justification"]?.takeIf { it.isNotEmpty() },
            waEdited = false,
            objection = flatObjection(context),
        )
    }
    val o = runCatching { lenient.parseToJsonElement(rawChange).jsonObject }.getOrNull()
        ?: return IdentityChange.Unreadable(summary = null, kind = null)
    val kind = o["kind"].str()
    val summary = o["summary"].str()
    val m1 = o["m1_justification"].str()?.takeIf { it.isNotEmpty() }
    val waEdited = o["wa_edited"].bool() == true
    val objection = o["objection"].obj()?.let {
        ConscienceObjection(it["conscience"].str(), it["objection_type"].str(), it["reason"].str())
    }
    return when (kind) {
        "amendment" -> o["proposed_text"].str()?.let { IdentityChange.Amendment(summary, it, m1, waEdited, objection) }
        "reapproval" -> o["proposed_text"].str()?.let {
            IdentityChange.Reapproval(summary, it, m1, o["supersedes_node_id"].str(), waEdited, objection)
        }
        "base_link" -> o["template"].obj()?.let {
            IdentityChange.BaseLink(
                summary,
                IdentityTemplate(it["template_name"].str(), it["template_version"].str(), it["manifest_digest"].str()),
            )
        }
        else -> null
    } ?: IdentityChange.Unreadable(summary, kind)
}

private fun flatObjection(context: Map<String, String>): ConscienceObjection? {
    val reason = context["conscience_objection"] ?: return null
    return ConscienceObjection(context["conscience"], context["conscience_objection_type"], reason)
}

/** What the WA's Approve sends besides `{resolution, guidance}`: exactly one subject. */
sealed interface ResolveSubject {
    /** `amendment_text`: the stored proposal, byte for byte. */
    data class AmendmentText(val text: String) : ResolveSubject
    /** `base_link_digest`: the template's manifest digest. */
    data class BaseLinkDigest(val digest: String) : ResolveSubject
}

/** Why Approve is off on an identity card. Null = it may be approved. */
enum class ApproveBlocked {
    /** The agent is not anchored: an approved amendment would fail with no chain head. */
    BASE_LINK_FIRST,
    /** The stored text has outer whitespace, which the API refuses; it is never trimmed here. */
    OUTER_WHITESPACE,
    /** No digest to approve. */
    NO_DIGEST,
    /** The agent does not declare this kind of approval: it would ignore the subject. */
    AGENT_CANNOT_BIND,
    /** The proposal could not be read. */
    UNREADABLE,
}

private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

/**
 * Can this identity change be approved here, and with what? Decided in one place
 * so the screen and the tests agree.
 */
fun identityApproval(
    change: IdentityChange,
    meta: DeferralListMeta,
): Pair<ResolveSubject?, ApproveBlocked?> = when (change) {
    is IdentityChange.Unreadable -> null to ApproveBlocked.UNREADABLE
    is IdentityChange.BaseLink -> {
        val d = change.template.manifestDigest
        when {
            !meta.capabilities.baseLinkApproval -> null to ApproveBlocked.AGENT_CANNOT_BIND
            d == null || !HEX64.matches(d) -> null to ApproveBlocked.NO_DIGEST
            else -> ResolveSubject.BaseLinkDigest(d) to null
        }
    }
    is IdentityChange.Amendment, is IdentityChange.Reapproval -> {
        val text = when (change) {
            is IdentityChange.Amendment -> change.proposedText
            is IdentityChange.Reapproval -> change.proposedText
            else -> error("unreachable")
        }
        val status = meta.profile?.status
        when {
            !meta.capabilities.exactAmendmentText -> null to ApproveBlocked.AGENT_CANNOT_BIND
            status != null && status != IdentityProfileStatus.ANCHORED -> null to ApproveBlocked.BASE_LINK_FIRST
            text.isEmpty() || text != text.trim() -> null to ApproveBlocked.OUTER_WHITESPACE
            else -> ResolveSubject.AmendmentText(text) to null
        }
    }
}

/**
 * The identity change this deferral carries, when the agent declares
 * `identity_change_review`. An agent without it gets the plain deferral view:
 * its context keys mean something else there, so nothing is read into them.
 */
fun identityChangeOf(deferral: ai.ciris.mobile.shared.api.DeferralData, meta: DeferralListMeta): IdentityChange? =
    if (!meta.capabilities.identityChangeReview) null else parseIdentityChange(deferral.context)

/**
 * The base-link deferral the profile banner opens: the one `profile.proposed_base_link`
 * names, else the first deferral whose change is a base link.
 */
fun baseLinkDeferral(
    deferrals: List<ai.ciris.mobile.shared.api.DeferralData>,
    meta: DeferralListMeta,
): ai.ciris.mobile.shared.api.DeferralData? {
    if (!meta.capabilities.identityChangeReview) return null
    meta.profile?.proposedBaseLink?.let { id -> deferrals.firstOrNull { it.deferralId == id }?.let { return it } }
    return deferrals.firstOrNull { identityChangeOf(it, meta) is IdentityChange.BaseLink }
}
