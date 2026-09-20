package ai.ciris.mobile.shared.ceg

import ai.ciris.mobile.shared.localization.LocalizationManager
import ai.ciris.mobile.shared.ui.nav.CohortScope
import ai.ciris.mobile.shared.ui.theme.Tone
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * FORMATTERS — from a dimension and a value to what a person reads.
 *
 * Pure Kotlin, no Color: a formatted value names a [Tone] and the theme
 * resolves it. Polarity is a visual rule enforced HERE, not in each card:
 * a violation marker refuses a non-negative score and a positive-only accrual
 * refuses a negative one, and the refusal renders as an error, never as a
 * flattering number.
 */
sealed interface Formatted {
    data class Text(val text: String, val mono: Boolean = false, val tone: Tone = Tone.INK) : Formatted
    /** The value contradicts the family's polarity. Rendered as an error, never coerced. */
    data class Refused(val reason: String) : Formatted
}

/** `abcd1234…wxyz` — the mono short form of a key id; the full id is always a tap away. */
fun shortKey(keyId: String, head: Int = 8, tail: Int = 4): String =
    if (keyId.length <= head + tail + 1) keyId else keyId.take(head) + "…" + keyId.takeLast(tail)

/** The 7→5 fold documented on [CohortScope]: a wire `cohort_scope` to the circle that shows it. */
fun cohortScopeOf(wire: String): CohortScope? = when (wire.trim().lowercase()) {
    "self" -> CohortScope.AGENT
    "family" -> CohortScope.FAMILY
    "community" -> CohortScope.LOCAL_COMMUNITY
    "affiliations" -> CohortScope.GLOBAL_COMMUNITIES
    "species", "planet", "federation" -> CohortScope.GLOBAL_COMMONS
    else -> null
}

/** `2026-03-14` — the plain date a receipt or a row shows. */
fun formatDate(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val d = instant.toLocalDateTime(zone).date
    return d.toString()
}

/** The plain label for a dimension: its gloss key when glossed, else the registry description, else the prefix. */
fun Dimension.plainLabel(l: LocalizationManager?, binds: Map<String, String> = emptyMap()): String {
    val fromKey = labelKey?.let { l?.getString(it) }
    if (fromKey != null && fromKey != labelKey) return fromKey
    if (description.isNotBlank()) return description
    return bind(binds)
}

/** What the renderer says about a wire score. */
fun Renderer.format(score: Double?, l: LocalizationManager?, segmentTail: String? = null): Formatted {
    fun t(key: String, fallback: String): String = l?.getString(key)?.takeIf { it != key } ?: fallback
    return when (this) {
        Renderer.VIOLATION_MARKER -> when {
            score == null -> Formatted.Refused("no score")
            score >= 0.0 -> Formatted.Refused("a violation marker cannot be non-negative ($score)")
            score <= -1.0 -> Formatted.Text(t("ceg.renderer.violation", "Crossed a line"), tone = Tone.DANGER)
            else -> Formatted.Text(t("ceg.renderer.partial_violation", "Partly crossed a line"), tone = Tone.DANGER)
        }
        Renderer.POSITIVE_ONLY_ACCRUAL -> when {
            score == null -> Formatted.Refused("no count")
            score < 0.0 -> Formatted.Refused("a positive-only accrual cannot be negative ($score)")
            else -> Formatted.Text(trimNumber(score), mono = true, tone = Tone.INK)
        }
        Renderer.BOOLEAN_VIA_SCORE -> when {
            score == null -> Formatted.Text(t("ceg.renderer.indeterminate", "Indeterminate"), tone = Tone.DIM)
            score > 0.0 -> Formatted.Text(t("ceg.renderer.verified", "Verified"), tone = Tone.OK)
            score < 0.0 -> Formatted.Text(t("ceg.renderer.not_verified", "Not verified"), tone = Tone.DANGER)
            else -> Formatted.Text(t("ceg.renderer.indeterminate", "Indeterminate"), tone = Tone.DIM)
        }
        Renderer.SIGNED_SCORE -> when {
            score == null -> Formatted.Refused("no score")
            score > 0.0 -> Formatted.Text("+" + trimNumber(score), mono = true, tone = Tone.OK)
            score < 0.0 -> Formatted.Text(trimNumber(score), mono = true, tone = Tone.DANGER)
            else -> Formatted.Text("0", mono = true, tone = Tone.DIM)
        }
        Renderer.CONSENT_LEAF -> when (segmentTail?.lowercase()) {
            "granted" -> Formatted.Text(t("ceg.renderer.granted", "Granted"), mono = true, tone = Tone.OK)
            "revoked" -> Formatted.Text(t("ceg.renderer.revoked", "Revoked"), mono = true, tone = Tone.DANGER)
            "expired" -> Formatted.Text(t("ceg.renderer.expired", "Expired"), mono = true, tone = Tone.DIM)
            null -> Formatted.Refused("a consent leaf needs its leaf")
            else -> Formatted.Text(segmentTail, mono = true)
        }
        Renderer.ENUMERATED_CHIP, Renderer.STATE_PILL ->
            if (segmentTail.isNullOrBlank()) Formatted.Refused("an enumerated value needs its segment")
            else Formatted.Text(segmentTail, mono = true)
        Renderer.CONFIG_RECORD -> Formatted.Text(
            listOfNotNull(segmentTail, t("ceg.renderer.self_report", "Self-report")).joinToString(" · "),
            mono = true, tone = Tone.DIM,
        )
        Renderer.CONTENT_REFERENCE, Renderer.RELATION_EDGE, Renderer.SETTLEMENT_RECEIPT ->
            Formatted.Text(segmentTail ?: score?.let(::trimNumber) ?: "", mono = true)
    }
}

private fun trimNumber(v: Double): String {
    val s = v.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
