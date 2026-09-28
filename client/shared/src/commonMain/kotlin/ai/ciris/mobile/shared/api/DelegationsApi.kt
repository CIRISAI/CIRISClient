package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.federation.CreateDelegationResponse
import ai.ciris.mobile.shared.models.federation.DelegationConstraints
import ai.ciris.mobile.shared.models.federation.DelegationDto

/**
 * The owner's device-authorization grants on the LOCAL node (CSD-055), as the
 * Delegations card drives them. A seam of its own, like [SelfDevicesApi], so the
 * view model can be driven by a fake. Every call throws [NodeRefusal] on a
 * non-2xx answer.
 */
interface DelegationsApi {
    /** `GET /v1/auth/device/grants`. */
    suspend fun list(): List<DelegationDto>

    /** `POST /v1/auth/device/delegate`. */
    suspend fun create(
        label: String,
        mode: String,
        existingKeyId: String?,
        constraints: DelegationConstraints?,
    ): CreateDelegationResponse

    /** `POST /v1/auth/device/approve`. */
    suspend fun approve(userCode: String, constraints: DelegationConstraints?)

    /** `POST /v1/auth/device/deny`. */
    suspend fun deny(userCode: String)

    /** `POST /v1/auth/device/revoke`. */
    suspend fun revoke(clientId: String)
}

/** [DelegationsApi] over the real client and its owner session. */
class ClientDelegations(private val client: CIRISApiClient) : DelegationsApi {
    override suspend fun list(): List<DelegationDto> = client.listDelegations()
    override suspend fun create(
        label: String,
        mode: String,
        existingKeyId: String?,
        constraints: DelegationConstraints?,
    ): CreateDelegationResponse = client.createDelegation(
        label = label,
        mode = mode,
        existingKeyId = existingKeyId,
        constraints = constraints,
    )
    override suspend fun approve(userCode: String, constraints: DelegationConstraints?) {
        client.approveDeviceCode(userCode, constraints = constraints)
    }
    override suspend fun deny(userCode: String) {
        client.denyDeviceCode(userCode)
    }
    override suspend fun revoke(clientId: String) {
        client.revokeDelegation(clientId)
    }
}
