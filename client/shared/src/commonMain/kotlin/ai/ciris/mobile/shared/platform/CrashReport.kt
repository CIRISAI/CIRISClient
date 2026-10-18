package ai.ciris.mobile.shared.platform

/**
 * THE PART OF THE iOS CRASH HOOK THAT CAN BE TESTED OFF A MAC (CIRISClient#165).
 *
 * `CrashRecorder.ios.kt` runs inside Kotlin/Native's unhandled-exception hook,
 * a few instructions before `abort()`. Its first version logged before it
 * wrote, and logged with `NSLog("[CrashRecorder] %@", report)`: a Kotlin
 * `String` passed through C varargs reaches `%@` as a C string, CoreFoundation
 * reads its bytes as an Objective-C object, and the process takes SIGSEGV in
 * `objc_opt_respondsToSelector`. A segfault is not an exception, so
 * `runCatching` did nothing, and the `kotlin_crash.log` write on the next line
 * never ran. Every report the hook existed to keep was lost.
 *
 * Two rules, both enforced here so a test can hold them:
 *  1. [record] WRITES THE FILE FIRST. Whatever the log call does — throw,
 *     crash — the file already exists.
 *  2. [nsLogLiteral] makes a message safe to hand to NSLog AS the format
 *     string, with no varargs at all: every `%` is doubled, so there is no
 *     specifier for CoreFoundation to fetch an argument for.
 */
object CrashReport {

    /**
     * Class, message, every cause, every suppressed throwable, then the full
     * stack. kotlinx.coroutines attaches a `DiagnosticCoroutineContextException`
     * as a suppressed exception naming the coroutine's context — the dispatcher
     * and job of the coroutine that threw — which is the clue #50 needs, so the
     * suppressed list is spelled out rather than left to the stack dump.
     */
    fun format(throwable: Throwable): String = buildString {
        append("UNHANDLED ").append(describe(throwable)).append('\n')
        var cause = throwable.cause
        var depth = 0
        val seen = mutableSetOf<Throwable>(throwable)
        while (cause != null && depth < MAX_CAUSES && seen.add(cause)) {
            append("CAUSED BY ").append(describe(cause)).append('\n')
            cause = cause.cause
            depth++
        }
        for (s in throwable.suppressedExceptions) {
            append("SUPPRESSED ").append(describe(s)).append('\n')
        }
        append(throwable.stackTraceToString())
    }

    /**
     * Records [throwable] to [write] (the crash file) and THEN to [log].
     * Each sink is guarded separately, so a failing log cannot cost the file
     * and a failing write still lets the log try. Returns the report.
     */
    fun record(
        throwable: Throwable,
        write: (String) -> Unit,
        log: (String) -> Unit,
    ): String {
        val report = runCatching { format(throwable) }
            .getOrElse { "UNHANDLED ${describe(throwable)} (report formatting failed: $it)" }
        runCatching { write(report) }
        runCatching { log(report) }
        return report
    }

    /**
     * [message] rewritten so that, passed as NSLog's FORMAT with no further
     * arguments, it prints verbatim. No `%` survives undoubled, so no
     * conversion specifier can read a vararg that was never passed.
     */
    fun nsLogLiteral(message: String): String = message.replace("%", "%%")

    // simpleName, not qualifiedName: commonMain also compiles to wasmJs, where
    // qualifiedName is unsupported. The stack dump that follows opens with
    // Throwable.toString(), which carries the qualified name on Kotlin/Native.
    private fun describe(t: Throwable): String =
        "${t::class.simpleName ?: "Throwable"}: ${t.message}"

    private const val MAX_CAUSES = 16
}
