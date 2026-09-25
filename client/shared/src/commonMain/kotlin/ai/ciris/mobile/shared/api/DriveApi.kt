package ai.ciris.mobile.shared.api

import ai.ciris.mobile.shared.models.drive.DriveListing
import ai.ciris.mobile.shared.models.drive.FileWrite
import ai.ciris.mobile.shared.models.drive.FileWritten
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
}
