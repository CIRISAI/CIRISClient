package ai.ciris.mobile.shared.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun FilePickerDialog(
    show: Boolean,
    mimeTypes: List<String>,
    onFilePicked: (PickedFile) -> Unit,
    onDismiss: () -> Unit,
    onTooLarge: (PickTooLarge) -> Unit,
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        when (val read = readFileFromUri(context, uri)) {
            is PickedFile -> onFilePicked(read)
            is PickTooLarge -> onTooLarge(read)
            else -> onDismiss()
        }
    }

    LaunchedEffect(show) {
        if (show) {
            launcher.launch(mimeTypes.toTypedArray())
        }
    }
}

/** A [PickedFile], a [PickTooLarge] (not read), or null when the file could not be read. */
@OptIn(ExperimentalEncodingApi::class)
private fun readFileFromUri(context: Context, uri: Uri): Any? {
    return try {
        val contentResolver = context.contentResolver
        val reportedType = contentResolver.getType(uri)

        // Get filename and size
        var fileName = "attachment"
        var fileSize = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: "attachment"
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
            }
        }

        if (fileSize > PickedFile.MAX_FILE_SIZE_BYTES) {
            PlatformLogger.w("FilePicker", "File too large: $fileSize bytes")
            return PickTooLarge(fileName, fileSize)
        }

        // The provider's type can be missing or a bare octet-stream; the
        // name decides then, so a PDF is not sent as an unknown document.
        val mimeType = AttachmentTypes.normaliseMediaType(reportedType, fileName)

        // Read bytes and encode to base64
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val base64 = Base64.encode(bytes)

        PickedFile(
            name = fileName,
            mediaType = mimeType,
            dataBase64 = base64,
            sizeBytes = bytes.size.toLong()
        )
    } catch (e: Exception) {
        PlatformLogger.e("FilePicker", "Failed to read file: ${e.message}")
        null
    }
}


/** The drive's picker: a stream from the content provider, never the bytes in memory. */
@Composable
actual fun StreamedFilePickerDialog(
    show: Boolean,
    onPicked: (PickedFileStream) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val picked = uri?.let { streamFromUri(context, it) }
        if (picked != null) onPicked(picked) else onDismiss()
    }
    LaunchedEffect(show) { if (show) launcher.launch(arrayOf("*/*")) }
}

private fun streamFromUri(context: Context, uri: Uri): PickedFileStream? {
    return try {
    val resolver = context.contentResolver
    var name = "file"
    var size = -1L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    // The node needs the exact length before the bytes. A provider that does not
    // report it is measured by reading the stream once, without keeping it.
    if (size < 0) {
        size = resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; total += n }
            total
        } ?: return null
    }
    val head = resolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(PickedFileStream.HEAD_BYTES)
        var read = 0
        while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
        buf.copyOf(read)
    } ?: return null
    PickedFileStream(
        name = name,
        mediaType = AttachmentTypes.normaliseMediaType(resolver.getType(uri), name),
        sizeBytes = size,
        head = head,
        open = { (resolver.openInputStream(uri) ?: error("the file is gone")).toByteReadChannel() },
    )
} catch (e: Exception) {
    PlatformLogger.e("FilePicker", "Could not open the file: ${e.message}")
    null
    }
}
