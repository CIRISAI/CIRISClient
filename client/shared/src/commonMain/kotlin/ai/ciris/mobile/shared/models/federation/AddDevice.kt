package ai.ciris.mobile.shared.models.federation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * **Add a device** — the wire shapes of CIRISServer 0.5.231's add-device plane
 * (`FSD/ADD_DEVICE.md`, `src/add_device.rs`). Two halves:
 *
 *  * the NEW device (loopback, first run, no session): `POST /v1/setup/add-device`,
 *    `GET /v1/setup/add-device`, `POST /v1/setup/add-device/complete`;
 *  * the device the person ALREADY has (owner session): `/v1/self/device-requests`
 *    — open the window, list, approve, deny.
 *
 * Refusals on both halves are the auth surface's `{error, reason_id}`
 * ([ai.ciris.mobile.shared.api.NodeRefusal]); the `add_device.*` ids resolve in
 * the bundles.
 */

/** `POST /v1/setup/add-device` body (`StartRequest`, `src/add_device.rs:380-385`). */
@Serializable
data class AddDeviceStartRequest(
    /** The person's fedID the way *Share my ID* shows it: the QR's text, the long form or the short form. */
    val contact: String,
    /** This device's optional name ("S23 Phone"); the approver sees it. The server says "new device" when absent. */
    val label: String? = null,
)

/**
 * Where this device's request stands — the server's `JoinProgress`
 * (`src/add_device.rs:323-337`, initial `idle` at `:344`), answered by the start (202), by
 * `GET /v1/setup/add-device` and by `complete` (200).
 *
 * [state] is one of `idle | requested | approved | applied | denied | expired | failed`.
 * `idle` is the server's starting value (not in the FSD's table): nothing asked
 * since this node booted, unless a verified approval was held across the boot,
 * in which case it already reads `approved`.
 */
@Serializable
data class AddDeviceProgress(
    val state: String,
    @SerialName("match_code") val matchCode: String? = null,
    @SerialName("target_node") val targetNode: String? = null,
    @SerialName("occurrence_key_id") val occurrenceKeyId: String? = null,
    /** The identity this device is joining, once the owner's approval VERIFIED. Shown, never asked for. */
    @SerialName("owner_key_id") val ownerKeyId: String? = null,
    /** Why it `failed` (the verification that did not hold, or the other device's answer). */
    val detail: String? = null,
)

/** `POST /v1/self/device-requests/open` body: the window, in seconds (default 600, capped at 1800 by the node). */
@Serializable
data class OpenDeviceRequestsBody(val seconds: Long? = null)

/** `POST /v1/self/device-requests/open` answer (`src/add_device.rs:964-967`). */
@Serializable
data class OpenDeviceRequestsResponse(
    val open: Boolean = false,
    val seconds: Long = 0,
)

/** One request waiting for the owner (`src/add_device.rs:980-990`). */
@Serializable
data class DeviceJoinRequest(
    @SerialName("request_id") val requestId: String,
    val label: String = "",
    @SerialName("device_class") val deviceClass: String = "",
    @SerialName("match_code") val matchCode: String = "",
    @SerialName("node_key_id") val nodeKeyId: String = "",
    @SerialName("requested_secs_ago") val requestedSecsAgo: Long = 0,
)

/**
 * `GET /v1/self/device-requests` (`src/add_device.rs:972-999`).
 *
 * [windowSecsLeft] is null once the window has closed (or was never opened):
 * the node's sweep clears it. A closed window refuses NEW requests only; the
 * [requests] already held stay approvable until each one's own
 * [ADD_DEVICE_REQUEST_TTL_SECS] runs out.
 */
@Serializable
data class DeviceJoinRequests(
    val requests: List<DeviceJoinRequest> = emptyList(),
    @SerialName("window_secs_left") val windowSecsLeft: Long? = null,
)

/** `POST /v1/self/device-requests/{id}/approve` answer (`src/add_device.rs:1076-1085`). */
@Serializable
data class ApproveDeviceResponse(
    val approved: Boolean = false,
    @SerialName("match_code") val matchCode: String? = null,
    @SerialName("node_key_id") val nodeKeyId: String? = null,
    @SerialName("occurrence_key_id") val occurrenceKeyId: String? = null,
    @SerialName("self_dek_granted") val selfDekGranted: Int = 0,
)

/** `POST /v1/self/device-requests/{id}/deny` answer. */
@Serializable
data class DenyDeviceResponse(val denied: Boolean = false)

/** `REQUEST_TTL` (`src/add_device.rs:49`): how long an undecided request is held, and how long the new device keeps asking. */
const val ADD_DEVICE_REQUEST_TTL_SECS: Long = 15 * 60

/** `WINDOW_DEFAULT` (`src/add_device.rs:53`): the window the approving device opens when it names none. */
const val ADD_DEVICE_WINDOW_DEFAULT_SECS: Long = 10 * 60
