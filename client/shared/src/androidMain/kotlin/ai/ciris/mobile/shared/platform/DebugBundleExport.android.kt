package ai.ciris.mobile.shared.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Android debug-bundle export — into the shared Downloads collection.
 *
 * NOT `getExternalFilesDir()`. That resolves under
 * `Android/data/<package>/files`, which scoped storage hides from ordinary file
 * managers and the system document picker on Android 11+. The UI would report
 * an absolute path as a successful "Download" that the user then cannot reach
 * without adb — the same "it said it worked" failure this whole change exists to
 * remove, one layer out.
 *
 * MediaStore rather than a share intent: this runs from screens where the app is
 * least healthy (a login that cannot exchange a token, a startup that never
 * completes), and a share sheet needs a live Activity. Writing a file needs only
 * a Context, and on Android 10+ MediaStore Downloads requires no runtime
 * permission.
 */
private var appContext: Context? = null

fun initDebugBundleExport(context: Context) {
    appContext = context.applicationContext
}

actual fun saveDebugBundle(fileName: String, content: String): String? =
    writeToDownloads(fileName, "text/plain", content.toByteArray())

actual fun saveFileCopy(fileName: String, mediaType: String, bytes: ByteArray): String? =
    writeToDownloads(fileName, mediaType, bytes)

internal fun writeToDownloads(fileName: String, mimeType: String, bytes: ByteArray): String? {
    val ctx = appContext ?: return null
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // IS_PENDING keeps the row invisible to other apps until the bytes
            // are actually there. The row has to be inserted before the stream
            // can be opened, so between those two calls a failure -- a full
            // disk, a revoked provider -- would otherwise publish an empty file
            // into Downloads. An empty debug bundle is worse than none: someone
            // sends it believing they sent their logs.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return@runCatching null

            val wrote = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("openOutputStream returned null for $uri")
            }
            if (wrote.isFailure) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                return@runCatching null
            }

            // Publish only now that the file is complete.
            //
            // AND TREAT A FAILED PUBLISH LIKE A FAILED WRITE (Codex, PR #18).
            // The bytes landing is not the same event as the row becoming
            // visible. If this update throws, the outer runCatching returns
            // null WITHOUT reaching the delete above, leaving the complete
            // bundle stored as an orphaned pending item — invisible to every
            // file manager, and never cleaned up. If it returns zero rows it
            // did not throw and did not publish, and the old code reported
            // "Downloads/$fileName" for a file the user cannot find.
            //
            // Both are the same failure as an empty row, which this function
            // already knew to clean up: something is in MediaStore that the
            // user cannot reach and did not ask to keep.
            val published = runCatching {
                ctx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }.getOrDefault(0)
            if (published <= 0) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                return@runCatching null
            }
            // A name the user can search for, not a URI they cannot act on.
            "Downloads/$fileName"
        } else {
            // Pre-Q (API 24-28): the app-specific external dir, NOT public
            // Downloads.
            //
            // Public Downloads needs WRITE_EXTERNAL_STORAGE on these versions —
            // which this app does not declare, and which would have to be
            // requested at runtime from an Activity that the screens using this
            // (a login that cannot exchange a token, a startup that never
            // completes) may not have. The write would simply throw and the
            // Download button would silently do nothing.
            //
            // The app-specific dir needs no permission, and on API 24-28 it is
            // genuinely browsable — scoped storage only hides it from Android 11
            // onward, which is exactly why the Q+ branch above exists.
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val out = File(dir, fileName)
            out.writeBytes(bytes)
            out.absolutePath
        }
    }.getOrNull()
}

actual fun copyToClipboard(text: String): Boolean {
    val ctx = appContext ?: return false
    return runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CIRIS debug bundle", text))
        true
    }.getOrDefault(false)
}

private fun shareFailed(why: String) =
    ai.ciris.mobile.shared.platform.PlatformLogger.w("DebugBundleExport", "[shareFile] failed on API ${Build.VERSION.SDK_INT}: $why")

/** The logs FileProvider's authority: `<package>.logs`, cache-path `logs/` (CIRISAgent 2.17.0). */
private fun logsAuthority(ctx: Context) = ctx.packageName + ".logs"

actual fun canShareFiles(): Boolean {
    val ctx = appContext ?: return false
    return runCatching { ctx.packageManager.resolveContentProvider(logsAuthority(ctx), 0) != null }.getOrDefault(false)
}

actual fun shareFile(fileName: String, mediaType: String, bytes: ByteArray): Boolean {
    val ctx = appContext ?: run { shareFailed("no Context"); return false }
    return runCatching {
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "ciris-logs.zip" })
        file.writeBytes(bytes)
        // Throws IllegalArgumentException where the app has no such provider
        // (an agent before 2.17.0): canShareFiles() keeps the button hidden there.
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, logsAuthority(ctx), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mediaType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        true
    }.getOrElse { shareFailed("${it::class.simpleName}: ${it.message}"); false }
}

actual fun zipTextEntry(zip: ByteArray, name: String): String? = jvmZipTextEntry(zip, name)

internal fun jvmZipTextEntry(zip: ByteArray, name: String): String? = runCatching {
    java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
        generateSequence { z.nextEntry }.firstOrNull { it.name == name }?.let { z.readBytes().decodeToString() }
    }
}.getOrNull()
