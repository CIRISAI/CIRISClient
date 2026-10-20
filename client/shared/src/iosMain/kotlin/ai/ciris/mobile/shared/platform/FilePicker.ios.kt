package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.lastPathComponent
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
@Composable
actual fun FilePickerDialog(
    show: Boolean,
    mimeTypes: List<String>,
    onFilePicked: (PickedFile) -> Unit,
    onDismiss: () -> Unit,
    onTooLarge: (PickTooLarge) -> Unit,
) {
    val delegate = remember {
        FilePickerDelegate(onFilePicked, onDismiss, onTooLarge)
    }

    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect

        // The mapping is common code (AttachmentTypes.iosContentTypes) so a
        // test can read it; this only resolves each ref to a UTType. "*/*"
        // (Files › Add) is public.item — UTType has no MIME for the wildcard.
        val utTypes = AttachmentTypes.iosContentTypes(mimeTypes).mapNotNull { ref ->
            ref.identifier?.let { UTType.typeWithIdentifier(it) }
                ?: ref.mime?.let { UTType.typeWithMIMEType(it) }
        }

        val uniqueTypes = utTypes.distinctBy { it.identifier }
        if (uniqueTypes.isEmpty()) {
            onDismiss()
            return@LaunchedEffect
        }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = uniqueTypes,
            asCopy = true
        )
        picker.delegate = delegate
        picker.allowsMultipleSelection = false

        val rootVc = UIApplication.sharedApplication.keyWindow?.rootViewController
        rootVc?.presentViewController(picker, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
private class FilePickerDelegate(
    private val onFilePicked: (PickedFile) -> Unit,
    private val onDismiss: () -> Unit,
    private val onTooLarge: (PickTooLarge) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onDismiss()
            return
        }

        val fileName = url.lastPathComponent ?: "attachment"
        val data = NSData.dataWithContentsOfURL(url)
        if (data == null) {
            PlatformLogger.e("FilePicker", "Failed to read file data from $fileName")
            onDismiss()
            return
        }

        val sizeBytes = data.length.toLong()
        if (sizeBytes > PickedFile.MAX_FILE_SIZE_BYTES) {
            PlatformLogger.w("FilePicker", "File too large: $sizeBytes bytes")
            onTooLarge(PickTooLarge(fileName, sizeBytes))
            return
        }

        val bytes = data.toByteArray()
        val base64 = Base64.encode(bytes)
        val mediaType = AttachmentTypes.mimeTypeForFileName(fileName)

        onFilePicked(
            PickedFile(
                name = fileName,
                mediaType = mediaType,
                dataBase64 = base64,
                sizeBytes = sizeBytes
            )
        )
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDismiss()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return bytes
}
