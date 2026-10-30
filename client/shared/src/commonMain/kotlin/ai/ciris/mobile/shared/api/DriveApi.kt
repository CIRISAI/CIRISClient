package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileCustody
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
    suspend fun readDrive(cohort: String? = null, roomId: String? = null, limit: Int = 100, after: String? = null): DriveListing
    /** `GET /v1/files/{id}`: [cohort] is the row's own (`DriveEntry.cohort`), never a default; [roomId] names the family or community room and is dropped for `self`. */
    suspend fun readFile(attestationId: String, cohort: String, roomId: String?): OpenedFile
    suspend fun writeFile(write: FileWrite): FileWritten
    suspend fun readNotes(): NoteListing
    suspend fun writeNote(body: String)

    /** `GET /v1/media/policy`: this node's render policy (0.5.217, CIRISServer#643). Public; a 404 is a node that predates it. */
    suspend fun readMediaPolicy(): MediaPolicy

    /**
     * `GET /v1/files/{id}/custody` (CSD-107): which of the person's devices a file
     * is on. [cohort] is `self` | `family` | `community`; [roomId] names the
     * family or community room and is omitted for `self`. In v0.5.218 (tagged, not released); 0.5.217
     * answers a bare 404.
     */
    suspend fun readCustody(attestationId: String, cohort: String, roomId: String?): FileCustody

    /** `POST /v1/files/{id}/rename {filename}` — author only (0.5.242). */
    suspend fun renameFile(attestationId: String, cohort: String, roomId: String?, filename: String): Unit =
        throw UnsupportedOperationException("renameFile")

    /** `POST /v1/files/{id}/move {cohort, room_id?, keep_source}` — author only, and a member of the target (0.5.242). */
    suspend fun moveFile(attestationId: String, cohort: String, roomId: String?, toCohort: String, toRoomId: String?, keepSource: Boolean): Unit =
        throw UnsupportedOperationException("moveFile")

    /** `DELETE /v1/files/{id}?cohort&room_id` — withdraw; author only. */
    suspend fun withdrawFile(attestationId: String, cohort: String, roomId: String?): Unit =
        throw UnsupportedOperationException("withdrawFile")
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
    override suspend fun readDrive(cohort: String?, roomId: String?, limit: Int, after: String?): DriveListing =
        client.readDrive(cohort, roomId, limit, nodeUrl = nodeUrl(), after = after)
    override suspend fun readFile(attestationId: String, cohort: String, roomId: String?): OpenedFile =
        client.readFile(attestationId, cohort, roomId, nodeUrl = nodeUrl())
    override suspend fun writeFile(write: FileWrite): FileWritten = client.writeFile(write, nodeUrl = nodeUrl())
    override suspend fun readNotes(): NoteListing = client.readNotes(nodeUrl = nodeUrl())
    override suspend fun writeNote(body: String) = client.writeNote(body, nodeUrl = nodeUrl())
    override suspend fun readMediaPolicy(): MediaPolicy = client.readMediaPolicy(nodeUrl = nodeUrl())
    override suspend fun readCustody(attestationId: String, cohort: String, roomId: String?): FileCustody =
        client.readFileCustody(attestationId, cohort, roomId, nodeUrl = nodeUrl())
    override suspend fun renameFile(attestationId: String, cohort: String, roomId: String?, filename: String) =
        client.fileAct(attestationId, "rename", cohort, roomId,
            kotlinx.serialization.json.buildJsonObject { put("filename", kotlinx.serialization.json.JsonPrimitive(filename)) }, nodeUrl())
    override suspend fun moveFile(attestationId: String, cohort: String, roomId: String?, toCohort: String, toRoomId: String?, keepSource: Boolean) =
        client.fileAct(attestationId, "move", cohort, roomId,
            kotlinx.serialization.json.buildJsonObject {
                put("cohort", kotlinx.serialization.json.JsonPrimitive(toCohort))
                toRoomId?.let { put("room_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                put("keep_source", kotlinx.serialization.json.JsonPrimitive(keepSource))
            }, nodeUrl())
    override suspend fun withdrawFile(attestationId: String, cohort: String, roomId: String?) =
        client.fileAct(attestationId, "withdraw", cohort, roomId, null, nodeUrl())
}
