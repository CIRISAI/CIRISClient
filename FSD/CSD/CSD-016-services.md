# CSD-016 — Services (what is running here, under This node)

**CSD**: CSD-016 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**The owner can see which of this node's services are up, which are failing,
and — when the answer cannot be had — that the answer could not be had.**
Serves CC 3.4.5's self-report discipline: *a node's running configuration is a
self-report; a third-party assertion of what you are running is a rumour.* The
client is a third party to the node it is pointed at, so everything on this
screen must be traceable to a read, and a value the client invented is the
rumour that rule names.

The falsifiable claim, and it fails in four separate places today:
**every value on this screen was read from the host.** Four are not. `priority`
is the literal `"NORMAL"`, `priorityGroup` is `0`, `strategy` is
`"FALLBACK"` and `capabilities` is the empty list — all four hardcoded in the
mapper at `CIRISApiClient.kt:11092-11097`, none of them present on
`GET /v1/system/services`. `handlers` is `emptyMap()` with a comment saying so
(`:11103`), so the whole "Handler-Specific Services" section is structurally
empty. And `circuitBreakerState` is not read either — it is
`if (service.healthy) "closed" else "open"` (`:11095`), a restatement of the
health flag wearing the name of a different mechanism.

**This is also the one card in the agent-work group that a run-without-AI
install can reach, and it cannot work there.**

## 2. Surface (what)

```yaml csd:surface
surface: services
screen: Services
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_services`. Services is listed at `CirclesNav.kt:145` and is
**absent from the `agentOnly` set** at `CirclesNav.kt:153-155`, so a node
install lists it — while `getServices()` calls the brain's
`GET /v1/system/services`, which `CIRISServer origin/main` does not serve. §3
has the consequence.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: health:liveness:{version}
    bind: {version: v1}
    use: display-only
    type: bool
    example: true
    renders: "Healthy / Unhealthy per service row, and the counts in the Service Health Overview"
    tag: "proposed:services_row_health"
  - ceg: x_private:service_name
    use: display-only
    type: string
    example: "llm_bus"
    renders: "the row title under its service-type group"
    tag: "proposed:services_row_name"
  - ceg: x_private:service_type
    use: display-only
    type: string
    example: "llm"
    renders: "the group header — services are grouped by type client-side (CIRISApiClient.kt:11086)"
    tag: "proposed:services_group_type"
  - ceg: x_private:healthy_service_count
    use: display-only
    type: int
    example: 20
    renders: "Healthy: 20 in the overview card, coloured by whether any are unhealthy"
    tag: "proposed:services_healthy_count"
  - ceg: x_private:circuit_breaker_state
    use: display-only
    type: "enum[closed,open]"
    example: "closed"
    renders: "the breaker chip — DERIVED from `healthy`, never read (CIRISApiClient.kt:11095)"
    tag: "proposed:services_row_breaker"
  - ceg: config:{scope}
    bind: {scope: runtime}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "PRIORITY, STRATEGY, CAPABILITIES — rendered as NORMAL / FALLBACK / none for every service, from constants in the mapper, not from any route"
    tag: "proposed:services_row_config"
  - ceg: system:*
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "THE NODE'S OWN SUBSTRATE HEALTH — the thing the card's own doc comment promises (EpistemicNav.kt:69, 'Services is a node-infra keeper'). Not read; the screen shows the brain's service registry instead."
    tag: "proposed:services_node_substrate"
```

**`config:{scope}` and `system:*` are both reserved and both `display-only`
here on purpose.** CC 3.4.5 makes `config:{scope}` self-or-owner and CC 3.4.3
reserves `system:*` to the substrate component itself — so the client may
render them and may never emit them. They are listed as `unconfirmed` because
the values the screen draws in their place are constants, which is worse than
absent: an absent field is visibly absent, and `NORMAL` looks like it was read.

```yaml csd:states
populated: {tag: "proposed:services_list", renders: "the health overview, the circuit-breaker controls, then one group per service type"}
empty:     {tag: "proposed:services_empty", renders: "No Services Found (ServicesScreen.kt:210) — and this is ALSO what a 404 renders"}
loading:   {tag: "proposed:services_loading", renders: "a progress indicator instead of the list, only while both maps are empty (ServicesScreen.kt:122)"}
error:     {tag: "proposed:services_error", renders: "NOTHING. ServicesScreen takes no error parameter (ServicesScreen.kt:54-63) and CIRISApp.kt:3568 passes none, so ServicesViewModel._error is composed and never drawn."}
```

**This is the CSD/3 §2.2 violation in its worst available form.** A failed read
renders as `No Services Found`, and on a run-without-AI install every read
fails, so the honest sentence — *this node has no brain, and the service
registry is the brain's* — is replaced by a sentence that reads as a fact about
the node. The string `mobile.services_unavailable` ("Services information is
currently unavailable") already exists in all 29 bundles and is not wired to
this state.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the service list | `GET /v1/system/services` | CIRISAgent (`routes/system/services.py:77`) | live on the brain — `CIRISApiClient.kt:11071` |
| priority, priority group, strategy, capabilities | **no route** | — | **missing**; the client supplies constants instead (`CIRISApiClient.kt:11092-11097`) |
| handler-specific services | **no route** | — | **missing**; `handlers = emptyMap()` (`CIRISApiClient.kt:11103`) |
| real circuit-breaker state | **no route** | CIRISAgent | **missing**; derived from `healthy` |
| reset a circuit breaker | **no route** | CIRISAgent | **missing** — and `btn_reset_all` / `btn_reset_by_type` / `btn_reset_confirm` are wired to `resetCircuitBreakers`, whose whole body is a status message reading `"(API not yet implemented)"` (`ServicesViewModel.kt:265-271`) that the screen never renders |
| diagnostics | **no route** | CIRISAgent | **missing**; `runDiagnostics` (`ServicesViewModel.kt:185`) re-counts the list already on screen, and since the breaker state is derived from `healthy`, its "open breakers" number is the unhealthy count restated |
| the node's own substrate health | `GET /v1/system/health` | CIRISServer (`src/health.rs:589`) | live — **and never called by this screen** |

**Wrong host, and this is the one to fix first.** The card is not `agentOnly`,
its doc comment calls it *a node-infra keeper* (`EpistemicNav.kt:69`), and its
only read is the brain's. A grep of every `"/v1/..."` literal in
`CIRISServer origin/main src/` returns 213 routes; `/v1/system/services` is not
one of them. On a run-without-AI install `syncBackendFromEnv` re-points the
agent client at the node (`CIRISApp.kt:5032`), `getServices()` is **not**
`nodeSkip`-guarded (unlike `getMessages`, `getAuditEntries` and six others at
`CIRISApiClient.kt:798, 7486, 7657, 7745, 8490, 8563, 10621`), so the call goes
out, 404s, throws, sets `_error`, and the screen draws `No Services Found`.

Three fixes, and exactly one of them is right:
1. **Add Services to the `agentOnly` set** — cheapest, and it hides the card
   from the install that has the least insight into itself.
2. **Point it at the node's own `/v1/system/health`** — matches the card's
   stated purpose and works in both modes, since the folded brain's service map
   is merged into that envelope (`src/health.rs`, CIRISServer#390).
3. Leave it and add `nodeSkip` — which turns the 404 into a *silent* empty list,
   the same lie with no error to catch.

(2) is the one that matches what the card says it is.

**CIRISAgent working tree is dated 2026-08-15**, five weeks behind today.

## 4. Flow (how)

My things → This node → Services, on an agent.

```yaml
expect:
  state: populated
  visible: [btn_services_refresh, btn_services_diagnose, btn_reset_all]
  count: {of: "proposed:services_row_*", min: 1}
```

Run diagnostics: `btn_services_diagnose`.

```yaml
expect:
  visible: [proposed:services_diagnostics]
```

Point the client at a bare node — a run-without-AI install — and open the card.

```yaml
expect:
  state: error
  visible: [proposed:services_error]
  absent: [proposed:services_empty]
```

**That third step is the whole point of this CSD and it fails today**: the card
is listed, the read 404s, and the screen says `No Services Found`.

## 5. QA plan

**Platforms.** All five, and **both modes**. This is the only surface in the
agent-work group where the node mode is a real case rather than a hidden one,
so a run against a bare node is mandatory here and not a nicety.

**Not tested here.** That a reset does anything — it cannot, there is no route.
That the priority and strategy shown match the agent's real bus configuration —
they cannot, they are constants.

**Upstream asks.**
CIRISAgent — carry `priority`, `priority_group`, `strategy`, `capabilities` and
the real `circuit_breaker_state` on `GET /v1/system/services`, or the client
must stop drawing those four columns; and add a breaker-reset route, or the
client must remove the four controls that pretend to one.
CIRISClient — wire `ServicesViewModel._error` into `ServicesScreen`, bind
`mobile.services_unavailable` to it, and settle the host question above.
