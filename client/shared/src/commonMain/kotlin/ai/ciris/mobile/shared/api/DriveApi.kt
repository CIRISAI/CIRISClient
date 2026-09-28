package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
import ai.ciris.mobile.shared.models.drive.MediaPolicy
import ai.ciris.mobile.shared.models.drive.NoteListing
import ai.ciris.mobile.shared.models.drive.OpenedFile

/**
 * The drive plane, as the Files and Notes view models need it.
 *
 * An interface of its own, rather than more members on the 12,000-line client
 * protocol, so the view models can be driven by a fake in tests. The
 * alternative, pointing the real client at a closed port, only works until
 * something is listening there (it did once, and a test minted a real identity
 * on a developer's node).
 *
 * Every call throws [NodeRefusal] with the server's id on a non-2xx answer.
 */
interface DriveApi {
    suspend fun readDrive(cohort: String? = null, roomId: String? = null, limit: Int = 100): DriveListing
    suspend fun readFile(attestationId: String, roomId: String): OpenedFile
    suspend fun writeFile(write: FileWrite): FileWritten
    suspend fun readNotes(): NoteListing
    suspend fun writeNote(body: String)

    /** `GET /v1/media/policy`: this node's render policy (0.5.217, CIRISServer#643). Public; a 404 is a node that predates it. */
    suspend fun readMediaPolicy(): MediaPolicy
}

/**
 * The real client, bound to the NODE. Every drive route is the node's
 * (`src/drive.rs`), and an agent at the api base does not serve them
 * (CIRISAgent#1213), so each call is sent to [nodeUrl] — read at call time,
 * because a node switch can move it while a view model lives.
 */
class ClientDrive(
    private val client: CIRISApiClient,
    private val nodeUrl: () -> String = { CIRISApiClient.LOCAL_NODE_URL },
) : DriveApi {
    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int): DriveListing =
        client.readDrive(cohort, roomId, limit, nodeUrl = nodeUrl())
    override suspend fun readFile(attestationId: String, roomId: String): OpenedFile =
        client.readFile(attestationId, roomId, nodeUrl = nodeUrl())
    override suspend fun writeFile(write: FileWrite): FileWritten = client.writeFile(write, nodeUrl = nodeUrl())
    override suspend fun readNotes(): NoteListing = client.readNotes(nodeUrl = nodeUrl())
    override suspend fun writeNote(body: String) = client.writeNote(body, nodeUrl = nodeUrl())
    override suspend fun readMediaPolicy(): MediaPolicy = client.readMediaPolicy(nodeUrl = nodeUrl())
}
