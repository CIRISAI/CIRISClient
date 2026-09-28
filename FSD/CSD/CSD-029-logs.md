# CSD-029 — Logs (what happened, from the node or from the brain)

**CSD**: CSD-029 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can read their node's log, switch to the agent's when there is one,
and be told plainly when there is not — rather than being offered a source
that silently returns nothing.**

The last clause is not aspirational: this is the one card in the This-node
instrument that already does it. The source picker disables Agent on a node
build and prints `mobile.source_agent_unavailable` under the disabled row
(`LogsScreen.kt:319`–`330`). Every other card in this area either hides the
question or answers it with an empty list. **Logs is the worked example the
other eleven should copy.**

## 2. Surface (what)

```yaml csd:surface
surface: logs
screen: Logs
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_logs`. Not `agentOnly`, and correctly so — `/v1/telemetry/logs`
is served by both hosts, and the screen degrades honestly when only one is
there.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:log_source
    use: read
    type: "enum[node,agent]"
    example: "node"
    renders: "This node / The agent — and, on a node build, 'The agent' is greyed with 'not running here' beneath it (mobile.source_agent_unavailable)"
    tag: dropdown_logs_source
  - ceg: x_private:log_level
    use: read
    type: "enum[DEBUG,INFO,WARNING,ERROR,CRITICAL]"
    example: "ERROR"
    renders: "the level filter, and the per-row level chip"
    tag: "proposed:chip_logs_level_error"
  - ceg: x_private:log_service
    use: read
    type: string
    example: "federation"
    renders: "federation — the service filter, built from `availableServices` in the response"
    tag: chip_logs_service_all
  - ceg: x_private:log_timestamp
    use: display-only
    type: timestamp
    example: "2026-09-25T09:31:29.000Z"
    renders: "09:31:29 — the row's time, monospaced"
    tag: "proposed:logs_row_ts_20260925093129"
  - ceg: x_private:log_message
    use: display-only
    type: string
    example: "peer wa-peer-4a19c2 admitted"
    renders: "the row's text"
    tag: "proposed:logs_row_msg_20260925093129"
  - ceg: x_private:log_auto_scroll
    use: read
    type: bool
    example: true
    renders: "Follow new lines — a switch; on by default"
    tag: switch_logs_auto_scroll
  - ceg: "trace_summary:{kind}"
    bind: {kind: log}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "What this log is, constitutionally. NOT APPLICABLE TODAY and that is the honest answer: `/v1/telemetry/logs` returns operational log lines, not a CC 3.1.5 trace. The field is declared so the CSD states the distinction rather than implying a log is evidence."
    tag: "proposed:logs_provenance_note"
    blocked_by: CIRISAgent#1210
```

**The `trace_summary` row is a deliberate negative.** A person reading this
screen after an incident will treat what they see as a record. Under CC 3.1.5 /
CC 3.4.5 a record of an agent's reasoning is a `trace:*` — signed, pro-self,
emitter-bound. A log line is none of those: it is unsigned, third-party-
writable in principle, and carries no `attesting_key_id`. The screen should say
so once rather than letting a person infer weight it does not have.

```yaml csd:states
populated: {tag: "proposed:logs_list"}
empty:     {tag: "proposed:logs_empty", renders: "LogsScreen.kt:198 — the `logs.isEmpty()` branch; no lines match the current filter"}
loading:   {tag: "proposed:logs_loading", renders: "LogsScreen.kt:191 — a progress affordance while the first read is in flight, no sentence"}
error:     {tag: "proposed:logs_error", renders: "`LogsScreenState.error` EXISTS (LogsScreen.kt:660) and the composable's empty branch does not consult it. A refused read renders 'no lines'."}
```

Same defect as CSD-027 and CSD-028: the error is modelled, carried into the
state object, and never read by the branch that decides what to draw.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the log | `GET /v1/telemetry/logs` | **both** | live on CIRISServer (`/v1/telemetry/logs`) and CIRISAgent (`telemetry.py`) |
| available services | same response | **both** | live; `availableServices` is derived from the returned lines |
| the agent source on a node build | — | — | correctly refused client-side, with a sentence |

Agent tree last commit 2026-08-15.

## 4. Flow (how)

Open My things → This node → Logs on a node build.

```yaml
expect:
  state: populated
  count: {of: "logs_row_*", min: 1}
  visible: [dropdown_logs_source, input_logs_search, switch_logs_auto_scroll]
```

Open the source picker.

```yaml
expect:
  visible: [menu_logs_source_node, menu_logs_source_agent]
  text: {menu_logs_source_agent: "not running"}
```

On an agent build, pick the agent source.

```yaml
expect:
  state: populated
  count: {of: "logs_row_*", min: 1}
```

Search for a string no line contains.

```yaml
expect:
  state: empty
  visible: [logs_empty]
  absent: [logs_error]
```

Point the app at a node that refuses the log read.

```yaml
expect:
  state: error
  visible: [logs_error]
  absent: [logs_empty]
```

## 5. QA plan

**Platforms.** All five. Auto-scroll is a list-state animation that behaves
differently per platform and is worth asserting on each: the assertion is that
the last row is visible after a refresh, not that the animation ran.

**Not tested here.** Log volume. The screen polls every 5 s with a 100-line
limit; behaviour at a node producing thousands of lines a second is a
performance question the gate does not ask.

## 6. Card vs API vs CC — the delta

1. **Placement and build flag are both right.** Node-served route, shown on
   both builds, degrades with a sentence. No change.
2. **The source picker is the pattern to copy.** `isNodeMode` disables the
   agent row and explains why (`LogsScreen.kt:319`). Adapters, Runtime and
   Telemetry each make the same agent-only call with no such treatment; the
   fix for those three is either the `agentOnly` flag (preferred, per CSD-020
   §6.1) or this pattern.
3. **The error state is modelled and unread.** `LogsScreenState.error` →
   render it, and make the empty branch `error == null && logs.isEmpty()`.
4. **Nine real tags, and they are the right nine** — source, search,
   auto-scroll, service filter, the two source menu items, refresh, back,
   filters toggle. What is missing is the list itself and the rows. A log
   viewer whose rows are untagged cannot have a flow assert that a specific
   line appeared, which is the only assertion a log screen is for.
5. **CC: nothing to bind, and the CSD says why.** An operational log is not a
   CEG object. The risk is the opposite of the usual one — not that the screen
   under-claims, but that a person over-reads it. `logs_provenance_note` is
   one sentence of prose that closes that gap: *"These are operating notes,
   not signed records."*
