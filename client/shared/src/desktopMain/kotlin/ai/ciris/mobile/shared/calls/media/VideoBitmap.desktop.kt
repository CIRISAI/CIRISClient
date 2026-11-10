package ai.ciris.mobile.shared.calls.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** Skia on desktop, iOS and the web: ARGB ints are BGRA bytes in little-endian memory, Skia's N32. */
actual fun VideoFrame.toImageBitmap(): ImageBitmap {
    val argb = toArgb()
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val p = argb[i]
        bytes[4 * i] = p.toByte()                 // B
        bytes[4 * i + 1] = (p shr 8).toByte()     // G
        bytes[4 * i + 2] = (p shr 16).toByte()    // R
        bytes[4 * i + 3] = (p shr 24).toByte()    // A
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
    bitmap.installPixels(bytes)
    return bitmap.asComposeImageBitmap()
}
