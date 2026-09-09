# CSD/3 — the lifecycle form

**Version**: 3.0 (draft)
**Supersedes**: CSD-B "buildable profile" (this file, v2), which added sample
outputs and namespaces as two tables and left the DSL unable to check either.
**Profiles**: `FSD/CSD_STANDARD.md` v1.0 (CIRISAgent) — §1–§6, the flow
byte-identity rule and `check_csd.py` are inherited and unchanged unless named.
**Registry**: CIRISConstitution `manifests/namespace_registry.json`, pinned per
document by `source_sha256`.

## 0. What changed, and why a third version

v1 documents a surface someone already built. v2 (CSD-B) reversed the arrow so a
surface could be designed first, and added §2a sample outputs and §3a namespaces.
Both v2 sections were **richer than the DSL could check** — the strongest value
predicate was `text: {tag: substring}`, so a CSD could promise "six capacities,
each a factor, and a composite equal to their minimum" and assert only that a
card was on screen.

A specification whose claims cannot be checked is a document, not a contract.

v3 does three things:

1. **The DSL expresses what the UI shows** — counts, numbers, ranges, sets,
   relations between values, and the empty/loading/error states — so §2a stops
   outrunning §4.
2. **The CEG prefix IS the field identifier.** v2's two tables become one. A
   field is named by its constitutional family, so the registry validates the
   vocabulary and the UI contract in a single pass, and a surface cannot render
   a value without saying which family it belongs to.
3. **The document is machine-readable and STAGED.** A CSD is written before the
   feature exists and accumulates implementation data as it is built, by
   different hands. The checker validates *for the declared stage*, so a missing
   route at `sketched` is expected and the same absence at `building` is a
   defect.

## 1. The lifecycle

```yaml csd:stage
stage: sketched          # envisioned|sketched|building|testable|verified|shipped
owner: CIRISClient       # who holds the pen at this stage
```

| stage | pen | lands in this stage | checker requires |
|---|---|---|---|
| `envisioned` | steward | §1 mission, §2 `ceg:` families touched | §1 falsifiable; every `ceg:` resolves in the registry |
| `sketched` | client | §2 screens/tags/`shows:`/`states:`, §4 flow | tags may be `proposed:`; flow must parse; floor `unreleased` |
| `building` | substrate | §3 contracts — routes, owners, payload shapes | no `unconfirmed` left in §3; `shows:` types match the route |
| `testable` | client | floor flips off `unreleased`; flow runs on the matrix | every `shows:` row asserted in §4 or disclaimed in §5 |
| `verified` | steward | §5 acceptance signed; the untested list accepted | flow green on its declared platforms; gallery has a shot per step |
| `shipped` | — | the release that carries it | floor names a published version |

**Absence is stage-relative, and that is the whole point.** A `sketched` CSD with
`unconfirmed` contracts is correct. The same document at `building` is a
document waiting on someone who has stopped. The checker tells them apart, so
"not yet" and "stalled" stop looking identical — which is the failure this repo
keeps finding under other names.

**A stage never advances itself.** The pen-holder edits `stage:` deliberately,
and the checker refuses an advance whose requirements are unmet, naming the
missing row. Nothing infers readiness from a green run.

## 2. Surface — `surface:` and `shows:`

### 2.0 Name the surface, not the route

```yaml csd:surface
surface: health-reputation      # the NavSurface id, verbatim from EpistemicNav.kt
screen: HealthReputation        # the Screen the router lands on
```

**The hop is derived and must not be written down.** `EpistemicSidebar.kt`
computes a surface's tag as `nav_epistemic_${id.replace('-','_')}` and a group's
as `nav_group_${group.id}`; `EpistemicNav.kt` holds group membership and the
parent of every child surface. So `testing/gate/nav_map.py` answers "where does
this screen live" from the client itself — `constitutional` resolves to
`nav_group_commons-layers -> nav_epistemic_layer_global_commons ->
nav_epistemic_constitutional` without anyone typing that chain.

v1's §2 had a "reached from / leaves to" table. It was a second source for a
question the client already answers, and it drifts the first time a surface
changes group — silently, because a wrong hop looks exactly like a screen that
failed to compose. A CSD names the surface; the runner does the walking, which
is what FSD/CSD_STANDARD.md §5 already assigns it.

The checker resolves `surface:` through the same map, so a CSD naming a surface
the sidebar cannot reach fails at load rather than at 2am against a timeout.

## 2.1 Fields — `shows:` (the unified field spec)

> Replaces v2 §2a **and** §3a. One row per rendered value; the CEG family is the
> identifier, so the constitutional vocabulary and the UI contract are one table.

```yaml csd:shows
registry_sha256: 87aede5012064288fd5ce8770d3e77a8c5131cd61d27799c4c06558507b9a9f5
fields:
  - ceg: capacity:composite
    use: display-only            # read | display-only | emit
    type: float
    example: 0.68
    renders: "𝒞_CIRIS — 0.68 (lowest factor: incompleteness awareness)"
    tag: value_capacity_composite
    assert:
      relation: {op: min_of, of: [capacity:core_identity, capacity:integrity,
                                  capacity:resilience,
                                  capacity:incompleteness_awareness,
                                  capacity:sustained_coherence]}
  - ceg: capacity:core_identity
    use: display-only
    type: float
    example: 0.82
    renders: "Core identity — 0.82"
    tag: row_capacity_core_identity
```

### 2.1.1 Required keys

| key | meaning | checked against |
|---|---|---|
| `ceg` | the family, exactly as the registry spells it | the pinned registry, or the `x_private:` prefix it reserves |
| `use` | `read` · `display-only` · `emit` | `reserved` + `reserved_rule`: an `emit` on a reserved family this component does not own is a **load error** |
| `type` | `string` · `int` · `float` · `bool` · `enum[…]` · `list[…]` · `timestamp` · `unconfirmed` | §3's route, once the stage is `building` |
| `example` | a literal value as it would arrive | shape only; a redacted real value beats an invented one |
| `renders` | what the user sees for that example | prose; the sentence a designer would write |
| `tag` | the §2 tag carrying it, `proposed:` prefixed until a PR names it | must appear in a §4 step by `testable` |
| `assert` | the predicate §4 must enforce (below) | the DSL |

### 2.1.2 Parameterised families

The registry declares placeholder classes per segment (`vocab`, `value`,
`external`, `hex`, `literal`). A CSD binding a parameterised family states the
instantiation and the checker validates each segment against its class and the
`vocab_pattern` `^[a-z0-9][a-z0-9_.-]*$`:

```yaml
  - ceg: capacity_assurance:witness:{domain}:{band}:v1
    bind: {domain: medical, band: full}     # each checked against its class
```

Case is **byte-exact** and consumers must not case-fold (`compare:` in the
registry). A malformed segment is refused with the registry's own token,
`namespace_dimension_case_malformed`, rather than a bespoke message.

### 2.2 States — all four, always

```yaml csd:states
populated: {tag: federation_capacity_live}
empty:     {tag: text_capacity_empty,  renders: "No capacity attestations for this node yet."}
loading:   {renders: "card frame with a progress affordance; NOT the empty sentence"}
error:     {tag: text_capacity_error,  renders: "Could not read capacity attestations."}
```

`error` is **mandatory and must be distinguishable from `empty`**. A surface that
renders a failed read as an empty one tells the user a different and more
flattering thing than the truth, and CSD-003 states the principle: a safety
surface that silently fails to render is worse than one that is absent, because
absence is at least visible.

## 3. The DSL, v2 — expressing what the UI shows

> Extends the parent's §3. `requires`/`do`/`expect`, `visible`/`absent`/`text`
> and the action verbs are unchanged. These are additions to `expect:`.

| predicate | form | says |
|---|---|---|
| `count` | `count: {of: <tag-glob>, eq\|min\|max: N}` | how many elements match |
| `number` | `number: {tag: {min: , max: , eq: }}` | the element's text parses as a number in range |
| `matches` | `matches: {tag: <regex>}` | anchored regex over the element's text |
| `one_of` | `one_of: {tag: [a, b, c]}` | membership in a closed set |
| `each` | `each: {of: <tag-glob>, <predicate>}` | the predicate holds for every match |
| `relation` | `relation: {left: <tag>, op: eq\|ne\|lt\|lte\|gt\|gte\|min_of\|max_of\|sum_of, right\|of: …}` | a value's relationship to other values |
| `state` | `state: populated\|empty\|loading\|error` | the surface is in that §2.3 state |

**Rules, each a load error:**

* **A predicate names tags that §2 declares.** A glob that matches nothing fails
  the step rather than passing vacuously — the empty-set trap, which is how a
  gate reports "everything tagged is drivable" about a screen with no elements.
* **`relation` operands are `ceg:` field ids, not tags.** The relationship is
  between *values*, and the constitutional meaning travels with the family; the
  tag is only where it is drawn. `capacity:composite` being the minimum of five
  factors is a fact about the vocabulary, and a UI that renders it as an average
  misstates the Constitution rather than merely looking wrong.
* **`number` refuses a non-numeric text** rather than reading 0. Reading a
  missing value as a zero is a score where there is none.
* **No predicate may be satisfied by absence.** `count: {eq: 0}` is legal only
  against an explicit `state: empty`.

### 3.1 What this buys, on the CSD that needed it

CSD-004 could previously assert that a card was on screen. It can now assert what
the card *means*:

```yaml
expect:
  state: populated
  count: {of: "row_capacity_*", eq: 5}
  each:  {of: "row_capacity_*", number: {min: 0.0, max: 1.0}}
  relation: {left: capacity:composite, op: min_of,
             of: [capacity:core_identity, capacity:integrity, capacity:resilience,
                  capacity:incompleteness_awareness, capacity:sustained_coherence]}
  visible: [text_capacity_attested_by]
```

The last line is constitutional rather than cosmetic: `capacity:*` carries
`no-self-emit (attesting_key_id != attested_key_id)` at CC 3.4.5, so a score with
no visible attester invites exactly the reading the Constitution forbids.

**The range in that example is `unconfirmed` today.** The registry gives each
family a symbol and a `signed` polarity, not bounds. A CSD at `sketched` writes
`type: float, range: unconfirmed`; the `building` stage is where CIRISLensCore
supplies it, and the checker refuses to advance until it does. That is the
mechanism working, not a gap in the example.

## 4. Machine readability

Every normative section is a fenced ```yaml csd:<section>``` block, so one file
serves both readers and the checker never parses prose. This is the parent's §4
flow-embedding idea generalised — and it is not decoration: **four separate
checks written in this repo in one day passed against their own explanatory
comments**, because a check over self-documenting text that reads the whole file
finds the paragraph describing the defect. Typed blocks make "what executes" and
"what explains" mechanically distinct.

`check_csd.py` gains, alongside the flow diff it already does:

* every block parses and validates against the schema for its section;
* `stage:` requirements are met, or the advance is refused naming the missing row;
* every `ceg:` resolves in the registry pinned by `registry_sha256`, or is
  `x_private:`; parameterised binds validate per segment class;
* no `use: emit` on a `reserved` family whose `reserved_rule` excludes this
  component;
* every `shows:` row is asserted in §4 or disclaimed in §5 — at `testable`;
* every DSL predicate names declared tags and cannot be satisfied by an empty set.

## 5. What v3 does not do, stated plainly

* **The runner must learn the new predicates.** `flow_spec.py` today implements
  `screen`/`visible`/`absent`/`text`. §3 is a specification of work, not work
  done, and until it lands a v3 CSD can express more than any harness enforces —
  which is the exact failure v3 exists to end, temporarily reintroduced at a
  different layer. It should be implemented before v3 is declared Active.
* **`relation` needs values, not pixels.** Comparing `capacity:composite` to five
  factors means parsing five rendered strings back into numbers. That is
  tolerable for a scalar row and will not extend to structured payloads; a
  future version may need the client to expose a typed read.
* **Nothing here checks that a screenshot exists.** §5 of every CSD says shots
  are emitted per step and rendered in the gallery; nothing asserts it. Worth
  pinning, on the evidence that a screenshot step was recently the only
  non-vacuous assertion in an entire gate.
* **This is still ours, not the standard.** v3 belongs upstream in
  `FSD/CSD_STANDARD.md` with `check_csd.py`. Two standards for one artifact is
  the drift this repo exists to measure, and every day it lives here is a day
  that drift is real.

## 6. Lineage

v1 CIRISAgent `FSD/CSD_STANDARD.md` — MDD for one surface; spec and test one
document; flow byte-identity. Unchanged and inherited.

v2 CSD-B (this file) — reversed the arrow; §2a sample outputs; §3a namespaces.
Correct in aim, and both sections outran the DSL.

v3 — the DSL checks what §2 promises; the CEG family becomes the field
identifier so the two tables are one; the document is staged and machine-readable
so a feature can be specified before it exists and filled in as it is built.

Prior art unchanged from v2 and worth re-reading before extending the DSL:
Specification by Example (one artifact, requirement and test) and its
over-specification failure mode; approval testing (concrete expected output —
whose weakness, verifying consistency rather than correctness, is why `example:`
is written by hand before the code); spec-driven development (a versioned spec as
source of truth, bound to a codebase and never to an external vocabulary, which
is the gap §2 closes); VDEX and Dublin Core controlled vocabularies (normative
term registries, the model for binding to the CEG namespace); design by contract.
