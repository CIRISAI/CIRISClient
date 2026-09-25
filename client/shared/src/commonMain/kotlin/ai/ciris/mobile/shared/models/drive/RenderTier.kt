package ai.ciris.mobile.shared.models.drive

/**
 * The receiver's media policy: which sniffed formats are Tier A here, and
 * their caps. [RECOMMENDED] is CC 5.3.2.6's recommended table with the caps
 * in `FSD/MEDIA_EDGE.md` §3. The constitution makes the table
 * recommended, not normative, and a node may narrow it. When the node
 * publishes its own (`GET /v1/media/policy`, CIRISServer#643 /
 * CIRISEdge#638 item 5), that value replaces this one. The client must not
 * widen it.
 */
data class MediaPolicy(
    /** Tier A essences and the byte cap for each. */
    val tierA: Map<String, Long>,
) {
    companion object {
        private const val MB = 1_048_576L
        val RECOMMENDED = MediaPolicy(
            tierA = mapOf(
                "text/plain" to 1 * MB,
                "image/jpeg" to 16 * MB,
                "image/png" to 16 * MB,
                "image/gif" to 25 * MB,
                "image/webp" to 10 * MB,
                "video/mp4" to 100 * MB,
                "audio/mp4" to 16 * MB,
                "audio/mpeg" to 16 * MB,
            ),
        )
    }
}

/**
 * Whether and how an opened file renders (CC 5.3.2.6 `render-tier`, rc5).
 *
 * The decision comes from the bytes and this client's policy, never from a
 * claim in the descriptor. The leading bytes are sniffed, and the sniffed
 * essence must EQUAL the declared type. A mismatch is a refusal, not a
 * correction. So a sender cannot widen what renders here by labelling a page
 * `text/plain`.
 *
 * The client renders only Tier A `text/plain` itself. Tier A images, audio and
 * video render from the node's rendition. `FSD/MEDIA_EDGE.md` §7's rule is that a client
 * renders only bytes produced by a memory-safe encoder we control. CIRISServer
 * has not built that pipeline yet (#614), so those formats are named as
 * waiting, not decoded here.
 *
 * Not done here: full-SHA verification (CC 5.3.2.5). Neither `/v1/drive` nor
 * `/v1/files/{id}` carries a digest yet (CIRISServer#641), so there is nothing to verify against.
 */
object RenderTier {

    sealed interface Decision {
        /** Tier A text: valid UTF-8, with every bidi control made visible. */
        data class Text(val visible: String) : Decision

        /** Tier A media: renders only from the node's safe rendition, which does not exist yet. */
        data class AwaitingNodeRendition(val format: String) : Decision

        /** Tier B raw / Tier C download (PDF, HEIC, WebM, over-cap, unknown binary…): no preview, a copy may be saved. */
        data class DownloadOnly(val format: String) : Decision

        /** Tier C refuse (HTML, SVG, archives, executables…): never rendered here. */
        data class Refused(val format: String) : Decision

        /** Claimed as text, but not valid UTF-8. A replacement character would be a guess. */
        data object NotUtf8 : Decision

        /** The bytes are not what the file says it is. CC 5.3.2.6: a refusal, not a correction. */
        data class Mismatch(val declared: String, val sniffed: String) : Decision

        /** A second file rides inside this one (a ZIP directory at the end, bytes after an image's end marker). */
        data class Polyglot(val format: String) : Decision
    }

    /** The one decision for [bytes] that claim to be [declared]. */
    fun decide(declared: String?, bytes: ByteArray, policy: MediaPolicy = MediaPolicy.RECOMMENDED): Decision {
        val sniffed = sniff(bytes)
        val claim = essence(declared)
        // A hidden second file first: its bytes can also confuse every check below.
        if (sniffed != ZIP && hasZipDirectory(bytes)) return Decision.Polyglot(claim ?: sniffed)
        if (hasTrailer(sniffed, bytes)) return Decision.Polyglot(sniffed)
        if (claim != null && claim != OCTET && sniffed != OCTET && claim != sniffed) {
            return Decision.Mismatch(claim, sniffed)
        }
        if (claim == TEXT_PLAIN && sniffed == OCTET) return Decision.NotUtf8

        val cap = policy.tierA[sniffed]
        return when {
            sniffed in TIER_C_REFUSE -> Decision.Refused(sniffed)
            // An absent claim can never EQUAL a sniffed essence, so it never renders.
            cap == null || claim != sniffed -> Decision.DownloadOnly(if (sniffed == OCTET) (claim ?: OCTET) else sniffed)
            bytes.size > cap -> Decision.DownloadOnly(sniffed)
            sniffed == TEXT_PLAIN -> {
                val text = bytes.decodeToStringOrNull() ?: return Decision.NotUtf8
                Decision.Text(showBidiControls(text.removePrefix("﻿")))
            }
            else -> Decision.AwaitingNodeRendition(sniffed)
        }
    }

    /**
     * Whether "Save a copy" is allowed. It never is for a mismatch or a
     * polyglot, because a disguised file is the attack. It never is for a kind
     * of file that runs code when opened, judged by the name it will be SAVED
     * under and by the sniffed bytes (CC 5.3.2.6: "dangerous-extension block
     * on save").
     */
    fun saveAllowed(decision: Decision, fileName: String, bytes: ByteArray): Boolean {
        if (decision is Decision.Mismatch || decision is Decision.Polyglot) return false
        if (sniff(bytes) in RUNS_CODE) return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext !in DANGEROUS_EXTENSIONS
    }

    /** The media-type essence: lowercased, parameters dropped, common aliases folded. */
    fun essence(mediaType: String?): String? {
        val e = mediaType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return ALIASES[e] ?: e
    }

    /**
     * The essence the leading bytes show: a masked-prefix table plus the
     * `ftyp` brand, `FSD/MEDIA_EDGE.md` §4 step 2. No libmagic. Unknown binary is
     * `application/octet-stream`.
     */
    fun sniff(bytes: ByteArray): String {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        fun ascii(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
        return when {
            ascii(0, "\u0089PNG\r\n\u001A\n") -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(0, "%PDF-") -> "application/pdf"
            ascii(4, "ftyp") -> when {
                ascii(8, "M4A ") -> "audio/mp4"
                ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") -> "image/heic"
                ascii(8, "avif") -> "image/avif"
                ascii(8, "qt  ") -> "video/quicktime"
                else -> "video/mp4"
            }
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> "video/webm"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "ID3") || (at(0) == 0xFF && at(1) >= 0xE0 && at(1) != 0xFF) -> "audio/mpeg"
            ascii(0, "PK\u0003\u0004") || ascii(0, "PK\u0005\u0006") -> ZIP
            ascii(0, "\u007FELF") -> EXECUTABLE
            ascii(0, "MZ") && isPortableExecutable(bytes) -> EXECUTABLE
            at(0) == 0xCF && at(1) == 0xFA && at(2) == 0xED && at(3) == 0xFE -> EXECUTABLE // Mach-O
            ascii(0, "#!") -> SCRIPT
            else -> sniffText(bytes)
        }
    }

    /** Text, or markup that must not pass as `text/plain`. */
    private fun sniffText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return TEXT_PLAIN
        val head = bytes.copyOfRange(0, minOf(bytes.size, SNIFF_BYTES))
        if (head.any { it.toInt() == 0 }) return OCTET
        // A head cut mid-character is still text: drop up to three trailing bytes of a partial sequence.
        val cut = if (bytes.size > head.size) 3 else 0
        val text = (0..cut).firstNotNullOfOrNull { head.copyOfRange(0, head.size - it).decodeToStringOrNull() }
            ?: return OCTET
        val lead = text.removePrefix("﻿").trimStart().lowercase()
        return when {
            lead.startsWith("<svg") || (lead.startsWith("<?xml") && "<svg" in lead) -> "image/svg+xml"
            lead.startsWith("<!doctype html") || lead.startsWith("<html") || lead.startsWith("<script") ||
                lead.startsWith("<head") || lead.startsWith("<body") || lead.startsWith("<iframe") -> "text/html"
            else -> TEXT_PLAIN
        }
    }

    /** A ZIP end-of-central-directory record in the last 64 KB: an archive riding behind another file. */
    private fun hasZipDirectory(bytes: ByteArray): Boolean {
        val from = maxOf(0, bytes.size - ZIP_EOCD_WINDOW)
        for (i in bytes.size - 22 downTo from) {
            if (bytes[i].toInt() == 'P'.code && bytes[i + 1].toInt() == 'K'.code &&
                bytes[i + 2].toInt() == 5 && bytes[i + 3].toInt() == 6
            ) return true
        }
        return false
    }

    /** Bytes after the image's own end: PNG past `IEND` + CRC, JPEG past the last `FFD9`. */
    private fun hasTrailer(sniffed: String, bytes: ByteArray): Boolean = when (sniffed) {
        "image/png" -> {
            val iend = lastIndexOf(bytes, byteArrayOf(0x49, 0x45, 0x4E, 0x44)) // "IEND"
            iend >= 0 && bytes.size > iend + 4 + 4
        }
        "image/jpeg" -> {
            val eoi = lastIndexOf(bytes, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
            eoi >= 0 && bytes.size > eoi + 2
        }
        else -> false
    }

    private fun lastIndexOf(bytes: ByteArray, needle: ByteArray): Int {
        for (i in bytes.size - needle.size downTo 0) {
            if (needle.indices.all { bytes[i + it] == needle[it] }) return i
        }
        return -1
    }

    /** `MZ` alone is two letters of text; a PE file also carries `PE\0\0` where `e_lfanew` (0x3C) points. */
    private fun isPortableExecutable(bytes: ByteArray): Boolean {
        if (bytes.size < 0x40) return false
        val off = (bytes[0x3C].toInt() and 0xFF) or ((bytes[0x3D].toInt() and 0xFF) shl 8) or
            ((bytes[0x3E].toInt() and 0xFF) shl 16) or ((bytes[0x3F].toInt() and 0xFF) shl 24)
        if (off < 0 || off + 4 > bytes.size) return false
        return bytes[off].toInt() == 'P'.code && bytes[off + 1].toInt() == 'E'.code && bytes[off + 2].toInt() == 0 && bytes[off + 3].toInt() == 0
    }

    private fun ByteArray.decodeToStringOrNull(): String? =
        try { decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }

    /**
     * Every bidi control shown as a visible mark, so text cannot read one way
     * and mean another (CC 5.3.2.6 Tier A text: "bidi controls rendered visibly").
     */
    fun showBidiControls(text: String): String = buildString(text.length) {
        for (c in text) append(BIDI_MARKS[c] ?: c.toString())
    }

    /** `FSD/MEDIA_EDGE.md` §4 step 2: "the first 2 KB". */
    private const val SNIFF_BYTES = 2048
    private const val ZIP_EOCD_WINDOW = 65_536 + 22

    private const val TEXT_PLAIN = "text/plain"
    private const val OCTET = "application/octet-stream"
    private const val ZIP = "application/zip"
    private const val EXECUTABLE = "application/x-executable"
    private const val SCRIPT = "text/x-script"

    private val ALIASES = mapOf(
        "image/jpg" to "image/jpeg", "image/pjpeg" to "image/jpeg",
        "audio/mp3" to "audio/mpeg", "audio/x-m4a" to "audio/mp4", "audio/m4a" to "audio/mp4",
        "image/heif" to "image/heic", "audio/x-wav" to "audio/wav", "audio/wave" to "audio/wav",
        "application/x-zip-compressed" to "application/zip",
    )

    /** CC 5.3.2.6 Tier C refuse (the formats this sniff can name). */
    private val TIER_C_REFUSE = setOf("text/html", "image/svg+xml", ZIP, EXECUTABLE, SCRIPT)

    /** Sniffed kinds that run code when opened, whatever they are named. */
    private val RUNS_CODE = setOf("text/html", "image/svg+xml", EXECUTABLE, SCRIPT)

    /** Names that run code when opened. */
    private val DANGEROUS_EXTENSIONS = setOf(
        "exe", "com", "scr", "msi", "msp", "bat", "cmd", "pif", "cpl", "dll", "sys", "lnk", "hta", "reg",
        "js", "jse", "mjs", "vbs", "vbe", "wsf", "wsh", "ps1", "psm1", "sh", "bash", "zsh", "command", "csh",
        "app", "dmg", "pkg", "deb", "rpm", "apk", "aab", "ipa", "jar", "appimage", "run", "bin",
        "html", "htm", "xhtml", "shtml", "svg", "svgz", "xml", "mht", "mhtml", "url", "webloc", "desktop",
        "docm", "xlsm", "pptm", "iso", "img", "vhd", "vhdx",
    )

    private val BIDI_MARKS: Map<Char, String> = mapOf(
        '‪' to "⟨LRE⟩", '‫' to "⟨RLE⟩", '‬' to "⟨PDF⟩", '‭' to "⟨LRO⟩", '‮' to "⟨RLO⟩",
        '⁦' to "⟨LRI⟩", '⁧' to "⟨RLI⟩", '⁨' to "⟨FSI⟩", '⁩' to "⟨PDI⟩",
        '‎' to "⟨LRM⟩", '‏' to "⟨RLM⟩", '؜' to "⟨ALM⟩",
    )
}
