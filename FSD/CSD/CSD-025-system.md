# CSD-025 — System (is this node well, and how do I know)

**CSD**: CSD-025 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can ask their node how it is and get an answer that separates "I am
unwell" from "I could not read myself" from "you are not allowed to ask" —
five distinct states, never four.**

This is the surface `FSD/RCA_INGEST_REJECTION_2026-08-05.md` was written
about: the trace plane was dead for 71 hours and every layer was individually
right, because nothing turned "nothing is arriving" into a signal a human saw.
`NodeOperatorState.kt`'s header states the rule this screen keeps — no field is
nullable-because-convenient, and a standing token this client does not
recognise parses to `null` rather than to a healthy default.

CC 3.4.3 is the constitutional basis: `system:*` is reserved to the substrate
itself, emittable only by a key whose `identity_type` is `substrate_persist` or
`substrate_edge`, "which is what makes them honest: the substrate cannot be
made to lie about its own health by a third party."

## 2. Surface (what)

```yaml csd:surface
surface: system
screen: System
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_system`. Not `agentOnly`, and correctly so: `GET /v1/node/state`
and `GET /v1/system/health` are the node's, and the node-state half of this
screen is the best-built thing in this area.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "system:*"
    use: display-only
    type: "enum[green,amber,red,unreadable,never_admitted,dark]"
    example: "amber"
    renders: "the headline band plus its raw wire token. CC 3.4.3: only `substrate_persist` / `substrate_edge` may emit this, so the band IS the substrate speaking about itself."
    tag: node_state_headline
  - ceg: x_private:standing_token_unknown
    use: display-only
    type: "list[string]"
    example: ["quiescent_pending"]
    renders: "This reading is newer than this app — a list of standing tokens this build does not know, rendered instead of a green default (NodeOperatorState.kt header rule)"
    tag: node_state_unknown_list
  - ceg: "trace_summary:{kind}"
    bind: {kind: plane}
    use: display-only
    type: string
    example: "dark"
    renders: "Traces: holds some, none recent — the trace plane's own standing, kept as three separable zeroes (`unreadable` / `never_admitted` / `dark`) rather than one empty state. CC 3.4.5 makes trace summaries pro-self."
    tag: node_state_trace_plane
  - ceg: "detection:hash_chain_integrity"
    use: display-only
    type: float
    example: 1.0
    renders: "the ingest plane's integrity reading, inside node_state_ingest"
    tag: node_state_ingest
  - ceg: "corpus_health:n_eff_measurable"
    use: display-only
    type: float
    example: 0.42
    renders: "Distinct signers — how many independent voices the corpus actually has (operator_ui.distinct_signers)"
    tag: node_state_signals
  - ceg: x_private:absent_sources
    use: display-only
    type: "list[string]"
    example: ["edge", "verify"]
    renders: "Two of the four sources contributed nothing — with each one's stated reason, never a blank. `OperatorSource.present = false` means 'contributed NOTHING', not 'read clean'."
    tag: node_state_absent_sources
  - ceg: "health:liveness:{version}"
    bind: {version: v1}
    use: display-only
    type: "enum[operational,degraded,outage]"
    example: "degraded"
    renders: "Services: 21 of 23 healthy (mobile.system_services_health). CC 3.1.9.4 fixes the mapping operational/degraded/outage → +1/0/−1, and pins that this is an EXTERNAL observation — 'never as the substrate', because `system:*` is reserved."
    tag: "proposed:system_services_health"
  - ceg: x_private:queue_depth
    use: display-only
    type: int
    example: 3
    renders: "3 thoughts waiting (mobile.system_queue_depth) — AGENT-ONLY (§3)"
    tag: "proposed:system_queue_depth"
  - ceg: x_private:environmental_cost
    use: display-only
    type: float
    example: 0.0184
    renders: "$0.018/h · 4.1 g CO₂/h · 12 Wh/h (mobile.telemetry_cost_hour / _co2_hour / _energy_hour) — AGENT-ONLY (§3)"
    tag: "proposed:system_environmental"
  - ceg: x_private:channels
    use: display-only
    type: "list[string]"
    example: ["discord:general"]
    renders: "Where it is listening — `GET /v1/agent/channels`. AGENT-ONLY (§3)."
    tag: "proposed:system_channels"
```

```yaml csd:states
populated: {tag: node_state_headline}
empty:     {tag: node_state_not_offered, renders: "This node doesn't offer an operator reading — HTTP 404 on /v1/node/state. A version gap, and the code says so: 'not a refusal and not ill health' (CIRISApiClient.kt:13728)."}
loading:   {tag: node_state_loading, renders: "the frame with a progress affordance and no sentence"}
error:     {tag: node_state_unreachable, renders: "No answer at all — transport, DNS, refused connection, timeout. THE fifth state, and the code comment pins it: 'it must not be dressed as a band.' Its siblings are node_state_refused (401/403 — the node answered and declined; it is UP) and node_state_malformed."}
```

**Five states, not four, and that is the point of this surface.**
`NodeStateReadout` is a sealed hierarchy of `Present` / `Refused` /
`NotOffered` / `Unreachable` / `Malformed`, each with its own tag. This is the
only card in this area that satisfies CSD/3 §2.2 today, and it satisfies it
better than the standard asks.

**Three tags named above are NOT `testable*` literals on this screen.**
`SystemScreen.kt` at v0.5.224 carries `node_state_headline`, `node_state_ingest`,
`node_state_signals`, `node_state_trace_plane`, `node_state_absent_sources`,
`node_state_unknown_list`, `btn_system_refresh`, `btn_system_back` and the
runtime dialog pair — and nothing else. `node_state_loading`,
`node_state_not_offered` and `node_state_unreachable` are written here without a
`proposed:` prefix and do not exist, so the `loading` state and two of the four
honest silences cannot be asserted. They must either be written `proposed:` or
tagged; `testing/flows/csd-025-system.yaml` asserts the six that are real and
says in a comment which three it is leaving out.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the operator reading | `GET /v1/node/state` | CIRISServer | live (`src/operator_surface.rs`, #356 / #369 / #370) |
| service health | `GET /v1/system/health` | **both** | live on CIRISServer (`/v1/system/health`) and CIRISAgent (`system/health.py`) |
| processor status | `GET /v1/system/health` | CIRISAgent | live; the client reads the processor block off the same response |
| every processor state and which is active | `GET /v1/system/processors` | CIRISAgent (`routes/system_extensions.py:581`, OBSERVER) | live, **not called** — the dedicated read for WAKEUP / WORK / DREAM / PLAY / SOLITUDE / SHUTDOWN; the card infers the same from the health envelope |
| resource usage | `GET /v1/system/resources` | CIRISAgent (`routes/system/services.py:28`) | live, **not called** |
| the agent's clock | `GET /v1/system/time` | CIRISAgent (`routes/system/health.py:979`) | live, **not called** |
| queue / cognitive state | `GET /v1/system/health` | CIRISAgent | live; **absent on the node's response** |
| environmental metrics | `GET /v1/telemetry/overview` | CIRISAgent | live (`telemetry.py`) — **wrong-host on a node build** |
| channels | `GET /v1/agent/channels` | CIRISAgent | live (`agent.py`) — **wrong-host on a node build** |
| pause / resume | `POST /v1/system/runtime/{action}` | CIRISAgent | live — **wrong-host on a node build** |

Agent tree last commit 2026-08-15.

## 4. Flow (how)

Open My things → This node → System on a node build.

```yaml
expect:
  state: populated
  visible: [node_state_headline, node_state_ingest, node_state_trace_plane, node_state_signals]
  absent: [system_channels, system_environmental, btn_pause_runtime]
```

The `absent:` line fails today.

Point the app at a node too old to serve the operator surface.

```yaml
expect:
  state: empty
  visible: [node_state_not_offered]
  absent: [node_state_headline]
```

Point it at a node that is down.

```yaml
expect:
  state: error
  visible: [node_state_unreachable]
  absent: [node_state_not_offered, node_state_headline]
```

Sign in as a non-owner and open it.

```yaml
expect:
  visible: [node_state_refused]
```

## 5. QA plan

**Platforms.** All five. The five-state matrix is driven against stub nodes
(404, 401, timeout, malformed body, good) on desktop; the other four platforms
drive the good case only.

**Not tested here.** A node whose standing token this build does not recognise
— `node_state_unknown_list` needs a stub emitting a future token, which the
gate does not yet stand up. This is the single most valuable untested
assertion on this screen, because it is the one that pins "never green by
default."

## 6. Card vs API vs CC — the delta

1. **Two cards are wearing one surface.** The node-state half is the node's
   (`GET /v1/node/state`, five states, twelve real tags). The processor /
   queue / environmental / channels half is the agent's and 404s on a node
   build with no visible consequence — those sections simply render zeros.
   **Ask (this repo): gate the agent half on `hasAgent` inside `SystemScreen`,
   or split it into its own card.** The surface itself stays every build's,
   because the node-state reading is the reason it exists.
2. **The good half has no error state of its own for the agent half.**
   `SystemViewModel._error` is modelled; the agent-side sections render
   defaults. The node-state half does not have this problem, which is exactly
   the contrast worth naming: one half was built to the standard and the other
   was not, in the same file.
3. **CC is satisfied, unusually.** `system:*` is reserved substrate-self-report
   (CC 3.4.3) and this screen renders it as a self-report, with the emitter's
   own tokens preserved. `health:liveness:{version}` (CC 3.1.9.4) is the right
   family for the services list and is explicitly *not* `system:*` — "never as
   the substrate" — which the client honours by sourcing it from a different
   route. Binding the services rows to that family would let the receipt sheet
   say so.
4. **`corpus_health:n_eff_measurable` and `detection:hash_chain_integrity` are
   both reserved substrate families** and both are rendered read-only here,
   which is correct: `use: display-only` throughout, no `emit`.
5. **Recommended placement: unchanged.** This card is where a person goes when
   something feels wrong, and My things → This node is where they will look.
