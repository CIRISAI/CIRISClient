package ai.ciris.mobile.shared.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/** Desktop: the user's home dir, which is always writable and always nameable. */
actual fun saveDebugBundle(fileName: String, content: String): String? = runCatching {
    val out = File(System.getProperty("user.home") ?: ".", fileName)
    out.writeText(content)
    out.absolutePath
}.getOrNull()

actual fun copyToClipboard(text: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    true
}.getOrDefault(false)

actual fun saveFileCopy(fileName: String, mediaType: String, bytes: ByteArray): String? = runCatching {
    val home = File(System.getProperty("user.home") ?: ".")
    val downloads = File(home, "Downloads").takeIf { it.isDirectory } ?: home
    val out = freeName(downloads, fileName)
    out.writeBytes(bytes)
    out.absolutePath
}.getOrNull()

/** `name.pdf`, then `name (2).pdf`, `name (3).pdf` … — never over someone's existing file. */
private fun freeName(dir: File, fileName: String): File {
    val first = File(dir, fileName)
    if (!first.exists()) return first
    val dot = fileName.lastIndexOf('.').takeIf { it > 0 }
    val stem = dot?.let { fileName.substring(0, it) } ?: fileName
    val ext = dot?.let { fileName.substring(it) } ?: ""
    var n = 2
    while (true) {
        val candidate = File(dir, "$stem ($n)$ext")
        if (!candidate.exists()) return candidate
        n++
    }
}
