package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.platform.util.Sha256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **The final genesis** — CIRISServer 0.5.220 `FSD/FINAL_GENESIS.md`, routes in
 * `src/final_genesis.rs` (read at e357f6bf), items planned by persist v53.0.1
 * `federation/genesis/ceremony.rs`.
 *
 * ONE ceremony that ALL THREE accord holders sign mints the whole root: every
 * serve node's record, the labelled charter (successor set + per-holder recovery
 * commitments), one grant per serve node, the lifecycle row, the
 * `humanity-accord` family record and the `ciris-canonical` birth — in one
 * bundle. It replaces the 2-of-3 propose/cosign re-mint, which a 0.5.220 node
 * answers 410 `accord.genesis_superseded`. Every route is loopback-only.
 *
 *   GET  /v1/accord/final-genesis               → [FinalGenesisStatusDto]
 *   POST /v1/accord/final-genesis/recovery-key  → [RecoveryKeyResponseDto]  (optional check)
 *   POST /v1/accord/final-genesis/plan          → [FinalGenesisStatusDto]
 *   POST /v1/accord/final-genesis/sign          → [FinalGenesisSignResponseDto]
 *   POST /v1/accord/final-genesis/finish        → [FinalGenesisFinishDto]
 *
 * The app holds no keys and builds no envelope: persist plans the items and
 * recomputes their bytes on every call; the node opens each holder's YubiKey.
 */

/** `GET /v1/accord/final-genesis` and the `plan` answer (`status_body`, `src/final_genesis.rs:544`). */
@Serializable
data class FinalGenesisStatusDto(
    val complete: Boolean = false,
    /** Items owed by someone and waiting on nothing — signable now. */
    @SerialName("signable_now")
    val signableNow: List<String> = emptyList(),
    /**
     * Item id → the holders still owed, for every item NOT yet complete,
     * waiting ones included (persist `CeremonyState::status`). An item every
     * holder has signed is ABSENT, not present with an empty list.
     */
    val owed: Map<String, List<String>> = emptyMap(),
)

/** A recovery key's public halves, as the charter commits to them (persist `CommittedKey`). */
@Serializable
data class CommittedKeyDto(
    @SerialName("key_id")
    val keyId: String,
    @SerialName("pubkey_ed25519_base64")
    val pubkeyEd25519Base64: String,
    @SerialName("pubkey_ml_dsa_65_base64")
    val pubkeyMlDsa65Base64: String,
)

/** `POST /v1/accord/final-genesis/recovery-key` answer (`src/final_genesis.rs:534`). */
@Serializable
data class RecoveryKeyResponseDto(
    @SerialName("holder_key_id")
    val holderKeyId: String,
    @SerialName("recovery_key")
    val recoveryKey: CommittedKeyDto,
    /** Every holder whose recovery key this node has read off a token so far. */
    val recorded: List<String> = emptyList(),
)

/**
 * `POST /v1/accord/final-genesis/sign` answer. `complete` is sent by the
 * hardware path (`:784`) and not by the dry run's software path (`:696`), so it
 * defaults to "not known" rather than false.
 */
@Serializable
data class FinalGenesisSignResponseDto(
    val signed: List<String> = emptyList(),
    val owed: Map<String, List<String>> = emptyMap(),
    val complete: Boolean? = null,
)

/** persist `CeremonyOutputsVerified` — what `verify_ceremony_outputs` proved at finish. */
@Serializable
data class FinalGenesisVerifiedDto(
    @SerialName("quorum_verified")
    val quorumVerified: Int = 0,
    /** The serve nodes' key ids. */
    @SerialName("serve_nodes")
    val serveNodes: List<String> = emptyList(),
    /** The bundle's attestation ids. */
    val attestations: List<String> = emptyList(),
    @SerialName("community_key_id")
    val communityKeyId: String = "",
    val founders: Int = 0,
)

/** `POST /v1/accord/final-genesis/finish` answer (`src/final_genesis.rs:823`). */
@Serializable
data class FinalGenesisFinishDto(
    val complete: Boolean = false,
    @SerialName("bundle_path")
    val bundlePath: String = "",
    /** `sha256:<hex>` over the bundle file's bytes — the out-of-band fingerprint. */
    @SerialName("bundle_sha256")
    val bundleSha256: String = "",
    val verified: FinalGenesisVerifiedDto = FinalGenesisVerifiedDto(),
    /** `amend` on the 2-of-3 re-scrub's finish (CIRISServer#798 `src/final_genesis.rs:1574`); absent on the full ceremony's. */
    val mode: String? = null,
    /** The bundle's version — 4 from the amend (it seeds each canonical's transit pair); absent on the full ceremony's finish. */
    val version: Int? = null,
    /** The canonicals whose signed transit row the bundle seeds (amend only). */
    @SerialName("serve_transport_destinations")
    val serveTransportDestinations: List<String> = emptyList(),
    /**
     * The `bundle` member EXACTLY as the node sent it (CIRISServer 4da726e8
     * `:917`): the raw text of the member, or a string member's contents.
     * Never decoded and re-encoded here — what is copied is what came off the
     * wire. Filled by the client from the body ([finalGenesisBundleText]).
     */
    @kotlinx.serialization.Transient
    val bundleText: String? = null,
) {
    /**
     * Whether [bundleText]'s bytes are the ones [bundleSha256] names —
     * MEASURED, not assumed: 4da726e8 re-serializes the file (compact, keys
     * sorted) into `bundle`, so the copy is the same bundle but not the hashed
     * bytes, and the fingerprint is the file at [bundlePath]. Null with no bundle.
     */
    val bundleMatchesFingerprint: Boolean?
        get() = bundleText?.let { bundleMatchesSha256(it, bundleSha256) }
}

/** SHA-256 over [text]'s UTF-8 bytes against a `sha256:<hex>` (or bare hex) fingerprint. */
fun bundleMatchesSha256(text: String, fingerprint: String): Boolean =
    Sha256.hex(text.encodeToByteArray()) == fingerprint.trim().removePrefix("sha256:").lowercase()

/**
 * The raw text of [key]'s value in the top-level JSON object [raw], byte for
 * byte — whitespace, key order and escapes as sent — or null when the member
 * is absent or the body is not an object. A small scanner, not a parser: it
 * only finds where the value starts and ends.
 */
fun rawJsonMember(raw: String, key: String): String? {
    var i = 0
    fun ws() { while (i < raw.length && raw[i].isWhitespace()) i++ }
    fun stringEnd(start: Int): Int {
        var j = start + 1
        while (j < raw.length) {
            when (raw[j]) {
                '\\' -> j += 2
                '"' -> return j + 1
                else -> j++
            }
        }
        return -1
    }
    fun valueEnd(start: Int): Int {
        if (start >= raw.length) return -1
        return when (raw[start]) {
            '"' -> stringEnd(start)
            '{', '[' -> {
                var depth = 0
                var j = start
                while (j < raw.length) {
                    when (raw[j]) {
                        '"' -> { j = stringEnd(j); if (j < 0) return -1; continue }
                        '{', '[' -> depth++
                        '}', ']' -> { depth--; if (depth == 0) return j + 1 }
                    }
                    j++
                }
                -1
            }
            else -> {
                var j = start
                while (j < raw.length && raw[j] != ',' && raw[j] != '}' && raw[j] != ']' && !raw[j].isWhitespace()) j++
                j
            }
        }
    }
    ws()
    if (i >= raw.length || raw[i] != '{') return null
    i++
    while (true) {
        ws()
        if (i >= raw.length || raw[i] == '}') return null
        if (raw[i] != '"') return null
        val kEnd = stringEnd(i)
        if (kEnd < 0) return null
        val name = raw.substring(i + 1, kEnd - 1)
        i = kEnd
        ws()
        if (i >= raw.length || raw[i] != ':') return null
        i++
        ws()
        val vEnd = valueEnd(i)
        if (vEnd < 0) return null
        if (name == key) return raw.substring(i, vEnd)
        i = vEnd
        ws()
        if (i < raw.length && raw[i] == ',') i++
    }
}

/**
 * The bundle text to copy from a finish body. `bundle_json` first (CIRISServer
 * e4cbedeb `:968`): a string holding the file's exact bytes, the ones
 * `bundle_sha256` covers. Else the older `bundle` member (4da726e8): a string
 * member's contents, or the member's raw text as sent. Neither → null.
 */
fun finalGenesisBundleText(body: String): String? {
    val member = rawJsonMember(body, "bundle_json")?.takeIf { it != "null" }
        ?: rawJsonMember(body, "bundle") ?: return null
    if (member == "null") return null
    if (member.startsWith('"')) {
        return (kotlinx.serialization.json.Json.parseToJsonElement(member) as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
    return member
}

/**
 * One holder's recovery key as the node will commit to it — an entry of
 * `GET /v1/accord/final-genesis/recovery-keys` (CIRISServer 0.5.220, the
 * per-holder merge fix). [recoveryKeyId] is null when the node has nothing on
 * record for the holder: that holder's spare must be read off its token before
 * a plan. [commitment] is persist's `recovery_commitment(key)`, the exact
 * string the charter carries in `recovery_commitments[holder]`.
 */
@Serializable
data class RecoveryKeyEntryDto(
    @SerialName("holder_key_id")
    val holderKeyId: String,
    @SerialName("recovery_key_id")
    val recoveryKeyId: String? = null,
    val commitment: String? = null,
    /** `record` (the accord ceremony's record) or `hardware` (read off the spare token on this node). */
    val source: String? = null,
)

/** `GET /v1/accord/final-genesis/recovery-keys` — readable before a plan. */
@Serializable
data class RecoveryKeysDto(
    /** True when every holder has a recovery key. */
    val complete: Boolean = false,
    @SerialName("recovery_keys")
    val recoveryKeys: List<RecoveryKeyEntryDto> = emptyList(),
)

/** Where a recovery key came from, as the node names it. */
object RecoveryKeySource {
    const val RECORD = "record"
    const val HARDWARE = "hardware"
}

/** A commitment's first 16 hex, in fours — what a holder reads aloud. */
fun shortCommitment(commitment: String?): String? =
    commitment?.trim()?.removePrefix("sha256:")?.takeIf { it.isNotEmpty() }?.take(16)?.chunked(4)?.joinToString(" ")

/**
 * One canonical the plan seats and where peers dial it — CIRISServer e4cbedeb
 * `ServeNodeSpec::WithHints` (`src/final_genesis.rs:263`). A bare id is refused
 * `final_genesis.serve_node_no_dial_hint`: a canonical seated without an
 * address is one no fresh node can find.
 */
data class PlanServeNode(val keyId: String, val destination: String)

/**
 * The address a serve node's field starts with: the node's own hint for it
 * (an `ip` one first), else nothing. No address is compiled in: on a node
 * holding the July bake, `remint-source` serves canonical-1's hint itself, and
 * a second copy here could not follow it being rebound (Codex on #154).
 */
fun initialDialHint(hints: List<TransportHintDto>?): String =
    hints.orEmpty().let { h -> (h.firstOrNull { it.kind == "ip" } ?: h.firstOrNull())?.destination?.trim() }
        .orEmpty()

/**
 * Whether [text] is a destination the node's dialer accepts — CIRISServer
 * 814dd7c6 `require_dial_hint` → `compose::ip_addrs_from_hints`, which keeps an
 * `ip` hint only when `destination.parse::<std::net::SocketAddr>()` succeeds:
 *
 * - `a.b.c.d:port` — four decimal octets 0–255, no leading zeros (Rust's
 *   `Ipv4Addr` refuses them), or
 * - `[ipv6]:port` — a bracketed IPv6 literal (`::` compression and a trailing
 *   dotted IPv4 allowed);
 * - never a hostname, never a bare address.
 *
 * Stricter than the server in two places, both on purpose: the port must be
 * 1–65535 (Rust parses `:0`, which no peer can dial) and digits only (`u16`
 * parsing takes a leading `+`); and an IPv6 zone (`%eth0`) is refused.
 */
fun isDialHint(text: String): Boolean {
    if (text.isEmpty() || text.any { it.isWhitespace() }) return false
    val host: String
    val port: String
    if (text.startsWith("[")) {
        val end = text.indexOf("]:")
        if (end < 0) return false
        host = text.substring(1, end)
        port = text.substring(end + 2)
        if (!isIpv6Literal(host)) return false
    } else {
        val i = text.lastIndexOf(':')
        if (i <= 0) return false
        host = text.substring(0, i)
        port = text.substring(i + 1)
        if (!isIpv4Literal(host)) return false
    }
    if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return false
    return port.toInt() in 1..65535
}

private fun isIpv4Literal(s: String): Boolean {
    val parts = s.split('.')
    if (parts.size != 4) return false
    return parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
            !(p.length > 1 && p[0] == '0') && p.toInt() <= 255
    }
}

private fun isIpv6Literal(s: String): Boolean {
    if (s.isEmpty() || s.contains('%')) return false
    var body = s
    var extra = 0
    if ('.' in s) {
        val cut = s.lastIndexOf(':')
        if (cut < 0 || !isIpv4Literal(s.substring(cut + 1))) return false
        body = s.substring(0, cut + 1) + "0"   // the IPv4 tail counts as two groups
        extra = 1
    }
    val halves = body.split("::")
    if (halves.size > 2) return false
    fun groups(h: String): List<String>? =
        if (h.isEmpty()) emptyList() else h.split(':').takeIf { g -> g.all { it.length in 1..4 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } } }
    val left = groups(halves[0]) ?: return false
    val count = if (halves.size == 2) {
        val right = groups(halves[1]) ?: return false
        left.size + right.size + extra
    } else {
        left.size + extra
    }
    return if (halves.size == 2) count <= 7 else count == 8
}

/** A refusal reduced to what the sheet renders: the id (localized by id), the node's English, the status. */
data class FinalGenesisRefusal(val reasonId: String?, val detail: String?, val statusCode: Int) {
    companion object {
        fun of(e: NodeRefusal) = FinalGenesisRefusal(e.reasonId, e.detail, e.statusCode)
        /** Not a node answer at all — the socket, a timeout. No id; the message is the detail. */
        fun transport(e: Throwable) = FinalGenesisRefusal(null, e.message ?: e::class.simpleName, 0)
    }
}

/** The refusal ids this sheet acts on rather than only showing. */
object FinalGenesisReason {
    const val NOT_PLANNED = "final_genesis.not_planned"
    const val CLOCK_UNVERIFIED = "final_genesis.clock_unverified"
    const val ALREADY_PLANNED = "final_genesis.already_planned"
    const val NOTHING_TO_SIGN = "final_genesis.nothing_to_sign"
    const val CEREMONY_INCOMPLETE = "ceremony_incomplete"
    /** The amend's status/sign/finish with nothing planned (409, CIRISServer#798 `amend_load`). */
    const val AMEND_NOT_PLANNED = "final_genesis.amend_not_planned"
    /** An amend plan over a planned one, without `replace` (409). */
    const val AMEND_ALREADY_PLANNED = "final_genesis.amend_already_planned"
    /** Finish below the charter's quorum (409). */
    const val AMEND_QUORUM_NOT_MET = "final_genesis.amend_quorum_not_met"
}

// ─── the 2-of-3 amend ("re-scrub", CC 5.3.4; CIRISServer#798) ───────────────
//
//   GET  /v1/accord/final-genesis/amend          → [FinalGenesisAmendStatusDto]
//   POST /v1/accord/final-genesis/amend/plan     → [FinalGenesisAmendStatusDto]
//   POST /v1/accord/final-genesis/amend/sign     → [FinalGenesisAmendStatusDto]
//   POST /v1/accord/final-genesis/amend/finish   → [FinalGenesisFinishDto] (mode, version 4)
//   GET  <canonical>/v1/trust-root/transit-row   → the canonical's signed row (PUBLIC)
//
// The steward's ruling on CIRISConstitution#172: the bundle v4 re-mint is an
// AMEND, not a second genesis — the pinned bundle is carried byte for byte,
// only each seated canonical's signed transit row is added, and the charter's
// quorum (2 of 3) authorizes the new digest. No rounds, no recovery keys.

/** `amend_status_body` (CIRISServer#798 `src/final_genesis.rs:1285-1306`). */
@Serializable
data class FinalGenesisAmendStatusDto(
    val mode: String = "",
    /** The charter's quorum — how many holder authorizations the amend needs. */
    val needed: Int = 0,
    /** The holders who have authorized, sorted, deduplicated. */
    val signed: List<String> = emptyList(),
    /** The node's own "M of N" (N = [needed]). */
    val status: String = "",
    val complete: Boolean = false,
    /** The canonicals whose signed transit row the amended bundle seeds. */
    @SerialName("serve_transport_destinations")
    val serveTransportDestinations: List<String> = emptyList(),
)

/** What the operator pasted or attached as transit rows, read locally before anything is sent. */
sealed interface TransitRows {
    /** Nothing: the node reads each canonical's row from its own directory. */
    data object Empty : TransitRows
    data class Rows(val rows: List<kotlinx.serialization.json.JsonObject>) : TransitRows {
        /** The canonical each row is about (`transport_destination.occurrence_key_id`), or `?`. */
        val subjects: List<String> get() = rows.map { transitRowSubject(it) ?: "?" }
    }
    /** Not JSON objects: nothing is sent. */
    data object Invalid : TransitRows
}

/** The canonical a signed transport_destination row is about. */
fun transitRowSubject(row: kotlinx.serialization.json.JsonObject): String? =
    ((row["transport_destination"] as? kotlinx.serialization.json.JsonObject)?.get("occurrence_key_id")
        as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The pasted text as rows: one JSON object, an array of objects, or several of
 * either one after another (an attached file is appended on a new line).
 */
fun parseTransitRows(text: String): TransitRows {
    if (text.isBlank()) return TransitRows.Empty
    val values = splitJsonValues(text) ?: return TransitRows.Invalid
    val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
    for (v in values) {
        val el = try {
            kotlinx.serialization.json.Json.parseToJsonElement(v)
        } catch (_: Exception) {
            return TransitRows.Invalid
        }
        when (el) {
            is kotlinx.serialization.json.JsonObject -> rows += el
            is kotlinx.serialization.json.JsonArray -> el.forEach { rows += (it as? kotlinx.serialization.json.JsonObject ?: return TransitRows.Invalid) }
            else -> return TransitRows.Invalid
        }
    }
    return if (rows.isEmpty()) TransitRows.Invalid else TransitRows.Rows(rows)
}

/** Top-level JSON values one after another (whitespace or commas between), as text; null when one does not close. */
private fun splitJsonValues(text: String): List<String>? {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isWhitespace() || c == ',') { i++; continue }
        if (c != '{' && c != '[') return null
        var depth = 0
        var j = i
        var end = -1
        while (j < text.length) {
            when (text[j]) {
                '"' -> {
                    j++
                    while (j < text.length && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                }
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) { end = j + 1; break } }
            }
            j++
        }
        if (end < 0) return null
        out += text.substring(i, end)
        i = end
    }
    return out
}

/**
 * A canonical's row as `GET /v1/trust-root/transit-row` served it (CIRISServer#798
 * @ 76e16ccf, `src/trust_root_api.rs:615`): [row] is sent into the plan as-is.
 * [pairFingerprint] is computed HERE — SHA-256 over the decoded x25519 key then
 * the ML-KEM-768 key from `signed_envelope.transit_kex_pubkeys`, first 16 hex in
 * fours — for comparing with the canonical's host; null when the row carries no pair.
 */
data class CanonicalTransitRow(
    val row: kotlinx.serialization.json.JsonObject,
    val keyId: String?,
    val pairFingerprint: String?,
)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
fun canonicalTransitRow(row: kotlinx.serialization.json.JsonObject): CanonicalTransitRow {
    val pair = ((row["signed_envelope"] as? kotlinx.serialization.json.JsonObject)?.get("transit_kex_pubkeys")
        as? kotlinx.serialization.json.JsonObject)
    fun half(k: String): ByteArray? = (pair?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.let {
        try { kotlin.io.encoding.Base64.decode(it.trim()) } catch (_: Exception) { null }
    }
    val x = half("x25519_base64")
    val m = half("ml_kem_768_base64")
    val fp = if (x != null && m != null) shortCommitment(Sha256.hex(x + m)) else null
    return CanonicalTransitRow(row, transitRowSubject(row), fp)
}

/** Where fetching a canonical's transit row stands. */
sealed interface TransitFetch {
    data object Idle : TransitFetch
    data object Fetching : TransitFetch
    data class Fetched(val row: CanonicalTransitRow) : TransitFetch
    /** A bare 404: that node is older than `GET /v1/trust-root/transit-row`. */
    data object TooOld : TransitFetch
    /** `trust_root.not_a_serve_node`, `.no_transit_row`, `.store_unavailable`, or no answer at all. */
    data class Refused(val refusal: FinalGenesisRefusal) : TransitFetch
}

/**
 * Which re-mint this node runs, decided by the ROUTE, never by a version string:
 * `GET /v1/accord/final-genesis` answers 200 (planned) or 404 with the reason id
 * `final_genesis.not_planned` on 0.5.220+. On 0.5.219 and older the route does
 * not exist and axum answers a BARE 404 (no body; the server mounts no
 * fallback) — that, and only that, keeps the 2-of-3 sheet. Any other refusal
 * (403 off the node's own machine, 5xx) is a refusal to show, not a reason to
 * fall back to a ceremony the node may have retired.
 */
enum class FinalGenesisProbe { LEGACY, NOT_PLANNED, REFUSED }

fun finalGenesisProbe(statusCode: Int, reasonId: String?, body: String? = null): FinalGenesisProbe = when {
    // ONLY a blank 404: a non-empty one without a known id (a proxy's HTML,
    // `{"error":"Not Found"}`) is a route that did not answer, not an old node.
    reasonId == FinalGenesisReason.NOT_PLANNED -> FinalGenesisProbe.NOT_PLANNED
    statusCode == 404 && reasonId == null && body.isNullOrBlank() -> FinalGenesisProbe.LEGACY
    else -> FinalGenesisProbe.REFUSED
}

/** What a refused `plan` asks of the operator next. */
enum class PlanConfirm { CLOCK, REPLACE }

fun planConfirmFor(reasonId: String?): PlanConfirm? = when (reasonId) {
    FinalGenesisReason.CLOCK_UNVERIFIED -> PlanConfirm.CLOCK
    FinalGenesisReason.ALREADY_PLANNED -> PlanConfirm.REPLACE
    else -> null
}

/**
 * The maintainer's pairing (server `RECOVERY_PAIRING`, `src/final_genesis.rs:111`
 * at 4da726e8), ENFORCED by the node (`check_recovery_keys` `:128`): a seated
 * holder recovers only with its own spare, and `POST …/recovery-key` with any
 * other pair is 400 `final_genesis.recovery_key_wrong_holder` before a token
 * opens. So for these holders the pairing — not whatever a node once listed —
 * names the token to read. A holder outside it (a test-anchor roster) uses the
 * id the node lists, if any.
 */
val RECOVERY_PAIRING: Map<String, String> = mapOf("A1" to "A2", "B1" to "B2", "C1" to "C2")

/** The two rounds of signing. Round two opens once every holder has signed the charter. */
object FinalGenesisItems {
    const val CHARTER = "row:genesis-charter"
    const val LIFECYCLE = "row:genesis-lifecycle"
    const val AUTHZ = "authz"

    /** Round two: the two genesis records and the authorization (persist `ceremony.rs:612-663`). */
    fun round(item: String): Int =
        if (item.startsWith("family:") || item.startsWith("community:") || item == AUTHZ) 2 else 1

    /** Bundle order: records, charter, grants, lifecycle, family, community, authorization. */
    fun rank(item: String): Int = when {
        item.startsWith("record:") -> 0
        item == CHARTER -> 1
        item.startsWith("row:genesis-grant:") -> 2
        item == LIFECYCLE -> 3
        item.startsWith("family:") -> 4
        item.startsWith("community:") -> 5
        item == AUTHZ -> 6
        else -> 7
    }

    fun sorted(items: Collection<String>): List<String> =
        items.distinct().sortedWith(compareBy<String>({ rank(it) }, { it }))
}

/** One cell of the grid: holder × item. */
enum class GenesisCell { SIGNED, SIGN_NOW, WAITING }

/** What a holder's row says beside its Sign button. */
sealed interface HolderGenesisState {
    /** Owes at least one item that is signable now. */
    data object SignNow : HolderGenesisState
    /** Owes only items that wait on the charter, which [on] have not signed yet. */
    data class Waiting(val on: List<String>) : HolderGenesisState
    /** Owes nothing. */
    data object Done : HolderGenesisState
}

data class FinalGenesisGrid(
    val holders: List<String>,
    val items: List<String>,
    val cells: Map<Pair<String, String>, GenesisCell>,
    /** True once no holder still owes the charter. */
    val roundTwoOpen: Boolean,
    /** The holders still owing the charter — whom round two waits on. */
    val charterOwedBy: List<String>,
    val complete: Boolean,
) {
    fun cell(holder: String, item: String): GenesisCell = cells[holder to item] ?: GenesisCell.SIGNED

    fun holderState(holder: String): HolderGenesisState {
        val mine = items.map { cell(holder, it) }
        return when {
            GenesisCell.SIGN_NOW in mine -> HolderGenesisState.SignNow
            GenesisCell.WAITING in mine -> HolderGenesisState.Waiting(charterOwedBy.filter { it != holder })
            else -> HolderGenesisState.Done
        }
    }
}

/**
 * The grid from the node's status. [known] carries item ids seen earlier in this
 * session: the node drops an item from `owed` once every holder has signed it,
 * and a row that vanished would read as "never existed" rather than "done".
 */
fun finalGenesisGrid(
    holders: List<String>,
    status: FinalGenesisStatusDto,
    known: Collection<String> = emptyList(),
): FinalGenesisGrid {
    val items = FinalGenesisItems.sorted(known + status.owed.keys + status.signableNow)
    val ready = status.signableNow.toSet()
    // The roster is the node's when it names holders the caller did not pass.
    val roster = (holders + status.owed.values.flatten()).distinct()
    val cells = buildMap {
        for (item in items) {
            val owing = status.owed[item].orEmpty()
            for (h in roster) {
                put(
                    h to item,
                    when {
                        h !in owing -> GenesisCell.SIGNED
                        item in ready -> GenesisCell.SIGN_NOW
                        else -> GenesisCell.WAITING
                    },
                )
            }
        }
    }
    val charterOwedBy = status.owed[FinalGenesisItems.CHARTER].orEmpty()
    return FinalGenesisGrid(
        holders = roster,
        items = items,
        cells = cells,
        roundTwoOpen = charterOwedBy.isEmpty(),
        charterOwedBy = charterOwedBy,
        complete = status.complete,
    )
}

/**
 * The file a holder's USB key carries for [holder]: `<holder>.mldsa65.seed.blob`
 * at the root of the mounted directory (`mldsa_usb_path`), e.g.
 * `/media/<user>/<LABEL>/A1.mldsa65.seed.blob`.
 */
fun seedBlobName(holder: String): String = "$holder.mldsa65.seed.blob"

/** [dir] joined with [holder]'s seed blob, whatever separator the picker returned. */
fun seedBlobPath(dir: String, holder: String): String =
    dir.trim().trimEnd('/', '\\') + "/" + seedBlobName(holder)

/** The PIV slot the holder key lives in, unless the operator says otherwise. */
const val DEFAULT_PIV_SLOT = "9c"

/**
 * A grid item in plain words: the localized key and its parameters, or null
 * for an id this client does not name (shown raw). The raw id is always shown
 * too, small, under the words.
 */
fun genesisItemLabel(item: String): Pair<String, Map<String, String>>? = when {
    item == FinalGenesisItems.AUTHZ -> "mobile.final_genesis_item_authz" to emptyMap()
    item.startsWith("record:") -> "mobile.final_genesis_item_record" to mapOf("node" to item.removePrefix("record:"))
    item == FinalGenesisItems.CHARTER -> "mobile.final_genesis_item_charter" to emptyMap()
    item.startsWith("row:genesis-grant:") -> "mobile.final_genesis_item_grant" to mapOf("node" to item.removePrefix("row:genesis-grant:"))
    item == FinalGenesisItems.LIFECYCLE -> "mobile.final_genesis_item_lifecycle" to emptyMap()
    item == "family:humanity-accord" -> "mobile.final_genesis_item_family" to emptyMap()
    item == "community:ciris-canonical" -> "mobile.final_genesis_item_community" to emptyMap()
    else -> null
}

/**
 * The PIN-tries part of a signer refusal, when the node said it
 * (CIRISServer `accord_provision.rs`: "2 of 3 PIN attempts remain.", "WARNING:
 * 1 of 3 PIN attempts left — …", "This token's PIN is now LOCKED — …", "This
 * token has 2 of 3 PIN attempts left."), from its start to the end of the
 * detail. Null when the detail says nothing about tries.
 */
fun pinTriesWarning(detail: String?): String? {
    val d = detail ?: return null
    val m = Regex(
        """WARNING: \d+ of \d+ PIN attempts? left|This token's PIN is now LOCKED|This token has \d+ of \d+ PIN attempts? left|\d+ of \d+ PIN attempts? remains?""",
    ).find(d) ?: return null
    return d.substring(m.range.first).trim()
}
