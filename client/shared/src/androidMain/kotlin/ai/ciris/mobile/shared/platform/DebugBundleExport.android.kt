package ai.ciris.mobile.shared.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
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

/**
 * WHY A WRITE FAILED, SAID IN THE LOG (the maintainer's phone, 2026-10-26:
 * Samsung SM-G998U, Android 15, a Play release build). Download "did not work"
 * and Copy did, and the bundle could not say which step refused: every failure
 * here returned a bare null. Each one now logs its step and cause into the
 * buffer the next bundle carries (Copy still works, so that bundle arrives).
 */
private fun exportFailed(step: String, e: Throwable? = null): String? {
    ai.ciris.mobile.shared.platform.PlatformLogger.w(
        "DebugBundleExport",
        "[writeToDownloads] $step failed on API ${Build.VERSION.SDK_INT} (${Build.MANUFACTURER} ${Build.MODEL})" +
            (e?.let { ": ${it::class.simpleName}: ${it.message}" } ?: ""),
    )
    return null
}

internal fun writeToDownloads(fileName: String, mimeType: String, bytes: ByteArray): String? {
    val ctx = appContext ?: return exportFailed("no Context (initDebugBundleExport was never called)")
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
            ) ?: return@runCatching exportFailed("MediaStore insert into Downloads (returned null)")

            val wrote = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("openOutputStream returned null for $uri")
            }
            if (wrote.isFailure) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                return@runCatching exportFailed("writing the bytes", wrote.exceptionOrNull())
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
            val publish = runCatching {
                ctx.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            val published = publish.getOrDefault(0)
            if (published <= 0) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                return@runCatching exportFailed("publishing (IS_PENDING=0 updated $published rows)", publish.exceptionOrNull())
            }
            // THE NAME MEDIASTORE ACTUALLY GAVE IT. On a collision Android
            // stores "name (1).txt" and the panel used to report the name it
            // asked for, which is a file the user then could not find.
            val stored = runCatching {
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: fileName
            // A name the user can search for, not a URI they cannot act on.
            "Downloads/$stored"
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
    }.getOrElse { exportFailed("the export", it) }
}

actual fun copyToClipboard(text: String): Boolean {
    val ctx = appContext ?: return false
    return runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CIRIS debug bundle", text))
        true
    }.getOrDefault(false)
}
