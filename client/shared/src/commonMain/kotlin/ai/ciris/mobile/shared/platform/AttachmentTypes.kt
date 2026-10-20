package ai.ciris.mobile.shared.platform

/**
 * The picker type gate, in one place so every platform picker asks the same
 * question and a test can read the answer (CIRISClient#217).
 *
 * The chat paperclip sends only [PickedFile.ALLOWED_MIME_TYPES]; every picker
 * offers only those, and `InteractViewModel.addAttachment` checks again with
 * [isChatAttachable], because a picker can be talked past (a typed file name
 * in a JFileChooser, a content provider that lies about its type).
 *
 * HEIC is REFUSED, not converted: the agent takes JPEG/PNG/GIF/WebP, and a
 * HEIC→JPEG step on iOS is iosMain code this repo's Linux CI cannot compile or
 * test. With exact UTTypes the iOS picker no longer offers HEIC at all; if one
 * arrives anyway it is named `image/heic` and refused with a word.
 */
object AttachmentTypes {

    const val OCTET = "application/octet-stream"
    const val ANY = "*/*"

    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Extension → MIME type. Image-like names are named as images, never [OCTET]. */
    private val BY_EXTENSION = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "heics" to "image/heic-sequence",
        "heif" to "image/heif",
        "heifs" to "image/heif-sequence",
        "avif" to "image/avif",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "svg" to "image/svg+xml",
        "dng" to "image/x-adobe-dng",
        "pdf" to "application/pdf",
        "docx" to DOCX,
        "doc" to "application/msword",
        "json" to "application/json",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "csv" to "text/csv",
    )

    /** Labels providers use for a type that has a canonical name. */
    private val ALIASES = mapOf(
        "image/jpg" to "image/jpeg",
        "image/pjpeg" to "image/jpeg",
        "text/x-markdown" to "text/markdown",
        "text/comma-separated-values" to "text/csv",
        "application/csv" to "text/csv",
    )

    /** MIME type → the extensions a chooser filter offers for it (only types a caller asks for). */
    private val EXTENSIONS = mapOf(
        "image/jpeg" to listOf("jpg", "jpeg"),
        "image/png" to listOf("png"),
        "image/gif" to listOf("gif"),
        "image/webp" to listOf("webp"),
        "application/pdf" to listOf("pdf"),
        DOCX to listOf("docx"),
        "application/json" to listOf("json"),
        "text/plain" to listOf("txt"),
        "text/markdown" to listOf("md", "markdown"),
        "text/csv" to listOf("csv"),
    )

    /** MIME type → the exact Uniform Type Identifier the iOS document picker opens with. */
    private val UTI = mapOf(
        ANY to "public.item",
        "image/jpeg" to "public.jpeg",
        "image/png" to "public.png",
        "image/gif" to "com.compuserve.gif",
        "image/webp" to "org.webmproject.webp",
        "application/pdf" to "com.adobe.pdf",
        DOCX to "org.openxmlformats.wordprocessingml.document",
        "application/json" to "public.json",
        "text/plain" to "public.plain-text",
        "text/markdown" to "net.daringfireball.markdown",
        "text/csv" to "public.comma-separated-values-text",
    )

    /** The MIME type a file's name implies, or [OCTET] when it implies none. */
    fun mimeTypeForFileName(fileName: String): String =
        BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()] ?: OCTET

    /** `type/subtype` lower-cased, parameters dropped; `image/jpg` is `image/jpeg`. */
    fun essence(mediaType: String): String {
        val e = mediaType.substringBefore(';').trim().lowercase()
        return ALIASES[e] ?: e
    }

    /** Whether the chat paperclip may send [mediaType]. */
    fun isChatAttachable(mediaType: String): Boolean = essence(mediaType) in PickedFile.ALLOWED_MIME_TYPES

    /**
     * File-name extensions a chooser filter offers for [mimeTypes]. A type with
     * no known extension is left out — [pickerAcceptsAll] does not then fall
     * back to "All Files", the chooser just offers less.
     */
    fun extensionsFor(mimeTypes: List<String>): List<String> =
        mimeTypes.flatMap { EXTENSIONS[essence(it)].orEmpty() }.distinct()

    /** Whether a chooser for [mimeTypes] keeps its "All Files" choice: only when the caller asked for any file. */
    fun pickerAcceptsAll(mimeTypes: List<String>): Boolean = ANY in mimeTypes

    /**
     * The media type to send for a file a content provider reported as
     * [reported]: missing, blank or [OCTET] is decided by [fileName].
     */
    fun normaliseMediaType(reported: String?, fileName: String): String {
        val e = reported?.let { essence(it) }.orEmpty()
        return if (e.isEmpty() || e == OCTET) mimeTypeForFileName(fileName) else e
    }

    /**
     * A content type the iOS document picker is opened with: a Uniform Type
     * [identifier] when one is known, else the [mime] for `UTType.typeWithMIMEType`.
     */
    data class UtRef(val identifier: String? = null, val mime: String? = null)

    /**
     * The iOS content types for [mimeTypes], de-duplicated. Every chat image
     * type is its exact UTType — never `public.image`, which admits HEIC and
     * TIFF that the agent cannot take.
     */
    fun iosContentTypes(mimeTypes: List<String>): List<UtRef> = mimeTypes.map { mime ->
        UTI[essence(mime)]?.let { UtRef(identifier = it) } ?: UtRef(mime = mime)
    }.distinct()
}
