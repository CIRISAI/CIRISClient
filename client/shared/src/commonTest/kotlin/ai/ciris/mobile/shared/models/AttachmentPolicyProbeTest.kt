package ai.ciris.mobile.shared.models

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `interact_attachment_policy`'s text — what the with-AI files flow probes
 * (CIRISClient#217). Only a bare 404 reads as the named skip reason; a failed
 * read and a read not yet made are their own words, so the flow fails on them
 * instead of skipping.
 */
class AttachmentPolicyProbeTest {

    @Test
    fun eachSourceHasItsOwnProbeWord() {
        assertEquals("live", AttachmentPolicySource.AGENT.probe)
        assertEquals("agent older than the attachments policy route", AttachmentPolicySource.FALLBACK_AGENT_TOO_OLD.probe)
        assertEquals("unavailable", AttachmentPolicySource.FALLBACK_UNAVAILABLE.probe)
        assertEquals("pending", AttachmentPolicySource.PENDING.probe)
        assertEquals(AttachmentPolicySource.entries.size, AttachmentPolicySource.entries.map { it.probe }.toSet().size)
    }
}
