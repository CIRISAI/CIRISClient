# CSD-011 — Cognitive Sessions (the agent's own state, under This node)

**CSD**: CSD-011 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**The owner of a node can see what the brain is doing instead of working, put it
into DREAM, PLAY or SOLITUDE deliberately, and bring it back — and the screen
must never claim a state it did not read.** It is an instrument and not a
circle's card because the audience is the operator, not a cohort: no other
party is entitled to an opinion about whether this agent is dreaming. Serves
**Fidelity** (CC 3.1.5.2): a state banner is a claim about the agent, and
CC 3.4.5's self-report discipline is that a third-party assertion of what you
are running is a rumour — a *client* assertion of it, with nothing read, is
worse.

The falsifiable claim, and it fails today: **a state shown on this screen was
read from the node in the last poll.** `SessionsViewModel._currentState` is
seeded `"WORK"` (`SessionsViewModel.kt:52`) and `SessionsScreen` takes no error
parameter (`SessionsScreen.kt:43-50`), so a screen that has never successfully
read anything renders a confident "WORK".

**The name is wrong and this CSD says so up front.** CC 3.1.3.1 gives
`session:*` a settled meaning — *which occurrence of a self is handling an
addressed, stateful exchange* — a routing table over a person's devices, keyed
`(community, session)`, ceiling `Cohort`. That is a **Devices & keys** concern
about the person. This screen is the agent's mood. The two share a word and
nothing else, and the surface id `sessions` is the one a later reader will
search for.

## 2. Surface (what)

```yaml csd:surface
surface: sessions
screen: Sessions
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_sessions`. `agentOnly` (`CirclesNav.kt:154`), so a
run-without-AI install never lists the row — correctly, because every route
below is the brain's.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:cognitive_state
    use: display-only
    type: "enum[WORK,DREAM,PLAY,SOLITUDE,WAKEUP,SHUTDOWN,UNKNOWN]"
    example: "WORK"
    renders: "the banner at the top: the state, with the colour the state carries"
    tag: "proposed:sessions_state_banner"
  - ceg: x_private:previous_cognitive_state
    use: display-only
    type: string
    example: "DREAM"
    renders: "what it was before the last transition — held in the view model, drawn nowhere"
    tag: "proposed:sessions_previous_state"
  - ceg: x_private:transition_target
    use: emit
    type: "enum[DREAM,PLAY,SOLITUDE,WORK]"
    example: "DREAM"
    renders: "the confirm dialog names the state and what it costs before it is requested"
    tag: btn_confirm_session
  - ceg: session:{kind}
    bind: {kind: claim}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT ON THIS SCREEN. The registry family this surface is named after is about which of the person's occurrences holds an exchange (CC 3.1.3.1); nothing here reads or writes it."
    tag: "proposed:sessions_occurrence_claim"
    blocked_by: CIRISAgent#1210
```

**The state vocabulary cannot be a CEG segment, and that is a fact about the
registry rather than a client shortcut.** `WORK` / `DREAM` / `PLAY` /
`SOLITUDE` fail `vocab_pattern` `^[a-z0-9][a-z0-9_.-]*$`, and
`_meta.case_rule.compare` is `byte-exact; consumers MUST NOT case-fold`. So
these four values are `x_private:` by necessity, not by preference, until
something registers them in a writable case.

```yaml csd:states
populated: {tag: "proposed:sessions_state_banner", renders: "the banner plus three session cards, each enabled only from WORK"}
empty:     {tag: "proposed:sessions_state_unknown", renders: "the honest banner for a state that was never read: Not read yet — NOT the word WORK"}
loading:   {tag: "proposed:sessions_loading", renders: "the refresh affordance disabled; the banner keeps the LAST READ value and says how old it is"}
error:     {tag: "proposed:sessions_error", renders: "Could not read the agent's state. — the view model already composes four distinct messages (SessionsViewModel.kt:110, :199, :214) and NONE of them reaches a composable"}
```

**`error` is not distinguishable from `empty` here because neither exists.**
This is the CSD/3 §2.2 failure in its exact form: a failed read renders as a
flattering one. The work is not missing — `_errorMessage`, `_statusMessage`,
`mobile.sessions_error_fetch_failed` and `mobile.sessions_error_invalid_state`
are all written and localized. The wire from `SessionsViewModel` to
`SessionsScreen` was never run: `CIRISApp.kt:3274` passes `currentState`,
`isLoading` and three callbacks, and stops.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the current cognitive state | `GET /v1/system/health` | CIRISAgent (`routes/system/health.py:166`) | live — read via `getSystemStatus` (`CIRISApiClient.kt:5929`), polled every 3 s (`SessionsViewModel.kt:31`) |
| request a transition | `POST /v1/system/state/transition` | CIRISAgent (`routes/system/runtime.py:94`) | live — `SystemApi.kt:905`, via `transitionCognitiveState` (`CIRISApiClient.kt:6359`) |
| which occurrence holds an exchange | **no route** — `session:claim:v1`, CC 3.1.3.1 | CIRISPersist | **missing**, and not this screen's job; recorded so the name collision is on the record |

**Host check.** Both routes are the brain's. `CIRISServer origin/main` serves
`/v1/system/health` (`src/health.rs:589`, merged with the folded brain's) but
has **no** `/v1/system/state/transition` — a grep of every `"/v1/..."` literal
in `src/` returns 213 routes and none of them is it. The card being `agentOnly`
is what keeps that from mattering.

**CIRISAgent working tree is dated 2026-08-15** (`git -C ~/CIRISAgent log -1`),
five weeks behind today, so "live" above means "present in that checkout".

## 4. Flow (how)

My things → This node → Sessions, on an agent in WORK.

```yaml
expect:
  state: populated
  visible: [btn_sessions_refresh, btn_initiate_dream, btn_initiate_play, btn_initiate_solitude]
  absent: [btn_return_to_work]
```

Click `btn_initiate_dream`, then `btn_confirm_session`.

```yaml
expect:
  visible: [btn_return_to_work]
  text: {proposed:sessions_state_banner: "DREAM"}
```

Point the client at a node whose brain is stopped and refresh.

```yaml
expect:
  state: error
  visible: [proposed:sessions_error]
  absent: [proposed:sessions_state_banner]
```

**That third step is the one that matters and it fails today** — the screen
stays on its seeded "WORK". It is written here so the fix has a target.

## 5. QA plan

**Platforms.** All five. Nothing here is platform-specific; the three cards and
the dialog are plain Compose.

**Not tested here.** Whether the agent actually dreams — the screen asserts the
transition was accepted, not that the cognitive loop did anything with it. The
3 s poller's behaviour across app backgrounding.

**Upstream ask.** None. Every gap on this surface is CIRISClient's own: tag the
banner, pass the error through, and stop seeding a state nobody read.
