# CSD-080 — Startup (the screen before there is a shell)

**CSD**: CSD-080 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10 (the paths every new user takes)
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**While the substrate comes up, the screen says what is happening; when it
cannot come up, it says that instead of looking like a slow success.** Startup
is the only surface a person sees before any circle, tab or instrument exists,
and everything it renders is the node's own report about itself. Serves
**CC 3.4.5.1** (a self-report is testimony, not proof): the client repeats the
node's liveness count verbatim and never invents one. The principle CSD-003
states applies here in its strongest form — a surface that renders a failed read
as a quiet one tells the user a more flattering thing than the truth, and on
this screen there is no other screen to correct it from.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: Startup
flow_only: true
entry: "cold start — the router's initial value (`mutableStateOf<Screen>(Screen.Startup)`, CIRISApp.kt:484)"
exit: "Login; or Setup directly under the HA addon"
```

**`flow_only: true` is checked in both directions**, which is the point of it:
`Screen.Startup` must be declared in `CIRISApp.kt` (it is —
`object Startup : Screen()`, line 5710), must **not** be sidebar-reachable, and
must say how a person arrives, since no hop can
(`check_csd_v3.py`, `SURFACE_KEYS` and the `flow_only` branch).

**It got there the hard way, and the history is worth keeping.** These eight
cards first wrote `screen_class:` — a key the checker did not read, so
`surface.get("screen")` returned `None` and both reachability branches were
skipped. Measured on CSD-035, a genuinely placed card, one line changed per run:

```
screen_class: ManageNodes  ->  [OK]     # the intended use
screen_class: Telemetry    ->  [OK]     # a CSD asserting Nodes-is-Telemetry, green
screeen: ManageNodes       ->  [OK]     # an ordinary typo, identical result
screen: Telemetry          ->  [FAIL] surface: 'nodes' derives 'nav_epistemic_nodes' but
                                       Screen.Telemetry is reached via 'nav_epistemic_telemetry'
```

A wrong screen passed, and a fat-finger was indistinguishable from intent —
AGENTS.md's loud-failure rule at the key-name layer. `SURFACE_KEYS` now refuses
any key it does not read, so all three of those greens are red.

**There is no nav hop, and that is the defining property of this card.**
`testing/gate/nav_map.py` derives hops from `CirclesNav.kt` placements and the
`NavSurface.X -> Screen.Y` table; `Screen.Startup` is in neither, and
`CIRISApp.kt:1789-1792` suppresses the sidebar entirely while it is on screen.
The gate names the same set: `Startup` is in `FLOW_ONLY`
(`testing/gate/screen_atlas.py:42`) and in `docs/atlas.json`'s `flow_only`.

CSD/3 §2.0 assumed every surface was in the sidebar and had no shape for a
pre-shell one. `flow_only:` is that shape, and all eight cards in the
CSD-080..087 range carry it. What it still cannot be cross-checked against is in
§5.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "health:liveness:{version}"
    bind: {version: v1}
    use: display-only
    type: string
    example: "18/22"
    renders: "18/22 services online — the node's own count, repeated, never derived"
    tag: "proposed:txt_startup_services"
    assert:
      matches: {proposed:txt_startup_services: "^[0-9]+/[0-9]+$"}
  - ceg: x_private:startup_phase
    use: display-only
    type: "enum[INITIALIZING,LOADING_RUNTIME,PREPARING,VERIFYING,STARTING_SERVER,WAITING_SERVER,LOADING_SERVICES,CHECKING_CONFIG,FIRST_RUN_SETUP,AUTHENTICATING,WAITING_FOR_AGENT,READY,ERROR]"
    example: "WAITING_SERVER"
    renders: "WAITING FOR BACKEND — the phase word, teal; yellow at FIRST-TIME SETUP, green at READY, red at ERROR"
    tag: "proposed:txt_startup_phase"
  - ceg: x_private:client_mode
    use: display-only
    type: "enum[NODE,AGENT,unprobed]"
    example: "NODE"
    renders: "on NODE the 22 cognitive-service lights are ABSENT, not zeroed — there is no brain to be 0/22 of"
    tag: "proposed:row_startup_service_lights"
  - ceg: x_private:startup_elapsed_seconds
    use: display-only
    type: int
    example: 7
    renders: "7.0s"
    tag: "proposed:txt_startup_elapsed"
  - ceg: x_private:setup_required
    use: display-only
    type: bool
    example: true
    renders: "not drawn; it is the branch — true routes to Login (or to Setup under the HA addon), false to the stored-token path"
    tag: "proposed:txt_startup_setup_required"
  - ceg: x_private:startup_error
    use: display-only
    type: string
    example: "Backend unreachable. Please restart the app."
    renders: "ENGINE FAILED in red, the message, then Retry ABOVE the debug block — the control is above the fold, the diagnosis below it"
    tag: "proposed:txt_startup_error"
```

**Only two tags on this screen are real**: `screen_startup`
(`StartupScreen.kt:148`) and `btn_startup_retry` (`StartupScreen.kt:357`). The
phase word, the elapsed counter, the prep lights, the service lights and the
service count are all untagged, so every value this screen exists to show is
`proposed:`. That is the honest state and it is also the gap: `screen_startup`
was added because "/tree is empty" was indistinguishable from "nothing
composed", and the same argument applies one level down — a harness can today
assert that Startup composed, and nothing about what it says.

```yaml csd:states
populated: {tag: screen_startup, renders: "phase word, elapsed counter, prep lights, and — on AGENT — the 22 service lights with a count"}
empty:     {tag: "proposed:txt_startup_no_services", renders: "NODE build: 'This node has no assistant services.' The 22-light row is absent rather than rendered at 0/22 — a zero is a score, an absence is a fact"}
loading:   {tag: screen_startup, renders: "the lights counting up with NO error block and NO retry control — loading is this screen's normal state, which is why error must look different"}
error:     {tag: btn_startup_retry, renders: "'CIRIS engine failed to start', the message, Retry; plus proposed:txt_startup_error for the sentence"}
```

`error` and `loading` are the pair that must not look alike here, not `error`
and `empty`: a startup that is merely slow and a startup that has failed are the
same picture until the red block appears, and the retry control is the only
element that exists in one and not the other.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| liveness + service map + `data.agent.{folded,reachable}` | `GET /v1/system/health` | CIRISServer (`src/health.rs`, 0.5.168+) | live |
| `ClientMode` (NODE / AGENT / undetermined) | derived from the above, `models/ClientMode.kt` | CIRISClient | live |
| is setup still required | `GET /v1/setup/status` | **both**: CIRISAgent `routes/setup/status.py:45` and CIRISServer `src/auth/bootstrap.rs` | live on the agent; **loopback-only on the node** (`src/auth/loopback.rs:44`, 403 off-host) |
| "the node answers, so setup is done" | node reachability probe on `:4243` | CIRISClient | live — `CIRISApp.kt:5051-5078` stops asking `:8080` after the run-without-AI hand-off (CIRISClient#48, still open) |

**Two upstream defects land on this screen.**
`CIRIS_FORCE_FIRST_RUN` is re-read on every `/v1/setup/status` call, so
`setup_required` never clears and Startup re-opens the wizard in a loop —
**CIRISAgent#1195**, five rounds observed. And a client that did not launch the
node guesses `<home>/claim_pin` because the status does not declare it —
**CIRISAgent#1196**; it does not break Startup, but it is the reason the claim
this screen hands off to silently skips.

## 4. Flow (how)

Cold start with the backend up.

```yaml
expect:
  state: loading
  visible: [screen_startup]
  absent: [btn_startup_retry]
```

Cold start with the backend down for longer than the 60 × 500 ms budget.

```yaml
expect:
  state: error
  visible: [screen_startup, btn_startup_retry]
```

Press `btn_startup_retry` with the backend now up → the error block clears and
the phase advances; the screen leaves for `Login` (or `Setup` under the HA
addon) without a further press.

## 5. QA plan

**Platforms.** All five. Startup is the first screen on every one of them and
`screen_startup` is what `wait_for_ui` keys off (`testing/gate/session_fixture.py:145`).

**Not tested here.** The phase sequence (untagged — a flow can assert arrival at
Startup and nothing about what it says); the prep and service lights (untagged);
the language rotation; the HA-addon branch (no fixture). Every `proposed:` tag
above is a one-line `.testable(...)` away and none of them is asserted until
they land.

**What is still open, and it is not in this document.** `flow_only:` now has a
second source that disagrees with it. `EpistemicNav.kt:395` lists five ids —
`startup`, `login`, `setup`, `server-connection`, `accord-ceremony` — and its
KDoc calls it "a single authoritative inventory for the no-orphans test in
`CirclesNavTest`". It is not: `ClaimNode`, `AddFederationId` and `VerifyAgent`
have **no `NavSurface` id at all**, so they are a third category the list cannot
model, and `flow_only:` has nothing to cross-check against. **Ask
(CIRISClient)**: either widen `FLOW_ONLY_SURFACES` to cover screens with no
surface id, or say in its KDoc that it inventories unplaced *surfaces* only. An
"authoritative inventory" that is silently partial is the same defect as a
parser returning zero and reporting green — and it is now the only thing
standing between `flow_only:` and being checked against the client itself
rather than only against the hop map.
