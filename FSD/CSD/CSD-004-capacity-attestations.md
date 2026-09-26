# CSD-004 — Federation capacity attestations (Health & Reputation)

**CSD**: CSD-004 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45,
**§2 and §3 updated** at the circles redesign
**Flow**: tools/qa_runner/flows/capacity_attestations.yaml

> **A PROFILE DEMONSTRATION, NOT A SECOND COPY.** The canonical CSD-004 is
> CIRISAgent's, where `check_csd.py` and the flow file live. This shows what
> CSD/3 looks like filled in, on the CSD that needed every part of it. It must
> go upstream and be deleted here.

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Why `sketched` and not further — and why the reason has changed.** The
previous cut said the capacity route was `unconfirmed`, and that was true when it
was written. **It is not true any more: CIRISServer#580 shipped
`GET /v1/my-data/capacity` and it is live on 0.5.217** (§3). What now blocks
`building` is worse than an unanswered route: *two* owners serve that path with
*two different payloads*, and the client parses only one of them (§3.1). The
stage stays `sketched` because the type of every `shows:` row is decided by
which host answered, which is not a thing this document can yet state.

## 1. Mission (why)

**A user can now see live federation capacity attestations on Health &
Reputation, where before #45 a gate placeholder stood in for them.** The
distinction is the whole feature: a placeholder that looks like data is the
metric-dishonesty MDD's anti-Goodhart measures name. Serves **Incompleteness** —
show what is measured, and only what is measured.

## 2. Surface (what)

```yaml csd:surface
surface: health-reputation
screen: HealthReputation
```

No "reached from" column: `nav_map` derives `circle_local_community ->
tab_decisions -> nav_epistemic_health_reputation` from the client's own tag
rules, and the checker refuses this block if that route does not exist.

**The chain ends on the row, not the tab** — the previous cut said "Neighbours ›
Decisions, where the tab's only card is this screen", and that stopped being
true when the environment snapshot moved into the same tab at the spine pass
(`CirclesNav.kt:124-126`: Health & Reputation, Environment Graph, Commons).
`expected_tail("health-reputation")` now returns `nav_epistemic_health_reputation`,
and a CSD claiming the tab would fail the checker's surface block. The hop is
derived precisely so this correction is mechanical rather than archaeological.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: capacity:core_identity
    use: display-only
    type: float
    range: unconfirmed
    example: 0.82
    renders: "Core identity — 0.82"
    tag: factor_row_c
    blocked_by: [CIRISServer#659, CIRISLensCore#25]
  - ceg: capacity:integrity
    use: display-only
    type: float
    range: unconfirmed
    example: 0.91
    renders: "Integrity — 0.91"
    tag: factor_row_i_int
    blocked_by: [CIRISServer#659, CIRISLensCore#25]
  - ceg: capacity:resilience
    use: display-only
    type: float
    range: unconfirmed
    example: 0.77
    renders: "Resilience — 0.77"
    tag: factor_row_r
    blocked_by: [CIRISServer#659, CIRISLensCore#25]
  - ceg: capacity:incompleteness_awareness
    use: display-only
    type: float
    range: unconfirmed
    example: 0.68
    renders: "Incompleteness awareness — 0.68"
    tag: factor_row_i_inc
    blocked_by: [CIRISServer#659, CIRISLensCore#25]
  - ceg: capacity:sustained_coherence
    use: display-only
    type: float
    range: unconfirmed
    example: 0.74
    renders: "Sustained coherence — 0.74"
    tag: factor_row_s
    blocked_by: [CIRISServer#659, CIRISLensCore#25]
  - ceg: capacity:composite
    use: display-only
    type: float
    range: unconfirmed
    example: 0.68
    renders: "𝒞_CIRIS — 0.68 (lowest factor: incompleteness awareness)"
    tag: card_capacity_composite
    assert:
      relation:
        left: capacity:composite
        op: min_of
        of: [capacity:core_identity, capacity:integrity, capacity:resilience,
             capacity:incompleteness_awareness, capacity:sustained_coherence]
    blocked_by: [CIRISServer#659, CIRISLensCore#25]
  - ceg: x_private:attesting_key_id
    use: display-only
    type: string
    example: "wa-lens-7f3c9a"
    renders: "Attested by wa-lens-7f3c9a"
    tag: "proposed:text_capacity_attested_by"
```

**The composite example is `0.68` — equal to the lowest factor, not their mean of
`0.784`.** The registry states the relationship outright (`capacity:composite` is
*"𝒞_CIRIS — the minimum over the five factors; anti-Goodhart unity-of-virtues"*),
so a rendering that averages misstates the Constitution rather than merely
looking wrong. The examples are chosen so that such a rendering is visibly wrong
against this table, and §4 asserts it rather than trusting the reader.

**Nothing on either side enforces the minimum.** The screen renders
`state.compositeScore` straight from the wire (`HealthReputationScreen.kt:198`)
and computes nothing; the agent's field is documented as *"Aggregate fleet CIRIS
score in `[0, 1]`"* (`routes/my_data.py:138`) with no min claim; the node's
capacity read serves rows, not a composite, and derives nothing. So the
`relation` above is a claim about the **vocabulary** that no implementation
checks — which is exactly what CSD/3 §3 says the predicate is for, and exactly
why it has never run.

**`range: unconfirmed` is deliberate, and the client's `[0,1]` is not a
substitute.** The registry gives each family a symbol (`C`, `I_int`, `R`,
`I_inc`, `S`) and a `signed` polarity, not bounds. `CellVizState.sanitized()`
coerces every factor into `[0,1]` and documents the reason as defending against
upstream drift — a *client* clamp, not a *substrate* range, and a clamp will
turn an out-of-range value into an in-range one silently. Inventing `0..1` in
this table would be a plausible number no substrate promised. CIRISLensCore
supplies it at `building`, and the checker refuses that stage until it does.

**`attesting_key_id` is `x_private:` and required, not decorative.** All six
`capacity:*` families carry `no-self-emit (attesting_key_id != attested_key_id)`
at **CC 3.4.5** — a node cannot attest its own capacity. A score with no visible
attester invites exactly the reading the Constitution forbids. It is `x_private:`
because the registry names the *rule* and not a family for the key itself.

**It is no longer blocked upstream.** The previous cut carried it as a proposed
tag against an unconfirmed route. The node's capacity read now puts
`attesting_key_id` on **every row**, and its module doc gives this CSD's own
reason for it — *"A capacity value with no visible attester invites exactly the
reading CC 3.4.5 forbids"* (`src/capacity_read.rs:16-19`). The data is served;
the element does not exist. `text_capacity_attested_by` has become a client
gap.

```yaml csd:states
populated: {tag: federation_capacity_live}
empty:     {tag: federation_capacity_warming_up, renders: "the card, with the factors not yet attested"}
loading:   {renders: "the card frame with a progress affordance — NOT the empty sentence"}
error:     {tag: federation_capacity_local_only, renders: "Federation capacity standing is UNAVAILABLE — /v1/my-data/capacity did not answer, so the score above is a local reading only."}
```

**`error` has stopped being `proposed:`.** The previous cut's sharpest paragraph
said a fresh node produces `warming_up`, a failed read produces nothing
distinguishable from it, and *"the mission's own distinction — 'we could not
ask' versus 'nothing has happened' — is not yet expressible on this surface."*
`federation_capacity_local_only` (`HealthReputationScreen.kt:404`) is that
distinction, shipped: it is a distinct tag on a distinct sentence that names the
route by name and says the number above it is local only.

**`federation_capacity_warming_up` was DISCOVERED, not designed.** Running this
CSD's flow against a #45 build on a fresh node showed the card present and the
live marker absent, with `federation_capacity_warming_up` in its place — a real
fifth state nobody had written down. It is recorded here as `empty` because that
is what it means: the node exists and nothing has attested it yet.

**And #580's module doc says that state was a lie on a node.** *"A client card
built on them could only render a permanent 'warming up' placeholder — a screen
that reads 'not yet' when the truth is 'not ever, on this build'"*
(`src/capacity_read.rs:5-8`). The empty state was honest about the corpus and
dishonest about the build, and it took the server author to notice.

### 2.1 What is still untagged

`txt_fragility_elevated` is tagged and `card_capacity_maturity` is tagged;
`btn_capacity_full_spec` is tagged. Untagged: the **"LOCAL + FLEET" / "FLEET
ONLY"** chip (`:219`), the `Local: x · Fleet: y` line (`:207`), and the
pre-fetch `—` (`:198`). The first two are the only thing on screen that says
*whose* number the big 36sp figure is, and the third is the difference between
"no reading yet" and a score of zero.

## 3. Contracts (who)

**Answered, and then complicated.** Verified against ciris-server `origin/main`
at 0.5.217 (2026-09-25) and the `~/CIRISAgent` working tree, last commit
**2026-08-15** — six weeks stale, so the agent row is "true of that tree".

| value | endpoint | owner | state |
|---|---|---|---|
| ~~the six capacities~~ — **superseded** | ~~unconfirmed; the path is not named in #45~~ | — | the row this CSD carried since #45 |
| capacity rows by subject, each with its attester | `GET /v1/my-data/capacity` | CIRISServer `src/system_data.rs:390`, `src/capacity_read.rs` (#580) | **live** — `{node_key_id, responsible_user_key_id, subjects[{key_id, any_standing, rows[{dimension, score, attesting_key_id, attested_by_this_node, standing, asserted_at, expires_at}]}], unscored[], truncated}` |
| the five factors + a composite + fragility + category | `GET /v1/my-data/capacity?scope=both` | CIRISAgent `routes/my_data.py:138-149` | **live on the agent** — `{composite_score, fragility_index, category, factors{…}, local_score, local_category}` |
| the range | **missing** on both | CIRISLensCore | blocks `building` — this is now the *only* clean unconfirmed left |

### 3.1 One path, two payloads, and a client that reads one

The two rows above are **the same URL on two hosts with incompatible shapes.**

`CIRISApiClient.getCapacity()` (`:12846`) parses the agent's:
`data.factors.C.score`, `data.composite_score`, `data.fragility_index`,
`data.local_score` — every one of them through `?: 0.0`
(`:12865-12874`).

The node's payload has no `factors` and no `composite_score`. Against a node
build, every one of those lookups misses, every `?: 0.0` fires, and the screen
renders **a composite of 0.00 with five factor rows at 0.00** — inside
`federation_capacity_live`, because the fetch succeeded.

CSD/3 §3 states the rule this breaks in one line: *"`number` refuses a
non-numeric text rather than reading 0. Reading a missing value as a zero is a
score where there is none."* The predicate was written for the harness. The
client does it in the parser, and `0.00` on an anti-Goodhart capacity screen is
not a neutral default — it is the worst score the scale has, presented as live
federation data.

**Three asks, and they are not the same ask:**
* **CIRISClient** — the parse must fail loudly on a payload it does not
  recognise rather than defaulting five factors and a composite to zero. This is
  the wheels-placeholder rule from `AGENTS.md`: the placeholder RAISES, and must
  never be made to return a value instead.
* **CIRISServer + CIRISAgent** — one path, two shapes, two owners is the
  "do not add a second source for anything that already has one" rule broken at
  the wire. Either the node projects the agent's shape, or the client learns
  both and says which it got.
* **CIRISLensCore** — the range (§2), unchanged since #45.

## 4. Flow (how)

The flow embedded upstream asserts only that the card and
`federation_capacity_live` are on screen — everything above was unassertable
before CSD/3 §3. **This is what it becomes**, using the tags a #45 build actually
serves:

```yaml
expect:
  state: populated
  count: {of: "factor_row_*", eq: 5}
  each:  {of: "factor_row_*", number: {min: 0.0, max: 1.0}}   # bounds pending §3
  relation: {left: capacity:composite, op: min_of,
             of: [capacity:core_identity, capacity:integrity, capacity:resilience,
                  capacity:incompleteness_awareness, capacity:sustained_coherence]}
  visible: [federation_capacity_live]
```

RUN AGAINST A REAL BUILD, THIS IS THE VERDICT IT RETURNED:

    [FAIL] expect: 'federation_capacity_live' is not on screen

and it is correct. `card_federation_capacity_attestations` WAS present; the live
marker was not, because the node was fresh. That is this flow's stated purpose
working — "a gate placeholder would satisfy the card tag alone" — distinguishing
a rendered card from live data on the first run that could reach the screen.

**The step §3.1 needs, and why it is not written.** Against a node build the
flow above would go **green**: five `factor_row_*` elements, five numbers in
`[0.0, 1.0]`, a composite that is trivially the minimum of five zeroes, and
`federation_capacity_live` on screen. Every predicate passes on a screen showing
a score nobody attested. `each: {number: {min: 0.0}}` cannot express "and not
all of them zero", and adding `relation: {left: capacity:composite, op: gt,
right: 0}` would be asserting that a real low score is a failure. **The
assertion this needs is on the parse, not on the pixels** — which is what the
first ask in §3.1 is.

## 5. QA plan

**Platforms.** All five. The hop is derived and identical on each.

**Acceptance — functional**
1. A person sees capacity attestations that are live data, not a gate.
2. The composite is legibly the **minimum** of the five, not a blend.
3. "Nothing attested yet" and "we could not ask" are visibly different.
4. Who attested is on screen.

**Untested and must be established**
* **The numbers themselves.** No fixture compares what is shown against the
  read — and §3.1 is what that gap costs.
* **The range** (§2). Until CIRISLensCore states it, the `each: number` bounds
  above are a placeholder and no assertion may depend on them.
* **Acceptance 2.** Nothing computes the minimum on either side (§2), so the
  `relation` predicate has never had a true implementation to check.
* **Acceptance 4.** The data arrived with #580; the element did not (§2).
* **Acceptance 3 is now COVERED** — `federation_capacity_warming_up` and
  `federation_capacity_local_only` are two tags on two sentences. This is the
  one line of this section that has moved since #45, and it moved because the
  distinction was written down here first.
* **The min relationship, on the current client.** Expressible in CSD/3 §3 and
  implemented in this repo's vendored runner; not yet in CIRISAgent's, which is
  where the canonical flow runs.
