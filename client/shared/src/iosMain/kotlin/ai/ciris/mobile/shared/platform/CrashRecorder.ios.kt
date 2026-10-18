package ai.ciris.mobile.shared.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * WRITES DOWN WHY THE APP IS ABOUT TO ABORT (CIRISClient#50).
 *
 * Kotlin/Native does not survive an unhandled exception the way the JVM
 * platforms do: a coroutine that throws with no handler ends in
 * `terminateWithUnhandledException` → `abort()`. The runtime prints the
 * exception to stderr first, and for a simulator-launched app stderr goes
 * nowhere. #50 has two crash reports that agree on the shape — a launched
 * coroutine on Compose's dispatcher, SIGABRT on the main thread — and neither
 * carries the one thing that would say which coroutine: the exception's type
 * and message. `.ips` files do not record them for Kotlin/Native aborts.
 *
 * This does not catch anything or change what happens next. It records the
 * throwable — class, message, causes, suppressed (kotlinx.coroutines puts the
 * coroutine's context there), stack — to `Documents/ciris/logs/kotlin_crash.log`
 * FIRST (the five-platform gate collects it with the rest of `Documents/ciris`),
 * then to os_log, and then hands over to
 * whatever hook was installed before (or to the default, which terminates).
 * The next occurrence arrives with its reason attached.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class, ExperimentalForeignApi::class)
object CrashRecorder {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = setUnhandledExceptionHook(null)
        setUnhandledExceptionHook { throwable ->
            // FILE FIRST, then the log, each guarded (CIRISClient#165). The log
            // used to come first, as NSLog("[CrashRecorder] %@", report): a
            // Kotlin String through C varargs reaches %@ as a char*, so
            // CoreFoundation dereferenced the report's bytes as an object and
            // the process took SIGSEGV before the file was written. Now the
            // message IS the format, every % doubled, and no varargs are passed.
            CrashReport.record(
                throwable,
                write = ::write,
                log = { NSLog(CrashReport.nsLogLiteral("[CrashRecorder] $it")) },
            )
            previous?.invoke(throwable)
        }
    }

    private fun write(report: String) {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return
        val dir = "$documents/ciris/logs"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        NSString.create(string = report)
            .writeToFile("$dir/kotlin_crash.log", true, NSUTF8StringEncoding, null)
    }
}
