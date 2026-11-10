package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable

@Composable
actual fun FilePickerDialog(
    show: Boolean,
    mimeTypes: List<String>,
    onFilePicked: (PickedFile) -> Unit,
    onDismiss: () -> Unit,
    onTooLarge: (PickTooLarge) -> Unit,
) {
    // TODO: Implement using HTML file input element
    // For now, dismiss immediately as web file picking requires HTML input element integration
    if (show) {
        onDismiss()
    }
}


/** No web picker yet: dismiss at once, as [FilePickerDialog] does. */
@Composable
actual fun StreamedFilePickerDialog(
    show: Boolean,
    onPicked: (PickedFileStream) -> Unit,
    onDismiss: () -> Unit,
) {
    if (show) onDismiss()
}
