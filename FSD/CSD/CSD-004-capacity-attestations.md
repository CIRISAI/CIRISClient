# CSD-004 — Federation capacity attestations (Health & Reputation)

**CSD**: CSD-004
**Version**: 1.1 — retrofitted to CSD-B
**Profile**: `buildable` (`CSD.md` in this repo)
**Status**: Draft
**Author**: CIRIS Development Team
**Date**: 2026-09-08
**Flow**: tools/qa_runner/flows/capacity_attestations.yaml
**Client floor**: unreleased
**Origin**: CIRISClient#45
**Standard**: CIRISAgent `FSD/CSD_STANDARD.md` v1.0
**Namespace registry**: CIRISConstitution `manifests/namespace_registry.json` @ `rc5`
**Registry sha256**: `87aede5012064288fd5ce8770d3e77a8c5131cd61d27799c4c06558507b9a9f5`

> **THIS IS A PROFILE DEMONSTRATION, NOT A SECOND COPY.** The canonical CSD-004
> is `FSD/CSD/CSD-004-capacity-attestations.md` in CIRISAgent `release/2.11.0`,
> where `check_csd.py` and the flow file live. This version exists to show what
> §2a and §3a of `CSD.md` look like when filled in, on the CSD that needs them
> most. **It must be merged upstream and deleted here** — two documents for one
> surface is exactly the drift this repo was built to measure. §1, §4 and §5 are
> the upstream text; §2 is extended and §2a/§3a are new.

## 1. Mission (why)

**A user can now see live federation capacity attestations on Health & Reputation,
where before #45 a gate placeholder stood in for them.** The distinction is the
whole feature: a placeholder that looks like data is the metric-dishonesty MDD's
anti-Goodhart measures name. Serves **Incompleteness** — show what is measured,
and only what is measured.

## 2. Surface (what)

### Screens

| screen | reached from | leaves to |
|---|---|---|
| `HealthReputation` | the Health & Reputation nav entry | — |

### Tags

| tag | kind | guaranteed by |
|---|---|---|
| `card_federation_capacity_attestations` | card | CIRISClient#45 |
| `federation_capacity_live` | the **live** marker — absent on the old gate | CIRISClient#45 |
| `row_capacity_{factor}` | one row per factor, `{factor}` ∈ the five below | **PROPOSED** — not named by #45 |
| `value_capacity_composite` | the composite score | **PROPOSED** — not named by #45 |
| `text_capacity_attested_by` | who signed the attestation | **PROPOSED** — not named by #45 |
| `text_capacity_empty` | the empty-state sentence | **PROPOSED** — not named by #45 |
| `text_capacity_error` | the error-state sentence | **PROPOSED** — not named by #45 |

**PROPOSED is the reversed arrow, and it is not a guess.** The parent standard
forbids putting a tag in a *flow* that nobody has confirmed, and that rule is
untouched — none of the proposed tags appear in §4. They are commitments this
document is asking an implementation to satisfy, marked so the difference between
"#45 guarantees this" and "we are asking for this" survives review. A PROPOSED tag
becomes `guaranteed by CIRISClient#NNN` when a PR names it, and only then may it
enter the flow.

### Collects / shows

| field | source (§3) | required |
|---|---|---|
| capacity attestations | capacity read | shows |

### Writes

Nothing. See §3a: every family this surface touches is `display-only`, and the
registry makes that more than a promise — all six are `reserved` with a
**no-self-emit** rule, so this node could not originate them if it tried.

## 2a. Sample output

> Per `CSD.md` §2a. The parent standard's single row above says a card exists;
> this section says what is in it.

### 2a.1 Fields

The six families in §3a are the vocabulary. Five are factors; the sixth is their
composite, and the registry states the relationship outright — `capacity:composite`
is *"𝒞_CIRIS — the minimum over the five factors; anti-Goodhart unity-of-virtues"*.
**That is a UI requirement, not trivia:** the composite is a MIN, so a design that
renders it as an average, or that lets a high factor visually dominate, would
misstate the constitutional meaning of the number.

| field | type | example | renders as | tag |
|---|---|---|---|---|
| `capacity:core_identity` | `float` **range unconfirmed** | `0.82` | `Core identity — 0.82` | `row_capacity_core_identity` |
| `capacity:integrity` | `float` **range unconfirmed** | `0.91` | `Integrity — 0.91` | `row_capacity_integrity` |
| `capacity:resilience` | `float` **range unconfirmed** | `0.77` | `Resilience — 0.77` | `row_capacity_resilience` |
| `capacity:incompleteness_awareness` | `float` **range unconfirmed** | `0.68` | `Incompleteness awareness — 0.68` | `row_capacity_incompleteness_awareness` |
| `capacity:sustained_coherence` | `float` **range unconfirmed** | `0.74` | `Sustained coherence — 0.74` | `row_capacity_sustained_coherence` |
| `capacity:composite` | `float` **range unconfirmed** | `0.68` | `𝒞_CIRIS — 0.68 (lowest factor: incompleteness awareness)` | `value_capacity_composite` |
| `attesting_key_id` | `string` | `wa-lens-…` | `Attested by wa-lens-… ` | `text_capacity_attested_by` |

Note the example composite is `0.68` — **equal to the lowest factor, not their
mean (0.784)**. The examples are chosen so that a rendering which averages is
visibly wrong against this table. That is what a sample output is for.

**`range unconfirmed` is deliberate.** The registry gives each family a symbol
(`C`, `I_int`, `R`, `I_inc`, `S`) and a polarity of `signed`; it does not state a
numeric range, and CC 3.1.8.1 has not been read for this. Inventing `0..1` here
would be the #39 defect wearing a lab coat — a plausible number that no substrate
promised. The examples are shaped to be *illustrative of the min relationship*,
and the range is a question for CIRISLensCore, tracked in §5.

### 2a.2 States

| state | what is on screen | tag |
|---|---|---|
| **populated** | the six rows above, composite labelled with which factor it took | `federation_capacity_live` present |
| **empty** | *"No capacity attestations for this node yet."* — the node exists and nobody has attested it. Distinct from error, and **must not** render as zeros: a zero is a score, and this is the absence of one. | `text_capacity_empty` |
| **loading** | the card frame with a progress affordance; **not** the empty sentence | neither empty nor error tag |
| **error** | *"Could not read capacity attestations."* — named, never silent, never rendered as empty | `text_capacity_error` |

**The empty/error split is the mission restated.** CSD-003 says a safety surface
that silently fails to render is worse than one that is absent, because absence is
at least visible; a card that renders a failed read as "no attestations" tells the
user this node has never been attested, which is a different and more flattering
claim than the truth. `federation_capacity_live` distinguishes live from gated; it
does **not** distinguish empty from broken, and nothing in #45 currently does.

## 3. Contracts (who)

| value | endpoint / surface | owner |
|---|---|---|
| capacity attestations | **unconfirmed** — `CapacityAttestation` is a type in ciris-server 0.5.199 and the pruned gate `LENSCORE_CAPACITY` says the lens read API on `:4243` serves it; the path is not named in #45 | CIRISServer / CIRISLensCore |

## 3a. Namespaces

> Per `CSD.md` §3a. Validated against the pinned RC5 registry.

| prefix | CC | use | why |
|---|---|---|---|
| `capacity:core_identity` | 3.1.8.1 | `display-only` | rendered as a factor row |
| `capacity:integrity` | 3.1.8.1 | `display-only` | rendered as a factor row |
| `capacity:resilience` | 3.1.8.1 | `display-only` | rendered as a factor row |
| `capacity:incompleteness_awareness` | 3.1.8.1 | `display-only` | rendered as a factor row |
| `capacity:sustained_coherence` | 3.1.8.1 | `display-only` | rendered as a factor row |
| `capacity:composite` | 3.1.8.1 | `display-only` | rendered as 𝒞_CIRIS, the min of the five |

**Registry facts that bind this surface** (all six families, from the pinned
registry — not restated by hand, looked up):

* `reserved: true`, `owning_component: lens`, `owning_repo: CIRISLensCore`. The
  §3 owner column is therefore a lookup, not an assertion.
* `reserved_rule: no-self-emit (attesting_key_id != attested_key_id)`, **CC 3.4.5**.
  A node cannot attest its own capacity. This is why `attesting_key_id` is in
  §2a.1 as a required field rather than a nicety: a capacity score with no visible
  attester invites the reading that the node scored itself, which the Constitution
  forbids.
* `polarity: signed`. The values arrive signed; a UI that renders them without any
  provenance affordance is dropping the property that makes them worth anything.

**`use` is `display-only` for all six, and that is checkable.** Every family here
is reserved to `lens`; a CSD claiming `emit` on any of them from the client would
fail `CSD.md` §4's checker rather than merely attract a review comment.

**Not this surface's:** `capacity_assurance:{level}:{domain}:{band}:{version}`
(CC 3.1.2, `attestation`/CIRISVerify) is the adult decision-capacity ladder and is
a different feature. It is listed here only because its prefix sorts adjacent and
the two have been conflated in conversation before.

## 4. Flow (how)

<!-- flow: tools/qa_runner/flows/capacity_attestations.yaml -->
```yaml
flow: capacity_attestations
title: Federation capacity attestations on Health & Reputation
description: >-
  CIRISClient#45 replaced FederationAttestationsGate with a live section. The
  point of the change is that the data is real, so the assertion is on
  `federation_capacity_live` and not merely on the card: a gate placeholder
  would satisfy the card tag alone.
# CIRISClient#45 is unmerged (2026-09-08): NO release carries this surface, so the
# flow is refused as "cannot start" rather than driven into "element not found".
# Replace with ">=<release>" when #45 ships. A numeric floor was tried first and
# stopped refusing on the next client cut (0.5.213), turning these into binding
# verdicts against a client that still lacks the screens.
client: "unreleased"

steps:
  - step_id: attestations_section
    title: The capacity attestations section is live, not gated
    requires:
      screen: HealthReputation
    expect:
      visible: [card_federation_capacity_attestations, federation_capacity_live]
```

**Unchanged, on purpose.** Every tag §2a adds is PROPOSED, and the parent
standard's rule is that an unconfirmed tag does not enter a flow. So the flow
still asserts only what #45 guarantees, and everything §2a promises is disclaimed
in §5 — which is `CSD.md` §2a.3 working as intended rather than being skipped.

## 5. QA plan

### Platforms

All five.

### How to run

```
python -m tools.qa_runner.modules.web_ui flow --spec tools/qa_runner/flows/capacity_attestations.yaml --platform <desktop|android|ios>
```

### Results — in the UI and in the log

Per step: a console line (`[OK]` with the held condition and duration, or `[FAIL]`
naming the phase — `requires` / `do` / `expect` — the predicate, and the tags on
screen and drivable now); a screenshot at `artifacts/shots/flow-capacity_attestations-<step_id>.png`
rendered in the five-platform gallery; a row in `artifacts/flows/capacity_attestations.json`.

### Acceptance

**Functional**
1. A person on Health & Reputation sees capacity attestations that are live data,
   not a gate.
2. The composite is legibly the **minimum** of the five factors, not a blend.
3. "Nothing attested yet" and "we could not ask" are visibly different.

**Tests** (the flow)
* `attestations_section` — the card **and** `federation_capacity_live` are on screen.
  The second tag is the assertion; the card alone would be satisfied by the gate
  this feature replaced.

**Untested and must be established**
* That the numbers are right. `federation_capacity_live` proves the section is the
  live one; it does not compare what it shows against the read. That needs the path
  in §3 named, then a `text:` predicate against a fixture.
* **The three states of §2a.2 are all unasserted.** No step covers empty, loading
  or error, because no tag for them is confirmed. This is the disclaimer
  `CSD.md` §2a.3 requires, and it is the largest single gap in this CSD: the
  error state is the one the mission cares about most and the one nothing checks.
* **The numeric range of the five factors is unknown** (§2a.1). Until
  CIRISLensCore states it, the examples illustrate the min relationship and
  nothing else, and no assertion may depend on a bound.
* **The min relationship itself is unasserted.** The DSL's strongest value
  predicate is substring containment, so `composite == min(factors)` is not
  expressible today. It needs the `matches:`/`each:` extension named in
  `CSD.md` §6.2, proposed to the parent standard.
* Whether an attester is displayed at all. `no-self-emit` (CC 3.4.5) makes the
  attesting key constitutionally load-bearing, and #45 names no tag for it.
