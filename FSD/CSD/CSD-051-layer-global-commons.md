# CSD-051 — Everyone's Rules card opens the radio settings

**CSD**: CSD-051 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, Rules tab
**Flow**: unwritten
**Reads with**: CSD-050 §2.0 — this surface is the negative test for `scopes:`.

```yaml csd:stage
stage: building
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
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self,family,community,affiliations,species,biosphere,federation]"
    example: "federation"
    renders: "what this circle IS — and nothing on the screen says it"
    tag: "proposed:layer_hub_scope"
```

`text_network_identity_key`, `card_network_identity`, `btn_network_identity_copy`,
`network_identity_error` / `network_identity_not_on_this_node`,
`federation_id_card` (agent attached only), `card_network_mode_link` and
`btn_network_mode_open_settings` (agent attached only) and `screen_network_hub`
are real tags on `NetworkScreen.kt`. None of them is a rule of the widest
circle; the last `shows:` row is the one the card was placed here to show, and
it is `proposed:` because nothing renders it.

**The mode card is gone (setup review decision, 2026-09-28).** The hub used to
draw the Client / Proxy / Server selector and call `PUT /v1/system/agent-mode`
behind a confirm — an agent-only route, on a surface every build shows, so on a
bare node the card read a 404 into an error banner and offered a switch that
could only fail. Settings owns that act (CSD-022 §2.0.1, where it is already
agent-plane only). With an agent attached the hub now draws one line and a link
(`card_network_mode_link`, "Your agent's network mode … is set in Settings",
`btn_network_mode_open_settings` → Settings); on a bare node it draws nothing
about modes. `banner_restart_pending`, `dialog_mode_confirm` and the
insufficient-disk banner went with the selector; Settings keeps its own.

**The Federation ID card is drawn only with an agent.** It reads the agent's
persist aggregate (`GET /v1/system/peers/federation-identity`); on a bare node
that read fails and the card said "Identity initializing…" indefinitely — a
failure drawn as a state that will resolve. The node's own signer key is the
Identity card above it, which the node serves.

```yaml csd:states
populated: {tag: screen_network_hub, renders: "the transport hub with its tiles"}
empty:     {tag: "proposed:layer_global_commons_empty", renders: "unreachable — the transport hub is never empty, it is configuration"}
loading:   {tag: "proposed:network_hub_loading", renders: "the hub frame with a progress affordance"}
error:     {tag: network_identity_error, renders: "the signer-key read failed — said under the Identity card by ReadFailureBlock, never as the '—' alone; network_identity_not_on_this_node for a host without the route"}
```

The `empty` row is the tell. A Rules card can be empty — "No rules set for this
circle" is already written (`nav.empty.rules`). A settings form cannot. A
surface whose empty state is unreachable is not the surface the tab asked for.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| this node's signer key | `GET /v1/federation/identity` (`src/federation_surface.rs:696`) | CIRISServer | live — `getFederationIdentity` (node URL) from `NetworkViewModel.loadFederationIdentity`; the same read CSD-036 makes, because both cards are `NetworkViewModel` |
| the persist identity aggregate (the Federation ID card) | `GET /v1/system/peers/federation-identity` (CIRISAgent `routes/system/peers.py:194`) | CIRISAgent | live on the agent only — `getFederationIdentityAggregate` raises `RouteNotOnThisHost` on a bare node, so the card is drawn only with an agent attached (§2) |
| the agent mode, read | `GET /v1/system/agent-mode` (CIRISAgent `routes/system/agent_mode.py:78`) | CIRISAgent | **not called by this card since the review** — the Network card reads it (`NetworkViewModel.loadAgentMode`, CSD-036) and Settings reads it for its selector (CSD-022) |
| the agent mode, switched | `PUT /v1/system/agent-mode` (`agent_mode.py`) | CIRISAgent | **removed from this card** (setup review decision): Settings (CSD-022 §2.0.1) is the one door. `NetworkViewModel.setMode` and the hub's selector are deleted; the hub links to Settings with an agent attached. The route map's duplicate row `PUT /v1/system/agent-mode :: LayerGlobalCommons, Settings` is gone from the baseline — red first: with the row removed the gate failed on the old code, and passes on this one |
| the Identity, Peers, Trust graph, Content, Interfaces and Queue tiles | `GET /v1/federation/identity`, `/node-code`, `/peers`, `/content/{id}`, `/metrics`; `POST /v1/system/peers/add-from-code` | CIRISServer / CIRISAgent | live — **other CSDs' cards**: CSD-032, CSD-033, CSD-046 (the Map tile folded into it), CSD-047, CSD-048, CSD-049. `NetworkPeerDetail` behind a peer row is CSD-104. Paths, Announces and Diagnostics have no CSD yet |
| the edge's counters (Interfaces, Queue tiles) | `GET /v1/federation/metrics` | CIRISServer `src/federation_surface.rs:697` | **live** — `CIRISApiClient.getFederationMetrics` (`CIRISApiClient.kt:1645`), read by `NetworkInterfacesViewModel.kt:86` and `NetworkQueueViewModel.kt:56`. The route-coverage report placed this on Telemetry (CSD-030); nothing on Telemetry reads it |
| the live event bus (Paths, Announces, Diagnostics tiles) | `GET /v1/federation/events/{channel}` (SSE) | CIRISServer `src/federation_surface.rs:703` | **live** — `FederationEventStream.kt:91`, opened by `NetworkPathsViewModel`, `NetworkAnnouncesViewModel`, `NetworkDiagnosticsViewModel` and `FederationStreamViewModel` |
| fetch content from a peer (Content tile) | `POST /v1/federation/content/{content_id}` | CIRISServer `src/federation_surface.rs:699`, owner-gated | **live** — `CIRISApiClient.fetchFederationContent` (`CIRISApiClient.kt:1726`) from `NetworkContentViewModel.kt:138`. The "fetch" half of CIRISServer#651; the directory half is the filed gap. Also noted against the Files card (`PENDING-CSD-007.md`) |
| **how big the widest circle is** — counts, never contents, with an `as_of` | `GET /v1/mesh/status` | CIRISServer `src/federation_admin.rs:919` (handler `:787`, CIRISServer#498) — **public**, served from cache | **live and never called.** It is the one read that is about the federation as a whole rather than about this node, and it deliberately enumerates nobody ("a public surface that enumerates peers is a reconnaissance surface", `:781-782`). When this card becomes `LayerHubScreen(GLOBAL_COMMONS)` (§5), this is the read its header can make without a scope filter that does not exist yet |
| the rules binding this circle | none | CIRISConstitution + CIRISServer | **missing**, and unspecifiable: there is no `trust_policy` family (CSD-050 §2) and no scope-scoped rules route |

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217). On `integ/0.5.218`
(97900cf5) `federation_surface.rs` moved +12 (`metrics :709`, `content :711`,
`events :715`); `mesh/status` did not move. The four network rows are the
hub's tiles, not the rules this card's name promises — they are recorded so the
§5 move takes them with it rather than losing them.

## 4. Flow (how)

None. A flow written now would pin the wrong screen to the right tab and make
the defect harder to move.

## 4.1 Review (2026-09-28)

* **Card vs CSD**: the CSD's mode-card rows described a selector that called an
  agent-only route from an every-build surface. **Closed**: selector removed,
  link to Settings with an agent attached, `setMode` deleted, baseline row
  removed. The Federation ID card no longer claims "initializing" on a bare
  node. The hub's `banner_error` (which showed the agent-mode 404) is replaced
  by the signer-key read's own failure block.
* **Open**: the recommendation in §5 (route this surface to
  `LayerHubScreen(GLOBAL_COMMONS)` and move the transport hub under My things ›
  This node) is a navigation change across two cards and the nav map; not made
  in this review. `GET /v1/mesh/status` is still unread.
* **Stage**: building → building (`proposed:layer_hub_scope`; no flow).

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
