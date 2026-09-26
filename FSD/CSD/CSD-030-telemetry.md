# CSD-030 — Telemetry (what the agent costs, and where that goes)

**CSD**: CSD-030 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: none — two real tags on a 968-line screen, so the stage is `envisioned`

```yaml csd:stage
stage: envisioned
owner: CIRISClient
```

## 1. Mission (why)

**A person can see what their agent is consuming, and — more importantly —
every outside place their telemetry is being sent to, with the ability to stop
one.**

The second half is the reason this card matters more than its name suggests.
`Telemetry` is not only a dashboard: it is the app's OTLP export console, where
an owner points their agent's metrics, logs and traces at a third-party
endpoint with a bearer token. That is a **flow**, in the Contextual Integrity
sense — data leaving the node to a party the node's other records know nothing
about — and it is configured here with no consent record and no receipt.

## 2. Surface (what)

```yaml csd:surface
surface: telemetry
screen: Telemetry
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_telemetry`. **Not in `agentOnly` (`CirclesNav.kt:153`) and it
must be** — every route is `/v1/telemetry/overview` or
`/v1/telemetry/export/*`, and `CIRISServer` serves neither.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:export_destination_endpoint
    use: read
    type: string
    example: "https://otlp.example.net/v1/metrics"
    renders: "otlp.example.net — where this node's telemetry is being sent (mobile.telemetry_endpoint)"
    tag: "proposed:telemetry_dest_endpoint_d1"
  - ceg: x_private:export_destination_signals
    use: read
    type: "list[string]"
    example: ["metrics", "logs", "traces"]
    renders: "What is being sent — Metrics · Logs · Traces (mobile.telemetry_signals). The third one is the one that matters: `traces` means the agent's reasoning leaves the node."
    tag: "proposed:telemetry_dest_signals_d1"
  - ceg: x_private:export_destination_auth
    use: read
    type: "enum[none,bearer,header]"
    example: "bearer"
    renders: "Bearer token (mobile.telemetry_auth_type / mobile.telemetry_bearer) — the credential the node presents to that endpoint"
    tag: "proposed:telemetry_dest_auth_d1"
  - ceg: x_private:export_destination_enabled
    use: read
    type: bool
    example: true
    renders: "Sending — a toggle; the one control that stops the flow"
    tag: "proposed:telemetry_dest_enabled_d1"
  - ceg: x_private:export_destination_interval
    use: read
    type: int
    example: 60
    renders: "Every 60 seconds (mobile.telemetry_interval)"
    tag: "proposed:telemetry_dest_interval_d1"
  - ceg: "consent:{kind}"
    bind: {kind: scope}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Under whose consent — the grant that permits this export. NOT SENT and NOT EMITTED: creating a destination writes no `consent:scope:*` row, so nothing in the audit answers 'who agreed to send my traces to otlp.example.net'. CC 3.4.5 makes `consent:scope:*` subject-emitted; the subject of an agent's traces is the agent and, through `subject_key_ids`, the people it talked to."
    tag: "proposed:telemetry_dest_consent_d1"
  - ceg: "health:liveness:{version}"
    bind: {version: v1}
    use: display-only
    type: "enum[operational,degraded,outage]"
    example: "operational"
    renders: "21 of 23 services healthy (mobile.telemetry_health / mobile.telemetry_online)"
    tag: "proposed:telemetry_service_health"
  - ceg: x_private:resource_usage
    use: display-only
    type: int
    example: 412
    renders: "CPU 12% · Memory 412 MB · Disk 2.1 GB (mobile.telemetry_cpu_usage / _memory_usage / mobile.system_disk)"
    tag: "proposed:telemetry_resources"
  - ceg: x_private:tokens_24h
    use: display-only
    type: int
    example: 184203
    renders: "184,203 tokens in 24 hours (mobile.telemetry_tokens_24h)"
    tag: "proposed:telemetry_tokens_24h"
```

**`telemetry_dest_consent_d1` is the field this CSD was written for.** An
export destination is a standing instruction to send an agent's traces to a
named third party. Under CC 3.1.5 a trace is the agent's own record; under
CC 2.3.3 the visibility axis is decided per envelope by the emitter under the
CC 1.13.3.4 smallest-scope default. Configuring a permanent egress to an
arbitrary HTTPS endpoint is the widest possible scope, set by an owner, with no
row recording that it happened.

```yaml csd:states
populated: {tag: "proposed:telemetry_overview"}
empty:     {tag: "proposed:telemetry_no_export", renders: "mobile.telemetry_no_export at TelemetryScreen.kt:224 — 'Nothing is being exported'. This is the GOOD empty: the safe state, stated plainly."}
loading:   {tag: "proposed:telemetry_loading", renders: "the frame with a progress affordance and no sentence"}
error:     {tag: "proposed:telemetry_error", renders: "`TelemetryViewModel._error` and `._destinationError` both EXIST and neither is a parameter of `TelemetryScreen` (signature at TelemetryScreen.kt:66–84). A failed overview read renders `TelemetryData()`: zero CPU, zero tokens, cognitive state WORK, zero services — an agent that looks idle and well."}
```

Same defaults-as-health failure as CSD-024: `TelemetryData()`'s
`cognitiveState = "WORK"` is a claim, not a blank.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the overview | `GET /v1/telemetry/overview` | CIRISAgent | live (`telemetry.py`) — **404 on a node** |
| destinations | `GET /v1/telemetry/export/destinations` | CIRISAgent | live (`telemetry_export.py`) — **404 on a node** |
| add one | `POST /v1/telemetry/export/destinations` | CIRISAgent | live |
| edit one | `PUT /v1/telemetry/export/destinations/{id}` | CIRISAgent | live |
| remove one | `DELETE /v1/telemetry/export/destinations/{id}` | CIRISAgent | live |
| test one | `POST /v1/telemetry/export/destinations/{id}/test` | CIRISAgent | live |
| resource history | `GET /v1/telemetry/resources/history` | CIRISAgent | live; **not called** |
| a consent row for an export | — **unconfirmed** | CIRISAgent | blocks `building` for `telemetry_dest_consent_d1` |

`CIRISServer` serves `/v1/telemetry/logs` and nothing else under
`/v1/telemetry`. Agent tree last commit 2026-08-15.

## 4. Flow (how)

Open My things → This node → Telemetry on an agent build.

```yaml
expect:
  state: populated
  visible: [telemetry_resources, telemetry_tokens_24h, telemetry_service_health]
```

With no export configured.

```yaml
expect:
  visible: [telemetry_no_export]
```

Add a destination.

```yaml
expect:
  visible: [telemetry_dest_endpoint_d1, telemetry_dest_signals_d1, telemetry_dest_auth_d1, telemetry_dest_consent_d1]
```

Turn it off.

```yaml
expect:
  state: populated
  text: {telemetry_dest_enabled_d1: "off"}
```

On a node build.

```yaml
expect:
  absent: [nav_epistemic_telemetry]
```

The last two blocks fail today.

## 5. QA plan

**Platforms.** All five for the dashboard. The export destination CRUD is
driven on desktop against a stub collector; `POST .../test` needs a reachable
endpoint.

**Not tested here.** That the export actually stops when the toggle is off.
The client sets `enabled = false` and the agent decides; nothing on this side
can observe the egress. That is worth stating rather than implying: **the one
thing a person uses this control for is the one thing this screen cannot
confirm.**

## 6. Card vs API vs CC — the delta

1. **Wrong build.** Shown on a node build, 404s on every call, renders a
   plausible zeroed dashboard. **Ask (this repo): add `NavSurface.Telemetry`
   to the `this-node` instrument's `agentOnly` set** (with `Adapters` and
   `Runtime`).
2. **Two tags on 968 lines.** `btn_telemetry_back` and
   `btn_telemetry_refresh`. Nothing else — not the dashboard, not a
   destination row, not the add dialog. This is the least drivable card in the
   area and the one whose controls have the largest real-world effect.
3. **Neither error flow reaches the screen.** `_error` and `_destinationError`
   are modelled, and `TelemetryScreen`'s signature takes neither. A failed
   destination write is silent.
4. **CC conflict — the substantive one.** Configuring a telemetry export is a
   consent-relevant act with no consent record. **Ask (CIRISAgent): emit a
   `consent:scope:*` row (CC 3.3.1 / CC 3.4.5) when an export destination is
   created or enabled, naming the endpoint and the signal set,** so the audit
   can answer where an agent's traces have been going. And render it here as a
   receipt: *who asked, what leaves, to whom, since when.*
5. **The `traces` signal deserves a warning the screen does not give.**
   Exporting `metrics` is operational. Exporting `traces` sends the agent's
   reasoning — and, through `subject_key_ids`, material about the people it
   reasoned about — off the node. The signal checkboxes render as three equal
   chips.
6. **Recommended placement: unchanged, but split.** The dashboard half belongs
   under This node. The export console is arguably an "Everything I shared"
   card, since that instrument is where a person looks for "what has left
   here". Recorded as an option, not a proposal: splitting one screen across
   two instruments is a cost, and the `consent:scope:*` receipt in (4) would
   surface the export in Everything I shared without moving the control.
