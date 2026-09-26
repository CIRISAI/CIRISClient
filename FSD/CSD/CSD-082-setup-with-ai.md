# CSD-082 — Setup, the with-AI pass (You → Join the federation → AI → Complete)

**CSD**: CSD-082 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B10 (the setup wizard)
**Pairs with**: CSD-083 (the run-without-AI pass through the same screen)
**Flow**: partly written — `testing/gate/session_fixture.py` drives it today

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

* **CC 3.4.11** — `age_self_declared:{band}:v1` is subject-signed and
  non-reserved; the band is `minor | adult`; "declined to state" resolves to the
  protective `minor` default. That is why declining is a real, selectable option
  that says on itself what it costs, and why nothing is recorded when it is
  chosen — writing `age_self_declared:minor:v1` for someone who never said it
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
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "age_self_declared:{band}:{version}"
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
empty:     {tag: "proposed:setup_consent_empty", renders: "the node served a consent disclosure with no `replication` grant, so the Yes/No question does not render — and `traceConsentAnswered` can never become true, so Next stays disabled with nothing on screen explaining why. Today this is a silent trap; the tag is the ask"}
loading:   {tag: setup_ownership_claiming, renders: "'Claiming ownership of this node…' — and there is NO advance control here on purpose: the claim is work, not a step, and it finishes on its own"}
error:     {tag: setup_ownership_error, renders: "'This node could not be claimed', the node's reason, and btn_setup_finish_unclaimed — the wizard stops on COMPLETE and lets the person choose rather than completing over a claim that did not happen (CIRISClient#68)"}
```

**The `empty` row is a real defect, not a hypothetical.** `SetupScreen.kt:954`
renders the trace question only under `d.grant("replication")?.let`, and
`SetupFormState.canProceedFromCurrentStep` returns `traceConsentAnswered` for
`JOIN_FEDERATION` (`SetupState.kt:921`). A node whose disclosure omits that
grant strands the wizard on step 2 with a disabled Finish and no visible cause.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| what joining grants, in the substrate's words | `GET /v1/setup/consent-disclosure` | CIRISServer (`src/auth/bootstrap.rs`) | live — **loopback-only** |
| mint the fed-ID | `POST /v1/self/identity` | CIRISServer (`src/identity.rs`) | live |
| adopt an existing fed-ID | `POST /v1/self/associate` | CIRISServer (`src/identity.rs`, `src/auth/occurrence.rs`) | live |
| this node's public handle | `GET /v1/federation/node-code` | CIRISServer (`src/federation_nodecode.rs`) | live |
| self-claim ownership | `POST /v1/setup/claim-remote` → target's `POST /v1/setup/root` | CIRISServer (`src/claim_remote.rs`) | live; claim-remote is loopback-only and first-run-gated, `/v1/setup/root` is the one setup route reachable off-host |
| owner session after the claim | `POST /v1/auth/login` | CIRISServer | live |
| record the age band | `POST /v1/self/age` (loopback, post-claim) | CIRISServer (`src/auth/gate.rs`, `src/claim_remote.rs`) | live |
| — the federation-tier route | `POST /v1/safety/age-assurance` | CIRISServer (`src/safety/age.rs`) | live, **not used pre-claim**: it needs an x-ciris signature the app cannot make |
| mint a steward request for a minor | `POST /v1/safety/minor-steward/request` · `/accept` | CIRISServer | **missing** — no route literal in `src/*.rs`; the client synthesises a local offer artifact and says so (`SetupViewModel.kt:1000`) |
| validate the LLM choice | `POST /v1/setup/validate-llm` | **CIRISAgent** (`routes/setup/llm_routes.py:190`) | live |
| list models | `POST /v1/setup/list-models` | **CIRISAgent** (`routes/setup/llm_routes.py:201`) | live |
| write the config and reload | `POST /v1/setup/complete` | **CIRISAgent** (`routes/setup/complete.py:951`) | live, and **not idempotent** — CIRISAgent#1193 |

**Four upstream defects sit on this pass**, all open (CIRISAgent working tree
read 2026-08-15, so it may be behind):

* **#1193** — two `setup/complete` calls 64 ms apart minted two ROOT owners with
  one name; every login after is refused as ambiguous. The client's half was
  CIRISClient#69, now closed by `beginFinalStep()` (`SetupViewModel.kt:2733`) —
  but see §5, that guard covers the agent branch only.
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
  visible: [input_llm_provider, btn_test_connection, txt_llm_state]
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

**Platforms.** All five. Every input on the YOU and AI steps declares its sink
at `SetupScreen.kt:201`, so they are drivable — with two exceptions below.

**Not tested here.** The fed-ID USB import (`btn_federation_import_usb` — a file
picker); the minor branch end to end (it needs an adult on a second device, and
its substrate route does not exist); `input_llm_model` and
`input_federation_associate_keyid`, both tagged and **not drivable**
(`client/tools/check_ui_drivable.py --list`); the OAuth-sourced free-AI path
(`btn_use_free_ai` depends on a completed browser handoff).

**One guard is missing, and it is the CIRISClient half of CIRISAgent#1193.**
`beginFinalStep()` protects the AGENT final step (`SetupScreen.kt:557`). The
NODE-client final step immediately above it (`SetupScreen.kt:542-546`) calls
`claimLocalNodeOwnership` + `nextStep()` with no compare-and-set, and
`claimLocalNodeOwnership` has no re-entry guard of its own
(`SetupViewModel.kt:1067`). Separately, `testableClickable` registers a handler
that calls `onClick()` **directly** and adds a `Modifier.clickable { onClick() }`
with no `enabled` argument (`platform/TestAutomation.kt:237-252`). Neither
consults the Button's `enabled`, so `/click btn_next` fires `onNext` regardless
of `isSubmitting` — certainly for the automation path, and the added gesture
node is live for a finger too. The guard belongs inside the lambda, which is
what `btn_add_fedid_confirm` does (CSD-086 §5). CSD-083 §5 carries the same
note; the fix belongs in one place.

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
