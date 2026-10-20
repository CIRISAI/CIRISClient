package ai.ciris.mobile.shared.models

import ai.ciris.mobile.shared.api.InteractWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The interact body's `attachments[]` and the policy body, as CIRISAgent
 * `fix/interact-attachments` @ 380455879 writes them (api_attachments.py:108-132),
 * and the note each refusal id renders as on a sent chip (CIRISClient#217).
 */
class AttachmentOutcomeWireTest {

    @Test
    fun everyOutcomeFieldIsRead() {
        val r = InteractWire.parseInteract(
            """{"success":true,"data":{"message_id":"m","response":"r","state":"WORK","processing_time_ms":1,"task_id":null,
               "outcome":"timeout","accord_forward":null,"attachments":[{"index":2,"kind":"document","filename":"a.pdf",
               "media_type":"application/pdf","status":"refused","reason":"too_large","size_bytes":11000000,
               "limit_bytes":10485760,"extracted_chars":null,"truncated":false}]}}""",
        )
        assertEquals("timeout", r.outcome)
        val o = r.attachments.single()
        assertEquals(AttachmentOutcome(2, "document", "a.pdf", "application/pdf", "refused", "too_large", 11000000, 10485760, null, false), o)
    }

    @Test
    fun anAgentWithoutTheFieldReadsAsNoVerdicts() {
        val r = InteractWire.parseInteract("""{"data":{"message_id":"m","response":"r","state":"WORK","processing_time_ms":1}}""")
        assertTrue(r.attachments.isEmpty())
        assertNull(r.outcome)
    }

    @Test
    fun thePolicyIsRead() {
        val p = assertNotNull(InteractWire.parsePolicy(
            """{"data":{"image_types":["image/png"],"image_max_bytes":20,"max_images":1,"document_types":["text/csv"],
               "document_max_bytes":10,"max_documents":2,"max_extracted_chars":50000}}""",
        ))
        assertEquals(listOf("image/png", "text/csv"), p.allTypes)
        assertNull(p.maxTotal)
    }

    private val en by lazy {
        val raw = assertNotNull(this::class.java.classLoader.getResource("localization/en.json")).readText()
        Json.parseToJsonElement(raw).jsonObject["mobile"]!!.jsonObject
    }

    private fun refused(reason: String) = AttachmentOutcome(0, "document", "a", "application/pdf", "refused", reason)

    @Test
    fun everyRefusalIdHasItsOwnLocalizedNote() {
        for (id in AttachmentReason.ALL) {
            val key = assertNotNull(refused(id).noteKey(), id)
            assertEquals("mobile.interact_attachment_reason_$id", key)
            assertNotNull(en[key.removePrefix("mobile.")], "en.json has no $key")
        }
    }

    @Test
    fun anUnknownRefusalIdIsStillSaid() {
        val key = assertNotNull(refused("some_future_reason").noteKey())
        assertEquals("mobile.interact_attachment_reason_other", key)
        assertNotNull(en[key.removePrefix("mobile.")])
    }

    @Test
    fun aTruncatedAcceptanceIsMarkedAndAPlainOneIsNot() {
        val ok = AttachmentOutcome(0, "document", "a", "text/plain", "accepted")
        assertNull(ok.noteKey())
        val cut = ok.copy(truncated = true)
        assertEquals("mobile.interact_attachment_truncated", cut.noteKey())
        assertNotNull(en["interact_attachment_truncated"])
    }
}
