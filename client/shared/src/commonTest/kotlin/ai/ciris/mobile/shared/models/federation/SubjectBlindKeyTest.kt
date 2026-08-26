package ai.ciris.mobile.shared.models.federation

import ai.ciris.mobile.shared.api.SystemWarning
import ai.ciris.mobile.shared.api.keyIdFromActionUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The identity card's scope (CIRISServer#490 / CIRISClient#3).
 *
 * The card claims *"your identity is unusable"*. Every test here is about that
 * claim being true when it is shown: a node holds federation keys for strangers,
 * and a malformed row belonging to someone else must never appear on this
 * operator's card.
 *
 * The live instance these are written against:
 * `eric-moore-v2-portable-f34de31d8c21-6e2b4kpvxk`, minted 2026-07-02, its
 * registration envelope 59 bytes long and naming only `key_id`.
 */
class SubjectBlindKeyTest {

    private val mine = "eric-moore-v2-portable-f34de31d8c21-6e2b4kpvxk"
    private val myLaptop = "eric-moore-v2-laptop-aa11bb22cc33-9x8y7z6w5v"
    private val stranger = "someone-else-v1-portable-000000000000-zzzzzzzzzz"
    private val roster = setOf(mine, myLaptop)

    private fun warn(
        code: String = WARNING_KEY_SUBJECT_BLIND,
        subject: String? = mine,
        actionUrl: String? = "/v1/federation/adopt-scrubbed",
        message: String = "key $mine has a subject-blind registration envelope",
        // `warning`, not `error`, and that is the node's contract rather than a
        // fixture convenience: `error` sets degraded_mode, and this condition is
        // permanent until repaired, so it would pin the node degraded forever
        // over something that degrades no service (CIRISServer#490).
        severity: String = "warning",
    ) = SystemWarning(
        code = code,
        message = message,
        severity = severity,
        actionUrl = actionUrl,
        subjectKeyId = subject,
    )

    @Test
    fun a_subject_blind_row_on_my_roster_is_surfaced_with_its_repair_route() {
        val found = subjectBlindKeyFor(listOf(warn()), roster)
        assertEquals(mine, found?.keyId)
        assertEquals("/v1/federation/adopt-scrubbed", found?.actionUrl)
    }

    @Test
    fun an_occurrence_of_mine_counts_as_mine() {
        // The roster IS the binding. A laptop occurrence is this operator's
        // identity as much as the portable ID that minted it.
        assertEquals(myLaptop, subjectBlindKeyFor(listOf(warn(subject = myLaptop)), roster)?.keyId)
    }

    @Test
    fun a_strangers_broken_row_is_not_my_problem() {
        // THE CASE THE SCOPE EXISTS FOR. The node holds keys for peers; showing
        // this operator a card about someone else's damaged identity would be a
        // false claim on the screen whose whole subject is their own identity.
        assertNull(subjectBlindKeyFor(listOf(warn(subject = stranger)), roster))
    }

    @Test
    fun a_warning_that_names_no_subject_renders_nothing() {
        // Fail closed. With no subject there is no way to know whose identity is
        // broken, and "your identity is unusable" about an unknown row is worse
        // than no card at all. The node must name it in a STRUCTURED field.
        assertNull(subjectBlindKeyFor(listOf(warn(subject = null)), roster))
        assertNull(subjectBlindKeyFor(listOf(warn(subject = "   ")), roster))
    }

    @Test
    fun the_subject_is_never_read_out_of_the_message_prose() {
        // `Warning.message` arrives already composed and is documented as never
        // localized. Parsing a key_id out of an English sentence would break the
        // first time the sentence improved — and this message names the key.
        val prose = warn(subject = null, message = "key $mine is subject-blind and must be re-scrubbed")
        assertNull(
            subjectBlindKeyFor(listOf(prose), roster),
            "the key_id is right there in the message, and reading it from there is the bug",
        )
    }

    @Test
    fun lineage_is_never_inferred_from_the_key_id_string() {
        // `-portable-` is a naming convention, not a binding. A stranger's key
        // named that way must not be adopted onto this card just because the
        // string looks familiar — that is the person/node axis mistake in a new
        // place, and it is the one this issue named explicitly.
        val lookalike = "eric-moore-v2-portable-ffffffffffff-notmine00x"
        assertNull(subjectBlindKeyFor(listOf(warn(subject = lookalike)), roster))
    }

    @Test
    fun another_code_is_another_surface() {
        // Keyed on the constant. A different degradation is a different card's
        // business, however similar its prose.
        assertNull(subjectBlindKeyFor(listOf(warn(code = "federation.key_self_signed")), roster))
        assertNull(subjectBlindKeyFor(listOf(warn(code = "")), roster))
    }

    // ── severity is not what selects this card ────────────────────────────────

    @Test
    fun the_card_is_selected_by_code_at_every_severity() {
        // The node sends `warning` and this must still surface — the card is red
        // because of what the code MEANS, not because of how the node scored its
        // effect on service. A card waiting for `error` would be waiting for a
        // bug: raising it as error sets degraded_mode, and this condition never
        // clears on its own.
        listOf("warning", "error", "critical", "info", "").forEach { sev ->
            assertEquals(
                mine,
                subjectBlindKeyFor(listOf(warn(severity = sev)), roster)?.keyId,
                "severity=$sev must not change whether the operator is told",
            )
        }
    }

    @Test
    fun a_high_severity_warning_of_another_code_still_belongs_elsewhere() {
        // The mirror: severity cannot promote an unrelated code onto this card.
        assertNull(subjectBlindKeyFor(listOf(warn(code = "memory.pressure", severity = "critical")), roster))
    }

    @Test
    fun an_empty_roster_can_own_nothing() {
        // A failed roster load means an EMPTY scope, and an empty scope resolves
        // to "no card" — never to "cannot tell, show it anyway".
        assertNull(subjectBlindKeyFor(listOf(warn()), emptySet()))
    }

    @Test
    fun the_first_owned_match_wins_and_unowned_ones_do_not_shadow_it() {
        val warnings = listOf(
            warn(code = "memory.pressure", subject = null),
            warn(subject = stranger),
            warn(subject = mine),
        )
        assertEquals(mine, subjectBlindKeyFor(warnings, roster)?.keyId)
    }

    @Test
    fun a_healthy_node_shows_no_card() {
        assertNull(subjectBlindKeyFor(emptyList(), roster))
    }

    // ── the action_url fallback: a URL parameter is a structure, not a sentence ──

    @Test
    fun the_repair_route_may_carry_the_subject_as_a_query_parameter() {
        assertEquals(mine, keyIdFromActionUrl("/v1/federation/adopt-scrubbed?key_id=$mine"))
        assertEquals(mine, keyIdFromActionUrl("https://node/x?a=1&subject_key_id=$mine&b=2"))
    }

    @Test
    fun a_route_with_no_usable_parameter_yields_nothing() {
        assertNull(keyIdFromActionUrl(null))
        assertNull(keyIdFromActionUrl("/v1/federation/adopt-scrubbed"))
        assertNull(keyIdFromActionUrl("/x?other=1"))
        assertNull(keyIdFromActionUrl("/x?key_id="))
        // Not a query parameter — a path segment that merely contains the name.
        assertNull(keyIdFromActionUrl("/v1/keys/key_id/$mine"))
    }
}
