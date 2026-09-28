# CSD-044 — Health & Reputation, the card in its circles (Decisions)

**CSD**: CSD-044 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

> **SCOPE — this is not a second CSD-004.** `CSD-004-capacity-attestations.md`
> owns the federation-attestations sub-section and the five `capacity:*` factor
> rows, and owns them well. This document owns the question CSD-004 never asks:
> **what is this card doing in three circles' Decisions tabs, and what does it
> show that is about the circle?** Where the two overlap, CSD-004 is normative
> and this file defers to it. One overlap is asserted deliberately (§2, the
> pre-fetch factor rows) because it is a *placement* defect wearing a rendering
> defect's clothes.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person standing in a circle can see how they and their agent are regarded
*in that circle* — and can tell a reading from a default, and a circle-specific
standing from a global one.**

Serves **CC 4.3 rule 2**, which is the sharpest statement CC makes about
reputation and is not about Wise Authorities alone: conduct records compose "on
`scores` exactly as `moderation_track_record` does for moderators — **relative
and positional, never a single global score, and never a substrate verdict.**"
The two families CC registers for this are parameterised by cohort —
`coherence_standing:{cohort}` and `manifold_conformity:{cohort}` (CC 3.1.8.3) —
and the card renders neither, in any circle.

## 2. Surface (what)

```yaml csd:surface
surface: health-reputation
screen: HealthReputation
```

`nav_map` derives `circle_local_community -> tab_decisions ->
nav_epistemic_health_reputation`. **CSD-004 §2's prose is stale on this point**
— it says "Neighbours › Decisions, where the tab's only card is this screen", and
`EnvironmentGraph` is placed in the same circle and tab
(`CirclesNav.kt`), so the chain ends on the row, not the tab. The typed block
still passes because `expected_tail` is derived; only the sentence is wrong.

The placement is `NEIGHBOURS_OUT` — **Neighbours, Communities and Businesses, and
Everyone**. One card, three circles, and the same bytes in all three.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "coherence_standing:{cohort}"
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED, in any circle. This is the family that would make the card mean something different in Neighbours than in Everyone — the standing of this subject within one cohort."
    tag: "proposed:standing_row_cohort"
    blocked_by: CIRISLensCore#25
  - ceg: "manifold_conformity:{cohort}"
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. Same family shape, same absence."
    tag: "proposed:conformity_row_cohort"
    blocked_by: CIRISLensCore#25
  - ceg: capacity:composite
    use: display-only
    type: float
    range: unconfirmed
    example: 0.68
    renders: "the 36sp hero, or an em dash before the first fetch. CSD-004 owns this row's min_of relation; it is repeated here ONLY as the anchor for the pre-fetch defect below."
    tag: card_capacity_composite
    blocked_by: [CIRISServer#659, CIRISLensCore#25]
  - ceg: x_private:score_provenance
    use: display-only
    type: "enum[local_and_fleet,fleet_only]"
    example: "fleet_only"
    renders: "a chip: LOCAL + FLEET / FLEET ONLY — the only thing on screen saying WHOSE number the hero is. UNTAGGED (HealthReputationScreen.kt:219), as CSD-004 §2.1 records."
    tag: "proposed:chip_score_provenance"
  - ceg: x_private:local_fleet_split
    use: display-only
    type: string
    example: "Local: 0.71 · Fleet: 0.68"
    renders: "Local: 0.71 · Fleet: 0.68 — untagged (:207)"
    tag: "proposed:txt_local_fleet"
  - ceg: x_private:circle_context
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. The card does not name the circle it is being read in, and does not vary by it. In Everyone it says exactly what it says in Neighbours."
    tag: "proposed:txt_circle_context"
  - ceg: x_private:consent_scope_analyze
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. CC 3.4.5: at federation tier a `capacity:*` row requires a live `consent:scope:analyze` grant; a subject who declined has an UNDEFINED composite that MUST NOT be emitted. The card has no rendering for 'not scored because not consented' — see §6."
    tag: "proposed:txt_capacity_unconsented"
    blocked_by: CIRISAgent#1219
```

**The pre-fetch state is flattering, and that is a constitutional problem, not a
cosmetic one.** `CompositeScoreHero` guards on `state.isPreFetch` and draws an em
dash (`:198`). `FactorRow` does not guard on anything (`:259-270`): it renders
`state.c`, `state.iInt`, `state.r`, `state.iInc`, `state.s` straight from
`CellVizState.DEFAULT`, which is **1.0 on every factor**, documented as "the cell
looks 'as designed' — we do NOT punish the user with a degraded viz just because
we haven't phoned home yet". So before any fetch, and after a failed one, the
composite honestly says *no reading* while the five factors that compose it each
say *perfect*. CC 3.1.8.1 makes the composite the **minimum** of those five, so
the screen simultaneously asserts `min(1,1,1,1,1) = —`.

```yaml csd:states
populated: {tag: screen_health_reputation}
empty:     {tag: "proposed:capacity_unscored", renders: "This subject has not been scored. — for the CC 3.4.5 unconsented case AND the pre-fetch case; the five factor rows must be ABSENT, not 1.00"}
loading:   {tag: "proposed:capacity_loading", renders: "the hero frame with a progress affordance and no factor rows"}
error:     {tag: federation_capacity_local_only, renders: "CSD-004 owns this: /v1/my-data/capacity did not answer, so the score above is a local reading only."}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| five factors, composite, fragility, category | `GET /v1/my-data/capacity?scope=both` | CIRISAgent `routes/my_data.py:1220` | live on the brain (tree of 2026-08-15) |
| capacity rows with attesters | `GET /v1/my-data/capacity` | CIRISServer `src/system_data.rs:390` | live, unauthenticated |
| `coherence_standing:{cohort}` for a named cohort | — | CIRISLensCore | **missing** — no route on either host |
| `manifold_conformity:{cohort}` for a named cohort | — | CIRISLensCore | **missing** |
| whether the subject granted `consent:scope:analyze` | — | CIRISAgent | **missing** — CC 3.4.5 refuses a `capacity:*` row without a live grant *at admission, before persistence*, and no route answers it per subject. **CIRISAgent#1219** |
| the factors' range | — | CIRISLensCore | **missing** — CSD-004 §3 already carries this row |

## 4. Flow (how)

Sign in on a node with a brain; stand in **Neighbours** and open Decisions ›
Health & Reputation.

```yaml
expect:
  state: populated
  visible: [screen_health_reputation, card_capacity_composite, "proposed:chip_score_provenance"]
  count: {of: "factor_row_*", eq: 5}
```

Now switch to **Everyone** and open the same card.

```yaml
expect:
  visible: ["proposed:txt_circle_context"]
  relation: {left: "coherence_standing:{cohort}", op: ne, right: "coherence_standing:{cohort}"}
```

That block is unsatisfiable as written and says so on purpose: there is no second
value to compare, because the card has no cohort parameter at all. It is the
shape the fix must make assertable — two circles, two standings — and it is the
one assertion in this file that cannot be written honestly today.

Before the first fetch (airplane mode at launch):

```yaml
expect:
  state: empty
  visible: ["proposed:capacity_unscored"]
  count: {of: "factor_row_*", eq: 0}
```

## 5. QA plan

**Platforms.** All five, in **two circles each** — the circle switch is the test
this CSD adds and no existing flow performs it.

**Not tested here.** Everything CSD-004 covers: the federation-attestations
sub-section, the `min_of` relation, the maturity note, the attester row. The
`relation` predicate itself, which `flow_spec.py` does not implement (CSD/3 §5).

## 6. Delta — card vs API vs CC

* **One card, three circles, no cohort.** The Decisions tab is documented in
  `CirclesNav.kt` as "how the circle is doing … standing and health", and this
  card shows how the *agent* is doing, identically in Neighbours, Communities and
  Everyone. CC 4.3 rule 2 says reputation is "relative and positional, never a
  single global score"; CC 3.1.8.3 registers the two cohort-parameterised
  families that would make it relative. **Ask (CIRISLensCore, via
  CIRISServer):** serve `coherence_standing:{cohort}` and
  `manifold_conformity:{cohort}` for the cohort the client is standing in.
  **Ask (CIRISClient):** pass the current `CohortScope` into the screen and name
  it on the card. Until then the honest interim is to place the card in **one**
  circle, not three — a repeated identical card teaches the person that the
  circles do not matter, which is the opposite of what the Locked Spec is for.
* **The pre-fetch factors are the most flattering possible reading.** Five rows
  at 1.00 under a composite of "—". **Ask (CIRISClient):** guard `FactorRow` on
  `isPreFetch` exactly as the hero is guarded, and render the empty state
  instead. This is a four-line change and it removes a screen that says *perfect*
  when it means *nothing was read*.
* **CC 3.4.5's consent gate has no rendering.** "A subject that declines analysis
  cannot be scored; its `capacity:composite` is undefined and MUST NOT be
  emitted; and every gate that requires a capacity verdict therefore **fails
  closed** for that subject." Undefined-because-unconsented and
  not-fetched-yet are different facts and the card draws neither. **Ask
  (CIRISAgent):** distinguish them on the wire. Note CC also **refused** the rule
  "an agent may read the rows filed about it, never the composed outputs" — so
  the fix is a distinct state, not a redaction.
* **CC guardrail for the neighbouring card.** Whatever CSD-039 eventually lists
  under "Everything I shared" must not let a subject withdraw these rows:
  CC 2.4.1.1 forbids subject-side withdrawal of a third-party `capacity:*` row,
  and points at CC 4.5.5 `reconsideration:{grounds}` as the contest path. This
  card is where a "contest this" affordance belongs.
* **Placement.** Decisions is the right tab. **Three** circles is wrong until the
  card varies by circle; recommended interim is Neighbours only, because that is
  the smallest cohort CC 4.4.3.2.1 gives a non-self scope to and the one a person
  can actually check a standing against.
