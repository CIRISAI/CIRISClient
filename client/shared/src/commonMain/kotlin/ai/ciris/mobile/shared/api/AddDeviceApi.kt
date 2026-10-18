package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.AddDeviceProgress
import ai.ciris.mobile.shared.models.federation.ApproveDeviceResponse
import ai.ciris.mobile.shared.models.federation.ClaimedSessionGrant
import ai.ciris.mobile.shared.models.federation.DenyDeviceResponse
import ai.ciris.mobile.shared.models.federation.DeviceJoinRequests
import ai.ciris.mobile.shared.models.federation.OpenDeviceRequestsResponse

/**
 * **The new device's half of "Add a device"** (CSD-093, CIRISServer 0.5.231
 * `FSD/ADD_DEVICE.md` §1, §2.1): every call goes to THIS device's own node over
 * loopback, with no session — first run has none.
 *
 * An interface so the wizard's view model is driven by a fake in tests; the
 * real one is [ClientAddDeviceSetup]. Every non-2xx is a [NodeRefusal].
 */
interface AddDeviceSetupApi {
    /** `POST /v1/setup/add-device {contact, label}`. */
    suspend fun start(contact: String, label: String?): AddDeviceProgress

    /** `GET /v1/setup/add-device`. */
    suspend fun status(): AddDeviceProgress

    /** `POST /v1/setup/add-device/complete`. */
    suspend fun complete(): AddDeviceProgress

    /** `POST /v1/setup/claimed-session {claim_pin}` — the owner session, once `complete` applied the claim. */
    suspend fun collectSession(claimPin: String): ClaimedSessionGrant
}

/** [AddDeviceSetupApi] over the real client, on the LOCAL node. */
class ClientAddDeviceSetup(private val client: CIRISApiClient) : AddDeviceSetupApi {
    override suspend fun start(contact: String, label: String?) =
        client.addDeviceStart(contact, label, CIRISApiClient.LOCAL_NODE_URL)
    override suspend fun status() = client.addDeviceStatus(CIRISApiClient.LOCAL_NODE_URL)
    override suspend fun complete() = client.addDeviceComplete(CIRISApiClient.LOCAL_NODE_URL)
    override suspend fun collectSession(claimPin: String) =
        client.collectClaimedSession(claimPin, CIRISApiClient.LOCAL_NODE_URL)
}

/**
 * **The approving device's half** (CSD-094): the owner's routes under
 * `/v1/self/device-requests`, with the client's owner session.
 */
interface DeviceRequestsApi {
    /** `POST /v1/self/device-requests/open {seconds?}`. */
    suspend fun open(seconds: Long?): OpenDeviceRequestsResponse

    /** `GET /v1/self/device-requests`. */
    suspend fun list(): DeviceJoinRequests

    /** `POST /v1/self/device-requests/{id}/approve`. */
    suspend fun approve(requestId: String): ApproveDeviceResponse

    /** `POST /v1/self/device-requests/{id}/deny`. */
    suspend fun deny(requestId: String): DenyDeviceResponse
}

/**
 * [DeviceRequestsApi] over the real client, on the node the card is showing.
 *
 * The node is a PROVIDER, read on every call: the app's view model outlives a
 * node-profile switch, and a URL captured at construction sent open/list/
 * approve/deny to the PREVIOUS node while the card showed the new one (Codex,
 * PR #189). Approving a device on the wrong node is an act on the wrong
 * identity, so the URL is never cached here.
 */
class ClientDeviceRequests(
    private val client: CIRISApiClient,
    private val nodeUrl: () -> String,
) : DeviceRequestsApi {
    /** A fixed node: tests, and a client that cannot switch. */
    constructor(client: CIRISApiClient, nodeBaseUrl: String = CIRISApiClient.LOCAL_NODE_URL) : this(client, { nodeBaseUrl })

    override suspend fun open(seconds: Long?) = client.openDeviceRequests(seconds, nodeUrl())
    override suspend fun list() = client.listDeviceRequests(nodeUrl())
    override suspend fun approve(requestId: String) = client.approveDeviceRequest(requestId, nodeUrl())
    override suspend fun deny(requestId: String) = client.denyDeviceRequest(requestId, nodeUrl())
}
