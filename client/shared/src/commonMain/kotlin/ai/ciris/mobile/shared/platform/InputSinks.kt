package ai.ciris.mobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Declare that the enclosing composable is LISTENING for `/input` on [tags].
 *
 * THE REGRESSION THIS CLOSES (CIRISClient#30)
 *
 * 0.5.197 made `/input` refuse with 422 when no sink is registered for a tag —
 * the right rule, because before that it answered `success: true` after a fixed
 * delay whether or not anything received the text. But nothing ever CALLED
 * `registerInputSink`. Six screens consume `TestAutomation.textInputRequests`
 * through a `when (request.testTag)` and all of them were working; the gate
 * asserted a registration none of them performed, and every text input on
 * every desktop went undrivable in one cut. Setup could not pass the YOU step.
 *
 * The invariant was right and the wiring was absent — the same shape as the
 * supervisor that nobody read in 0.5.196. So this time the registration is not
 * a separate thing to remember: it sits on the line after the collector, it
 * names the tags the dispatch below handles, and `check_ui_drivable.py` fails
 * the build if a tag is dispatched in a file that does not declare it here.
 *
 * Registered on composition, unregistered on dispose, keyed on the tag set so a
 * recomposition with the same tags does nothing.
 */
@Composable
fun rememberInputSinks(vararg tags: String) {
    DisposableEffect(tags.contentHashCode()) {
        for (t in tags) TestAutomation.registerInputSink(t)
        onDispose { for (t in tags) TestAutomation.unregisterInputSink(t) }
    }
}
