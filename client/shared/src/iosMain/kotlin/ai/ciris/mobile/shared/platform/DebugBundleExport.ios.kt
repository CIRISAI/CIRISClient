package ai.ciris.mobile.shared.platform

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.UIKit.UIPasteboard

/**
 * iOS: Documents, so the file is visible in Files.app when sharing is enabled.
 *
 * THE OPT-IN IS NOT DECORATION — without it this file does not compile, and it
 * did not: `:shared:compileKotlinIosArm64` failed on it, which is why the
 * 0.5.191 publish produced an Android .aar and NO iOS XCFramework. The
 * `writeToFile(..., error)` overload takes a `CPointer<ObjCObjectVar<NSError?>>`
 * for its last argument, and passing even a `null` for one is foreign-API use.
 * Every other file in iosMain that touches cinterop already carries this; this
 * one arrived from the vendor without it and nothing on Linux or Android could
 * see that, because only a macOS runner compiles this target.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun saveDebugBundle(fileName: String, content: String): String? = runCatching {
    val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val dir = dirs.firstOrNull() as? String ?: return null
    val path = "$dir/$fileName"
    val ok = (content as NSString).writeToFile(path, true, NSUTF8StringEncoding, null)
    if (ok) path else null
}.getOrNull()

actual fun copyToClipboard(text: String): Boolean = runCatching {
    UIPasteboard.generalPasteboard.string = text
    true
}.getOrDefault(false)
