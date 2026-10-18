package ai.ciris.mobile.shared.platform

import platform.Foundation.NSLog

/**
 * NSLog [message] VERBATIM: it becomes the format with every `%` doubled and
 * no varargs, so nothing in it can be read as a conversion specifier
 * (CIRISClient#165).
 *
 * `NSLog("… $x")` makes the interpolated text the FORMAT. A Python log line
 * with `%s`, an error message with `%@`, a URL with `%20` would each send
 * CoreFoundation after a vararg that was never passed — garbage at best,
 * SIGSEGV on `%@`/`%s`. Never pass a Kotlin String as a vararg either: it
 * reaches `%@` as a char*, which is the crash #165 recorded.
 */
fun nsLogVerbatim(message: String) {
    NSLog(CrashReport.nsLogLiteral(message))
}
