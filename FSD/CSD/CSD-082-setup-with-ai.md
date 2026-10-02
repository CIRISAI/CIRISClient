# CSD-082 — Setup, the with-AI pass (You → Join the federation → AI → Complete)

**CSD**: CSD-082 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10 (the setup wizard)
**Pairs with**: CSD-083 (the run-without-AI pass through the same screen)
**Flow**: `testing/flows/drafts/csd-082-setup-with-ai.yaml` (floor `>=0.5.224`); `testing/gate/session_fixture.py` also drives it during sign-in

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Three questions, asked once each: who are you, do you join, and what powers
it — and nothing is written on anyone's behalf that they did not answer.**
Every value this wizard collects becomes a signed record about a person, so the
screen's job is to make each answer deliberate and each cost legible BEFORE the
choice, not after.

Three constitutional rules do the shaping, and all three are visible in the UI:

* **CC 3.4.11** — `age_self_declared:band:{band}:v1` is subject-signed and
  non-reserved; the band is `minor | adult`; "declined to state" resolves to the
  protective `minor` default. That is why declining is a real, selectable option
  that says on itself what it costs, and why nothing is recorded when it is
  chosen — writing `age_self_declared:band:minor:v1` for someone who never said it
  would put a statement they did not make into their own assurance record.
* **CC 3.2**, minor-stewardship rule — no minor `user` identity operates without
  a live adult steward, fail-secure. So a `minor` band does **not** self-claim;
  it mints a steward request and stops.
* **CC 3.4.5** — `ownership:*` is owner-only: the owner's signature *is* the
  claim of ownership, and "a third party asserting your responsible party is a
  seizure by attestation". The app performs **no crypto**; it hands
  `{node_code, claim_pin}` to the local node and the node signs its own
  owner-binding. Everything `ownership:*` on this screen is therefore
  `display-only` — the client renders a claim it constitutionally cannot make.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: Setup
flow_only: true
entry: "Login `btn_local_login` on a first-run node; Startup directly under the HA addon; or ServerConnection after a node switch"
exit: "Login — completion restarts the node, which invalidates the session by design, so the wizard always ends on the sign-in screen"
```

No nav hop — `Setup` is in `FLOW_ONLY` (`screen_atlas.py:42`); CSD-080 §2
records why `flow_only:` is a checked key.

**Three steps on this pass**, because `hasAiStep(hasAgent, runWithoutAi)` is true
(`viewmodels/SetupState.kt:117`): `YOU → JOIN_FEDERATION → AI → COMPLETE`. The
step rail shows three dots, and `isFinalSetupStep` puts "Finish" on the AI step.
CSD-083 is the same screen with `runWithoutAi = true`: two dots, Finish on
`JOIN_FEDERATION`.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "age_self_declared:band:{band}:{version}"
    bind: {band: adult, version: v1}
    use: emit
    type: "enum[adult,minor,declined]"
    example: "adult"
    renders: "'18 or over' / 'Under 18' side by side, nothing preselected; 'Prefer not to say' is a full-width row BELOW them that states its own consequence — declining is treated as under-18, stewardship included"
    tag: age_band_adult
    assert:
      one_of: {age_band_adult: [adult, minor, declined]}
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: bool
    example: true
    renders: "the send-traces question in the substrate's own words (GET /v1/setup/consent-disclosure), Yes / No with neither preselected, and the cost of No stated underneath: no capacity score, no commons credits"
    tag: trace_consent_yes
  - ceg: "consent:{kind}"
    bind: {kind: analyze}
    use: display-only
    type: bool
    example: true
    renders: "'Be scored on what you send' — a SEPARATE grant, and it only appears once traces are being sent at all"
    tag: toggle_trace_analyze
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "SYSTEM_ADMIN"
    renders: "'This node is yours.' with the role the node returned. The client shows it; the node signs it (CC 3.4.5)"
    tag: setup_ownership_claimed
  - ceg: attestation:hardware_rooted
    use: emit
    type: bool
    example: true
    renders: "'Protect my key with this device's secure hardware' — sets the mint backend to pkcs11"
    tag: toggle_secure_2fa
  - ceg: x_private:federation_label
    use: emit
    type: string
    example: "eric-laptop"
    renders: "the fed-ID name — 'your portable digital identity, how the network knows it is you'. Generic labels are rejected to avoid the ciris-client-user collision"
    tag: input_fedid_label
  - ceg: x_private:username
    use: emit
    type: string
    example: "qaadmin"
    renders: "the local account this node signs you in as; stamped on the ROOT cert by the claim so the owner can obtain a session afterwards"
    tag: input_username
  - ceg: x_private:setup_step
    use: display-only
    type: "enum[you,join_federation,ai,complete]"
    example: "join_federation"
    renders: "three dots; the active one carries the text `active`, done ones `complete`. A fourth dot for a screen this pass will skip would be a progress bar that lies"
    tag: step_indicator_join_federation
    assert:
      count: {of: "step_indicator_*", eq: 3}
  - ceg: x_private:llm_provider
    use: emit
    type: string
    example: "mobile_local"
    renders: "the provider picker; every keyless option is listed ahead of the ones that need a key"
    tag: input_llm_provider
  - ceg: x_private:llm_verdict
    use: display-only
    type: "enum[testing,not run,ok,failed]"
    example: "ok"
    renders: "the Test Connection verdict, as a machine-readable state AND the provider's own message — `txt_llm_state` carries the state unconditionally so 'not run' is readable, `txt_llm_verdict` the sentence"
    tag: txt_llm_state
```

**The AI step cannot simply be skipped.** Any path that ends without a usable
provider must write `CIRIS_SERVICES_DISABLED=true`, or the next boot makes
`llm_service` critical and initialization aborts instead of degrading
(`SetupState.kt:75-77`). That is why "no AI" is a question on screen 1 (CSD-083)
rather than an escape hatch on screen 3.

```yaml csd:states
populated: {tag: setup_step_indicators, renders: "the rail plus the current step's form; Next is disabled until the step's required answers exist"}
empty:     {tag: setup_consent_empty, renders: "the node served a consent disclosure with no `replication` grant: there is no question to ask, the step SAYS so ('This node doesn't offer to send your traces anywhere… nothing will be sent'), nothing is sent, and Next is live (`SetupFormState.traceQuestionOffered`)"}
loading:   {tag: setup_ownership_claiming, renders: "'Claiming ownership of this node…' — and there is NO advance control here on purpose: the claim is work, not a step, and it finishes on its own"}
error:     {tag: setup_ownership_error, renders: "'This node could not be claimed', the node's reason, and btn_setup_finish_unclaimed — the wizard stops on COMPLETE and lets the person choose rather than completing over a claim that did not happen (CIRISClient#68)"}
```

**The `empty` row was a real defect, and is closed (setup review, 2026-09-27).**
The trace question rendered only under `d.grant("replication")?.let`, and
`canProceedFromCurrentStep` returned `traceConsentAnswered` for
`JOIN_FEDERATION`, so a disclosure without that grant stranded the wizard on
step 2 with a disabled Next and no visible cause. Now the step calls
`noteTraceQuestionAbsent()`, renders `setup_consent_empty`, and
`canProceedFromCurrentStep` reads `traceConsentAnswered || !traceQuestionOffered`
(`SetupReviewGapsTest.aDisclosureWithNoTraceGrantDoesNotStrandScreenTwo`, red
against the old predicate).

**The disclosure read failing is an error, not a loading screen.** It used to
print the exception text in the loading slot with nothing to press. It now
renders `setup_consent_error` with the reason and `btn_setup_consent_retry`;
Next stays disabled, because the question cannot be asked without the
substrate's own words (CIRISAgent `routes/setup/providers.py:105-110` fails LOUD
for the same reason).

**Announce is per device, and not offered to minors (CIRISServer 0.5.218,
CIRISServer#655; CIRISClient#96).** The switch copy says "this device"
(`mobile.announce_decision_toggle_label`, `_body_on`, `_body_off`; the stale
"OFF (recommended)" is gone — the default is ON because announce is the floor
for service). For an under-18 or undeclared band the switch is not rendered;
`txt_announce_not_offered` says why (CIRISConstitution#111: minors are not
discoverable by unconnected adults), and the claim reads
`SetupFormState.announcesThisDevice()`, never the raw switch
(`SetupReviewGapsTest.aMinorNeverAnnouncesWhateverTheSwitchHeld`). The claim
already skipped minors entirely; the rule is now local to announce too. The
YOU step no longer asks announce a second time: `AnnounceDecisionCard` there
was the same question with one answer (it stays on AddFederationId, CSD-086).

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| what joining grants, in the substrate's words | `GET /v1/setup/consent-disclosure` | **both**: CIRISServer `src/auth/bootstrap.rs:1497` (loopback-only) and CIRISAgent `routes/setup/providers.py:85` (serves `ciris_server.consent_disclosure()` unedited, 503 rather than a substitute) | live — the client asks `$baseUrl`, the agent on a with-AI install |
| mint the fed-ID | `POST /v1/self/identity` | CIRISServer (`src/identity.rs`) | live |
| adopt an existing fed-ID | `POST /v1/self/associate` | CIRISServer (`src/identity.rs`, `src/auth/occurrence.rs`) | live |
| read a keyset folder before adopting it | `POST /v1/self/identity/inspect` | CIRISServer `src/identity.rs:1951` (`:2000` on `integ/0.5.218`) | **live** — `inspectKeysetFolder` (`CIRISApiClient.kt:3182`) from `SetupViewModel.kt:1531`, the verdict the adopt path shows before `associate` |
| this node's public handle | `GET /v1/federation/node-code` | CIRISServer (`src/federation_nodecode.rs`) | live |
| self-claim ownership | `POST /v1/setup/claim-remote` → target's `POST /v1/setup/root` | CIRISServer (`src/claim_remote.rs`) | live; claim-remote is loopback-only and first-run-gated, `/v1/setup/root` is the one setup route reachable off-host |
| owner session after the claim | `POST /v1/auth/login` | CIRISServer | live |
| record the age band | `POST /v1/self/age` (loopback, post-claim) | CIRISServer (`src/auth/gate.rs`, `src/claim_remote.rs`) | live |
| — the federation-tier route | `POST /v1/safety/age-assurance` | CIRISServer (`src/safety/age.rs`) | live, **not used pre-claim**: it needs an x-ciris signature the app cannot make |
| mint a steward request for a minor | `POST /v1/safety/minor-steward/request` · `/accept` | CIRISServer | **missing** — no route literal in `src/*.rs`; the client synthesises a local offer artifact and says so (`SetupViewModel.kt:1000`) |
| validate the LLM choice | `POST /v1/setup/validate-llm` | **CIRISAgent** (`routes/setup/llm_routes.py:211` on `main` 29371660de; `:190` in the 2026-08-15 tree) | live — generated `validateLlmV1SetupValidateLlmPost` (`CIRISApiClient.kt:6722`) |
| list models | `POST /v1/setup/list-models` | **CIRISAgent** (`routes/setup/llm_routes.py:222`; was `:201`) | live — `CIRISApiClient.kt:6782` |
| which LLM providers to offer | `GET /v1/setup/providers` (setup-only) | **CIRISAgent** (`routes/setup/providers.py:27`) | live, **not called** — the picker is a compiled-in list (`SetupViewModel.availableProviders`, `SetupViewModel.kt:76`). A provider the agent adds never appears, and one it drops still does |
| which agent templates to offer | `GET /v1/setup/templates` (setup-only) | **CIRISAgent** (`routes/setup/providers.py:38`) | **live, called** from the AI step (`OptionalFeaturesSection`, setup review). Was wired and unreached — only tests invoked `loadAvailableTemplates`. `stewardship_tier` (REQUIRED on the agent's `AgentTemplate`, `models.py:44`) was dropped by the mapping and is now shown on each option. A failed read renders `setup_templates_error`, not an empty list; `opt_template_<id>` selects |
| which communication adapters to offer | `GET /v1/setup/adapters` (setup-only) | **CIRISAgent** (`routes/setup/providers.py:49`) | **live, called** from the AI step. `platform_available` and `missing_binaries` (`models.py:67-72`) were dropped by the mapping; an adapter the agent says cannot run here is no longer offered, and missing binaries are named. `api` is not offered (always on — the always-on disclosure covers it). `toggle_adapter_<id>`; an adapter with `requires_config` opens its configure wizard instead of flipping. Failed read: `setup_adapters_error` + `btn_setup_features_retry` |
| adapters with eligibility (ready / missing requirements) | `GET /v1/setup/adapters/available` | **CIRISAgent** (`routes/setup/providers.py:113`) | live, **not called** and no hand-written method |
| **what each optional tool would be able to do** | `GET /v1/setup/tool-disclosure` (setup-only) | **CIRISAgent** (`routes/setup/providers.py:61`) | **live, called and drawn**: each optional feature carries `tool_disclosure_<id>` (what it lets the agent do, expandable per tool) directly under its switch, and `tool_disclosure_always_on` lists the tools no choice controls. A failed read renders `setup_tool_disclosure_error` — never "grants nothing" |
| write the config and reload | `POST /v1/setup/complete` | **CIRISAgent** (`routes/setup/complete.py:1511` on `main`) | live, and **not idempotent** — CIRISAgent#1193. Carries `template_id` and `enabled_adapters` from the choices above |
| pair with an organisation's Portal record | `POST /v1/setup/connect-node` → `GET /v1/setup/connect-node/status` (poll) · `POST /v1/setup/reset-device-auth` (back out) | CIRISServer `src/auth/device_auth.rs:371-377` (the agent's copy is retired; only `download-package` remains there) | **live, called** from `PortalConnectSection` on the AI step (setup review). Was wired and unreached, and could not have worked: the node answers with BARE bodies and `{"error": …}` refusals, the client required a `data` envelope and read `detail` (`DeviceAuthWireTest`, red against the old parser). `approved_adapters`, `package_download_url` and `package_template_id` were dropped; now parsed. Tags: `btn_portal_connect_open`, `input_portal_url`, `btn_portal_connect`, `txt_portal_user_code`, `btn_portal_open_link`, `btn_portal_cancel`, `setup_portal_complete`, `setup_portal_error` |

**Four upstream defects sit on this pass**, all open (re-checked 2026-09-27):

* **#1193** — two `setup/complete` calls 64 ms apart minted two ROOT owners with
  one name; every login after is refused as ambiguous. The client's half was
  CIRISClient#69, now closed by `beginFinalStep()` on BOTH branches (the node
  client's final step goes through `finishNodeClientSetup`, which takes the same
  guard).
* **#1194** — `setup/complete` ran on a node whose server had already closed
  `setup/root`; two stores disagree about "already owned" and the one that said
  "not owned" got to write.
* **#1195** — `CIRIS_FORCE_FIRST_RUN` is re-read on every status call, so
  completion never clears it and the client re-opens this wizard in a loop.
* **#1196** — `/v1/setup/status` does not declare `claim_pin_file`, so a client
  that did not launch the node guesses the path, fails to read the PIN, and
  completes setup **unclaimed and without the owner's fed-ID**. That is the
  single most consequential of the four for this screen: it turns the claim —
  the whole constitutional point of first run — into a silent no-op.

### 3.1 Every route this screen calls (generated)

Shared with CSD-083: it is one screen, and the rows are the code's. Rows the
table above does not discuss are the wizard doing other cards' acts — sign-in
(CSD-081), the adapter configure wizard (CSD-020), the first consent grant
(CSD-067 holds the toggle), delegation (CSD-055) — each one door to one act,
listed in `scratchpad/csd-route-map.md`'s duplicates.

<!-- generated: python3 packaging/check_csd_routes.py --print CSD-082 (screen Setup; heuristic) -->
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

**Still not called, and why.** `GET /v1/setup/providers`: the provider picker is
a compiled-in list (`SetupViewModel.availableProviders`), so a provider the
agent adds never appears; it is a picker change, not a disclosure, and no CC
clause turns on it — left for the AI-step owner. `GET /v1/setup/adapters/available`:
the eligibility report duplicates `platform_available`/`missing_binaries`,
which `/v1/setup/adapters` now carries into the list.

## 4. Flow (how)

Fresh node, agent build. `session_fixture.run_setup` drives exactly this.

Step **You**:

```yaml
expect:
  state: populated
  visible: [setup_step_indicators, input_username, input_password, input_password_confirm, input_device_name, input_fedid_label, age_band_adult, opt_run_with_ai]
  count: {of: "step_indicator_*", eq: 3}
```

Type username / password / confirm / device name, click `age_band_adult`, click
`btn_next`.

Step **Join the federation**:

```yaml
expect:
  visible: [trace_consent_yes, toggle_announce_ownership, btn_consent_details, btn_next, btn_back]
```

Click `trace_consent_yes`, then `btn_next`.

Step **AI**: choose a provider, `btn_test_connection`.

```yaml
expect:
  visible: [input_llm_provider, btn_test_connection, txt_llm_state, setup_optional_features, btn_portal_connect_open]
  one_of: {txt_llm_state: [testing, "not run", ok, failed]}
```

`btn_next` (labelled Finish here) → the claim runs, then completion:

```yaml
expect:
  state: loading
  visible: [setup_ownership_claiming]
  absent: [btn_next]
```

and settles:

```yaml
expect:
  visible: [setup_ownership_claimed]
```

The node restarts and the app returns to **Login** with
`banner_setup_complete_relogin` (CSD-081).

## 5. QA plan

**Flow not complete.** `testing/flows/drafts/csd-082-setup-with-ai.yaml` (floor `>=0.5.224`) cannot go green on an ordinary matrix run as written: `the_claim_is_a_real_loading_state` presses Finish and asserts `setup_ownership_claiming` with `btn_next` absent. Its only precondition is `screen: Setup`, which always holds; whether Finish is live at all depends on the runner having an LLM key, and the loading state it asserts is transient, so an ordinary run can fail it either way. No tag marks the state it needs beforehand. It is complete when that step is gated on one (or split into a flow for a runner that holds a key); `FinalStepOnceTest` pins the behaviour meanwhile. Until then this card is not ready to promote.

**Platforms.** All five. Every input on the YOU and AI steps declares its sink
at `SetupScreen.kt:201`, so they are drivable — with two exceptions below.

**Not tested here.** The fed-ID USB import (`btn_federation_import_usb` — a file
picker); the minor branch end to end (it needs an adult on a second device, and
its substrate route does not exist); `input_llm_model` and
`input_federation_associate_keyid`, both tagged and **not drivable**
(`client/tools/check_ui_drivable.py --list`); the OAuth-sourced free-AI path
(`btn_use_free_ai` depends on a completed browser handoff).

**The guard this section used to ask for is in place.** The node-client final
step goes through `SetupViewModel.finishNodeClientSetup`, which checks the step
against its own state and takes `beginFinalStep()` (CIRISClient#69), and
`testableClickable` now takes `enabled` and binds its automation handler with
it (`platform/TestAutomation.kt:259-278`), so `/click btn_next` is refused while
`isSubmitting`. Verified in the setup review; `FinalStepOnceTest` pins it.

**And the agent-side walker cannot complete this wizard today.**
CIRISAgent's Android and iOS setup tests (`tools/qa_runner/modules/mobile/test_cases.py:590-700`,
`ios_test_cases.py:784`) drive it by visible English copy: they fill
username/password only inside `if is_text_visible("Confirm Setup") or
is_text_visible("Your Account")`, and answer the metrics consent by clicking
`"I agree to share anonymous alignment metrics"`. None of those three strings
renders in this client — `setup.confirm_title` has no call site anywhere in
`commonMain`, "Your Account" is not a string in `en.json`, and the metrics
sentence does not exist at all (the question is now `trace_consent_yes`/`_no`).
So the walker clicks "Next" fifteen times against a step whose Next is disabled
until the trace question is answered. **CIRISAgent**: drive this screen by
testTag, as `testing/gate/session_fixture.py:76-125` already does —
`input_username`, `input_password`, `input_password_confirm`,
`input_device_name`, `age_band_adult`, `trace_consent_yes`, `btn_next`.

**Known platform failures on this pass.** CIRISClient#50 — iOS SIGABRT as the
wizard enters the AI step; CIRISClient#44/#42 (closed) — the YOU step overflowed
on phone viewports and `/scroll` returned 200 without moving it.
