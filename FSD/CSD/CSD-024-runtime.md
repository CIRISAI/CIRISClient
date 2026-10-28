# CSD-024 — Runtime (the agent's reasoning, stopped mid-thought)

**CSD**: CSD-024 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**An owner can pause their agent mid-thought, walk it forward one step at a
time, and see which of the eleven H3ERE points it is standing on — and the
screen says when the owner is not allowed to do that rather than showing dead
buttons.**

This is the one surface where a person watches reasoning happen rather than
reading its result. CC 3.1.5 calls that record a `trace:{form}:{version}` and
CC 3.4.5 makes it **pro-self**: "a trace records its own producer's reasoning;
a trace someone else minted about you is a claim, not a trace." The step points
this screen renders are that trace, live and unsigned.

## 2. Surface (what)

```yaml csd:surface
surface: runtime
screen: Runtime
```

`nav_map` derives `btn_my_things -> nav_instrument_this_agent ->
nav_epistemic_runtime`. Runtime is under **This agent**, whose whole instrument
is `requiresAgent` (`CirclesNav.kt`), so a bare node is never offered it
(§6.1). **It is the ONE runtime control**: System (CSD-025) has no
pause/resume door onto `POST /v1/system/runtime/{action}` and links here
(`btn_system_open_runtime`), see §7.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "trace:{form}:{version}"
    bind: {form: complete, version: v1}
    use: display-only
    type: "enum[start_round,gather_context,perform_dmas,perform_aspdma,conscience_execution,recursive_aspdma,recursive_conscience,finalize_action,perform_action,action_complete,round_complete]"
    example: "perform_aspdma"
    renders: "Now: Choosing an action — the current H3ERE step point, with the ten before it marked done (mobile.step_*). CC 3.1.5 / CC 3.4.5: this IS the agent's own trace, and only the agent may emit it."
    tag: "proposed:runtime_step_point"
  - ceg: x_private:cognitive_state
    use: display-only
    type: "enum[WAKEUP,WORK,PLAY,SOLITUDE,DREAM,SHUTDOWN]"
    example: "WORK"
    renders: "Working — the cognitive state (system.system_cognitive_state)"
    tag: "proposed:runtime_cognitive_state"
  - ceg: x_private:queue_depth
    use: display-only
    type: int
    example: 3
    renders: "3 thoughts waiting (system.system_queue_depth)"
    tag: "proposed:runtime_queue_depth"
  - ceg: x_private:processor_state
    use: display-only
    type: "enum[running,paused,unknown]"
    example: "paused"
    renders: "Paused — which is what makes Step meaningful"
    tag: "proposed:runtime_processor_state"
  - ceg: x_private:last_step_time_ms
    use: display-only
    type: int
    example: 412
    renders: "Last step took 412 ms (mobile.runtime_time)"
    tag: "proposed:runtime_last_step_time"
  - ceg: x_private:tokens_used
    use: display-only
    type: int
    example: 2140
    renders: "2,140 tokens (mobile.runtime_tokens)"
    tag: "proposed:runtime_tokens"
  - ceg: x_private:active_task_id
    use: display-only
    type: string
    example: "task-4a19c2ef01"
    renders: "task-4a19c2ef · 3 thoughts · updated 2s ago (mobile.runtime_task / mobile.runtime_task_info)"
    tag: "proposed:runtime_task_4a19c2ef01"
  - ceg: x_private:stream_connected
    use: display-only
    type: bool
    example: false
    renders: "Not live — updates are being polled, not streamed (mobile.interact_disconnected). This distinction is load-bearing: a stalled stream and an idle agent look identical without it."
    tag: "proposed:runtime_stream_state"
  - ceg: x_private:is_admin
    use: display-only
    type: bool
    example: false
    renders: "You cannot pause this agent — mobile.runtime_admin_required + mobile.runtime_admin_hint, rather than three buttons that do nothing"
    tag: runtime_admin_required
```

```yaml csd:states
populated: {tag: "proposed:runtime_pipeline"}
empty:     {tag: "proposed:runtime_idle", renders: "Nothing is being thought about right now — no active tasks, no current step. Distinct from paused: an idle agent is working and has nothing to do."}
loading:   {tag: "proposed:runtime_loading", renders: "the pipeline frame with a progress affordance and NO sentence"}
error:     {tag: "proposed:runtime_error", renders: "`RuntimeViewModel._error` is modelled and never rendered — `RuntimeScreen` takes `runtimeData`, `isLoading` and `isAdmin` and no error. A failed `GET`-equivalent therefore shows the default `RuntimeData()`: state `unknown`, cognitive state `WORK`, queue depth `0` — a plausible, wrong, reassuring screen."}
```

**The default-object failure is the worst error handling in this area.**
`RuntimeData()`'s defaults are not neutral: `cognitiveState = "WORK"` and
`queueDepth = 0` read as "your agent is up and idle". CSD/3 §2.2's rule —
"a surface that renders a failed read as an empty one tells the user a
different and more flattering thing than the truth" — is exactly instantiated
here, with the aggravation that the flattering thing is a specific claim.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| pause | `POST /v1/system/runtime/pause` | CIRISAgent | live (`system/runtime.py:42`, `/runtime/{action}`) |
| resume | `POST /v1/system/runtime/resume` | CIRISAgent | live, same route |
| one step | `POST /v1/system/runtime/step` | CIRISAgent | live, **a DIFFERENT route** (`system_extensions.py:292`) — see below |
| state read-back | `POST /v1/system/runtime/state` | CIRISAgent | **live, and contracted as a read** (`system/helpers.py:580-594`, "Get current state without changing it") |
| the live step stream | `GET /v1/system/runtime/reasoning-stream` | CIRISAgent | live (`system_extensions.py:922`); opened by `InteractViewModel`, never by `RuntimeViewModel` — see below |
| queue | `GET /v1/system/runtime/queue` | CIRISAgent | live (`system_extensions.py:39`); not called (`SystemExtensionsApi.kt:75` has zero callers) |
| release memory | `POST /v1/system/runtime/memory/release` | CIRISAgent (`routes/system/runtime.py:204`) | live, **not called** — a runtime control this card does not offer |

CIRISServer serves **no** `/v1/system/runtime*` (0.5.217), and the node folds
only `/v1/system/health` from a brain (`src/health.rs:576-591`). There is no
general brain proxy. Agent rows are CIRISAgent `main`.

**The state read is `state`, not `status`.** `valid_actions =
["pause","resume","state"]` (`system/helpers.py:455`), so
`POST /v1/system/runtime/status` would 400; the client posts
`action = "state"` (`CIRISApiClient.kt:11338, :11344`). `state` returns before
any mutation (`runtime.py:78-80`) and its helper is a pure read.

**`step` is not "the same route".** `POST /v1/system/runtime/step` resolves to
`system_extensions.py:292`, and it is reachable ONLY because
`system_extensions.router` is registered before `system.router`
(`api/app.py:360-361`). `step` is absent from `valid_actions`, so reversing that
order turns this row into a 400 with nothing on either side to catch it.

**`streamConnected` is rendered from a poll, not a stream.**
`RuntimeViewModel.kt:313` sets it `true` on a successful poll (its own comment:
"Simulated - we're successfully polling"), while the only opener of
`reasoning-stream` is `ReasoningStreamClient.kt:77` from `InteractViewModel.kt:469`.
The screen therefore says "live" when nothing is streamed — the inverse of what
§2's `renders` text promises, and a `proposed:runtime_stream_state` tag must not
be written against the current behaviour.

## 4. Flow (how)

Open My things → This node → Runtime on an agent build, as an admin.

```yaml
expect:
  state: populated
  visible: [btn_pipeline_pause, btn_runtime_refresh]
  visible: [runtime_cognitive_state, runtime_queue_depth]
```

Pause.

```yaml
expect:
  visible: [btn_pipeline_resume, btn_pipeline_step]
  text: {runtime_processor_state: "aused"}
```

Step once.

```yaml
expect:
  state: populated
  visible: [runtime_step_point, runtime_last_step_time]
```

As a non-admin.

```yaml
expect:
  visible: [runtime_admin_required]
  absent: [btn_pipeline_pause, btn_pipeline_step]
```

On a node build.

```yaml
expect:
  absent: [nav_epistemic_runtime]
```

## 5. QA plan

**Platforms.** All five, but the step controls are driven on desktop only —
pausing a real agent mid-round in CI on five platforms multiplies the flake
surface for one assertion.

**Not tested here.** The reasoning stream (no client subscriber to drive);
`runtime_stream_state` is asserted at its `false` value only.

## 6. Card vs API vs CC — the delta

1. **Agent-only — closed.** Runtime is in the `this-agent` instrument, which
   `requiresAgent`, so a no-agent build is not offered it. Every one of its
   endpoints is `/v1/system/runtime/*`, which CIRISServer does not serve; and
   there is no reasoning to step through without a brain. The same applies to
   `Telemetry` (CSD-030) and `Adapters` (CSD-020).
2. **The error state is a lie shaped like health.** See §2 states. **Ask (this
   repo): pass `RuntimeViewModel.error` into `RuntimeScreen` and render a
   distinguishable banner; never fall back to `RuntimeData()`.**
3. **Almost no data tags.** The real tags are the controls
   (`btn_pipeline_pause`, `btn_pipeline_resume`, `btn_pipeline_step`,
   `btn_runtime_back`, `btn_runtime_refresh`) and `runtime_admin_required`.
   Nothing that renders a value is tagged, so a flow can drive this screen and
   assert nothing about what it says.
4. **A read on a POST route.** `getRuntimeState` POSTs `action = "state"` to
   the control route (`controlRuntimeV1SystemRuntimeActionPost`); `state` is a
   pure read (§3), on a mutating route. `GET /v1/system/runtime/queue` exists
   and is unused. **Ask (CIRISAgent): give the read its own GET.**
5. **CC, and this is a genuine future ask.** What this screen shows live is
   the substance of `trace:complete:v1`. CC 3.1.5's trace is signed,
   pro-self, and carries the "exactly-one-of inline-`trace`/`manifest`" shape;
   this screen's step points are none of those — they are UI state. That is
   defensible for a live debugger. It stops being defensible the moment a
   person is asked to trust what they saw here: a paused-and-stepped round
   should be able to produce its `trace:complete:v1`, and the screen should
   offer it. Recorded as an ask, not a defect.

## 7. Review — runtime group

Card checked against CIRISAgent `main` and CIRISServer `main`. Tests: `client/shared/src/desktopTest/.../viewmodels/RuntimeGroupReviewTest.kt`.

**One runtime control (Runtime CSD-024, not System CSD-025).** Only Runtime
calls `POST /v1/system/runtime/{action}`. System shows the cognitive state
read-only with `btn_system_open_runtime` → `Screen.Runtime`.

**Closed**
- *Step reads its own shape.* `POST /v1/system/runtime/step`
  (`system_extensions.py:292`, ADMIN) answers a `SingleStepResponse`
  (`step_point`, `processing_time_ms`, `tokens_used`), not a
  `RuntimeControlResponse`; `parseRuntimeStepBody` (`api/RuntimeWire.kt`) reads
  it, so `runtime_step_point` is real data.
- *A declined act is not reported as done.* A 200 with `success:false`
  ("Not paused") is `RuntimeActionDeclined`.
- *Admin is read, not assumed.* Every runtime action including `state` is
  ADMIN, so the agent answers it: admin is false until a state read succeeds; a
  403 sets `adminRefused` and renders the admin card (`runtime_admin_required`),
  not a failed read. An observer sees no Pause/Step buttons that 403.
- *Back arrow* goes where the tree places the card (a bare node has no
  Interact).

**Still open**
- `streamConnected` is a poll, not the reasoning stream (§3): the only
  subscriber is Interact's, and opening a second stream here is a design call,
  not a fix.
- The state read on a mutating POST route, and `GET /runtime/queue` unused:
  `blocked_by: CIRISAgent#1209`.
- `trace:complete:v1` from a stepped round (§6.5) remains an ask.

**Stage:** building → building (every contract confirmed; the flow is unwritten
and `runtime_pipeline` / `runtime_idle` / `runtime_loading` are `proposed:`).
