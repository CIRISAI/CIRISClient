package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.api.NodeRefusal
import ai.ciris.mobile.shared.platform.util.Sha256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
)

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

fun finalGenesisProbe(statusCode: Int, reasonId: String?): FinalGenesisProbe = when {
    statusCode == 404 && reasonId == null -> FinalGenesisProbe.LEGACY
    reasonId == FinalGenesisReason.NOT_PLANNED -> FinalGenesisProbe.NOT_PLANNED
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
 * The maintainer's pairing (server `RECOVERY_PAIRING`, `src/final_genesis.rs:104`):
 * each seated holder recovers with its own spare. `POST …/recovery-key` needs
 * the spare's id. A node with `GET …/recovery-keys` names it per holder (and
 * that answer wins); a 0.5.220 node from before that route answers a bare 404,
 * and then this table is all the client has. A holder outside it (a
 * test-anchor roster) has no recorded recovery key.
 */
val RECOVERY_PAIRING: Map<String, String> = mapOf("A1" to "A2", "B1" to "B2", "C1" to "C2")

/** A short, comparable fingerprint of a key: the first 16 hex of SHA-256 over the Ed25519 public bytes, in fours. */
@OptIn(ExperimentalEncodingApi::class)
fun shortKeyFingerprint(pubkeyEd25519Base64: String): String? {
    val bytes = try {
        Base64.decode(pubkeyEd25519Base64.trim())
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.isEmpty()) return null
    return Sha256.hex(bytes).take(16).chunked(4).joinToString(" ")
}

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
