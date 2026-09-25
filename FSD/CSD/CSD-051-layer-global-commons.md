# CSD-051 — Everyone's Rules card opens the radio settings

**CSD**: CSD-051 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten
**Reads with**: CSD-050 §2.0 — this surface is the negative test for `scopes:`.

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**The first card in Everyone's Rules tab should say what the federation's
standing rules are and who is bound by them. It opens the Reticulum transport
hub instead.** Serves **Transparency**, by failing it: a card whose name
promises the constitutional bounds of the widest circle and whose body is
network mode, interfaces and a signer key teaches a person that "Rules" means
"settings", and the Locked Spec put App and model settings under My things
precisely so that it would not.

This CSD exists to be fixed, not to be built. It records the current binding so
the move is a documented change rather than a silent one.

## 2. Surface (what)

```yaml csd:surface
surface: layer-global-commons
screen: LayerGlobalCommons
```

`nav_map` derives `circle_global_commons -> tab_rules ->
nav_epistemic_layer_global_commons` — the nav tree places it exactly where its
four siblings are placed (`CirclesNav.kt`, Rules block). The router does not
agree: `Screen.LayerGlobalCommons` renders `NetworkScreen`, not `LayerHubScreen`
(`CIRISApp.kt:4751-4753`; the stub at `:4864` reads "Screen.LayerGlobalCommons
handled above alongside Screen.Network — renders the federation transport
NetworkScreen"). **The nav map and the router can disagree because they are
derived from different files**, which is the drift `nav_map.py` closes for hops
and does not close for what a screen actually composes.

The label compounds it. The copy this card carries,
`commons.layer.global_commons.title`, is "Global Commons"; the circle it sits in
is "Everyone". So the first row of Everyone's Rules renames the circle and then
shows a transport form.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:attesting_key_id
    use: display-only
    type: string
    example: "wa-self-88b1"
    renders: "This node's federation signing key, in mono, with a copy control"
    tag: text_network_identity_key
  - ceg: x_private:network_mode
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the node's transport mode, with a confirm dialog before it changes"
    tag: card_network_mode
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self,family,community,affiliations,species,biosphere,federation]"
    example: "federation"
    renders: "what this circle IS — and nothing on the screen says it"
    tag: "proposed:layer_hub_scope"
```

`text_network_identity_key`, `card_network_identity`, `card_network_mode`,
`banner_error`, `banner_restart_pending`, `btn_network_identity_copy` and
`screen_network_hub` are real tags on `NetworkScreen.kt`. None of them is a rule
of the widest circle; the third row is the one the card was placed here to show,
and it is `proposed:` because nothing renders it.

```yaml csd:states
populated: {tag: screen_network_hub, renders: "the transport hub with its tiles"}
empty:     {tag: "proposed:layer_global_commons_empty", renders: "unreachable — the transport hub is never empty, it is configuration"}
loading:   {tag: "proposed:network_hub_loading", renders: "the hub frame with a progress affordance"}
error:     {tag: banner_error, renders: "the hub's error banner"}
```

The `empty` row is the tell. A Rules card can be empty — "No rules set for this
circle" is already written (`nav.empty.rules`). A settings form cannot. A
surface whose empty state is unreachable is not the surface the tab asked for.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| federation identity / transport state | the NetworkScreen reads, via `NetworkViewModel` | CIRISServer | live — and node-infra, which is the point |
| the rules binding this circle | none | CIRISConstitution + CIRISServer | **missing**, and unspecifiable: there is no `trust_policy` family (CSD-050 §2) and no scope-scoped rules route |

## 4. Flow (how)

None. A flow written now would pin the wrong screen to the right tab and make
the defect harder to move.

## 5. QA plan

**Platforms.** All five.

**Not tested here.** Everything `NetworkScreen` does — it is a node-infra
surface with its own owner, and grading it here would be grading the wrong
question.

**The recommendation, so it is on the record.** `layer-global-commons` should
route to `LayerHubScreen(scope = GLOBAL_COMMONS)` like its four siblings, and
the transport hub should move under **My things › This node**, beside
`NetworkOps` — which is already there and is already described as "THIS node's
local edge facts … the operator-infra slice" (`EpistemicNav.kt`, `NetworkOps`
doc). Two network surfaces exist; exactly one of them is in a circle, and it is
the one that should not be. From CC: `cohort_scope` is a privacy/routing axis
over *contributions* (CC 2.3.3), not a place to configure a radio; and a
Reticulum announce is itself gated on it (*"A destination whose `cohort_scope`
is below federation … MUST NOT emit a Reticulum announce"*,
`part_5_transport_substrate.md:748`), so the transport is downstream of the
circle, not a member of it.
