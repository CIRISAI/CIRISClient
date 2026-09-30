package ai.ciris.mobile.shared.models.drive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `GET /v1/files/{attestation_id}/custody?cohort=…&room_id=…` — which of the
 * person's devices a file is on (CSD-107).
 *
 * **CIRISServer#704** at `d1a15286` (`feat/file-custody-0.5.218`, open into
 * `chore/adopt-edge-v33`; `src/drive.rs` handler, `src/file_custody.rs`,
 * `FSD/FILE_CUSTODY.md` §1.1). A device that holds the row and not the bytes
 * answers 200 with its own `holds: "none"` (no longer a 409). Not merged and on no released node, so field
 * names can still move: every field is optional and the parse is by hand over
 * a [JsonObject]. A renamed or retyped member reads as absent (rendered "not
 * sent"), never as a crash.
 *
 * Shape at #704:
 * `{ attestation_id, cohort, room_id, tier, size_bytes|null, at_rest_sha256,
 *    author_device, this_device_is_author, checked_at, devices_total,
 *    devices: [{node_key_id, label?, this_device, can_open: bool|null,
 *               received: {epoch, k, at|null}|null, holds: here|received|none|unknown,
 *               checked_at (this device only), reported_at (null until persist v53's
 *               custody:ack:v1, CC 3.1.3.3)}],
 *    held_here, copies_known, copies_observable,
 *    announced_holders: [{node_key_id, size_bytes}], access: [{person_key_id, devices, via}]|null,
 *    receipts_supported, receipts_unsupported_reason, receipts_from_other_keys: [..],
 *    why: [{reason_id, detail}] }`
 */
data class FileCustody(
    val devicesTotal: Int? = null,
    val devices: List<CustodyDevice> = emptyList(),
    val heldHere: Boolean? = null,
    val copiesKnown: Int? = null,
    /** False for self and family files, by design (CC 5.2): copies elsewhere can't be counted. */
    val copiesObservable: Boolean? = null,
    /** How many announced holders the node listed (community / commons only). */
    val announcedHolders: Int? = null,
    /** How many `access` entries (people who can open the bytes); null on a no-copy device. Read, not rendered. */
    val accessCount: Int? = null,
    /** At-rest size; null on a device with no copy (`custody.no_copy_here`). */
    val sizeBytes: Long? = null,
    /** When THIS device answered. */
    val checkedAt: String? = null,
    /** False for inline files (≤ 1 MiB) until persist v52 / edge v38: no device can say "received". */
    val receiptsSupported: Boolean? = null,
    /** `custody.inline_no_receipt` when [receiptsSupported] is false. */
    val receiptsUnsupportedReason: String? = null,
    /** Receipts signed by keys that are none of this person's devices. */
    val receiptsFromOtherKeys: Int = 0,
    /** The device that wrote the file: receipts are collected THERE, so its view is the fullest. */
    val authorDevice: String? = null,
    val thisDeviceIsAuthor: Boolean? = null,
    /** The server's reasons for every partial answer; see [custodyWhyKey]. */
    val why: List<CustodyWhy> = emptyList(),
) {
    companion object {
        fun fromWire(body: JsonObject): FileCustody = FileCustody(
            devicesTotal = body.int("devices_total"),
            devices = (body["devices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(CustodyDevice::fromWire) },
            heldHere = body.bool("held_here"),
            copiesKnown = body.int("copies_known"),
            copiesObservable = body.bool("copies_observable"),
            announcedHolders = body.countOrInt("announced_holders"),
            accessCount = (body["access"] as? JsonArray)?.size,
            sizeBytes = body.long("size_bytes"),
            checkedAt = body.str("checked_at"),
            receiptsSupported = body.bool("receipts_supported"),
            receiptsUnsupportedReason = body.str("receipts_unsupported_reason"),
            receiptsFromOtherKeys = (body["receipts_from_other_keys"] as? JsonArray)?.size ?: 0,
            authorDevice = body.str("author_device")?.takeIf { it.isNotBlank() },
            thisDeviceIsAuthor = body.bool("this_device_is_author"),
            why = (body["why"] as? JsonArray).orEmpty().mapNotNull(CustodyWhy::fromWire),
        )
    }
}

/**
 * One `why[]` entry: `{reason_id, detail}` at #704. A bare string (the planned
 * shape before #704) is still read: an id-shaped one as the id, anything else
 * as the detail.
 */
data class CustodyWhy(val reasonId: String?, val detail: String?) {
    companion object {
        fun fromWire(e: JsonElement): CustodyWhy? = when (e) {
            is JsonObject -> CustodyWhy(
                e.str("reason_id")?.trim()?.takeIf { it.isNotEmpty() },
                e.str("detail")?.trim()?.takeIf { it.isNotEmpty() },
            ).takeIf { it.reasonId != null || it.detail != null }
            else -> e.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (custodyWhyKey(it) != null) CustodyWhy(it, null) else CustodyWhy(null, it)
            }
        }
    }
}

/** How a `why[]` entry renders: a headline, and the node's detail as mono small print when it adds anything. */
data class WhyLines(val headline: String, val smallPrint: String?)

/**
 * Pure. [lookup] is the bundle (returns the key itself when missing, as
 * `localizedString` does). The reason id is localized; the detail goes under
 * it. With no key in the bundle, the detail is the headline (no small print),
 * else the raw id.
 */
fun custodyWhyLines(why: CustodyWhy, lookup: (String) -> String): WhyLines {
    val key = why.reasonId?.let(::custodyWhyKey)
    val localized = key?.let { k -> lookup(k).takeIf { it != k && it.isNotBlank() } }
    return when {
        localized != null -> WhyLines(localized, why.detail)
        why.detail != null -> WhyLines(why.detail, null)
        else -> WhyLines(why.reasonId.orEmpty(), null)
    }
}

/**
 * Whether the card's own always-on footnotes are still needed, or the node's
 * `why[]` already says the same thing by id (then the node's entry is the one
 * shown, and it is not said twice).
 */
fun custodyWhyHas(c: FileCustody, reasonId: String): Boolean = c.why.any { it.reasonId == reasonId }

const val WHY_RECEIPT_IS_DELIVERY = "custody.receipt_is_delivery_not_holding"
const val WHY_INLINE_NO_RECEIPT = "custody.inline_no_receipt"
const val WHY_NO_COPY_HERE = "custody.no_copy_here"

data class CustodyDevice(
    val nodeKeyId: String? = null,
    val label: String? = null,
    val thisDevice: Boolean = false,
    val canOpen: Boolean? = null,
    val received: CustodyReceipt? = null,
    /** The wire token, verbatim. Read it through [custodyHolds]. */
    val holds: String? = null,
    /** When this device answered (this device only). */
    val checkedAt: String? = null,
    /** When a remote device reported its holds (`custody:ack:v1`, CC 3.1.3.3); null until persist v53. */
    val reportedAt: String? = null,
) {
    companion object {
        fun fromWire(o: JsonObject): CustodyDevice = CustodyDevice(
            nodeKeyId = o.str("node_key_id"),
            label = o.str("label")?.takeIf { it.isNotBlank() },
            thisDevice = o.bool("this_device") ?: false,
            canOpen = o.bool("can_open"),
            received = (o["received"] as? JsonObject)?.let {
                CustodyReceipt(epoch = it.long("epoch"), k = it.int("k"), at = it.str("at"))
            },
            holds = o.str("holds"),
            checkedAt = o.str("checked_at"),
            reportedAt = o.str("reported_at")?.takeIf { it.isNotBlank() },
        )
    }
}

/** A delivery receipt: the device said it received the file. NOT proof it still holds it. [at] is null until persist v52. */
data class CustodyReceipt(val epoch: Long? = null, val k: Int? = null, val at: String? = null)

/**
 * What a device row says under "holds". "Not on this device" is [None] and
 * ONLY that: the device itself said it has no copy. Silence is [Unknown].
 */
sealed interface Holds {
    data object Here : Holds
    data class Received(val at: String?) : Holds
    data object None : Holds
    data object Unknown : Holds
}

/**
 * A device's holds line. Pure.
 *
 * * `here` → [Holds.Here].
 * * `received` → [Holds.Received], with the receipt's time — unless the node
 *   says receipts are not supported for this file, in which case a received
 *   claim cannot be true and it is [Holds.Unknown].
 * * `none` → [Holds.None]: the device said it has no copy (this device's own
 *   answer today; other devices' with `custody:ack:v1`). The server already
 *   lets a device's own "none" override an older receipt from it; the client
 *   does not re-derive that.
 * * anything else — `unknown`, a token this client does not know, or nothing —
 *   is [Holds.Unknown]. **Never "not on".** An inline file (≤ 1 MiB) carries no
 *   receipts until persist v52 / edge v38, so silence is not absence.
 */
fun custodyHolds(device: CustodyDevice, receiptsSupported: Boolean?): Holds = when (device.holds) {
    "here" -> Holds.Here
    "none" -> Holds.None
    "received" -> if (receiptsSupported == false) Holds.Unknown else Holds.Received(device.received?.at)
    else -> Holds.Unknown
}

/** The "On N of your M devices" line. */
sealed interface CustodySummary {
    /** Every device is accounted for: [on] of [total]. */
    data class Exactly(val on: Int, val total: Int) : CustodySummary

    /** Some devices can't be told: at least [on] of [total], and [unknown] of them can't say. */
    data class AtLeast(val on: Int, val total: Int, val unknown: Int) : CustodySummary

    /** The node named no devices. */
    data object NoDevices : CustodySummary
}

/**
 * Pure. [total] is the node's `devices_total` when sent, else the rows it
 * listed. A device counts as ON when it holds the file here or has receipted
 * it, and as OFF only when it said `none`; an unknown device is never counted
 * as off, so any unknown turns the sentence into "at least".
 */
fun custodySummary(c: FileCustody): CustodySummary {
    val total = c.devicesTotal ?: c.devices.size
    if (total <= 0 && c.devices.isEmpty()) return CustodySummary.NoDevices
    val holds = c.devices.map { custodyHolds(it, c.receiptsSupported) }
    val on = holds.count { it is Holds.Here || it is Holds.Received }
    // Devices the node counted but did not list are unknown too.
    val unknown = holds.count { it is Holds.Unknown } + (total - c.devices.size).coerceAtLeast(0)
    return if (unknown == 0) CustodySummary.Exactly(on, total) else CustodySummary.AtLeast(on, total, unknown)
}

/** The copies line. */
sealed interface CopiesLine {
    data class Known(val count: Int) : CopiesLine

    /** `copies_observable: false` — say copies elsewhere can't be counted. Never "one copy". */
    data object NotObservable : CopiesLine

    /** Observable, but the node sent no count. */
    data object NotSent : CopiesLine
}

/**
 * Pure. A count is shown ONLY when the node says copies are observable. When
 * they are not (self and family files, CC 5.2) — or the node did not say — no
 * number is shown at all, because the one number available (the copies this
 * node can see) would read as "the only copy".
 */
fun custodyCopies(c: FileCustody): CopiesLine = when {
    c.copiesObservable != true -> CopiesLine.NotObservable
    c.copiesKnown != null -> CopiesLine.Known(c.copiesKnown)
    else -> CopiesLine.NotSent
}

/**
 * Whether the "receipts aren't kept for this file" footnote shows: only when
 * the node said so. Absent is not false.
 */
fun custodyReceiptsUnsupported(c: FileCustody): Boolean = c.receiptsSupported == false

/**
 * A reason id → the bundle key to try, or null when it is not id-shaped.
 * Server reason ids are keyed as themselves (`custody.inline_no_receipt` is
 * `{"custody": {"inline_no_receipt": …}}`, as `membership.consent_required`,
 * #137), exactly as refusal ids are (`refusalText`).
 */
fun custodyWhyKey(entry: String): String? = entry.trim().takeIf { WHY_ID.matches(it) }

private val WHY_ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")

/**
 * Whether a chat message is a file (for the chat hamburger's "Where is this
 * file"). A message is a file when its content type is not text. The chat
 * plane carries text only today (`chat.unsupported_content_type`,
 * CIRISServer `src/contacts_chat.rs`), so this is false on every row a
 * released node sends; the item appears the day a chat row is a file.
 */
fun isFileContentType(contentType: String?): Boolean {
    val ct = contentType?.trim()?.lowercase().orEmpty()
    return ct.isNotEmpty() && !ct.startsWith("text/")
}

// ── lenient readers: a retyped member is absent, never an exception ─────────

private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonObject.int(key: String): Int? = prim(key)?.takeIf { !it.isString }?.intOrNull
private fun JsonObject.long(key: String): Long? = prim(key)?.takeIf { !it.isString }?.longOrNull
private fun JsonObject.countOrInt(key: String): Int? = (this[key] as? JsonArray)?.size ?: int(key)
private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
