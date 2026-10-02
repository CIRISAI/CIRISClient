# CSD-050 — The layer hub (one card, four scopes)

**CSD**: CSD-050 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: none of its own — the Family, Neighbours and Communities hubs are driven by CSD-100's and CSD-102's flows; Just me's rows are `proposed:` and may not enter a flow
**Covers**: `layer-agent`, `layer-family`, `layer-local-community`, `layer-global-communities`.
**Does NOT cover**: `layer-global-commons` — see CSD-051 and §2.0.
**Rewritten** from the code in the adapters-and-hubs review (2026-09-28): the
earlier text said the screen made no network call and drew three description-only
sections in every circle. That stopped being true when the household (CSD-100)
and the communities section (CSD-102) were mounted on it.

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Standing in a circle, a person can see who is in it and the standing rules
that decide what happens to it — or read the honest sentence saying the node
was not asked.** This is the first card in every Rules tab and it answers the
question the tab is named for. Serves **Contextual Integrity**: `cohort_scope`
is CC 2.3.3's privacy/routing axis, and a circle IS a `cohort_scope`, so the
card that names the scope is the card that says what may cross out of it.

**What each circle's hub does today** (`LayerHubScreen.kt`, `CIRISApp.kt`'s four
`Screen.Layer*` arms):

| circle | screen | what the hub reads | the card that owns it |
|---|---|---|---|
| Family | `LayerFamily` | the household — `GET /v1/families` and its acts, at the node URL — then the delegations card | CSD-100 (`familyContent`); its roster is CSD-101 |
| Neighbours | `LayerLocalCommunity` | the Environment card (agent only), then the community section at `tier: community` — `GET /v1/communities`, `GET /v1/communities/{id}` and its acts, at the node URL | CSD-102 (`communities`) |
| Communities and Businesses | `LayerGlobalCommunities` | the community section at `tier: affiliations` | CSD-102 |
| Just me | `LayerAgent` | **nothing.** Three sections — Identities, Trust, Policies — each a localized sentence describing rows no route serves | this CSD, and it is the gap |

**Where a circle has a group of its own, the description-only sections are
gone.** The household replaced them on Family when it landed; since this review
the community section does the same on Neighbours and on Communities and
Businesses (`LayerHubScreen.kt`, `return@Column` after the section). Under a
real roster they read as a second list with nobody in it, which is the
error-looks-like-empty failure in a new costume. They remain only on Just me,
where there is nothing else to draw.

**The doc comment that hid the gap is corrected.** It said "EDGE_PEERRESOLVER
(CIRISEdge#22) has shipped; the cohort-aware views are active". `EDGE_PEERRESOLVER`
is the node's internal Reticulum transport-address resolver
(`src/compose.rs`), not a client-facing listing, and "CIRISEdge#22" has no
reference in CIRISServer. The comment now says what each circle reads.

## 2. Surface (what)

### 2.0 Four surfaces, one screen — and why `scopes:` must be refused for the fifth

```yaml csd:surface
surface: layer-agent
screen: LayerAgent
scopes: [layer-agent, layer-family, layer-local-community, layer-global-communities]
```

`nav_map` derives `circle_agent -> tab_rules -> nav_epistemic_layer_agent`; the
other three derive the same chain with their own circle and row. All four
route to one composable, `LayerHubScreen(scope = …)`, so the route gate treats
them as SIBLINGS (`check_csd_routes.py`, "one composable, many screens"): a
route cited by CSD-100 or CSD-102 is cited for all four, which is why
`--print CSD-050` lists the community and household routes even for Just me.
That is a fact about the shared composable, not about Just me's UI — the
`familyContent` and `communities` slots are null on `LayerAgent`.

**`scopes:` is the right operator and this file is its first user.** The four
differ in the `${scope.id}` suffix on the frame tag and in which slot is
filled. `check_csd_v3.py` reads only `surface:` and `screen:` from this block
and ignores the extra key, so the file validates while the operator is
unimplemented — the "specification of work, not work done" posture `CSD.md` §5
takes for the v3 predicates.

**And it must carry a refusal, or it will lie the first time it is used.**
`layer-global-commons` looks like the fifth member — same naming, same tab,
same `NavSurface` shape — and it is not: `Screen.LayerGlobalCommons` renders
`NetworkScreen`, the transport hub (CSD-051). **The rule: `scopes:` is a load
error unless every listed surface resolves to the same `screen:`.**
`layer-global-commons` is the negative test, and it is a real one.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self,family,community,affiliations,species,biosphere,federation]"
    example: "self"
    renders: "the circle you are standing in, as its own name — Just me / Family / Neighbours / Communities and Businesses — in the header"
    tag: "proposed:layer_hub_scope"
  - ceg: x_private:cohort_identities
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Just me: who is here — one row per identity visible at this scope. Today: a sentence describing those rows, and no rows. (Family and the two community circles show their real rosters through CSD-100/101 and CSD-102/103.)"
    tag: "proposed:layer_row_identity"
    blocked_by: CIRISServer#662
  - ceg: "trust:{job}:{version}"
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Trusted / Not trusted, and by which conferral — trust:confers:v1 from a root this node accepted, or a direct grant"
    tag: "proposed:layer_row_trust"
    blocked_by: CIRISServer#662
  - ceg: x_private:trust_policy
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Trust policies — the standing rules that trust someone here without being asked each time"
    tag: "proposed:layer_row_policy"
    blocked_by: CIRISConstitution#109
```

**Two of these four have no family to be named by, and that is the finding.**

* `cohort_scope` is a CC 2.1 envelope member, not a registry family, so it is
  `x_private:` by the convention CSD-004 set. Its value set is **`self /
  family / community / affiliations / species / biosphere / federation`** (CC
  2.5). The client says `planet` where CC says `biosphere`
  (`CohortScope.kt`), and CC 3.1.9.7 forbids reconciling the two vocabularies.
* **`trust_policy` has no registry row** (CIRISConstitution#109), so the
  Policies section can never be more than a sentence, whatever the node learns
  to serve.
* `trust:{job}:{version}` is **reserved** (CC 3.1.1), so `display-only` is
  checkable here: the hub shows conferrals and accepts, and mints neither.

```yaml csd:states
populated: {tag: "proposed:layer_row_identity", renders: "Just me: at least one identity row under Who is here. Family: CSD-100's household_record. Neighbours / Communities: CSD-102's communities_list"}
empty:     {tag: "proposed:layer_empty", renders: "Just me: Nobody is here yet. Family: households_empty. Neighbours / Communities: communities_empty"}
loading:   {tag: "proposed:layer_loading", renders: "Just me: nothing to load. Family: households_loading. Neighbours / Communities: communities_loading"}
error:     {tag: "proposed:layer_error", renders: "Just me: nothing is asked, so nothing can fail. Family: households_error / households_not_on_this_node. Neighbours / Communities: communities_error / communities_not_on_this_node"}
```

**The states are real on three circles and `proposed:` on one.** Family,
Neighbours and Communities and Businesses have four distinct states each, with
real tags, owned by CSD-100 and CSD-102 — error never draws as empty there.
Just me still has exactly one state: it asks nothing, so a node that refuses,
a node too old to answer and a circle with nobody in it all render the same
three paragraphs. That is why the tags above stay `proposed:`.

**The frame tags are real**: `layer_hub_${scope}` on every circle, and
`layer_section_identities_agent`, `layer_section_trust_agent`,
`layer_section_policies_agent` on Just me only. They are frames, not values, so
no `shows:` row binds them.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the household (Family) | `GET /v1/families` and the five household writes | CIRISServer `src/family_api.rs` | **live, called** — CSD-100 §3 is the table (node URL) |
| the community at a tier (Neighbours, Communities) | `GET /v1/communities`, `GET /v1/communities/{id}`, and found / role / leave / dissolve / cosign / assemble | CIRISServer `src/communities.rs` | **live, called** — CSD-102 §3 is the table (node URL) |
| names for keys on those cards | `GET /v1/contacts` | CIRISServer `src/contacts_chat.rs` | **live, called** by both view models — CSD-005 |
| identities at a cohort scope (Just me) | none | CIRISServer | **missing**. `GET /v1/federation/peers` (`src/federation_peers.rs:597`) takes no scope parameter. CIRISServer#662 |
| trust state per identity, grouped by scope | `PUT /v1/federation/peers/{key_id}/trust` sets one | CIRISServer | **missing** for the read shape. CIRISServer#662 |
| one identity, opened | `GET /v1/federation/peers/{key_id}` | CIRISServer | **live — not called by this card**; CSD-104's peer detail is the door a Just me row would open |
| trust policies at a scope | none | CIRISServer + CIRISConstitution | **missing on both**; no route and no family (CIRISConstitution#109) |

## 4. Flow (how)

**None of its own.** The three circles with a group are driven through their
owners' flows (`testing/flows/drafts/csd-100-household.yaml`,
`csd-102-communities.yaml`), which land on this hub by its derived hop and
assert `layer_hub_family` / the community section. Just me's rows are
`proposed:` and `CSD.md` §2.1.1 forbids a proposed tag in a flow; the three
frame tags would assert only that three sentences composed.

The flow this CSD is waiting on, once CIRISServer#662 lands:

```yaml
expect:
  state: populated
  count: {of: "layer_row_identity_*", min: 1}
  visible: [layer_section_identities_agent, layer_section_trust_agent, layer_section_policies_agent]
```

## 5. QA plan

**Platforms.** All five.

**Not tested here.**
* Anything Just me's three sections claim to show. There is no data.
* The per-scope feature cards: the Environment card
  (`card_local_community_environment`) needs `hasAgent`; the delegations card
  (`card_family_delegations`) belongs to CSD-055.
* The household and community sections — CSD-100/101 and CSD-102/103 test them.
* That Just me's copy means what the Locked Spec means. It still describes an
  agent-and-its-occurrences reading of the circles
  (`commons.layer.agent.subtitle`: "The agent itself — implicit self at every
  scale"); the Family copy that defined Family as sibling occurrences is no
  longer drawn, because the household replaced it.

## 6. Review (2026-09-28)

* **Card vs CSD**: the CSD said "no network call" and "three description-only
  sections"; false for three of four circles. Rewritten from the code.
* **Closed**: the description-only sections no longer render under the
  community section on Neighbours and Communities and Businesses (UI change,
  no unit test: it is composition, verified by compile and by CSD-102's flow
  asserting the section); the false doc comment is corrected. Moderation now
  receives the room's id from the community card (CSD-102 §3.2).
* **Open**: Just me asks nothing — CIRISServer#662 (no cohort-scoped roster)
  and CIRISConstitution#109 (no `trust_policy` family), both filed.
* **Stage**: building → building (three rows still `blocked_by`, and `proposed:` tags).
