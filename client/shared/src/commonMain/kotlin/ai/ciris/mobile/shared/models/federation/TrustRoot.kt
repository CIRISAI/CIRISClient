package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.api.NodeRefusal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// ─── This node's trust root (CIRISServer src/trust_root_api.rs, #400) ─────────
//
// The accord family IS the default trust root: `GET /v1/trust-root` always lists
// `humanity-accord` first (`trust_root_api.rs:156-157`). These routes are this
// node's side of it — does its own `trust:accepts` edge reach the root, and is the
// root sound by persist's `trust_root_valid` — plus the two levers CC 3.2 says a
// conformant consumer MUST have (part_3 "Default trust, not forced root"; T3 "the
// un-trust lever is one row"): adopt a portable seed, and withdraw an acceptance.
//
// The three routes are LOOPBACK-ONLY (`trust_root_api.rs:422-424`,
// CIRISServer#652): a device on the node's own machine reaches them, a phone never
// does. That refusal is a fact about WHERE the caller is, and it is rendered as
// such — never as "this node trusts nothing".

/** `GET /v1/trust-root` (`trust_root_api.rs:55-66`). */
@Serializable
data class TrustRootListingDto(
    /** persist's `GenesisPosture`, `{"state": …, "leg"?, "detail"?}` (serde tag = `state`). */
    val posture: JsonElement? = null,
    /** The node's operator sentence; null once entrenched. */
    val banner: String? = null,
    val entrenched: Boolean = false,
    val roots: List<TrustRootEntryDto> = emptyList(),
)

/** One root (`trust_root_api.rs:68-80`). [verdict] is persist's `TrustRootVerdict`, verbatim. */
@Serializable
data class TrustRootEntryDto(
    @SerialName("root_key_id")
    val rootKeyId: String = "",
    @SerialName("root_kind")
    val rootKind: String = "",
    /**
     * WRONG ON THE WIRE, and deliberately unread (CIRISServer#681): the server
     * fills this from `verdict.user_accepts`, a field `TrustRootVerdict` does not
     * have (the fact is `edge_exists`), so it is `false` for every root —
     * including the one the node is entrenched under. [trustRootView] reads
     * `verdict.edge_exists` instead. Kept so the payload still decodes.
     */
    val accepted: Boolean = false,
    val verdict: JsonElement? = null,
)

/** `POST /v1/trust-root/import` (`trust_root_api.rs:215-226`). Two acts, reported apart. */
@Serializable
data class TrustRootImportResult(
    /** The root's records were installed — it became KNOWN. */
    val installed: Boolean = false,
    /** This node's `trust:accepts` edge was written — it became TRUSTED. */
    val accepted: Boolean = false,
    val posture: JsonElement? = null,
    val entrenched: Boolean = false,
    val banner: String? = null,
)

/**
 * Installed and NOT accepted: the root is known here and not trusted. Since
 * ciris-server 0.5.220 this is also how a DEFERRED acceptance reports (the node
 * does not hold the root's genesis head yet; persist retries at the next boot
 * or import) — before, that case read `accepted: true`. Never a success.
 */
fun importNotYetAccepted(r: TrustRootImportResult): Boolean = r.installed && !r.accepted

/**
 * `GET /v1/trust-root/bundle` (ciris-server 0.5.220, CIRISServer#726, PUBLIC):
 * the genesis bundle this node runs on, in the registry's shape. The bundle
 * itself is read and not kept here — the card shows what a person compares out
 * of band: the fingerprint and the charter root. `community` is the
 * `ciris-canonical` birth, null before a v3 bundle.
 */
@Serializable
data class TrustRootBundleDto(
    @SerialName("bundle_fingerprint")
    val bundleFingerprint: String? = null,
    @SerialName("charter_root_key_id")
    val charterRootKeyId: String? = null,
    @SerialName("served_by")
    val servedBy: String? = null,
    val community: JsonElement? = null,
)

/** What the card shows of the served bundle; null when the node sent nothing comparable. */
data class ServedBundleView(val fingerprint: String, val charterRootKeyId: String?, val carriesCommunity: Boolean)

fun servedBundleView(dto: TrustRootBundleDto): ServedBundleView? {
    val fp = dto.bundleFingerprint?.takeIf { it.isNotBlank() } ?: return null
    return ServedBundleView(
        fingerprint = fp,
        charterRootKeyId = dto.charterRootKeyId?.takeIf { it.isNotBlank() },
        carriesCommunity = dto.community != null && dto.community !is kotlinx.serialization.json.JsonNull,
    )
}

/** CIRISServer#726 (814dd7c6): `GET /v1/trust-root/bundle` on a node not entrenched on its bake. */
const val TRUST_ROOT_BUNDLE_NOT_IN_FORCE = "trust_root.bundle_not_in_force"

/** What the served-bundle card shows. Exactly one at a time. */
sealed interface ServedBundleRead {
    /** No route (bare 404), unreachable, or nothing comparable: no card at all. */
    data object Absent : ServedBundleRead
    data class Shown(val view: ServedBundleView) : ServedBundleRead
    /**
     * `409 trust_root.bundle_not_in_force`: this node is not running on the
     * bundle it carries yet, so it serves none. A named state, not an error;
     * [detail] is the node's words (persist's banner rides inside it).
     */
    data class NotInForce(val detail: String?) : ServedBundleRead
}

fun servedBundleRead(dto: TrustRootBundleDto): ServedBundleRead =
    servedBundleView(dto)?.let { ServedBundleRead.Shown(it) } ?: ServedBundleRead.Absent

fun servedBundleRead(e: Throwable): ServedBundleRead =
    if (e is NodeRefusal && e.reasonId == TRUST_ROOT_BUNDLE_NOT_IN_FORCE) ServedBundleRead.NotInForce(e.detail)
    else ServedBundleRead.Absent

/** `DELETE /v1/trust-root/{root_key_id}` (`trust_root_api.rs:388-396`). */
@Serializable
data class TrustRootUntrustResult(
    @SerialName("root_key_id")
    val rootKeyId: String = "",
    /** False when there was no live acceptance to withdraw (already un-trusted). */
    val withdrawn: Boolean = false,
    /** Always true today: the root's signed records are history and are kept. */
    @SerialName("records_retained")
    val recordsRetained: Boolean = true,
    val entrenched: Boolean = false,
    val banner: String? = null,
)

/** `GET /v1/accord/family/history` (`src/accord.rs:2355`) — persist `GroupVersion`s (`cohort.rs:706`). */
@Serializable
data class FamilyHistoryResponse(
    val versions: List<FamilyVersionDto> = emptyList(),
)

@Serializable
data class FamilyVersionDto(
    @SerialName("group_key_id")
    val groupKeyId: String = "",
    val version: Int = 0,
    /** The full `Family` row at this version. */
    val snapshot: JsonElement? = null,
    /** The quorum envelope that produced this version; absent for genesis. */
    val authorization: JsonElement? = null,
    @SerialName("superseded_at")
    val supersededAt: String? = null,
    @SerialName("is_current")
    val isCurrent: Boolean = false,
)

// ─── Pure views ──────────────────────────────────────────────────────────────

/** persist `GenesisPosture` (v48.0.0 `genesis/posture.rs:255`), each arm its own rendering. */
enum class GenesisState {
    /** The one green arm. */
    ENTRENCHED,
    /** A leg is simply not installed — a node awaiting a root, not a broken one. */
    PRE_GENESIS,
    /** A constitutional row is present and WRONG — an established root altered. */
    DIVERGENT,
    /** The backend could not answer. Not entrenched; rendered as an error. */
    UNREADABLE,
    /** A state this client has never heard of — shown verbatim, never as green. */
    UNKNOWN,
}

data class TrustPosture(
    val state: GenesisState,
    /** The raw `state` token, for [GenesisState.UNKNOWN]. */
    val token: String,
    val leg: String?,
    val detail: String?,
    val banner: String?,
    val entrenched: Boolean,
    /**
     * CIRISPersist#973 (v53, ciris-server 0.5.220): a `pre_genesis` leg whose
     * `reason.kind` is `bake_not_adopted` — this binary carries a newer root
     * the node did NOT adopt. Not "awaiting a ceremony". False on an older node
     * (no `reason`), which reads as `not_seeded`, persist's own default.
     */
    val bakeNotAdopted: Boolean = false,
    /**
     * With [bakeNotAdopted]: the node still holds its PREVIOUS verified root on
     * that leg, and it stays in force. The banner is then persist's "ROOT NOT
     * ADOPTED", never "NO TRUST ROOT", and the headline must not say "not rooted".
     */
    val heldRootInForce: Boolean = false,
)

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonObject?.bool(key: String): Boolean? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

fun trustPosture(posture: JsonElement?, banner: String?, entrenched: Boolean): TrustPosture {
    val o = posture.obj()
    // Tolerate a bare string token too — cheap, and a shape change must not read as green.
    val token = o.str("state") ?: (posture as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val state = when (token) {
        "entrenched" -> GenesisState.ENTRENCHED
        "pre_genesis" -> GenesisState.PRE_GENESIS
        "divergent" -> GenesisState.DIVERGENT
        "unreadable" -> GenesisState.UNREADABLE
        else -> GenesisState.UNKNOWN
    }
    // persist v53 `AbsentReason`, tagged by `kind`, inside a pre_genesis posture only.
    val reason = (o?.get("reason") as? JsonObject)
    val notAdopted = state == GenesisState.PRE_GENESIS && reason.str("kind") == "bake_not_adopted"
    return TrustPosture(
        // `entrenched` is persist's own bool; a token that says entrenched while the
        // bool says not is a disagreement, and it is not rendered green.
        state = if (state == GenesisState.ENTRENCHED && !entrenched) GenesisState.UNKNOWN else state,
        token = token,
        leg = o.str("leg"),
        detail = o.str("detail"),
        banner = banner?.takeIf { it.isNotBlank() },
        entrenched = entrenched,
        bakeNotAdopted = notAdopted,
        heldRootInForce = notAdopted && reason.bool("held_root_in_force") == true,
    )
}

fun TrustRootListingDto.trustPosture(): TrustPosture = trustPosture(posture, banner, entrenched)

/** CC 3.2 T4's bands (0–90 green · 90–180 yellow · 180+ or never red) — a signal, never a gate. */
enum class DrillBand { GREEN, YELLOW, RED }

enum class RootKindView { FAMILY, KEY, UNREADABLE, OTHER }

data class CharterQuorumView(val distinctHolders: Int, val required: Int, val rosterSize: Int) {
    val met: Boolean get() = distinctHolders >= required
}

data class HolderHardwareView(
    val keyId: String,
    /** Layer A's hardware class, verbatim; null = no evidence, or a class the policy does not accept. */
    val hardwareClass: String?,
    val layerA: Boolean,
    /** Layer B: true/false, or null = no vendor root pinned for this class (never a refusal). */
    val layerB: Boolean?,
    val refusal: String?,
)

data class TrustRootView(
    val rootKeyId: String,
    val kind: RootKindView,
    /** The raw kind token, for [RootKindView.OTHER]. */
    val kindToken: String,
    /** This node's own `trust:accepts` edge reaches the root; null = the node could not say. */
    val acceptedByThisNode: Boolean?,
    /** `trust_root_valid`'s gate; null when the root could not be evaluated. */
    val valid: Boolean?,
    /** The root's own `trust:charter:v1` names itself (persist `root_self_declares`); null = not evaluated. */
    val rootSelfDeclares: Boolean?,
    /** That charter carries a pre-rotation recovery commitment — the T3 leg (`charter_has_recovery`). */
    val charterHasRecovery: Boolean?,
    val quorum: CharterQuorumView?,
    val holders: List<HolderHardwareView>,
    val holdersAttested: Boolean?,
    val drillBand: DrillBand?,
    /** Null with a band = never drilled (persist folds "never" into RED on purpose). */
    val lastDrillAt: String?,
    /** true latched · false clear · null this backend cannot answer. */
    val haltLatched: Boolean?,
    /** When this verdict can first stop holding on time alone; null = nothing time-bounded. */
    val boundedUntil: String?,
    /** The node could not evaluate this root (`root_kind: "unreadable"`, `verdict.error`). */
    val evaluationError: String?,
)

fun trustRootView(entry: TrustRootEntryDto): TrustRootView {
    val v = entry.verdict.obj()
    val evaluationError = v.str("error")
    val kindToken = entry.rootKind.ifBlank { v.str("root_kind").orEmpty() }
    // persist serializes RootKind without rename (`Family`/`Key`); the handler's doc
    // says lowercase. Compare case-insensitively, show the token when unknown.
    val kind = when (kindToken.lowercase()) {
        "family" -> RootKindView.FAMILY
        "key" -> RootKindView.KEY
        "unreadable" -> RootKindView.UNREADABLE
        else -> RootKindView.OTHER
    }
    val quorum = v?.get("charter_quorum").obj()?.let { q ->
        val d = q.int("distinct_holders")
        val r = q.int("required")
        val n = q.int("roster_size")
        if (d != null && r != null && n != null) CharterQuorumView(d, r, n) else null
    }
    val holders = (v?.get("holders_hardware") as? JsonArray).orEmpty().mapNotNull { h ->
        val ho = h.obj() ?: return@mapNotNull null
        val cls = ho["class"]
        HolderHardwareView(
            keyId = ho.str("key_id").orEmpty(),
            hardwareClass = when (cls) {
                null, JsonNull -> null
                is JsonPrimitive -> cls.content.takeIf { it.isNotBlank() }
                else -> cls.toString()
            },
            layerA = ho.bool("layer_a") ?: false,
            layerB = ho.bool("layer_b"),
            refusal = ho.str("refusal"),
        )
    }
    val band = when (v.str("drill_freshness")?.lowercase()) {
        "green" -> DrillBand.GREEN
        "yellow" -> DrillBand.YELLOW
        "red" -> DrillBand.RED
        else -> null
    }
    return TrustRootView(
        rootKeyId = entry.rootKeyId,
        kind = kind,
        kindToken = kindToken,
        // CIRISServer#681: `entry.accepted` is always false (it reads a verdict field
        // that does not exist). The acceptance fact is persist's `edge_exists` — a
        // live `delegates_to(node → root)` — read straight from the verdict the
        // server passes through. No verdict (an unreadable root) = unknown, not "no".
        acceptedByThisNode = if (evaluationError != null) null else v.bool("edge_exists"),
        valid = if (evaluationError != null) null else v.bool("valid"),
        rootSelfDeclares = if (evaluationError != null) null else v.bool("root_self_declares"),
        charterHasRecovery = if (evaluationError != null) null else v.bool("charter_has_recovery"),
        quorum = quorum,
        holders = holders,
        holdersAttested = v.bool("holders_hardware_attested"),
        drillBand = band,
        lastDrillAt = v.str("last_drill_at"),
        haltLatched = v.bool("halt_latched"),
        boundedUntil = v.str("bounded_until"),
        evaluationError = evaluationError,
    )
}

/** What withdrawing this node's acceptance of one root stops. */
enum class UntrustConsequence {
    /** No other root is known to be accepted: every root-requiring gate fails closed and no `trace:*` row is served. */
    LAST_ROOT,
    /** Another root this node accepts remains; only what rests on THIS root stops. */
    OTHERS_REMAIN,
}

/**
 * Pure. Conservative in the direction that matters: a root whose acceptance the
 * node could not report does NOT count as "another root remains", so the sheet
 * never promises the node stays rooted when that is unknown.
 */
fun untrustConsequence(roots: List<TrustRootView>, rootKeyId: String): UntrustConsequence =
    if (roots.any { it.rootKeyId != rootKeyId && it.acceptedByThisNode == true }) {
        UntrustConsequence.OTHERS_REMAIN
    } else {
        UntrustConsequence.LAST_ROOT
    }

/**
 * The root a seed's charter names — what the node will write its acceptance to.
 * Mirrors CIRISServer `mesh_genesis::charter_root_key_id` (`src/mesh_genesis.rs:435`,
 * `charter_of` `:462`): the `delegates_to` attestation whose id is
 * `genesis-charter`, its ATTESTED key (the family id for a family root, the key
 * itself for a solo one). Display-only: the node re-derives it and is the authority.
 */
fun seedCharterRoot(bundle: JsonElement): String? {
    val atts = bundle.obj()?.get("attestations") as? JsonArray ?: return null
    return atts.asSequence()
        .mapNotNull { (it.obj()?.get("attestation") ?: it).obj() }
        .firstOrNull { it.str("attestation_type") == "delegates_to" && it.str("attestation_id") == "genesis-charter" }
        ?.str("attested_key_id")
}

/** Why a trust-root call produced no answer. Each is its own sentence. */
sealed interface TrustRootFailure {
    /** The node refused because this device is not on its machine (CIRISServer#652). */
    data class LoopbackOnly(val reasonId: String?) : TrustRootFailure
    /** This node has no such route (it predates CIRISServer#400). */
    data object NotOnThisNode : TrustRootFailure
    /** The node refused, and typed why. */
    data class Refused(val reasonId: String?, val detail: String?) : TrustRootFailure
    /** The call failed — the node was not heard. */
    data class Failed(val detail: String?) : TrustRootFailure
}

/**
 * Pure. The loopback guard answers a bare `403` with a text body and no
 * `reason_id` (`src/auth/loopback.rs:52-55`); #652 asks for
 * `trust_root.loopback_required`, and either reads as the same fact here. A 404
 * with no id is a node that predates the routes.
 */
fun trustRootFailure(e: Throwable): TrustRootFailure = when {
    e is NodeRefusal && e.statusCode == 403 &&
        (e.reasonId == null || e.reasonId == "trust_root.loopback_required") ->
        TrustRootFailure.LoopbackOnly(e.reasonId)
    e is NodeRefusal && e.statusCode == 404 && e.reasonId == null -> TrustRootFailure.NotOnThisNode
    e is NodeRefusal -> TrustRootFailure.Refused(e.reasonId, e.detail)
    else -> TrustRootFailure.Failed(e.message ?: e::class.simpleName)
}

/** The client's own sentence for a refusal id the node emits here; null = use the node's words. */
fun trustRootRefusalKey(reasonId: String?): String? = when (reasonId) {
    "trust_root.bad_request" -> "mobile.trust_root_refused_bad_request"
    "trust_root.bad_bundle" -> "mobile.trust_root_refused_bad_bundle"
    "trust_root.bundle_refused" -> "mobile.trust_root_refused_bundle_refused"
    "trust_root.install_failed" -> "mobile.trust_root_refused_install_failed"
    "trust_root.no_root" -> "mobile.trust_root_refused_no_root"
    "trust_root.withdraw_failed" -> "mobile.trust_root_refused_withdraw_failed"
    TRUST_ROOT_BUNDLE_UNLABELLED -> "mobile.trust_root_refused_bundle_unlabelled"
    else -> null
}

/**
 * ciris-server 0.5.220 (persist v53, CC 3.2 T4a): `POST /v1/trust-root/import`
 * refuses an old portable bundle whose trust rows carry no job labels with
 * `422 trust_root.bundle_unlabelled` — outside the one pinned genesis it would
 * install as no charter at all. Before 0.5.220 the same bundle answered
 * `200 installed: true, accepted: false`.
 */
const val TRUST_ROOT_BUNDLE_UNLABELLED = "trust_root.bundle_unlabelled"

/**
 * The client's guidance UNDER a refusal title, when the refusal needs more than
 * the title to act on; null = show the node's own words. An unlabelled bundle
 * needs saying what it is and what to import instead (a v3 seed from the final
 * genesis), in the reader's language — never the node's raw English.
 */
fun trustRootRefusalBodyKey(reasonId: String?): String? = when (reasonId) {
    TRUST_ROOT_BUNDLE_UNLABELLED -> "mobile.trust_root_refused_bundle_unlabelled_body"
    else -> null
}

/** One family version, for the Accord card's history. */
data class FamilyVersionView(
    val version: Int,
    val isCurrent: Boolean,
    val members: List<String>,
    val consensusProtocol: String?,
    val foundedAt: String?,
    val supersededAt: String?,
    /** Produced by a quorum-signed change (false = genesis / a plain put). */
    val byQuorum: Boolean,
)

fun familyVersionView(v: FamilyVersionDto): FamilyVersionView {
    val s = v.snapshot.obj()
    // A SignedFamily snapshot nests the row under `family`; a bare Family does not.
    val fam = s?.get("family").obj() ?: s
    val members = (fam?.get("members") as? JsonArray).orEmpty().mapNotNull { m ->
        when (m) {
            is JsonObject -> m.str("key_id")
            is JsonPrimitive -> m.takeIf { it.isString }?.content
            else -> null
        }
    }
    return FamilyVersionView(
        version = v.version,
        isCurrent = v.isCurrent,
        members = members,
        consensusProtocol = fam.str("consensus_protocol"),
        foundedAt = fam.str("founded_at"),
        supersededAt = v.supersededAt?.takeIf { it.isNotBlank() },
        byQuorum = v.authorization != null && v.authorization !is JsonNull,
    )
}
