package ai.ciris.mobile.shared.ceg

import androidx.compose.runtime.Immutable

/**
 * THE NAMESPACE IS THE DESIGN SYSTEM.
 *
 * Types for the generated [Dim] table (`Dimensions.kt`, from
 * `client/ceg/namespace_registry.json` via `client/tools/gen_dimension_table.py`).
 * A screen names `Dim.consentKind`; it never constructs a [Dimension] — that is
 * the whole gate. A family with no registry row cannot be named, so it cannot
 * be rendered (CC 3.1.7 R2).
 */

/** The registry's nine polarity strings, normalised. Polarity is a render instruction. */
enum class Polarity {
    SIGNED,
    BOOLEAN_VIA_SCORE,
    POSITIVE_ONLY,
    ENUMERATED,
    /** `-1 only` — a mark, never a score. */
    MINUS_ONE_ONLY,
    /** `-1 / -0.5 only` — a mark, never a score. */
    MINUS_ONE_OR_HALF,
    /** `per-leaf (CC 3.3.1)` — the consent family; each leaf has its own. */
    PER_LEAF,
    /** `see CC 3.4.1` — the accord family; the section is the rule. */
    BY_RULE;

    val minusOnly: Boolean get() = this == MINUS_ONE_ONLY || this == MINUS_ONE_OR_HALF
}

/** The eleven renderer classes (design execution plan, "116 families → 11 renderers"). */
enum class Renderer {
    /** A value with polarity and its attester named. Never without the attester. */
    SIGNED_SCORE,
    /** Verified, not verified, or indeterminate — three states, because indeterminate is a real answer. */
    BOOLEAN_VIA_SCORE,
    /** A count that can only go up. No transfer control, ever. */
    POSITIVE_ONLY_ACCRUAL,
    /** Minus-one only. Can never render as a positive or a neutral. */
    VIOLATION_MARKER,
    /** A closed set, drawn from the registry vocabulary. */
    ENUMERATED_CHIP,
    /** A lifecycle position with the next transition named. */
    STATE_PILL,
    /** A file card: claim plus SHA plus holders, with a real gone state. */
    CONTENT_REFERENCE,
    /** A link row saying what it points at and why. */
    RELATION_EDGE,
    /** A self-report, labelled as one. */
    CONFIG_RECORD,
    /** The transmission principle: a closed leaf set, never a free-text box. */
    CONSENT_LEAF,
    /** A record that value moved elsewhere. Undoing it does not undo the payment. */
    SETTLEMENT_RECEIPT,
}

/** The registry's placeholder classes per segment (CC 3.4.7 case rule). */
enum class SegmentClass { LITERAL, VOCAB, VALUE, WILDCARD, EXTERNAL, HEX }

@Immutable
data class Segment(val segment: String, val cls: SegmentClass)

@Immutable
data class Dimension(
    /** Exactly as the registry spells it, placeholders included: `consent:{kind}`. */
    val prefix: String,
    /** `consent_kind` — the localization key stem. */
    val slug: String,
    val ccSection: String,
    val polarity: Polarity,
    val polarityRaw: String,
    val indeterminateAllowed: Boolean,
    val renderer: Renderer,
    val owningComponent: String,
    val reserved: Boolean,
    val reservedRule: String?,
    val segments: List<Segment>,
    /** `ceg.<slug>.label`, or null when nobody has glossed the family yet. */
    val labelKey: String?,
    val glossKey: String?,
    /** The registry's own description — the fallback when there is no gloss. */
    val description: String,
) {
    /** `consent:{kind}` bound with `kind=replication` → `consent:replication`. */
    fun bind(binds: Map<String, String>): String = segments.joinToString(":") { seg ->
        when (seg.cls) {
            SegmentClass.LITERAL -> seg.segment
            SegmentClass.WILDCARD -> binds[seg.segment] ?: "*"
            else -> binds[seg.segment.trim('{', '}')] ?: seg.segment
        }
    }
}

/** A CC 2.1 envelope member the receipt shows. Not a registry row; always glossed. */
@Immutable
data class EnvelopeMember(
    val id: String,
    /** The protocol field name, shown in mono under the plain label. */
    val wire: String,
    val ccSection: String,
    val labelKey: String,
    val glossKey: String,
)
