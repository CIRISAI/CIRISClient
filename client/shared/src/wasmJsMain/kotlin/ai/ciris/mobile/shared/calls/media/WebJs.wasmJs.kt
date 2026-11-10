package ai.ciris.mobile.shared.calls.media

/**
 * Moving bytes between Kotlin/Wasm arrays and JS typed arrays.
 *
 * A Kotlin ByteArray is a Wasm GC array, not linear memory, so JS cannot view
 * it. Element-by-element calls would cross the boundary once per byte (a video
 * frame is ~0.5 MB), so the bytes travel as a String instead: the runtime
 * copies strings across in bulk, two bytes per UTF-16 code unit (raw code
 * units, so any 16-bit value survives, lone surrogates included).
 */
internal fun ByteArray.toJsBytes(): JsAny {
    val n = size
    val chars = CharArray((n + 1) / 2) { i ->
        val lo = this[2 * i].toInt() and 0xFF
        val hi = if (2 * i + 1 < n) this[2 * i + 1].toInt() and 0xFF else 0
        (lo or (hi shl 8)).toChar()
    }
    return jsBytesFromString(chars.concatToString(), n)
}

/** Any Uint8Array / ArrayBuffer / ArrayBufferView, copied into a ByteArray. */
internal fun JsAny.jsToByteArray(): ByteArray {
    val n = jsByteLength(this)
    val s = jsBytesToString(this)
    return ByteArray(n) { i ->
        val c = s[i shr 1].code
        (if (i and 1 == 0) c and 0xFF else c shr 8).toByte()
    }
}

internal fun ShortArray.toJsInt16(): JsAny {
    val chars = CharArray(size) { this[it].toInt().toChar() }
    return jsInt16FromString(chars.concatToString())
}

internal fun JsAny.jsToShortArray(): ShortArray {
    val s = jsInt16ToString(this)
    return ShortArray(s.length) { s[it].code.toShort() }
}

/** A packed I420 buffer (Y, then U, then V) from JS, split into a [VideoFrame]. */
internal fun i420FromJs(width: Int, height: Int, timestampUs: Long, data: JsAny): VideoFrame {
    val all = data.jsToByteArray()
    val ySize = width * height
    val cSize = ySize / 4
    return VideoFrame(
        width, height,
        all.copyOfRange(0, ySize),
        all.copyOfRange(ySize, ySize + cSize),
        all.copyOfRange(ySize + cSize, ySize + 2 * cSize),
        timestampUs,
    )
}

internal fun VideoFrame.i420ToJs(): JsAny {
    val all = ByteArray(y.size + u.size + v.size)
    y.copyInto(all, 0)
    u.copyInto(all, y.size)
    v.copyInto(all, y.size + u.size)
    return all.toJsBytes()
}

private fun jsBytesFromString(s: String, n: Int): JsAny = js(
    """{
    const u = new Uint8Array(n);
    const m = s.length;
    for (let i = 0; i < m; i++) {
        const c = s.charCodeAt(i);
        u[2 * i] = c & 255;
        if (2 * i + 1 < n) u[2 * i + 1] = c >> 8;
    }
    return u;
}"""
)

private fun jsByteLength(b: JsAny): Int = js("b.byteLength")

private fun jsBytesToString(b: JsAny): String = js(
    """{
    const u = (b instanceof ArrayBuffer) ? new Uint8Array(b)
        : (b instanceof Uint8Array) ? b
        : new Uint8Array(b.buffer, b.byteOffset, b.byteLength);
    const n = u.length;
    const m = (n + 1) >> 1;
    const CH = 8192;
    const t = new Uint16Array(Math.min(m, CH));
    const parts = [];
    for (let off = 0; off < m; off += CH) {
        const k = Math.min(CH, m - off);
        for (let j = 0; j < k; j++) {
            const i = 2 * (off + j);
            t[j] = u[i] | ((i + 1 < n ? u[i + 1] : 0) << 8);
        }
        parts.push(String.fromCharCode.apply(null, k === t.length ? t : t.subarray(0, k)));
    }
    return parts.join('');
}"""
)

private fun jsInt16FromString(s: String): JsAny = js(
    """{
    const n = s.length;
    const a = new Int16Array(n);
    for (let i = 0; i < n; i++) a[i] = s.charCodeAt(i);
    return a;
}"""
)

private fun jsInt16ToString(a: JsAny): String = js(
    """{
    const u = new Uint16Array(a.buffer, a.byteOffset, a.length);
    const CH = 8192;
    const parts = [];
    for (let off = 0; off < u.length; off += CH) parts.push(String.fromCharCode.apply(null, u.subarray(off, Math.min(u.length, off + CH))));
    return parts.join('');
}"""
)

internal fun jsLog(message: String): Unit = js("console.warn('[calls/media] ' + message)")
