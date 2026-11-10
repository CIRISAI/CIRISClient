@file:OptIn(ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.calls.media

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreVideo.*
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo

// Pixel formats, as literal four-character codes so their Kotlin type is exactly UInt.
internal const val PIXEL_NV12_VIDEO = 0x34323076u // '420v' kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
internal const val PIXEL_NV12_FULL = 0x34323066u // '420f' kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
internal const val PIXEL_I420 = 0x79343230u // 'y420' kCVPixelFormatType_420YpCbCr8Planar
internal const val PIXEL_BGRA = 0x42475241u // 'BGRA' kCVPixelFormatType_32BGRA

/** MacTypes `Boolean` comes through cinterop as either Boolean or UByte; this reads both. */
internal fun macBool(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is UByte -> v.toInt() != 0
    is Byte -> v.toInt() != 0
    is Int -> v != 0
    else -> false
}

internal fun nowUs(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000.0).toLong()

/** A CFDictionary with CF-retaining callbacks. The caller CFReleases it. */
internal fun cfDictionary(entries: List<Pair<CFStringRef?, CFTypeRef?>>): CFMutableDictionaryRef? {
    val dict = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        entries.size.toLong(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    for ((k, v) in entries) CFDictionarySetValue(dict, k, v)
    return dict
}

/** Runs [block] with a +1 CFNumber, released after. */
internal inline fun <R> withCfInt(value: Int, block: (CFTypeRef?) -> R): R {
    val n = CFBridgingRetain(NSNumber(int = value))
    try {
        return block(n)
    } finally {
        if (n != null) CFRelease(n)
    }
}

/** Pixel-buffer attributes asking for an IOSurface-backed buffer of [pixelFormat] (or any format when null). */
internal fun pixelBufferAttributes(pixelFormat: UInt?): CFMutableDictionaryRef? {
    val surface = cfDictionary(emptyList())
    val format = pixelFormat?.let { CFBridgingRetain(NSNumber(unsignedInt = it)) }
    val entries = buildList<Pair<CFStringRef?, CFTypeRef?>> {
        add(kCVPixelBufferIOSurfacePropertiesKey to surface)
        if (format != null) add(kCVPixelBufferPixelFormatTypeKey to format)
    }
    val dict = cfDictionary(entries)
    if (surface != null) CFRelease(surface)
    if (format != null) CFRelease(format)
    return dict
}

/** BT.601 full range → limited range, so [VideoFrame.toArgb] reads every source the same way. */
private fun lumaFullToLimited(b: Int): Byte = (16 + (b * 219 + 127) / 255).toByte()
private fun chromaFullToLimited(b: Int): Byte = (128 + ((b - 128) * 224) / 255).toByte()

/**
 * A CVPixelBuffer (NV12 either range, planar I420 or BGRA) to an I420 [VideoFrame],
 * downscaled by a whole-number step so neither side exceeds [maxDimension] (0 = no limit).
 * Null for a format it does not read.
 */
internal fun pixelBufferToI420(pb: CVPixelBufferRef, maxDimension: Int = 0, timestampUs: Long = nowUs()): VideoFrame? {
    if (CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly) != 0) return null
    try {
        val format = CVPixelBufferGetPixelFormatType(pb)
        val srcW = CVPixelBufferGetWidth(pb).toInt()
        val srcH = CVPixelBufferGetHeight(pb).toInt()
        val longest = maxOf(srcW, srcH)
        val step = if (maxDimension in 1 until longest) (longest + maxDimension - 1) / maxDimension else 1
        val w = (srcW / step) and 1.inv()
        val h = (srcH / step) and 1.inv()
        if (w < 2 || h < 2) return null
        val y = ByteArray(w * h)
        val u = ByteArray(w * h / 4)
        val v = ByteArray(w * h / 4)
        val cw = w / 2
        when (format) {
            PIXEL_NV12_VIDEO, PIXEL_NV12_FULL -> {
                val full = format == PIXEL_NV12_FULL
                val yp = CVPixelBufferGetBaseAddressOfPlane(pb, 0uL)?.reinterpret<UByteVar>() ?: return null
                val ys = CVPixelBufferGetBytesPerRowOfPlane(pb, 0uL).toInt()
                val uvp = CVPixelBufferGetBaseAddressOfPlane(pb, 1uL)?.reinterpret<UByteVar>() ?: return null
                val uvs = CVPixelBufferGetBytesPerRowOfPlane(pb, 1uL).toInt()
                for (r in 0 until h) {
                    val row = r * step * ys
                    for (c in 0 until w) {
                        val b = yp[row + c * step].toInt()
                        y[r * w + c] = if (full) lumaFullToLimited(b) else b.toByte()
                    }
                }
                for (r in 0 until h / 2) {
                    val row = r * step * uvs
                    for (c in 0 until cw) {
                        val o = row + c * step * 2
                        val ub = uvp[o].toInt()
                        val vb = uvp[o + 1].toInt()
                        u[r * cw + c] = if (full) chromaFullToLimited(ub) else ub.toByte()
                        v[r * cw + c] = if (full) chromaFullToLimited(vb) else vb.toByte()
                    }
                }
            }
            PIXEL_I420 -> {
                val planes = (0..2).map { CVPixelBufferGetBaseAddressOfPlane(pb, it.toULong())?.reinterpret<UByteVar>() ?: return null }
                val strides = (0..2).map { CVPixelBufferGetBytesPerRowOfPlane(pb, it.toULong()).toInt() }
                for (r in 0 until h) for (c in 0 until w) y[r * w + c] = planes[0][r * step * strides[0] + c * step].toByte()
                for (r in 0 until h / 2) for (c in 0 until cw) {
                    u[r * cw + c] = planes[1][r * step * strides[1] + c * step].toByte()
                    v[r * cw + c] = planes[2][r * step * strides[2] + c * step].toByte()
                }
            }
            PIXEL_BGRA -> {
                val base = CVPixelBufferGetBaseAddress(pb)?.reinterpret<UByteVar>() ?: return null
                val stride = CVPixelBufferGetBytesPerRow(pb).toInt()
                for (r in 0 until h) for (c in 0 until w) {
                    val o = r * step * stride + c * step * 4
                    val bb = base[o].toInt(); val gg = base[o + 1].toInt(); val rr = base[o + 2].toInt()
                    y[r * w + c] = (((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16).toByte()
                    if (r % 2 == 0 && c % 2 == 0) {
                        val ci = (r / 2) * cw + c / 2
                        u[ci] = (((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128).toByte()
                        v[ci] = (((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128).toByte()
                    }
                }
            }
            else -> return null
        }
        return VideoFrame(w, h, y, u, v, timestampUs)
    } finally {
        CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly)
    }
}

/** An I420 [VideoFrame] to a new IOSurface-backed NV12 (video range) CVPixelBuffer, +1 retained. */
internal fun i420ToPixelBuffer(frame: VideoFrame): CVPixelBufferRef? {
    val attrs = pixelBufferAttributes(null)
    val pb = memScoped {
        val out = alloc<CVPixelBufferRefVar>()
        val status = CVPixelBufferCreate(kCFAllocatorDefault, frame.width.toULong(), frame.height.toULong(), PIXEL_NV12_VIDEO, attrs, out.ptr)
        if (status != 0) null else out.value
    }
    if (attrs != null) CFRelease(attrs)
    if (pb == null) return null
    if (CVPixelBufferLockBaseAddress(pb, 0uL) != 0) {
        CVPixelBufferRelease(pb)
        return null
    }
    var written = false
    try {
        val w = frame.width
        val h = frame.height
        val yp = CVPixelBufferGetBaseAddressOfPlane(pb, 0uL)?.reinterpret<ByteVar>()
        val ys = CVPixelBufferGetBytesPerRowOfPlane(pb, 0uL).toInt()
        val uvp = CVPixelBufferGetBaseAddressOfPlane(pb, 1uL)?.reinterpret<ByteVar>()
        val uvs = CVPixelBufferGetBytesPerRowOfPlane(pb, 1uL).toInt()
        if (yp != null && uvp != null) {
            frame.y.usePinned { src ->
                for (r in 0 until h) platform.posix.memcpy(yp + r * ys, src.addressOf(r * w), w.toULong())
            }
            val cw = w / 2
            for (r in 0 until h / 2) {
                val row = r * uvs
                for (c in 0 until cw) {
                    uvp[row + 2 * c] = frame.u[r * cw + c]
                    uvp[row + 2 * c + 1] = frame.v[r * cw + c]
                }
            }
            written = true
        }
    } finally {
        CVPixelBufferUnlockBaseAddress(pb, 0uL)
    }
    if (!written) {
        CVPixelBufferRelease(pb)
        return null
    }
    return pb
}
