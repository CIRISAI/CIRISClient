package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import io.ktor.utils.io.ByteReadChannel

/**
 * A file picked for the drive, as a STREAM, never as bytes in memory.
 *
 * The node takes a file of up to 2.5 GiB as a streamed multipart upload
 * (`POST /v1/files`, `size` declared before the `file` part; CIRISServer
 * `src/drive.rs`), so the client must never hold one whole: a phone would run
 * out of memory long before the node's ceiling. And the client imposes no size
 * limit of its own: the node refuses what it will not take, by name
 * (`drive.too_large`), and the screen says the node's words.
 *
 * @param sizeBytes the exact length; the node seals by it and refuses a body of
 *        any other length (`drive.declared_length_mismatch`).
 * @param head the first bytes, at most [HEAD_BYTES], for sniffing the type.
 * @param open a fresh stream of the whole file each time it is called.
 */
class PickedFileStream(
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val head: ByteArray,
    val open: () -> ByteReadChannel,
) {
    companion object {
        /** Enough for every format the sniff knows (`RenderTier`). */
        const val HEAD_BYTES = 4096
    }
}

/**
 * The drive's file picker: any file, any size, handed over as a [PickedFileStream].
 * Calls [onDismiss] on cancel, or when the file cannot be opened. On web there is
 * no picker yet, and it dismisses at once.
 */
@Composable
expect fun StreamedFilePickerDialog(
    show: Boolean,
    onPicked: (PickedFileStream) -> Unit,
    onDismiss: () -> Unit,
)
