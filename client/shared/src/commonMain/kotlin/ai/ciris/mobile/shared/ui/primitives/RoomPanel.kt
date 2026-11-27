package ai.ciris.mobile.shared.ui.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * A panel over the bottom of the screen that is NOT a ModalBottomSheet, for a
 * panel whose content opens sheets of its own: a room's "Files in this room"
 * hosts the Files screen, whose file view, receipt and custody are sheets. A
 * sheet opened inside a sheet left the desktop window taking no clicks at all
 * (Eric, 2026-11-27, opening a file attached in a chat). This is a popup, so a
 * sheet opened from inside it is the only sheet on screen.
 *
 * Tapping the dimmed area, or Esc / back, closes it.
 */
@Composable
fun RoomPanel(
    onDismiss: () -> Unit,
    height: Dp = 560.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        // Outside clicks are the scrim's, below; a click on a sheet opened from this
        // panel is on that sheet's own layer and must never close the panel.
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
            Surface(
                modifier = modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) { content() }
        }
    }
}
