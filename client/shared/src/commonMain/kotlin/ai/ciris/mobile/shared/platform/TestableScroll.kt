package ai.ciris.mobile.shared.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import ai.ciris.mobile.shared.testing.TestAutomationState
import kotlinx.coroutines.flow.collectLatest

/**
 * A SCROLLABLE SCREEN THAT AUTOMATION CAN ACTUALLY SCROLL.
 *
 * `/scroll` posted to a StateFlow that NOTHING IN THE TREE COLLECTED, on any
 * platform, and answered `success: true` regardless (CIRISClient#33). Nobody
 * noticed while `/click` and `/input` still drove off-screen elements through
 * a coordinate fallback; when 0.5.206 correctly made them refuse, a harness
 * recovering by scrolling was told the scroll worked and then met the same
 * refusal — which reads as a broken app rather than a missing feature.
 *
 * This is the missing half. Use it wherever a screen scrolls:
 *
 *     Modifier.testableVerticalScroll()                     // was: verticalScroll(rememberScrollState())
 *     val s = rememberTestableScrollState(); Modifier.verticalScroll(s)   // when the state is needed (a scrollbar)
 *
 * ONE IMPLEMENTATION, NOT A PER-PLATFORM COPY (CIRISClient#33). Registration
 * and dispatch are common code, so a screen cannot be scrollable on desktop
 * and inert on Android — which is exactly how Android ended up without the
 * `/scroll` route at all while the shared handler existed.
 *
 * Outside test mode this is `rememberScrollState()` and nothing else: no
 * registration, no collector, no cost.
 */
@Composable
fun rememberTestableScrollState(initial: Int = 0): ScrollState {
    val state = rememberScrollState(initial)
    if (!TestAutomation.isEnabled()) return state

    // The token identifies THIS scrollable among any that are composed. The
    // most recent one owns the dispatch, so a scrollable dialog over a
    // scrollable screen scrolls the dialog — what a person would expect, and
    // what stops two collectors racing on one request.
    val token = remember { TestAutomationState.registerScrollable() }
    DisposableEffect(token) {
        onDispose { TestAutomationState.unregisterScrollable(token) }
    }

    // Publish how far this scrollable can travel, so the dispatcher can pick a
    // container that is able to move rather than merely the newest one.
    LaunchedEffect(token, state.maxValue) {
        TestAutomationState.setScrollableCapacity(token, state.maxValue)
    }

    LaunchedEffect(token, state) {
        TestAutomationState.scrollRequests.collectLatest { request ->
            if (request == null || !TestAutomationState.isActiveScrollable(token)) return@collectLatest
            val delta = when (request.direction.lowercase()) {
                "up" -> -request.amount
                else -> request.amount
            }
            val before = state.value
            state.animateScrollBy(delta.toFloat())
            // REPORT THE MOVEMENT, NOT THE ATTEMPT. A scrollable with no
            // overflow, or one already at its end, consumes the request and
            // does not move; without these offsets `/scroll` answered 200 for
            // that exactly as for a scroll that worked (CIRISClient#44).
            TestAutomationState.recordScrollOutcome(before, state.value, state.maxValue)
            // CLEARING IS THE ACKNOWLEDGEMENT. handleScroll waits for this, so
            // it must happen after the scroll has run, never before.
            TestAutomationState.clearScrollRequest()
        }
    }
    return state
}

/** `verticalScroll` over a state automation can drive. See [rememberTestableScrollState]. */
fun Modifier.testableVerticalScroll(): Modifier = composed {
    verticalScroll(rememberTestableScrollState())
}
