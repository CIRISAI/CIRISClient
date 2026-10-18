package ai.ciris.mobile.shared.ui.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity

/**
 * THE KEYBOARD, AS A CHAT COMPOSER SEES IT (CIRISClient#186).
 *
 * A chat screen pads its OWN content by the soft keyboard, so the composer sits
 * above it — never the whole app, whose top bar and back must not jump when a
 * field takes focus. Null means the platform's `WindowInsets.ime`; a headless
 * test provides a fixed inset here, because the desktop's `ime` is a constant
 * zero and a phone's keyboard cannot otherwise be put in front of a composer
 * off a phone.
 */
val LocalImeInsets = staticCompositionLocalOf<WindowInsets?> { null }

/** The keyboard inset the composers pad by: [LocalImeInsets] when provided, else the platform's. */
@Composable
fun imeInsets(): WindowInsets = LocalImeInsets.current ?: WindowInsets.ime

/** Pad by the keyboard — on a chat screen's content, under its own top bar. */
fun Modifier.composerImePadding(): Modifier = composed { windowInsetsPadding(imeInsets()) }

/** Is a keyboard up? The transcript scrolls to its newest message when this turns true. */
@Composable
fun imeOpen(): Boolean = imeInsets().getBottom(LocalDensity.current) > 0
