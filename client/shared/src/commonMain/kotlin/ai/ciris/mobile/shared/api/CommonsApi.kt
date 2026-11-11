package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.surfaces.CommonsBallotRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsDismissalRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsObjectionRequest
import ai.ciris.mobile.shared.models.surfaces.CommonsStanding
import ai.ciris.mobile.shared.models.surfaces.CommonsWriteResult

/**
 * **The four commons routes** (CSD-070; CIRISServer `src/commons_surface.rs`),
 * on this device's own node.
 *
 * An interface so the view model's order of events (what a re-read clears, what
 * a submission sends) is driven by a scripted node in tests; the real one is
 * [ClientCommons].
 */
interface CommonsApi {
    /** `GET /v1/commons/standing`. */
    suspend fun standing(cohort: String, cohortKeyId: String, actionId: String): CommonsStanding

    /** `POST /v1/commons/objections`. */
    suspend fun objection(request: CommonsObjectionRequest): CommonsWriteResult

    /** `POST /v1/commons/ballots`. */
    suspend fun ballot(request: CommonsBallotRequest): CommonsWriteResult

    /** `POST /v1/commons/dismissals`, the dry run and the submission both. */
    suspend fun dismissal(request: CommonsDismissalRequest): CommonsWriteResult
}

/** [CommonsApi] over the real client. */
class ClientCommons(private val client: CIRISApiClient) : CommonsApi {
    override suspend fun standing(cohort: String, cohortKeyId: String, actionId: String) =
        client.getCommonsStanding(cohort = cohort, cohortKeyId = cohortKeyId, actionId = actionId)
    override suspend fun objection(request: CommonsObjectionRequest) = client.postCommonsObjection(request)
    override suspend fun ballot(request: CommonsBallotRequest) = client.postCommonsBallot(request)
    override suspend fun dismissal(request: CommonsDismissalRequest) = client.postCommonsDismissal(request)
}
