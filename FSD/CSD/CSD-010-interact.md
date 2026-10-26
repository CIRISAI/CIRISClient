# CSD-010 — Interact (the agent conversation, in Just me › Chats)

**CSD**: CSD-010 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
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
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
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
    blocked_by: [CIRISAgent#1210, CIRISConstitution#106]
  - ceg: dma:pdma:*
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the ≈ row in the timeline — the four DMA verdicts on this turn"
    tag: "proposed:interact_timeline_dma"
    blocked_by: CIRISAgent#1210
  - ceg: conscience:coherence
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the ◎ row — a conscience faculty's verdict on the drafted action"
    tag: "proposed:interact_timeline_conscience"
    blocked_by: CIRISAgent#1210
  - ceg: session:{kind}
    bind: {kind: claim}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "WHICH OF MY DEVICES IS HANDLING THIS — not shown today. The node switcher names a node; it does not say this occurrence holds the exchange."
    tag: "proposed:interact_session_holder"
    blocked_by: CIRISAgent#1210
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

### 2.1 The agent is busy (SHED)

While a resource is at CRITICAL pressure the agent closes admission:
`POST /v1/agent/interact` (and `/message`) answer `503`, `Retry-After: 30`, and
`{"detail": {"error": "resource_shed", "reason": "RESOURCE_SHED", "resources":
[...], "retry_after_seconds": 30}}` (`routes/agent.py` `_raise_if_shed`,
v2.16.2-stable). It is runtime self-protection, not an agent decision, so it is
never rendered with DEFER or REJECT wording.

**Resending cannot duplicate.** The refusal happens in
`base_observer.handle_incoming_message` *before credit is charged and before
any task exists* (`base_observer.py:957-966`), so a shed message was not
admitted. The client therefore waits out `Retry-After` (delta-seconds or an
HTTP-date) and sends the SAME request again — `AgentShed.sendWithShedRetry`,
bounded at 3 sends and 120 s of waiting, every wait at least 1 s. A
`Retry-After` past the budget gives up at once rather than waiting. Any other
503 (handler missing, credit provider down) stays an error.

```yaml
busy:     {tag: chat_shed_notice, renders: "'The agent is busy — retrying in Ns' (mobile.interact_shed_retrying), counting down from Retry-After; the user's bubble stays, no error row"}
gave_up:  {tag: chat_shed_notice, renders: "'The agent is still busy, so your message was not sent…' (mobile.interact_shed_gave_up) with chat_shed_retry and chat_shed_dismiss; the unsent bubble is withdrawn and the text and attachments are back in input_message"}
```

`InteractShedTest` drives both against a real socket; `AgentShedWireTest`
proves the wait honours `Retry-After` in both forms and that a non-SHED 503 is
not resent.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the diagnostics panel's full logs (CIRISClient#249) | `GET /v1/telemetry/logs/download?tail_kib=N` → `application/zip` | CIRISAgent (the AGENT base URL only) | **2.17.0** (CIRISAgent#1326). Asked by the panel's Download here, where the owner session exists (`InteractViewModel.downloadLogsZip`); saved beside the text bundle. 403 / 404 / a failure fall back to the bundle's 32 KiB tails and the panel says which it gave. The route's own row is CSD-029's |
| send a message | `POST /v1/agent/interact` | CIRISAgent (`routes/agent.py:1186` on v2.16.2-stable) | live — `AgentApi.kt:215`, called at `CIRISApiClient.kt:771`. **A SHED 503 is a named, retryable state** (agent 2.15+, CIRISAgent#1242, CIRISClient#173): see §2.1 |
| the paperclip's limits — types, per-file bytes and per-message count for images and documents | `GET /v1/agent/attachments/policy` | CIRISAgent (`routes/agent.py:1350`, `api_attachments.py:123` on `fix/interact-attachments` @ 380455879) | live on that branch — `CIRISApiClient.getAttachmentPolicy`, read when chat opens (`InteractViewModel.loadAttachmentPolicy`) and driving the picker's types, the re-check before send and the count/size pre-refusal (CIRISClient#217). **An older agent answers a bare 404**: the client's own constants (`AttachmentPolicy.CLIENT_FALLBACK`) apply, logged, and attaching is never blocked. The per-file verdicts come back on `POST /v1/agent/interact` as `attachments[]` (complete AND timeout) and are drawn by refusal id under each chip of the sent message (`msg_user_<n>_attachment_<i>`, `..._note`) |
| the transcript | `GET /v1/agent/history` | CIRISAgent (`routes/agent.py:981`) | live — `AgentApi.kt:113`; `nodeSkip`-guarded at `CIRISApiClient.kt:798`, so a bare node returns an empty list rather than 404ing |
| the reasoning timeline | `GET /v1/system/runtime/reasoning-stream` (SSE) | CIRISAgent (`routes/system_extensions.py:922`) | live, but **not** a `trace:complete:v1` envelope — see §2 |
| the cognitive state | `GET /v1/system/health` | CIRISAgent, merged by the node (`CIRISServer src/health.rs:589`) | live — the one route both hosts answer |
| stop everything | `POST /v1/system/shutdown` | CIRISAgent (`routes/system/shutdown.py:41`) | live — and note `btn_emergency_stop` calls the GRACEFUL route (`CIRISApiClient.kt:6351`), not `POST /emergency/shutdown` (`routes/emergency.py:174`, mounted outside the `/v1` prefix at `app.py:386`). A control labelled emergency that takes the graceful path is a naming mismatch worth settling before someone needs it. |
| which occurrence holds this exchange | **no route** — `session:claim:v1` is CC 3.1.3.1, owned by CIRISPersist | CIRISPersist | **missing**; blocks `building` for `proposed:interact_session_holder` |
| a signed trace for a turn | **no route** — `/v1/system/runtime/reasoning-stream` carries symbols, not envelopes | CIRISAgent | **missing**; blocks `building` for the three `trace`/`dma`/`conscience` rows |

**Reads this screen makes for the instruments it hosts.** Interact is the
shell's landing screen, so its `when` arm reaches the status bar, the cell
visualisation and the post-login bootstrap. Each row below is a READ whose card
is elsewhere; it is cited here because this screen calls it (route map,
2026-09-27; agent handlers on `main` 29371660de under
`ciris_engine/logic/adapters/api/routes/`, node handlers in CIRISServer `src/`).

| value | endpoint | owner | state |
|---|---|---|---|
| credits in the status bar (`btn_credits`, CIRIS-proxy installs only); blocks sending when out — the card is CSD-056 | `GET /v1/api/billing/credits` | CIRISAgent `billing.py:643` | called — `InteractViewModel.kt:1196`. A failed read leaves the badge stale or hidden (`isLoaded` keeps its old value, `:1206`); it never shows 0 |
| "recent task completions" when a gratitude mote is tapped — the card is CSD-071 | `GET /v1/audit/entries` | CIRISAgent `audit.py:781` | called — `InteractViewModel.kt:2516`. **Defect:** a failure renders "No recent task completions" (`:2520`) — error as empty |
| memory motes / legacy cylinder nodes — the card is CSD-028 | `GET /v1/memory/timeline` | CIRISAgent `memory.py:488` | called — `graph/CellVisualization.kt:425`, `graph/LiveGraphBackground.kt:303`. No `nodeSkip` guard: a bare node 404s and the motes stay empty |
| a tapped memory mote's type, scope, attributes — the card is CSD-027 | `GET /v1/memory/{id}` | CIRISAgent `memory.py:817` | called — `InteractViewModel.kt:2489`. `createdAt` is filled from `updatedAt` (`CIRISApiClient.kt:11059`) |
| provider + "on the CIRIS proxy?" (decides the credits badge and send-blocking) — the card is CSD-021 | `GET /v1/setup/config` | CIRISAgent `setup/config.py:308` | called — `InteractViewModel.kt:1182` via `getLlmConfig` (`CIRISApiClient.kt:7627`). Not a fallback pair: this supplies provider and URL, the next row supplies the enabled flag; the real fallback is the local `.env` (`InteractViewModel.kt:1215`) |
| CIRIS services enabled? — the card is CSD-021 | `GET /v1/system/llm/ciris-services/status` | CIRISAgent `system/llm_routes.py:1121` | called — `CIRISApiClient.kt:7643` (same `getLlmConfig`). **Defect:** any failure reads as "enabled" (`:10675-10682`) and the proxy test also matches any base URL containing `proxy` (`:7656`) — a BYOK person can be shown a credits badge and blocked |
| the node list in the node-switcher pill (`btn_node_switcher`) — the card is CSD-035 | `GET /v1/setup/owned-nodes` | CIRISServer `auth/bootstrap.rs:1378` (route `:1489`) | called — `NodeSwitcherViewModel.kt:141`, on the NODE URL (correct host). `owner`, `nodes[].key_id`, `nodes[].is_self` match |
| "is this brain still unconfigured?" before promoting a switched node to AGENT — the card is CSD-080 | `GET /v1/setup/status` | CIRISAgent `setup/status.py:45`; node `auth/bootstrap.rs:1488` | called — `NodeSwitcherViewModel.kt:355`. The node answers loopback only, so after switching to a remote node it defaults to "configured" |
| none — dead branch | `GET /v1/setup/verify-status` | CIRISAgent `setup/attestation.py:292` | **never reached**: the branch at `CIRISApiClient.kt:7136-7138` runs only with a Play Integrity token and no caller passes one. Delete the branch; this row goes with it |
| a tapped adapter port's status and counts — the card is CSD-020 | `GET /v1/system/adapters/{id}` | CIRISAgent `system/adapters.py:807` | called — `InteractViewModel.kt:2394`; a failure is `SelectionDetail.Error` (distinct) |
| the adapter ports the cell visualisation draws, loaded by the post-login bootstrap — the card is CSD-020 | `GET /v1/system/adapters` | CIRISAgent `system/adapters.py` | called — `InteractViewModel.kt:693` (`listAdapters`); moved here from CSD-081 in the identity review |
| the LLM bus arc's "n/m providers · req" — the card is CSD-021 | `GET /v1/system/llm/status` | CIRISAgent `system/llm_routes.py:327` | called — `InteractViewModel.kt:2415`; fields match `llm_schemas.py:124-139`; a failure falls back to the bare label |
| provider rows when the LLM arc is tapped — the card is CSD-021 | `GET /v1/system/llm/providers` | CIRISAgent `system/llm_routes.py:399` | called — `InteractViewModel.kt:2416`; fields match `llm_schemas.py:109-114`. **Defect:** a failure shows an empty list |
| the trust shield (`btn_trust_shield`) and the fast poll while attestation is pending — the card is CSD-052 | `GET /v1/system/verify-status` | CIRISServer `health.rs:612` (route `:683`); the agent relays (`auth_proxy.py:157`) | called — `InteractViewModel.kt:1151, 1234`. The node ignores `?refresh=true` |
| a tapped non-LLM bus arc's health and counts — the card is CSD-030 | `GET /v1/telemetry/unified` | CIRISAgent `telemetry.py:1854` | called — `InteractViewModel.kt:2443`. **Defect:** any failure is swallowed into zero counts (`CIRISApiClient.kt:9994`) |
| the pending-deferrals banner count (tap opens Wise Authority) — the card is CSD-041 | `GET /v1/wa/status` | CIRISAgent `wa.py:440` | called — `InteractViewModel.kt:1249`; `pending_deferrals` matches; a failure keeps the previous count |
| the wallet badge (`btn_wallet_badge`) — the card is CSD-057 | `GET /v1/wallet/status` | CIRISAgent `wallet.py:570` | called — `InteractViewModel.kt:705`. **Closed 2026-09-27:** a failed read used to render "set up wallet" (`hasWallet=false`); it is now `WalletBadgeState.NOT_READ` ("wallet not read", amber, the badge's `testable` value names the state; `WalletBadgeStateTest`) |
| the fleet-coherence cell signal (composite, fragility, category) — the card is CSD-004 | `GET /v1/my-data/capacity` | CIRISAgent `my_data.py` (`_compute_local_capacity`) | called — `InteractViewModel.kt:630` (`refreshCapacity`, agent-only, skipped in node mode); moved here from CSD-081 in the identity review |
| report a message (`btn_moderation_*` → `sheet_moderation_proposal`) — the card is CSD-065 | `POST /v1/safety/reports` | **no handler on either host** | called — `ModerationViewModel.kt:96` → `CIRISApiClient.kt:1778`, and it fails every time: the agent forwards the unknown path to the node, which 404s. `blocked_by: CIRISServer#665`. Two more defects for CSD-065: the body puts a chat MESSAGE id into `target_key_id` (`InteractScreen.kt:1924` → `CIRISApiClient.kt:1784`), and the adult-only gate is not enforced (`ModerationViewModel.kt:55`) |

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

**On a phone the composer owns the bottom edge (CIRISClient#186).** Below
the 900 dp rail the shell hides its circle bar (`shell_bottom_bar`) on this
screen — `screenHostsComposer` in `CIRISApp.kt` names Interact and a user/pair
chat — and keeps the top bar and the one back. The screen's own content pads by
the soft keyboard (`composerImePadding`, `ui/shell/ComposerInsets.kt`), not the
whole app, and the transcript scrolls to its newest message when the keyboard
opens. At rail width nothing changes. Pinned headless at 360 x 640 dp with a
300 dp keyboard: `ChatComposerOnPhoneTest`. **Not verified on a device:** iOS
does not compile on the Linux box this was written on, and Compose iOS's default
`OnFocusBehavior.FocusableAboveKeyboard` pans the view only when the focused
field would still be covered; with the inset applied it should not be.

**Stated limit.** This screen does not show a verifiable trace. It shows that
reasoning occurred and in what order. Until a route carries `trace:complete:v1`
the honest sentence is that one, and the CSD says so rather than letting the
timeline imply attestation.
