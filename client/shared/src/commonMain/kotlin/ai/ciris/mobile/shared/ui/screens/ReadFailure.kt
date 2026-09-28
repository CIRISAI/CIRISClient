package ai.ciris.mobile.shared.ui.screens

import ai.ciris.mobile.shared.api.RouteNotOnThisHost
import ai.ciris.mobile.shared.localization.localizedString
import ai.ciris.mobile.shared.ui.glyphs.GlyphName
import ai.ciris.mobile.shared.ui.primitives.ListState
import ai.ciris.mobile.shared.ui.primitives.StateBlock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Why a read produced no reading.
 *
 * CSD/3 §2.2 and [StateBlock]: error and empty never look alike, and a value
 * that was not read is never drawn as a reading. Two distinct failures, because
 * they are claims about different things:
 *
 *  * [NotOnThisNode] — the host has no such route. A fact about the NODE
 *    ("this node doesn't have X"), not about the person ("you have nothing").
 *  * [Failed] — the host was asked and the read failed.
 *
 * Carried inside the data objects the screens already receive, so a screen can
 * render it without its caller threading a second parameter through.
 */
sealed interface ReadFailure {
    val detail: String?

    data class NotOnThisNode(override val detail: String? = null) : ReadFailure
    data class Failed(override val detail: String?) : ReadFailure

    companion object {
        private val HTTP_404 = Regex("""\b404\b""")

        /**
         * Classify a thrown read. A served 404 means the route is absent on this
         * host; everything else is a failed read.
         */
        fun of(e: Throwable): ReadFailure {
            val message = e.message
            return when {
                e is RouteNotOnThisHost -> NotOnThisNode(message)
                message != null && HTTP_404.containsMatchIn(message) -> NotOnThisNode(message)
                else -> Failed(message ?: e::class.simpleName)
            }
        }
    }
}

/**
 * What a value that was not read draws as. One glyph, never a number: a zero,
 * a "WORK" or a 1.00 standing in for an absent value is a reading nobody took.
 */
const val NOT_READ = "—"

/** The [ListState] a [ReadFailure] renders as. Pure, so the distinction is testable without Compose. */
fun ReadFailure.listState(notOnThisNode: String, failedTitle: String, failedBody: String?): ListState =
    when (this) {
        is ReadFailure.NotOnThisNode -> ListState.Empty(notOnThisNode, glyph = GlyphName.INFO)
        is ReadFailure.Failed -> ListState.Error(title = failedTitle, body = failedBody, detail = detail)
    }

/**
 * Renders a [ReadFailure]. Tags: `<tagPrefix>_not_on_this_node` for an absent
 * route, `<tagPrefix>_error` for a failed read — two tags, so a flow can tell
 * them apart without reading copy.
 */
@Composable
fun ReadFailureBlock(
    failure: ReadFailure,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    notOnThisNode: String = localizedString("mobile.state_not_on_this_node"),
    inline: Boolean = false,
) {
    val tag = when (failure) {
        is ReadFailure.NotOnThisNode -> "${tagPrefix}_not_on_this_node"
        is ReadFailure.Failed -> "${tagPrefix}_error"
    }
    StateBlock(
        state = failure.listState(
            notOnThisNode = notOnThisNode,
            failedTitle = localizedString("mobile.state_read_failed"),
            failedBody = localizedString("mobile.state_read_failed_body"),
        ),
        tag = tag,
        modifier = modifier,
        inline = inline,
    )
}
