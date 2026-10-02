package ai.ciris.mobile.shared

import ai.ciris.mobile.shared.platform.TestAutomation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * The screen the host actually COMPOSES for a navigation [target].
 *
 * They differ in one place. `Screen.Setup` will not compose the wizard while
 * the brain is unprobed (`brainPresent == null`, CIRISClient#21), and holds on
 * StartupScreen until it is probed. The host branch asks this function, so the
 * hold and what `/screen` reports follow the same rule.
 */
internal fun composedScreen(target: Screen, brainPresent: Boolean?): Screen =
    if (target is Screen.Setup && brainPresent == null) Screen.Startup else target

/** The name `/screen` carries, the screen's class name as it always has been. */
internal fun screenName(screen: Screen): String = screen::class.simpleName ?: "unknown"

/**
 * Tell test automation which screen is composed. Call it from INSIDE the screen
 * host's content, so it runs only when that content is composed.
 *
 * `/screen` used to be set by `LaunchedEffect(currentScreen)` at the top of
 * CIRISApp, which reported the navigation intent. In CIRISClient#149 that
 * intent was Setup while the composition was StartupScreen with an empty tree.
 * A harness waiting for "Setup" passed, then timed out on a wizard control,
 * and the diagnosis started at the wrong layer.
 *
 * A DisposableEffect runs when this composition is applied, so the report
 * cannot arrive before the screen it names. The key is the name, so it reports
 * once per change and not once per frame. Every platform's test server reads
 * this one call: desktop through its `onSetScreen` hook, Android and iOS
 * through `TestAutomationState.currentScreen`.
 */
@Composable
internal fun ReportComposedScreen(screen: Screen) {
    val name = screenName(screen)
    DisposableEffect(name) {
        TestAutomation.setCurrentScreen(name)
        onDispose { }
    }
}
