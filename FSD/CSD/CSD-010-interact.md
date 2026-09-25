# CSD-010 — Interact (the agent conversation, in Just me › Chats)

**CSD**: CSD-010 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person talks to the agent they run, sees what it did and why, and can stop
it — and when there is no agent the circle says so instead of saying nothing
happened.** This is the one card in Just me › Chats, and Just me is the right
circle for an unarguable reason: the exchange is between the person and a key
they own, so nobody else is a party to it. Serves **Fidelity** (CC 3.1.5.2 —
"Be Honest"): the transcript, the reasoning timeline and the hallucination
banner are the same commitment at three grains, and the screen is where CC
3.1.5's `trace:*` is either delivered to the person or quietly not.

The falsifiable claim: **every agent turn on this screen is addressable by tag,
and every one of them can be traced back to the reasoning that produced it or
says it cannot be.** `msg_agent_0` exists because before `ChatTranscriptTags.kt`
the product's core feature was the one thing automation could not assert
(CIRISClient#27).

## 2. Surface (what)

```yaml csd:surface
surface: interact
screen: Interact
```

`nav_map` derives `circle_agent -> tab_chats`: Interact is the only card the
Chats tab holds in Just me, so the shell shows it directly and the chain ends on
the tab (`CirclesNav.kt:87`, `Placement(NavSurface.Interact, Tab.CHATS,
setOf(AGENT), agentOnly = true)`).

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:agent_reply_text
    use: display-only
    type: string
    example: "I can help with that — here is what I found."
    renders: "an agent bubble in the transcript, oldest first"
    tag: msg_agent_0
  - ceg: x_private:user_message_text
    use: emit
    type: string
    example: "what did you decide about the ticket?"
    renders: "the person's own bubble, echoed back from the same list the agent's reply lands in"
    tag: msg_user_0
  - ceg: trace:{form}:{version}
    bind: {form: complete, version: v1}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the reasoning timeline: one row per pipeline step (thought_start ❓, snapshot_and_context ▶, dma_results ≈, conscience_result ◎, action_result ⚠)"
    tag: "proposed:interact_timeline_row"
  - ceg: dma:pdma:*
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the ≈ row in the timeline — the four DMA verdicts on this turn"
    tag: "proposed:interact_timeline_dma"
  - ceg: conscience:coherence
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the ◎ row — a conscience faculty's verdict on the drafted action"
    tag: "proposed:interact_timeline_conscience"
  - ceg: session:{kind}
    bind: {kind: claim}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "WHICH OF MY DEVICES IS HANDLING THIS — not shown today. The node switcher names a node; it does not say this occurrence holds the exchange."
    tag: "proposed:interact_session_holder"
  - ceg: x_private:cognitive_state
    use: display-only
    type: "enum[WORK,DREAM,PLAY,SOLITUDE,WAKEUP,SHUTDOWN]"
    example: "WORK"
    renders: "the state signet in the bar — tap opens Cognitive Sessions (CSD-011)"
    tag: btn_agent_state
  - ceg: x_private:pending_deferrals
    use: display-only
    type: int
    example: 2
    renders: "a banner: human review requests waiting; tapping goes to Someone I trust"
    tag: "proposed:interact_pending_deferrals"
  - ceg: capacity:composite
    use: display-only
    type: float
    example: 0.68
    renders: "the cell visualization's health reading, drawn behind the transcript"
    tag: "proposed:interact_cellviz"
```

**The transcript tags are real and exhaustive by construction.**
`transcriptRole` (`ChatTranscriptTags.kt:14`) is a `when` with no `else`, so a
sixth `MessageType` is a compile error rather than an untagged row. The scheme
is `msg_<role>_<n>` counted per role from the oldest (`ChatTranscriptTags.kt:71`),
which is why `msg_agent_0` stays addressable however many ACTION rows the agent
interleaved ahead of it.

**Everything under `trace:*`, `dma:*` and `conscience:*` is `unconfirmed`, and
that is the honest reading of the wire.** The timeline is drawn from the SSE
symbol stream at `/v1/system/runtime/reasoning-stream`
(`ReasoningStreamClient.kt:77`), which emits an event *name* and a symbol — it
is not a `trace:complete:v1` envelope and carries no `trace_id`,
`agent_id_hash`, or producer signature. So the screen shows that reasoning
happened, in order; it does not show the attested artifact CC 3.1.5 names. A
person cannot verify from this screen that the timeline they are reading is the
trace that was signed.

**`capacity:composite` is display-only and must stay that way.** CC 3.4.5's
composition-context rule forbids placing a `scores` row whose `subject_key_ids`
include a party into any automated loop that selects that party's next action.
Drawing the composite behind the transcript is a read by the *person*; feeding
it back into the agent's context would be the violation. The client reads it at
`InteractViewModel.kt:616` and renders it into `cellVizState` only.

```yaml csd:states
populated: {tag: interact_transcript, renders: "the list of bubbles; the AI-hallucination banner sits above it permanently"}
empty:     {tag: "proposed:interact_empty", renders: "the first-run panel — EmptyStateView (InteractScreen.kt:1689) carries NO tag today, so an empty transcript is invisible to /tree"}
loading:   {tag: "proposed:interact_thinking", renders: "the processing indicator; NOT the empty panel"}
error:     {tag: msg_error_0, renders: "a red centred row IN the transcript — MessageType.ERROR routes through the same tag scheme, so the failure is a row the gate can name"}
```

`error` and `empty` cannot look alike here for a structural reason rather than a
cosmetic one: an error is a transcript row (`ErrorMessage`,
`InteractScreen.kt:2079`) and the empty state is the absence of the transcript.
The defect is on the other side — **the empty panel has no tag at all**, so
"the agent has never spoken to me" and "the transcript failed to compose" are
the same observation to the gate.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| send a message | `POST /v1/agent/interact` | CIRISAgent (`routes/agent.py:841`) | live — `AgentApi.kt:215`, called at `CIRISApiClient.kt:770` |
| the transcript | `GET /v1/agent/history` | CIRISAgent (`routes/agent.py:981`) | live — `AgentApi.kt:113`; `nodeSkip`-guarded at `CIRISApiClient.kt:798`, so a bare node returns an empty list rather than 404ing |
| the reasoning timeline | `GET /v1/system/runtime/reasoning-stream` (SSE) | CIRISAgent (`routes/system_extensions.py:922`) | live, but **not** a `trace:complete:v1` envelope — see §2 |
| the cognitive state | `GET /v1/system/health` | CIRISAgent, merged by the node (`CIRISServer src/health.rs:589`) | live — the one route both hosts answer |
| stop everything | `POST /v1/system/shutdown` | CIRISAgent (`routes/system/shutdown.py:41`) | live — and note `btn_emergency_stop` calls the GRACEFUL route (`CIRISApiClient.kt:6351`), not `POST /emergency/shutdown` (`routes/emergency.py:174`, mounted outside the `/v1` prefix at `app.py:386`). A control labelled emergency that takes the graceful path is a naming mismatch worth settling before someone needs it. |
| which occurrence holds this exchange | **no route** — `session:claim:v1` is CC 3.1.3.1, owned by CIRISPersist | CIRISPersist | **missing**; blocks `building` for `proposed:interact_session_holder` |
| a signed trace for a turn | **no route** — `/v1/system/runtime/reasoning-stream` carries symbols, not envelopes | CIRISAgent | **missing**; blocks `building` for the three `trace`/`dma`/`conscience` rows |

**Wrong-host risk, stated once.** Every route above is the AGENT's. The client
addresses the brain on `:8080` and the node on `:4243` and never conflates them
(`CIRISApp.kt:338-347`). On a run-without-AI install `syncBackendFromEnv`
re-points the agent client at the node, and `/v1/agent/*` does not exist there —
which is exactly why `getMessages` is `nodeSkip`-guarded and `sendMessage` is
not reachable, because the card is `agentOnly`.

## 4. Flow (how)

Sign in on an agent; land on `Interact`.

```yaml
expect:
  state: populated
  visible: [interact_transcript, input_message, btn_send, btn_agent_state]
```

Type into `input_message`, click `btn_send`, wait for the reply.

```yaml
expect:
  count: {of: "msg_user_*", min: 1}
  count: {of: "msg_agent_*", min: 1}
```

Open the reasoning timeline: click `btn_toggle_timeline`.

```yaml
expect:
  visible: [btn_clear_timeline]
```

On a node install the card is not listed at all: Just me › Chats renders
`nav.empty.chats`.

```yaml
expect:
  state: empty
  absent: [nav_epistemic_interact]
```

## 5. QA plan

**Platforms.** All five. Interact is what CIRISAgent's five-platform gate leans
on, and `msg_agent_0` is the assertion it was missing.

**Not tested here.** The reasoning stream's ordering under reconnect (SSE resume
is not specified); the cell visualization's rendering (a canvas, not a tag
tree); the emergency-stop path, which takes the runtime down and cannot run in
a suite that has later steps.

**Stated limit.** This screen does not show a verifiable trace. It shows that
reasoning occurred and in what order. Until a route carries `trace:complete:v1`
the honest sentence is that one, and the CSD says so rather than letting the
timeline imply attestation.
