# CSD-004 — Federation capacity attestations (Health & Reputation)

**CSD**: CSD-004 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45
**Flow**: tools/qa_runner/flows/capacity_attestations.yaml

> **A PROFILE DEMONSTRATION, NOT A SECOND COPY.** The canonical CSD-004 is
> CIRISAgent's, where `check_csd.py` and the flow file live. This shows what
> CSD/3 looks like filled in, on the CSD that needed every part of it. It must go
> upstream and be deleted here.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

Why `sketched` and not further: the capacity route is still `unconfirmed` (§3),
which is *correct* at this stage and a **failure** at `building`. That is the
distinction the stage machine exists to draw — "nobody has asked the substrate
yet" and "the substrate stopped answering" look identical in a document without
one.

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

No "reached from" column: `nav_map` derives
`nav_group_manage -> nav_epistemic_health_reputation` from the client's own tag
rules, and the checker refuses this block if that route does not exist.

```yaml csd:shows
registry_sha256: 87aede5012064288fd5ce8770d3e77a8c5131cd61d27799c4c06558507b9a9f5
fields:
  - ceg: capacity:core_identity
    use: display-only
    type: float
    range: unconfirmed
    example: 0.82
    renders: "Core identity — 0.82"
    tag: factor_row_c
  - ceg: capacity:integrity
    use: display-only
    type: float
    range: unconfirmed
    example: 0.91
    renders: "Integrity — 0.91"
    tag: factor_row_i_int
  - ceg: capacity:resilience
    use: display-only
    type: float
    range: unconfirmed
    example: 0.77
    renders: "Resilience — 0.77"
    tag: factor_row_r
  - ceg: capacity:incompleteness_awareness
    use: display-only
    type: float
    range: unconfirmed
    example: 0.68
    renders: "Incompleteness awareness — 0.68"
    tag: factor_row_i_inc
  - ceg: capacity:sustained_coherence
    use: display-only
    type: float
    range: unconfirmed
    example: 0.74
    renders: "Sustained coherence — 0.74"
    tag: factor_row_s
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

**`range: unconfirmed` is deliberate.** The registry gives each family a symbol
(`C`, `I_int`, `R`, `I_inc`, `S`) and a `signed` polarity, not bounds. Inventing
`0..1` would be a plausible number no substrate promised. CIRISLensCore supplies
it at `building`, and the checker refuses that stage until it does.

**`attesting_key_id` is `x_private:` and required, not decorative.** All six
`capacity:*` families carry `no-self-emit (attesting_key_id != attested_key_id)`
at **CC 3.4.5** — a node cannot attest its own capacity. A score with no visible
attester invites exactly the reading the Constitution forbids. It is `x_private:`
because the registry names the *rule* and not a family for the key itself.

```yaml csd:states
populated: {tag: federation_capacity_live}
empty:     {tag: federation_capacity_warming_up, renders: "the card, with the factors not yet attested"}
loading:   {renders: "the card frame with a progress affordance — NOT the empty sentence"}
error:     {tag: "proposed:text_capacity_error",  renders: "Could not read capacity attestations."}
```

**`federation_capacity_warming_up` was DISCOVERED, not designed.** Running this
CSD's flow against a #45 build on a fresh node showed the card present and the
live marker absent, with `federation_capacity_warming_up` in its place — a real
fifth state nobody had written down. It is recorded here as `empty` because that
is what it means: the node exists and nothing has attested it yet.

`error` is still `proposed:` and still the gap that matters. A fresh node
produces `warming_up`; a failed read produces nothing distinguishable from it,
so the mission's own distinction — "we could not ask" versus "nothing has
happened" — is not yet expressible on this surface.

### Namespaces, from the registry rather than by hand

All six `capacity:*` are `reserved`, `owning_component: lens`, `owning_repo:
CIRISLensCore`, `polarity: signed`, **CC 3.1.8.1**. `use: display-only` for every
one is therefore checkable rather than promised: an `emit` here fails the load,
because this component does not own them.

Not this surface's: `capacity_assurance:{level}:{domain}:{band}:{version}`
(CC 3.1.2, `attestation`/CIRISVerify) is the adult decision-capacity ladder,
listed only because its prefix sorts adjacent and the two have been conflated in
conversation before.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the six capacities | **unconfirmed** — `CapacityAttestation` is a type in ciris-server 0.5.199 and the pruned gate `LENSCORE_CAPACITY` says the lens read API on `:4243` serves it; the path is not named in #45 | CIRISServer / CIRISLensCore | blocks `building` |

## 4. Flow (how)

The flow embedded upstream asserts only that the card and `federation_capacity_live`
are on screen — everything above was unassertable before CSD/3 §3. **This is what
it becomes**, using the tags a #45 build actually serves:

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

## 5. QA plan

**Platforms.** All five. The hop is `nav_group_manage ->
nav_epistemic_health_reputation`, derived, and identical on each.

**Acceptance — functional**
1. A person sees capacity attestations that are live data, not a gate.
2. The composite is legibly the **minimum** of the five, not a blend.
3. "Nothing attested yet" and "we could not ask" are visibly different.
4. Who attested is on screen.

**Untested and must be established**
* **The numbers themselves.** No fixture compares what is shown against the read.
* **The range** (§2). Until CIRISLensCore states it, the `each: number` bounds
  above are a placeholder and no assertion may depend on them.
* **All four states.** No step covers empty, loading or error, because no tag for
  them is confirmed — and `error` is the one the mission cares about most.
* **The min relationship, on the current client.** Expressible in CSD/3 §3 and
  implemented in this repo's vendored runner; not yet in CIRISAgent's, which is
  where the canonical flow runs.
