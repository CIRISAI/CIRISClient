package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.ExportDestination
import ai.ciris.mobile.shared.models.TelemetryResponse

/**
 * The two READS the Telemetry card draws from (CSD-030): the overview and the
 * export destinations. The destination writes stay on the client.
 *
 * An interface of its own, like [ContactsApi] and [PeeringApi], so
 * `TelemetryViewModel` can be driven by a fake — the failed-read state the
 * card must publish is then asserted on directly, not awaited against a stub
 * server on a clock (the 15 s `awaitThat` in `HonestStatesTest` timed out on
 * CI twice, #98 and #122) — and so the calls stay statically traceable for
 * `packaging/check_csd_routes.py`, which follows an interface to its
 * implementor and on to the client method.
 *
 * A served 404 throws with the status in the message, as the client does, so
 * `ReadFailure.of` reads it as "not on this node".
 */
interface TelemetryApi {
    /** `GET /v1/telemetry/overview`. */
    suspend fun overview(): TelemetryResponse

    /** `GET /v1/telemetry/export/destinations`. */
    suspend fun exportDestinations(): List<ExportDestination>
}

/** [TelemetryApi] over the real client, with the client's session. */
class ClientTelemetryApi(private val client: CIRISApiClient) : TelemetryApi {
    override suspend fun overview(): TelemetryResponse = client.getTelemetry()
    override suspend fun exportDestinations(): List<ExportDestination> = client.getExportDestinations()
}
