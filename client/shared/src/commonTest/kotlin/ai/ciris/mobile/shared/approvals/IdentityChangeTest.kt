package ai.ciris.mobile.shared.approvals

import ai.ciris.mobile.shared.api.agentRefusal
import ai.ciris.mobile.shared.api.parseIdentityResolve
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identity changes on the WA screen (CIRISAgent v2.23.1-stable; Eric 2026-11-20:
 * accurate and robust). What must hold: the proposed text reaches Approve byte
 * for byte; Approve is off whenever the approval could not bind or apply; an
 * unreadable or unknown proposal is never approvable; an older agent gets the
 * plain view; refusals keep their reason id.
 */
class IdentityChangeTest {

    private val digest = "a".repeat(64)
    private val allCaps = DeferralListMeta(
        profile = IdentityProfile(IdentityProfileStatus.ANCHORED, null),
        capabilities = IdentityCapabilities(true, true, true),
    )

    private fun ctx(json: String) = mapOf("identity_change" to json)
    private fun q(s: String) = JsonPrimitive(s).toString() // a JSON string literal

    // ── the list's meta ───────────────────────────────────────────────

    @Test
    fun metaIsReadFromTheWrappedOrBareBody() {
        val body = """{"profile":{"status":"unanchored","proposed_base_link":"d-base"},
            "capabilities":{"identity_change_review":true,"exact_amendment_text":true,"base_link_approval":false}}"""
        for (raw in listOf("""{"data":$body}""", body)) {
            val m = parseDeferralListMeta(raw)
            assertEquals(IdentityProfileStatus.UNANCHORED, m.profile?.status)
            assertEquals("d-base", m.profile?.proposedBaseLink)
            assertEquals(IdentityCapabilities(true, true, false), m.capabilities)
        }
    }

    @Test
    fun aMissingOrUnreadableMetaIsThePlainView() {
        for (raw in listOf(null, "", "not json", """{"data":{"deferrals":[],"total":0}}""", """{"data":{"capabilities":{}}}""")) {
            val m = parseDeferralListMeta(raw)
            assertEquals(IdentityCapabilities.NONE, m.capabilities, "raw=$raw")
        }
        assertEquals(IdentityProfileStatus.UNKNOWN, parseDeferralListMeta("""{"profile":{"status":"sideways"}}""").profile?.status)
    }

    @Test
    fun anAgentWithoutIdentityReviewGetsThePlainView() {
        val d = deferral(ctx("""{"kind":"amendment","proposed_text":"I serve."}"""))
        assertNull(identityChangeOf(d, DeferralListMeta.NONE))
        assertIs<IdentityChange.Amendment>(identityChangeOf(d, allCaps))
    }

    // ── parsing each kind ─────────────────────────────────────────────

    @Test
    fun theProposedTextIsKeptByteForByte() {
        val text = "Line one.\n\n  Indented line,  with two spaces.\r\nTab\there — and ünïcode."
        val c = parseIdentityChange(ctx("""{"kind":"amendment","summary":"s","proposed_text":${q(text)},"m1_justification":"coherence"}"""))
        assertIs<IdentityChange.Amendment>(c)
        assertEquals(text, c.proposedText)
        val (subject, blocked) = identityApproval(c, allCaps)
        assertNull(blocked)
        assertEquals(ResolveSubject.AmendmentText(text), subject)
    }

    @Test
    fun eachKindParses() {
        val a = parseIdentityChange(ctx("""{"kind":"amendment","proposed_text":"x","m1_justification":null,"wa_edited":true,
            "objection":{"conscience":"coherence","objection_type":"justification","reason":"thin"}}"""))
        assertIs<IdentityChange.Amendment>(a)
        assertNull(a.m1Justification)
        assertTrue(a.waEdited)
        assertEquals(ConscienceObjection("coherence", "justification", "thin"), a.objection)

        val r = parseIdentityChange(ctx("""{"kind":"reapproval","proposed_text":"y","supersedes_node_id":"n1"}"""))
        assertIs<IdentityChange.Reapproval>(r)
        assertEquals("n1", r.supersedesNodeId)

        val b = parseIdentityChange(ctx("""{"kind":"base_link","template":{"template_name":"default","template_version":"2.23","manifest_digest":"$digest"}}"""))
        assertIs<IdentityChange.BaseLink>(b)
        assertEquals(IdentityTemplate("default", "2.23", digest), b.template)
        assertEquals(ResolveSubject.BaseLinkDigest(digest) to null, identityApproval(b, allCaps))
    }

    @Test
    fun anUnknownKindOrBrokenProposalIsShownButNeverApprovable() {
        for (raw in listOf("""{"kind":"merge","summary":"?"}""", "{not json", """{"kind":"amendment"}""", """{"kind":"base_link"}""")) {
            val c = parseIdentityChange(ctx(raw))
            assertIs<IdentityChange.Unreadable>(c, raw)
            assertEquals(null to ApproveBlocked.UNREADABLE, identityApproval(c, allCaps))
        }
    }

    @Test
    fun theFlatFormIsReadWhenIdentityChangeIsAbsent() {
        val c = parseIdentityChange(mapOf("proposed_amendment" to "I serve.", "m1_justification" to "why",
            "conscience_objection" to "too broad", "conscience_objection_type" to "substantive", "conscience" to "entropy"))
        assertIs<IdentityChange.Amendment>(c)
        assertEquals("I serve.", c.proposedText)
        assertEquals(ConscienceObjection("entropy", "substantive", "too broad"), c.objection)
        assertNull(parseIdentityChange(mapOf("unrelated" to "x")))
    }

    // ── when Approve is off ───────────────────────────────────────────

    @Test
    fun anAmendmentWaitsForTheBaseLink() {
        val c = IdentityChange.Amendment(null, "I serve.", "why", false, null)
        for (st in listOf(IdentityProfileStatus.UNANCHORED, IdentityProfileStatus.MISMATCH, IdentityProfileStatus.CONTESTED, IdentityProfileStatus.UNKNOWN)) {
            val m = allCaps.copy(profile = IdentityProfile(st, null))
            assertEquals(null to ApproveBlocked.BASE_LINK_FIRST, identityApproval(c, m), "status=$st")
        }
        // A base link itself is approvable while unanchored: it is what anchors.
        val b = IdentityChange.BaseLink(null, IdentityTemplate("t", "1", digest))
        assertEquals(ResolveSubject.BaseLinkDigest(digest) to null,
            identityApproval(b, allCaps.copy(profile = IdentityProfile(IdentityProfileStatus.UNANCHORED, null))))
    }

    @Test
    fun outerWhitespaceIsNeverTrimmedAndNeverApproved() {
        for (t in listOf(" I serve.", "I serve.\n", "\tI serve.", "", "   ")) {
            val c = IdentityChange.Amendment(null, t, null, false, null)
            assertEquals(null to ApproveBlocked.OUTER_WHITESPACE, identityApproval(c, allCaps), "text=[$t]")
        }
    }

    @Test
    fun anAgentThatCannotBindIsNotSentASubject() {
        val noExact = allCaps.copy(capabilities = IdentityCapabilities(true, false, true))
        assertEquals(null to ApproveBlocked.AGENT_CANNOT_BIND,
            identityApproval(IdentityChange.Amendment(null, "x", null, false, null), noExact))
        val noBase = allCaps.copy(capabilities = IdentityCapabilities(true, true, false))
        assertEquals(null to ApproveBlocked.AGENT_CANNOT_BIND,
            identityApproval(IdentityChange.BaseLink(null, IdentityTemplate("t", "1", digest)), noBase))
    }

    @Test
    fun aDigestThatIsNotSixtyFourHexIsNotSent() {
        for (d in listOf(null, "abc", "g".repeat(64), digest + "0")) {
            assertEquals(null to ApproveBlocked.NO_DIGEST,
                identityApproval(IdentityChange.BaseLink(null, IdentityTemplate("t", "1", d)), allCaps), "digest=$d")
        }
    }

    @Test
    fun theBannerOpensTheNamedBaseLinkElseTheFirstOne() {
        val base = deferral(ctx("""{"kind":"base_link","template":{"manifest_digest":"$digest"}}"""), id = "d2")
        val other = deferral(ctx("""{"kind":"amendment","proposed_text":"x"}"""), id = "d1")
        assertEquals("d2", baseLinkDeferral(listOf(other, base), allCaps)?.deferralId)
        val named = allCaps.copy(profile = IdentityProfile(IdentityProfileStatus.UNANCHORED, "d1"))
        assertEquals("d1", baseLinkDeferral(listOf(other, base), named)?.deferralId)
        assertNull(baseLinkDeferral(listOf(other, base), DeferralListMeta.NONE))
    }

    // ── the wire ──────────────────────────────────────────────────────

    @Test
    fun refusalsKeepTheirReasonId() {
        val agent = agentRefusal(400, """{"error":{"code":"BAD_REQUEST","message":"one subject","details":{"reason_id":"wa.resolve_one_subject","kind":"refused"}}}""")
        assertEquals("wa.resolve_one_subject", agent.reasonId)
        assertEquals(400, agent.statusCode)
        val node = agentRefusal(409, """{"error":"deferral.unknown_request","reason_id":"deferral.unknown_request"}""")
        assertEquals("deferral.unknown_request", node.reasonId)
        val wrapped = agentRefusal(409, """{"detail":{"error":{"message":"no row","details":{"reason_id":"wa.no_signed_request_row"}}}}""")
        assertEquals("wa.no_signed_request_row", wrapped.reasonId)
        assertNull(agentRefusal(502, "<html>bad gateway</html>").reasonId)
    }

    @Test
    fun theResolveAnswerCarriesItsReceipts() {
        val r = parseIdentityResolve("""{"data":{"success":true,"deferral_id":"d1","resolved_at":"2026-11-20T00:00:00Z",
            "request_attestation_id":"req-1","response_attestation_id":"res-1"}}""", "d1")
        assertTrue(r.success)
        assertEquals("req-1", r.requestAttestationId)
        assertEquals("res-1", r.responseAttestationId)
        val old = parseIdentityResolve("""{"data":{"success":true,"deferral_id":"d1"}}""", "d1")
        assertNull(old.requestAttestationId)
    }

    private fun deferral(context: Map<String, String>?, id: String = "d1") = ai.ciris.mobile.shared.api.DeferralData(
        deferralId = id, createdAt = "2026-11-20T00:00:00Z", deferredBy = "agent", taskId = "t", thoughtId = "th",
        reason = "identity", channelId = null, userId = null, priority = "normal", assignedWaId = null,
        requiresRole = null, status = "pending", resolution = null, resolvedAt = null, question = null,
        context = context, timeoutAt = null,
    )
}
