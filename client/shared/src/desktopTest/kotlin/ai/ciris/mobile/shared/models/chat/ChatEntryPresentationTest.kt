package ai.ciris.mobile.shared.models.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two axes, and every way collapsing them into one goes wrong (#37).
 *
 * The server's table maps each case the client must distinguish; these assert
 * that table row by row, so a rendering change that quietly merges two of them
 * fails here rather than in a transcript.
 */
class ChatEntryPresentationTest {

    private fun entry(
        kind: String = CegChatMessage.KIND_MESSAGE,
        authorKind: String = CegChatMessage.AUTHOR_KIND_PERSON,
        relation: String? = CegChatMessage.RELATION_OTHER,
        mine: Boolean = false,
        duties: List<String> = emptyList(),
        role: String? = null,
        body: String = "hello",
        messageId: String? = null,
    ) = CegChatMessage(
        attestationId = "att-1",
        kind = kind,
        authorKind = authorKind,
        relation = relation,
        mine = mine,
        authorDuties = duties,
        authorRole = role,
        body = body,
        messageId = messageId,
    )

    // ── the server's table, row by row ────────────────────────────────────

    @Test
    fun self_is_own_message() {
        assertEquals(
            Presentation.OwnMessage,
            presentationOf(entry(relation = CegChatMessage.RELATION_SELF, mine = true)),
        )
    }

    @Test
    fun another_person_is_their_own_bubble() {
        assertEquals(Presentation.OtherPersonMessage, presentationOf(entry()))
    }

    @Test
    fun my_agent_is_never_rendered_as_me() {
        // THE ONE THAT MATTERS MOST. An own-agent row shares an OWNER with the
        // viewer, and can carry mine=true for that reason. Rendering it as the
        // user's own message attributes a machine's words to a human.
        val e = entry(
            authorKind = CegChatMessage.AUTHOR_KIND_AGENT,
            relation = CegChatMessage.RELATION_OWN_AGENT,
            mine = true,
        )
        assertEquals(Presentation.OwnAgentMessage, presentationOf(e))
    }

    @Test
    fun someone_elses_agent_is_distinct_from_mine() {
        assertEquals(
            Presentation.OtherAgentMessage,
            presentationOf(entry(
                authorKind = CegChatMessage.AUTHOR_KIND_AGENT,
                relation = CegChatMessage.RELATION_OTHER,
            )),
        )
    }

    @Test
    fun system_is_a_note_in_the_transcript() {
        assertEquals(
            Presentation.SystemNote,
            presentationOf(entry(kind = CegChatMessage.KIND_SYSTEM, messageId = "chat.state.ready")),
        )
    }

    @Test
    fun error_is_a_note_that_reads_as_a_problem() {
        assertEquals(
            Presentation.ErrorNote,
            presentationOf(entry(kind = CegChatMessage.KIND_ERROR)),
        )
    }

    // ── kind is asked before author ───────────────────────────────────────

    @Test
    fun a_system_entry_has_no_author_and_is_still_a_note() {
        // `author` is empty on a system entry BY DEFINITION. Asking author-kind
        // first would send every system note down the unresolved-author branch
        // and render "End-to-end encrypted." as a stranger's message.
        val e = entry(
            kind = CegChatMessage.KIND_SYSTEM,
            authorKind = CegChatMessage.AUTHOR_KIND_UNKNOWN,
            relation = CegChatMessage.RELATION_NONE,
        )
        assertEquals(Presentation.SystemNote, presentationOf(e))
    }

    // ── unknown is a real state, not a fallback to person ─────────────────

    @Test
    fun an_unresolved_author_is_neutral_not_a_person() {
        val e = entry(authorKind = CegChatMessage.AUTHOR_KIND_UNKNOWN)
        assertEquals(Presentation.UnresolvedAuthorMessage, presentationOf(e))
        assertTrue(e.authorIsUnresolved)
    }

    @Test
    fun my_own_message_whose_author_is_still_resolving_is_still_mine() {
        // Showing the viewer's own words as an unresolved stranger is worse than
        // either answer, and the server has already told us it is ours.
        val e = entry(
            authorKind = CegChatMessage.AUTHOR_KIND_UNKNOWN,
            relation = CegChatMessage.RELATION_SELF,
            mine = true,
        )
        assertEquals(Presentation.OwnMessage, presentationOf(e))
    }

    // ── moderation is a duty, not a role ──────────────────────────────────

    @Test
    fun a_plain_member_holding_a_delegation_moderates() {
        // CIRISServer 6d6239b: a founder appoints a moderator with a scoped
        // delegates_to, so roster role and moderation authority are independent.
        val e = entry(role = "member", duties = listOf(CegChatMessage.DUTY_MODERATE))
        assertTrue(e.moderates, "a delegation makes a member a moderator")
    }

    @Test
    fun a_founder_with_no_live_delegation_chain_does_not_moderate() {
        // A LEGITIMATE state, not a bug: no live steward-bound chain reaches
        // them. Reading the badge off author_role would show it anyway.
        val e = entry(role = "founder", duties = emptyList())
        assertFalse(e.moderates)
    }

    @Test
    fun an_operator_defined_role_is_an_ordinary_member_not_an_error() {
        // The vocabulary is OPEN. Hardcoding a two-value enum is what the
        // correction on #37 explicitly warned against.
        val e = entry(role = "curator-in-residence")
        assertFalse(e.moderates)
        assertEquals(Presentation.OtherPersonMessage, presentationOf(e))
    }

    @Test
    fun an_unrecognised_duty_is_ignored_rather_than_failing() {
        val e = entry(duties = listOf("some_future_scope"))
        assertTrue(e.moderates, "unknown duties still indicate authority; they are not errors")
    }

    // ── text: never a blank line, never a raw key ─────────────────────────

    @Test
    fun a_system_note_prefers_its_localized_string() {
        val e = entry(kind = CegChatMessage.KIND_SYSTEM, messageId = "chat.state.ready",
                      body = "End-to-end encrypted.")
        assertEquals("Chiffré de bout en bout.",
                     chatEntryText(e) { "Chiffré de bout en bout." })
    }

    @Test
    fun an_untranslated_id_falls_back_to_the_body_not_a_blank_line() {
        val e = entry(kind = CegChatMessage.KIND_SYSTEM, messageId = "chat.state.ready",
                      body = "End-to-end encrypted.")
        assertEquals("End-to-end encrypted.", chatEntryText(e) { null })
    }

    @Test
    fun a_localizer_echoing_the_key_back_is_not_a_translation() {
        // Exactly how mobile.login_setup_complete_relogin reached a user (#34).
        val e = entry(kind = CegChatMessage.KIND_SYSTEM, messageId = "chat.state.ready",
                      body = "End-to-end encrypted.")
        assertEquals("End-to-end encrypted.", chatEntryText(e) { key -> key })
    }

    @Test
    fun an_unopened_row_explains_itself_rather_than_rendering_empty() {
        val e = CegChatMessage(attestationId = "a", body = "",
                               unopenedReason = "the room's key material is not here yet")
        assertEquals("the room's key material is not here yet", chatEntryText(e) { null })
    }

    // ── the transcript envelope ───────────────────────────────────────────

    @Test
    fun total_counts_what_people_said_not_what_is_displayed() {
        // An unstarted conversation is total:0 with one entry. Driving an unread
        // badge off messages.size would show traffic in a room where nobody has
        // spoken.
        val t = ChatTranscript(
            messages = listOf(entry(kind = CegChatMessage.KIND_SYSTEM,
                                    messageId = "chat.state.awaiting_peer")),
            total = 0,
        )
        assertEquals(1, t.messages.size)
        assertEquals(0, t.total)
    }

    @Test
    fun a_state_that_converges_on_its_own_must_not_offer_a_retry() {
        val t = ChatTranscript(ready = false, convergesOnItsOwn = true)
        assertFalse(t.ready)
        assertTrue(t.convergesOnItsOwn,
                   "a retry button here says the user can fix what only replication can")
    }

    @Test
    fun defaults_are_the_pre_37_shape_so_an_older_node_still_renders() {
        // A node that predates this schema sends none of these fields. It must
        // degrade to an ordinary message, not to a system note or a blank.
        val old = CegChatMessage(attestationId = "a", body = "hi", mine = false)
        assertEquals(CegChatMessage.KIND_MESSAGE, old.kind)
        assertFalse(old.isSystemEntry)
        assertFalse(old.moderates)
    }
}
