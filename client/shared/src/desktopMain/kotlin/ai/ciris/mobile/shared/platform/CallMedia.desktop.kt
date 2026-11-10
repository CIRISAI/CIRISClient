package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable

/** No capture on this platform yet: the call screen says so as one sentence. */
@Composable
actual fun rememberCallMediaPlatform(): CallMediaPlatform = NoCallMediaPlatform
