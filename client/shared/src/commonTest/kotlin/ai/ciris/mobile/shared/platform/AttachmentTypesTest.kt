package ai.ciris.mobile.shared.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker type gate (CIRISClient#217): every platform picker offers only
 * what the chat paperclip can send, and nothing image-like is named
 * `application/octet-stream` (which `isDocument` would ship as a document).
 */
class AttachmentTypesTest {

    private val chat = PickedFile.ALLOWED_MIME_TYPES

    // --- desktop: the JFileChooser filter ---

    @Test
    fun theChatChooserHasNoAllFilesChoice() {
        assertFalse(AttachmentTypes.pickerAcceptsAll(chat))
    }

    @Test
    fun filesAddKeepsAllFiles() {
        assertTrue(AttachmentTypes.pickerAcceptsAll(listOf("*/*")))
    }

    @Test
    fun theTrustRootJsonChooserOffersJsonOnly() {
        assertEquals(listOf("json"), AttachmentTypes.extensionsFor(listOf("application/json")))
        assertFalse(AttachmentTypes.pickerAcceptsAll(listOf("application/json")))
    }

    @Test
    fun everyChatTypeHasAnExtensionAndEveryExtensionMapsBackIntoTheChatSet() {
        val exts = AttachmentTypes.extensionsFor(chat)
        assertEquals(listOf("jpg", "jpeg", "png", "gif", "webp", "pdf", "docx"), exts)
        for (e in exts) assertTrue(AttachmentTypes.mimeTypeForFileName("a.$e") in chat, e)
    }

    // --- guessMimeType (desktop + iOS) ---

    @Test
    fun imageLikeNamesAreNamedAsImagesNotOctetStream() {
        val cases = mapOf(
            "IMG_0001.HEIC" to "image/heic",
            "x.heif" to "image/heif",
            "scan.tiff" to "image/tiff",
            "scan.tif" to "image/tiff",
            "x.bmp" to "image/bmp",
            "x.avif" to "image/avif",
            "x.svg" to "image/svg+xml",
            "photo.JPG" to "image/jpeg",
        )
        for ((name, want) in cases) assertEquals(want, AttachmentTypes.mimeTypeForFileName(name), name)
    }

    @Test
    fun aHeicIsAnImageThatTheChatRefuses() {
        val f = PickedFile("IMG_0001.HEIC", AttachmentTypes.mimeTypeForFileName("IMG_0001.HEIC"), "", 1)
        assertTrue(f.isImage)
        assertFalse(AttachmentTypes.isChatAttachable(f.mediaType))
    }

    @Test
    fun theChatAcceptsExactlyTheAllowedSet() {
        for (m in chat) assertTrue(AttachmentTypes.isChatAttachable(m), m)
        for (m in listOf("application/octet-stream", "image/heic", "image/tiff", "text/plain", "application/msword", ""))
            assertFalse(AttachmentTypes.isChatAttachable(m), m)
        assertTrue(AttachmentTypes.isChatAttachable("IMAGE/PNG; charset=binary"))
    }

    // --- Android: the provider's getType() ---

    @Test
    fun aMissingOrOctetProviderTypeIsDecidedByTheName() {
        assertEquals("application/pdf", AttachmentTypes.normaliseMediaType(null, "a.pdf"))
        assertEquals("application/pdf", AttachmentTypes.normaliseMediaType("application/octet-stream", "a.pdf"))
        assertEquals("image/jpeg", AttachmentTypes.normaliseMediaType("", "a.jpeg"))
        assertEquals("image/jpeg", AttachmentTypes.normaliseMediaType("image/jpg", "a.jpeg"))
        assertEquals("image/png", AttachmentTypes.normaliseMediaType("Image/PNG", "noext"))
        assertEquals("application/octet-stream", AttachmentTypes.normaliseMediaType(null, "noext"))
    }

    // --- iOS: the document picker's content types ---

    @Test
    fun theIosPaperclipOffersExactImageTypesNotPublicImage() {
        val ids = AttachmentTypes.iosContentTypes(chat)
        assertEquals(
            listOf(
                "public.jpeg", "public.png", "com.compuserve.gif", "org.webmproject.webp",
                "com.adobe.pdf", "org.openxmlformats.wordprocessingml.document",
            ),
            ids.map { it.identifier },
        )
        assertTrue(ids.none { it.identifier == "public.image" })
    }

    // --- text documents (the agent reads them from fix/interact-attachments) ---

    private val text = listOf("text/plain", "text/markdown", "text/csv")

    @Test
    fun textDocumentsHaveExtensionsUtisAndNames() {
        assertEquals(listOf("txt", "md", "markdown", "csv"), AttachmentTypes.extensionsFor(text))
        assertEquals(
            listOf("public.plain-text", "net.daringfireball.markdown", "public.comma-separated-values-text"),
            AttachmentTypes.iosContentTypes(text).map { it.identifier },
        )
        assertEquals("text/plain", AttachmentTypes.mimeTypeForFileName("a.TXT"))
        assertEquals("text/markdown", AttachmentTypes.mimeTypeForFileName("README.markdown"))
        assertEquals("text/csv", AttachmentTypes.mimeTypeForFileName("t.csv"))
    }

    @Test
    fun androidTextLabelsAreNormalised() {
        assertEquals("text/markdown", AttachmentTypes.normaliseMediaType("application/octet-stream", "a.md"))
        assertEquals("text/markdown", AttachmentTypes.normaliseMediaType("text/x-markdown", "a.md"))
        assertEquals("text/csv", AttachmentTypes.normaliseMediaType("text/comma-separated-values", "a.csv"))
        assertEquals("text/plain", AttachmentTypes.normaliseMediaType("text/plain; charset=utf-8", "a.txt"))
    }

    @Test
    fun filesAddOnIosIsAnyItem() {
        assertEquals(listOf(AttachmentTypes.UtRef(identifier = "public.item")), AttachmentTypes.iosContentTypes(listOf("*/*")))
    }

    @Test
    fun anUnknownMimeOnIosFallsBackToItsMime() {
        val r = AttachmentTypes.iosContentTypes(listOf("application/x-unknown"))
        assertEquals(listOf(AttachmentTypes.UtRef(mime = "application/x-unknown")), r)
        assertNull(r.single().identifier)
    }
}
