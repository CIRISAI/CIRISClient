package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable

/**
 * Represents a file picked by the user for attachment to a chat message.
 * Used across all platforms (Android, iOS, Desktop).
 */
data class PickedFile(
    val name: String,
    val mediaType: String,
    val dataBase64: String,
    val sizeBytes: Long
) {
    val isImage: Boolean get() = mediaType.startsWith("image/")
    val isDocument: Boolean get() = !isImage

    companion object {
        const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024 // 10MB
        const val MAX_ATTACHMENTS = 5

        val ALLOWED_MIME_TYPES = listOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
    }
}

/**
 * A file the picker would not read: it is over [limitBytes]
 * ([PickedFile.MAX_FILE_SIZE_BYTES]; uploads are still one inline base64
 * body, so the cap stays until streaming upload lands).
 */
data class PickTooLarge(val name: String, val sizeBytes: Long, val limitBytes: Long = PickedFile.MAX_FILE_SIZE_BYTES)

/**
 * Platform-specific file picker composable.
 * When [show] is true, displays a native file picker dialog.
 * Calls [onFilePicked] with the selected file, or [onDismiss] if cancelled.
 *
 * A file over [PickedFile.MAX_FILE_SIZE_BYTES] is NOT read and calls
 * [onTooLarge] instead — required, with no default, because every picker used
 * to drop such a file without a word and the person saw nothing happen.
 */
@Composable
expect fun FilePickerDialog(
    show: Boolean,
    mimeTypes: List<String> = PickedFile.ALLOWED_MIME_TYPES,
    onFilePicked: (PickedFile) -> Unit,
    onDismiss: () -> Unit,
    onTooLarge: (PickTooLarge) -> Unit,
)
