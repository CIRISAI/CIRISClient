# CSD-083 — Setup, the run-without-AI pass (You → Join the federation → Complete)

**CSD**: CSD-083 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10 (the setup wizard)
**Pairs with**: CSD-082 (the with-AI pass through the same screen)
**Flow**: `testing/flows/drafts/csd-083-setup-without-ai.yaml` (floor `>=0.5.224`); `session_fixture` drives only the with-AI pass, so this one has no fixture

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Running without an assistant is a first-class way to use CIRIS, and the wizard
says so in those words rather than framing it as a downgrade.** Federation,
consent and your own data all work; what is absent is a brain. Serves
**CC 3.2** — authority roots in an accountable human, never in a bare node, and
nothing about that requires a model. A node-only install is a full member of the
federation: it has an owner, a fed-ID, consent grants and a record.

This is a separate card from CSD-082 for one reason: **the two passes end on
different backends.** Answering "without an AI assistant" writes
`CIRIS_RUN_WITHOUT_AI=true`, and from the next boot the client talks to the node
on `:4243` instead of the agent on `:8080`
(`platform/BackendEndpoint.kt:83`). Six of this repo's closed and open bugs live
in that hand-off, and none of them can be seen on the with-AI pass.

**The question is asked first, and asked once.** It used to be a switch on the
LLM screen — so the only way to say "no AI" was to visit the screen that
configures one, and the wizard then walked you through choosing a provider you
had just said you did not want. Now the answer decides whether that screen
exists at all (`viewmodels/SetupState.kt:117`,
`hasAiStep = hasAgent && !runWithoutAi`).

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: Setup
flow_only: true
entry: "Login `btn_local_login` on a first-run node, then `opt_run_without_ai` on step 1 — the same Screen as CSD-082, entered the same way and answered differently"
exit: "Login, then the node home (Contacts) — the hand-off re-points the client from the agent on :8080 to the node on :4243"
```

No nav hop; CSD-080 §2 records why `flow_only:` is a checked key.
**Two steps**, not three:
`YOU → JOIN_FEDERATION → COMPLETE`, with "Finish" on `JOIN_FEDERATION`
(`isFinalSetupStep`, `SetupState.kt:156`). The step rail renders two dots —
"a third dot for a screen the wizard will skip is a progress bar that lies
about how much is left" (`SetupScreen.kt:674`).

**The node client reaches the same two steps by a different route.** A brainless
node (`hasAgent = false`, from the probed `ClientMode`) has no AI screen to skip;
the person was never asked. Both collapse to one predicate deliberately, and the
distinction survives in the copy, not in the step machine.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:run_without_ai
    use: emit
    type: bool
    example: true
    renders: "'Without an AI assistant — The node runs on its own: federation, consent and your own data all work. You can add a provider later from Settings.' Neither option is preselected as a recommendation"
    tag: opt_run_without_ai
  - ceg: x_private:backend_endpoint
    use: display-only
    type: "enum[agent:8080,node-only:4243]"
    example: "node-only:4243"
    renders: "not drawn anywhere — and that is the defect. The answer above silently re-points every subsequent request, and the only surface that reports which backend is live is the Login status chip"
    tag: "proposed:txt_setup_backend"
  - ceg: "age_self_declared:{band}:{version}"
    bind: {band: adult, version: v1}
    use: emit
    type: "enum[adult,minor,declined]"
    example: "adult"
    renders: "identical to CSD-082 — the band is a property of the person, not of the install"
    tag: age_band_adult
    assert:
      one_of: {age_band_adult: [adult, minor, declined]}
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: bool
    example: false
    renders: "the send-traces question. A node-only install has no reasoning traces to send, and the question is still asked in the substrate's words — the grant is about replication, not about a brain"
    tag: trace_consent_no
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "SYSTEM_ADMIN"
    renders: "'This node is yours.' — the node signs it; the client cannot (CC 3.4.5)"
    tag: setup_ownership_claimed
  - ceg: x_private:setup_step
    use: display-only
    type: "enum[you,join_federation,complete]"
    example: "join_federation"
    renders: "TWO dots. `step_indicator_ai` must be ABSENT, not greyed"
    tag: step_indicator_join_federation
    assert:
      count: {of: "step_indicator_*", eq: 2}
  - ceg: x_private:federation_label
    use: emit
    type: string
    example: "eric-laptop"
    renders: "the fed-ID name — required on this pass exactly as on the other; the self-claim 503s without a responsible-user identity"
    tag: input_fedid_label
```

```yaml csd:states
populated: {tag: setup_step_indicators, renders: "the rail with TWO dots and the current step's form"}
empty:     {tag: setup_consent_empty, renders: "no `replication` grant in the disclosure: the step says there is nothing to answer and nothing will be sent, and Finish is live. This mattered most on this pass, where JOIN_FEDERATION is the FINAL step (closed in the setup review, CSD-082 §2)"}
loading:   {tag: setup_ownership_claiming, renders: "'Claiming ownership of this node…', no advance control"}
error:     {tag: setup_ownership_error, renders: "the node's reason plus btn_setup_finish_unclaimed — finish unclaimed is a choice the person makes, never one the wizard makes for them"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the hand-off flag | `CIRIS_RUN_WITHOUT_AI` in the home's `.env` | CIRISClient + CIRISAgent | live; the two readers accept **exactly** the same token set on purpose (`BackendEndpoint.kt:117`) |
| which backend answers | `GET :4243/health` (node) vs `GET :8080/v1/system/health` (agent) | CIRISServer / CIRISAgent | live. `:4243/health` serves a constant `"status":"ok"` — refused while coming up, 200 once serving, no intermediate state (CIRISServer#548) |
| write the config and reload | `POST /v1/setup/complete` | **CIRISAgent** (`routes/setup/complete.py:1511` on `main`) | live — and it is the LAST thing the agent does for this install |
| self-claim ownership | `POST /v1/setup/claim-remote` → `POST /v1/setup/root` | CIRISServer (`src/claim_remote.rs`) | live |
| everything after the hand-off | node routes on `:4243` | CIRISServer | live |
| the wizard's agent catalogues | `GET /v1/setup/providers` · `/templates` · `/adapters` · `/adapters/available` · `/tool-disclosure` · `/models` | CIRISAgent `routes/setup/providers.py:27, 38, 49, 113, 61`; `llm_routes.py:93` | live, **correctly not read on this branch** — there is no brain to configure, and the AI step that reads them does not exist here. Since the setup review the with-AI branch DOES read templates, adapters and tool-disclosure (CSD-082 §3), so "this branch skips them" is now distinguishable from "nothing reads them"; `providers` is still a compiled-in list |
| is a node there at all | `GET /v1/identity` (unauthenticated) | CIRISServer `src/compose.rs:3132` | live — the desktop runtime's readiness probe after the hand-off (`PythonRuntime.desktop.kt:673`), see CSD-084 |

**`run_without_ai` must reach `setup/complete`, or the agent never learns.**
CIRISClient#41 (closed) was exactly that: the flag was dropped between the YOU
screen and the completion request and the agent always saw `false`.

**The hand-off is where this pass breaks, and it breaks per platform.** Only
ONE of these is still open: **#51** — a run-without-AI install has no logout
affordance at all (no Agent group ⇒ no Settings ⇒ no `btn_logout`, so Login and
factory reset are unreachable). **#43, #47 and #48 are CLOSED** and were listed
here as open: #43 (the client stayed on `:8080` after the hand-off and never
moved to `:4243`), #47 (a `waitForServices` timeout thrown on the main thread
killed the app — Android FATAL EXCEPTION, iOS SIGABRT), #48 (`checkFirstRunStatus`
polled `:8080` 61 times, then parked on "Backend unreachable" while the client's
own reviver logged a healthy `:4243`; the fix is at `CIRISApp.kt:5066`). All four
remain the regression suite this pass owes, because four of them were found on a
different platform from the one they were fixed on. Also closed and worth the
same test: #66 (macOS stalled on "Restarting your node…" at Starting Services 0/22 — a
node-only backend reports no services), #67 (Windows: session not saved, the
watchdog revived a healthy node, the Login press was silently dropped while the
header said Connected).

### 3.1 Every route this screen calls (generated)

**Why two CSDs for one screen.** CSD-082 and this card share `Screen.Setup` and
therefore every row below. They are two passes, not two doors to one act: the
answer on screen 1 decides which backend the install ends on (§1), and the
defects of each pass are invisible on the other. The route rows are the code's
and identical in both; the flows (`csd-082-*.yaml`, `csd-083-*.yaml`) differ.

<!-- generated: python3 packaging/check_csd_routes.py --print CSD-083 (screen Setup; heuristic) -->
| value | endpoint | owner | state |
|---|---|---|---|
| `authorFederationConsent` | `GET /v1/accord/canonical/servers` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1405` |
| `getCredits` | `GET /v1/api/billing/credits` | CIRISAgent (front door) | called — `viewmodels/BillingViewModel.kt:174` |
| `createDelegation` | `POST /v1/auth/device/delegate` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1004` |
| `login`, `loginToNode` | `POST /v1/auth/login` | CIRISAgent (front door), CIRISServer | called — `CIRISApp.kt:2785, viewmodels/SetupViewModel.kt:1298` |
| `nativeAuth` | `POST /v1/auth/native/apple` | CIRISAgent (front door) | called — `CIRISApp.kt:2732` |
| `nativeAuth` | `POST /v1/auth/native/google` | CIRISAgent (front door) | called — `CIRISApp.kt:2732` |
| `announceOwnership` | `POST /v1/federation/announce` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1367` |
| `authorFederationConsent` | `POST /v1/federation/consent` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1405` |
| `getNodeCode` | `GET /v1/federation/node-code` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1200` |
| `isLocalNodeUp` | `GET /v1/identity` | CIRISServer | called — `viewmodels/SetupViewModel.kt:638` |
| `setAgeSelf` | `POST /v1/self/age` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1342` |
| `associateFedId` | `POST /v1/self/associate` | CIRISServer | called — `viewmodels/SetupViewModel.kt:777` |
| `mintUserIdentity` | `POST /v1/self/identity` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1128, viewmodels/SetupViewModel.kt:719` |
| `inspectKeysetFolder` | `POST /v1/self/identity/inspect` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1533` |
| `getSetupAdapters` | `GET /v1/setup/adapters` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:3516` |
| `claimRemote` | `POST /v1/setup/claim-remote` | CIRISServer | called — `viewmodels/SetupViewModel.kt:1206` |
| `completeSetup` | `POST /v1/setup/complete` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:412, ui/screens/SetupScreen.kt:427` |
| `connectToNode` | `POST /v1/setup/connect-node` | CIRISServer | called — `ui/screens/SetupScreen.kt:3746` |
| `pollNodeAuthStatus` | `GET /v1/setup/connect-node/status` | CIRISServer | called — `ui/screens/SetupScreen.kt:3708` |
| `getConsentDisclosure` | `GET /v1/setup/consent-disclosure` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:867` |
| `discoverLocalLlmServers` | `POST /v1/setup/discover-local-llm` | CIRISAgent (front door) | called — `ui/components/LocalLlmDiscovery.kt:113` |
| `getOwnedNodes` | `GET /v1/setup/owned-nodes` | CIRISServer | called — `viewmodels/NodeSwitcherViewModel.kt:141` |
| `resetDeviceAuthOnServer` | `POST /v1/setup/reset-device-auth` | CIRISServer | called — `ui/screens/SetupScreen.kt:3713` |
| `startLocalLlmServer` | `POST /v1/setup/start-local-server` | CIRISAgent (front door) | called — `ui/components/LocalLlmDiscovery.kt:151` |
| `getSetupTemplates` | `GET /v1/setup/templates` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:3513` |
| `getSetupToolDisclosure` | `GET /v1/setup/tool-disclosure` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:3519` |
| `listAdapters` | `GET /v1/system/adapters` | CIRISAgent (front door) | called — `viewmodels/AdaptersViewModel.kt:210` |
| `getConfigurationSessionStatus` | `GET /v1/system/adapters/configure/{}` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:305, viewmodels/SetupViewModel.kt:2057` |
| `completeAdapterConfiguration` | `POST /v1/system/adapters/configure/{}/complete` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:308, viewmodels/SetupViewModel.kt:2174` |
| `executeConfigurationStep` | `POST /v1/system/adapters/configure/{}/step` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:302, viewmodels/SetupViewModel.kt:1906` |
| `getLoadableAdapters` | `GET /v1/system/adapters/loadable` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:296` |
| `startAdapterConfiguration` | `POST /v1/system/adapters/{}/configure/start` | CIRISAgent (front door) | called — `ui/screens/SetupScreen.kt:299, viewmodels/SetupViewModel.kt:1855` |

## 4. Flow (how)

Fresh node, agent build, person chooses to run without an assistant.

Step **You**:

```yaml
expect:
  state: populated
  visible: [setup_step_indicators, opt_run_with_ai, opt_run_without_ai, input_username, input_password, input_password_confirm, input_fedid_label, age_band_adult]
  count: {of: "step_indicator_*", eq: 3}
```

Click `opt_run_without_ai` — the rail loses a dot **while the person is standing
on step 1**, which is the only place that transition is safe:

```yaml
expect:
  count: {of: "step_indicator_*", eq: 2}
  absent: [step_indicator_ai]
```

Fill the account fields, `age_band_adult`, `btn_next`.

Step **Join the federation**, which is now the final one:

```yaml
expect:
  visible: [trace_consent_yes, trace_consent_no, toggle_announce_ownership, btn_next, btn_back]
```

Answer the trace question, then `btn_next` (labelled Finish):

```yaml
expect:
  state: loading
  visible: [setup_ownership_claiming]
```

```yaml
expect:
  visible: [setup_ownership_claimed]
```

Then the hand-off: the node restarts, the client re-points to `:4243`, and the
app returns to **Login** with `banner_setup_complete_relogin`. Signing in lands
on the node home — `Contacts`, which is `homeScreen(hasAgent = false)` and
CIRISClient#48's settled answer.

## 5. QA plan

**Platforms.** All five, and this pass is the one that must be run on all five
rather than argued about: every one of #43, #47, #48, #51, #66 and #67 is a
platform-specific failure of the same hand-off, and four of them were found on a
different platform from the one they were fixed on.

**Not tested here.** The `.env` round trip (the flag is written by
`setup/complete` on the agent and read back by the client on the next boot —
two processes, one file, and no test drives both); the reviver/watchdog
interaction; the node-client variant (`hasAgent = false`), which reaches the
same two steps without ever asking the question and has no fixture.

**The node-client double-submit this section reported is closed.** The
`isFinalStep && !hasAgent` branch now calls `SetupViewModel.finishNodeClientSetup`,
which checks the step and takes `beginFinalStep()` (CIRISClient#69), and
`testableClickable` binds its automation handler with `enabled`
(`platform/TestAutomation.kt:259-278`), so `/click` on a disabled Finish is
refused. Verified in the setup review; `FinalStepOnceTest` pins it.

**Announce on this pass.** The same per-device switch as CSD-082 §2, with the
same under-18 rule: for a minor or undeclared band it is not offered
(`txt_announce_not_offered`).
