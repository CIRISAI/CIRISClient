# CSD-050 — The layer hub (one card, four scopes)

**CSD**: CSD-050 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten — every tag below is `proposed:`, and a proposed tag may not enter a flow
**Covers**: `layer-agent`, `layer-family`, `layer-local-community`, `layer-global-communities`.
**Does NOT cover**: `layer-global-commons` — see CSD-051 and §2.0.

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Standing in a circle, a person can see who is in it, whether their node
trusts each of them, and the standing rules that decide trust here — or read
the honest sentence saying the node was not asked.** This is the first card in
every Rules tab and it answers the question the tab is named for. Serves
**Contextual Integrity**: `cohort_scope` is CC 2.3.3's privacy/routing axis, and
a circle IS a `cohort_scope`, so the card that names the scope is the card that
says what may cross out of it.

**What it does today is none of that.** `LayerHubScreen.kt:93-115` renders three
`LayerSection`s, each of which is an icon, a localized title and a localized
*description of data it does not fetch* — `commons.layer.family.identities_description`
is the sentence "Sibling occurrences … and their occurrence_id. Friendly name
from operator config", drawn over an empty card. The screen makes **no network
call of any kind**: it takes `scope`, `hasAgent` and three lambdas
(`LayerHubScreen.kt:63-69`) and there is no view model, no `apiClient`, no
`LaunchedEffect`. Its own doc comment says "EDGE_PEERRESOLVER (CIRISEdge#22) has
shipped; the cohort-aware views are active" (`LayerHubScreen.kt:58-60`). No node
route exposes a cohort-scoped identity listing — `list_peers` in CIRISServer
takes no query parameters at all (`src/federation_peers.rs:597`, registered at
`:1435`) — so the comment describes a state that does not exist on either side.

## 2. Surface (what)

### 2.0 Four surfaces, one screen — and why `scopes:` must be refused for the fifth

```yaml csd:surface
surface: layer-agent
screen: LayerAgent
scopes: [layer-agent, layer-family, layer-local-community, layer-global-communities]
```

`nav_map` derives `circle_agent -> tab_rules -> nav_epistemic_layer_agent`; the
other three derive the same chain with their own circle and row. All four route
to the same composable, `LayerHubScreen(scope = …)` (`CIRISApp.kt:4821, 4847,
4853, 4859`), so every tag below is the same tag with a different scope suffix
and every contract is the same contract.

**`scopes:` is the right operator and this file is its first user.** The four
differ only in the `${scope.id}` suffix on a tag stem
(`LayerHubScreen.kt:75, 94, 102, 110`), so four copies of this document would be
four places to update when one section learns to fetch. Today's
`check_csd_v3.py` reads only `surface:` and `screen:` from this block and
ignores the extra key, so the file validates while the operator is unimplemented
— the same "specification of work, not work done" posture `CSD.md` §5 takes for
the v3 predicates.

**And it must carry a refusal, or it will lie the first time it is used.**
`layer-global-commons` looks like the fifth member of this set — same naming,
same tab, same `NavSurface` shape — and it is not: `Screen.LayerGlobalCommons`
renders `NetworkScreen`, the Reticulum transport hub, not `LayerHubScreen`
(`CIRISApp.kt:4751-4753`, and the comment at `:4864` saying so). A `scopes:`
that only collects surface ids would have swept it in on the strength of its
name and asserted `layer_section_trust_global_commons` against a screen that has
no such element — which is `CSD.md` §3's empty-set trap arriving through the
front door. **The rule: `scopes:` is a load error unless every listed surface
resolves to the same `screen:`.** `layer-global-commons` is the negative test,
and it is a real one rather than a planted one.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self,family,community,affiliations,species,biosphere,federation]"
    example: "self"
    renders: "the circle you are standing in, as its own name — Just me / Family / Neighbours / Communities and Businesses"
    tag: "proposed:layer_hub_scope"
  - ceg: x_private:cohort_identities
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who is here — one row per identity visible at this scope, friendly name where there is one, key_id otherwise. Today: a sentence describing those rows, and no rows."
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
  `x_private:` by the convention CSD-004 set and CSD-006 follows. Its value set
  is **`self / family / community / affiliations / species / biosphere /
  federation`** (CC 2.5, `part_2_the_grammar.md:333`). The client says `planet`
  where CC says `biosphere` (`CohortScope.kt:5-14`, and CSD-006's `receipt_scope`
  row repeats it). CC rules on this by name: *"`planet` is not `biosphere` …
  `federation` is not a belonging scale … they MUST NOT be reconciled into a
  single vocabulary"* (CC 3.1.9.7, `part_3_the_namespace.md:363`). `planet`
  belongs to `goal:{scale}`; putting it in a `cohort_scope` enum imports the
  belonging ladder into the privacy ladder, which is the one move that ruling
  forbids. The fold is otherwise sound — it is the seventh token that is wrong,
  and the cost is that `biosphere` has no UI at all.
* **`trust_policy` has no registry row**, and `client/ceg/README.md` states the
  consequence: *"A family with no registry row cannot be rendered because it
  cannot be named — that is the 'nothing renders an unregistered family' gate
  (CC 3.1.7 R2)."* The Policies section can therefore never be more than the
  sentence it is today, whatever the node learns to serve. That is an ask on
  CIRISConstitution, not on CIRISServer.
* `trust:{job}:{version}` is **reserved** (CC 3.1.1, CIRISRegistry), so
  `display-only` is checkable here: the hub shows conferrals and accepts, and
  mints neither.

```yaml csd:states
populated: {tag: "proposed:layer_row_identity", renders: "at least one identity row under Who is here"}
empty:     {tag: "proposed:layer_empty", renders: "Nobody is here yet. — the circle is real and has no members"}
loading:   {tag: "proposed:layer_loading", renders: "the three section frames with a progress affordance and NO sentence"}
error:     {tag: "proposed:layer_error", renders: "Could not read who is in this circle. — the danger tone, never the empty sentence"}
```

**All four are `proposed:` because the screen has exactly one state.** It makes
no request, so it cannot be loading, cannot fail, and cannot be empty — a node
that refuses to answer, a node too old to answer and a circle with nobody in it
all render the same three paragraphs. CSD-003's principle applies directly: a
surface that renders a failed read as an empty one tells the user a different
and more flattering thing than the truth. Here it is worse than that, because
the sentence it renders instead is a *description of the absent data* — the
Family card says its rows carry "occurrence_id. Friendly name from operator
config" and shows none, which reads as "there are none" rather than "none were
asked for".

**The three section frames do exist** and are the only real tags on this screen:
`layer_hub_${scope}`, `layer_section_identities_${scope}`,
`layer_section_trust_${scope}`, `layer_section_policies_${scope}`
(`LayerHubScreen.kt:75, 94, 102, 110`). They are frames, not values, so no
`shows:` row binds them.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| identities at a cohort scope | none — the screen issues no request | CIRISServer | **missing**. `GET /v1/federation/peers` (`src/federation_peers.rs:1435`) is the nearest route and takes no scope parameter (`:597`). The ask is a scope filter, or a new route. |
| trust state per identity | `PUT /v1/federation/peers/{key_id}/trust` sets one (`src/federation_peers.rs:1444`) | CIRISServer | **missing** for the read shape: there is no scope-grouped trust listing. |
| trust policies at a scope | none | CIRISServer + CIRISConstitution | **missing on both**. No route (`trust_polic` has zero hits repo-wide in CIRISServer) and no registry family, so the route cannot be specified until the family exists. |

`EDGE_PEERRESOLVER` is an internal Reticulum transport-address resolver in the
node (`src/compose.rs`, `crates/ciris-lens-core/.../ceg_egress.rs`), not a
client-facing listing; "CIRISEdge#22" has zero references in CIRISServer. The
doc comment at `LayerHubScreen.kt:58` should be corrected in the same PR that
adopts this CSD, because a comment claiming a shipped dependency is how this gap
stayed invisible.

## 4. Flow (how)

**None, and it may not have one.** Every tag in §2 is `proposed:` and `CSD.md`
§2.1.1 forbids a proposed tag in a flow. The four frame tags are drivable today
and assert only that three empty cards composed, which is the vacuous assertion
CSD/3 exists to stop being mistaken for coverage.

The flow this CSD is waiting on, once a route exists:

```yaml
expect:
  state: populated
  count: {of: "layer_row_identity_*", min: 1}
  visible: [layer_section_identities_agent, layer_section_trust_agent, layer_section_policies_agent]
```

## 5. QA plan

**Platforms.** All five. The screen is static, so it composes everywhere; that
is a fact about its emptiness, not about its portability.

**Not tested here.**
* Anything the three sections claim to show. There is no data.
* The per-scope branches: the Local Community environment card
  (`card_local_community_environment`, `LayerHubScreen.kt:165`) needs
  `hasAgent`, and the Family delegations card (`card_family_delegations`, `:225`)
  needs a non-null `onOpenDelegations`. Both are real tags and both belong to
  CSD-001, not here.
* That the Family circle's copy means what the Locked Spec means. It does not:
  `commons.layer.family.identities_description` defines Family as *"Sibling
  occurrences (different runtime instances of the same agent)"* while the same
  tab places `Delegation` — a person-to-person authority grant — in that circle.
  Two definitions of Family, one tab. Naming, not machinery; recorded in the
  area report.
