package ai.ciris.mobile.shared.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True while a screen is drawn INSIDE the circles shell — the frame that
 * already carries the circle, the tabs, My things, Stop, the card's name and
 * the one back arrow.
 *
 * THE NAME IS THE POINT. Wave 1 said "inside the shell" by providing
 * `LocalIsCompactWindow = true` at every width, because compact was the flag
 * screens happened to read to drop their back arrow. That made a control named
 * for the window size mean something else entirely: a desktop at 1600dp was
 * told it was a phone, and every screen that read the local for LAYOUT got the
 * wrong answer so that screens reading it for CHROME could get the right one.
 * Two questions, one flag. This is the second question, asked honestly:
 *
 *   LocalIsCompactWindow — how wide is the window?   (layout)
 *   LocalInsideShell     — is the frame already there? (chrome)
 *
 * A screen inside the shell draws NO top bar of its own: no title (the shell
 * shows the card's name), no back arrow (the shell owns back). Its actions
 * survive — see [ai.ciris.mobile.shared.ui.shell.ScreenTopBar], which is what
 * screens call instead of Material's `TopAppBar` so that the rule holds in one
 * place instead of in fifty `if` statements.
 */
val LocalInsideShell = staticCompositionLocalOf<Boolean> { false }
