package ai.ciris.mobile.shared.models.drive

import ai.ciris.mobile.shared.models.drive.RenderTier.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** CC 5.3.2.6: the render tier is receiver policy, computed from verified, sniffed bytes. */
class RenderTierTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)

    @Test
    fun plainUtf8TextClaimedAsTextRenders() {
        val d = RenderTier.decide("text/plain; charset=utf-8", "hello\nline two".encodeToByteArray())
        assertEquals("hello\nline two", assertIs<Decision.Text>(d).visible)
    }

    @Test
    fun aPageLabelledTextIsARefusalNotAPreview() {
        // The declared type is never an authorisation: the sender cannot widen what renders.
        val d = RenderTier.decide("text/plain", "<!DOCTYPE html><script>alert(1)</script>".encodeToByteArray())
        val m = assertIs<Decision.Mismatch>(d)
        assertEquals("text/plain", m.declared)
        assertEquals("text/html", m.sniffed)
    }

    @Test
    fun anImageLabelledTextIsAMismatchAndCannotBeSaved() {
        val d = RenderTier.decide("text/plain", png)
        assertIs<Decision.Mismatch>(d)
        assertFalse(RenderTier.saveAllowed(d, "notes.txt", png), "a disguised file must not be saved")
    }

    @Test
    fun textThatIsNotUtf8IsNotShown() {
        val latin1 = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), ' '.code.toByte(), 'x'.code.toByte())
        assertEquals(Decision.NotUtf8, RenderTier.decide("text/plain", latin1))
    }

    @Test
    fun bidiControlsAreShownNotObeyed() {
        // Trojan Source: text that reads one way and means another.
        val d = RenderTier.decide("text/plain", "pay ‮exe.txt".encodeToByteArray())
        val shown = assertIs<Decision.Text>(d).visible
        assertTrue("⟨RLO⟩" in shown, shown)
        assertFalse('‮' in shown)
    }

    @Test
    fun tierAMediaWaitsForTheNodesRenditionAndIsNeverDecodedHere() {
        assertEquals(Decision.AwaitingNodeRendition("image/png"), RenderTier.decide("image/png", png))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
        assertEquals(Decision.AwaitingNodeRendition("image/jpeg"), RenderTier.decide("image/jpg", jpeg), "image/jpg folds to image/jpeg")
    }

    @Test
    fun svgAndHtmlAreRefusedAndNeverSaved() {
        val svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()
        val d = RenderTier.decide("image/svg+xml", svg)
        assertIs<Decision.Refused>(d)
        assertFalse(RenderTier.saveAllowed(d, "logo.svg", svg))
    }

    @Test
    fun aPdfIsDownloadOnlyAndMaySave() {
        val pdf = "%PDF-1.7\n".encodeToByteArray()
        val d = RenderTier.decide("application/pdf", pdf)
        assertEquals(Decision.DownloadOnly("application/pdf"), d)
        assertTrue(RenderTier.saveAllowed(d, "report.pdf", pdf))
    }

    @Test
    fun aDangerousNameIsNotSavedWhateverTheBytes() {
        val text = "echo hi".encodeToByteArray()
        val d = RenderTier.decide("application/octet-stream", text)
        assertFalse(RenderTier.saveAllowed(d, "setup.EXE", text))
        assertFalse(RenderTier.saveAllowed(d, "run.sh", text))
        assertTrue(RenderTier.saveAllowed(d, "readme.txt", text))
    }

    @Test
    fun noClaimNeverRenders() {
        // An absent type can never EQUAL a sniffed essence.
        assertIs<Decision.DownloadOnly>(RenderTier.decide(null, "hello".encodeToByteArray()))
        assertIs<Decision.DownloadOnly>(RenderTier.decide("application/octet-stream", "hello".encodeToByteArray()))
    }

    @Test
    fun textThatStartsWithMzIsStillText() {
        val d = RenderTier.decide("text/plain", "MZ is a note, not a program".encodeToByteArray())
        assertIs<Decision.Text>(d)
    }

    @Test
    fun aLongTextCutMidCharacterIsStillSniffedAsText() {
        // 1023 ASCII bytes then a 3-byte character straddling the 1024-byte sniff window.
        val bytes = ("a".repeat(1023) + "€ tail").encodeToByteArray()
        assertEquals("text/plain", RenderTier.sniff(bytes))
    }

    @Test
    fun anArchiveRidingBehindTextIsAPolyglotAndIsNotSaved() {
        val bytes = "a harmless note\n".encodeToByteArray() + byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6) + ByteArray(18)
        val d = RenderTier.decide("text/plain", bytes)
        assertIs<Decision.Polyglot>(d)
        assertFalse(RenderTier.saveAllowed(d, "note.txt", bytes))
    }

    @Test
    fun bytesAfterAJpegsEndMarkerAreAPolyglot() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0xFF.toByte(), 0xD9.toByte()) +
            "<script>".encodeToByteArray()
        assertEquals(Decision.Polyglot("image/jpeg"), RenderTier.decide("image/jpeg", jpeg))
    }

    @Test
    fun aNodeThatNarrowsThePolicyIsObeyed() {
        // The table is the node's to narrow (GET /v1/media/policy); a format it drops is not rendered here.
        val narrowed = MediaPolicy(tierA = MediaPolicy.RECOMMENDED.tierA - "image/png")
        assertEquals(Decision.DownloadOnly("image/png"), RenderTier.decide("image/png", png, narrowed))
    }

    @Test
    fun textOverTheCapIsNotRendered() {
        val big = ByteArray(1_048_577) { 'a'.code.toByte() }
        assertEquals(Decision.DownloadOnly("text/plain"), RenderTier.decide("text/plain", big))
    }
}
