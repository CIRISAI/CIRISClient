# CSD-038 — Account (My things › Devices & keys › Account)

**CSD**: CSD-038 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see what this device is signed in as, what language and money it
speaks, whether its keys are hardware-rooted, and can sign out — on every build,
including one with no brain at all.**

Serves **CC 3.4.5 condition (iv)**, "verifiability preserved — the subject
retains a means to verify what is filed about it": the attestation block on this
screen is the only place in the app where a person can ask the device what it
proved about itself. And it serves the plainer obligation that made the card
exist: `Screen.Settings` carries `btn_logout`, and before CIRISClient#51 nothing
on a node install reached it, so a run-without-AI owner could not sign out on any
platform.

## 2. Surface (what)

**This card has no `csd:surface` block. There is no honest one to write, and
the checker now refuses all three dishonest ones.**

`CirclesNav.kt` places `NavSurface.Account` under the `devices-keys` instrument,
so the row exists and a person can reach it: `btn_my_things ->
nav_instrument_devices_keys -> nav_epistemic_account`. But `Account` and
`AgentSettings` both route to `Screen.Settings` (`CIRISApp.kt:5980-5983`), and
`nav_map.build()` keys its hop table by **Screen class**
(`testing/gate/nav_map.py:_screen_routes`, `setdefault`), so `Screen.Settings`
resolves to `AgentSettings` and `Account` appears nowhere in the map — **49 hops
for 50 placed surfaces**. The hop is real; what is missing is the map's ability
to express it.

Each way of writing the block fails, and each failure is correct:

| written as | `check_csd_v3.py` says |
|---|---|
| `surface: account` + `screen: Settings` | `surface: 'account' derives 'nav_epistemic_account' but Screen.Settings is reached via 'nav_epistemic_agent_settings' — the surface id and the screen disagree` |
| `flow_only: true` + `screen: Settings` | refused — `flow_only` requires a screen the sidebar **cannot** reach (`:273-277`), and this one can |
| `screen_class:` / `unroutable:` | `surface: unknown key(s) ['screen_class', 'unroutable'] … An unread key silently disables the reachability check` (`:242-250`) |

The third row was briefly the house form for unaddressable screens and is gone
from the vocabulary: negative-tested against a normally-placed card,
`screen_class: Telemetry` under `surface: nodes` passed green, and so did a plain
`screeen:` fat-finger — the spelling did not mark an exception, it switched the
reachability check off for every CSD that carried it. `SURFACE_KEYS`
(`check_csd_v3.py:61`) now closes the set.

**Account is not flow-only and must not be filed as such.** `flow_only` is for a
real Screen the sidebar cannot reach — `VerifyAgent`, `AddFederationId`, whose
`screenToSurface` returns null (`CIRISApp.kt:5964`). Account has a NavSurface, an
instrument and a working hop. Marking it `flow_only` would trade a visible defect
for an invisible exemption.

Declaring `surface: agent-settings` to make the check pass is rejected for the
same reason: it would be a lie about which card this document is for. The two are
genuinely different — **Account is the account; `AgentSettings` is the brain's
settings** — which is why `AgentSettings` is `agentOnly` in everything but name
and Account is not.

So the absence above is the finding, and it is load-bearing: this CSD stays
unaddressable until `Screen.Account` exists. The ask is in §6.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: attestation:hardware_rooted
    use: display-only
    type: bool
    example: true
    renders: "Hardware — Secure Enclave / StrongBox / TPM, or 'software fallback'"
    tag: "proposed:settings_row_hardware"
  - ceg: attestation:self_verify
    use: display-only
    type: "enum[verified,unverified,unknown]"
    example: "verified"
    renders: "Attestation — Verified (green) / Unverified (amber) / Unknown"
    tag: "proposed:settings_row_attestation"
  - ceg: attestation:agent_integrity
    use: display-only
    type: int
    example: 3
    renders: "mobile.settings_attestation_checks — 'level 3' from `status.maxLevel`"
    tag: "proposed:settings_row_attestation_level"
  - ceg: x_private:signed_in_as
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. The screen offers `btn_logout` and never says WHO is signed in — no fed-ID, no username, no node. Sign-out with no subject named is the one control on this card whose blast radius the card does not state."
    tag: "proposed:settings_row_identity"
  - ceg: x_private:ui_language
    use: display-only
    type: string
    example: "yo"
    renders: "Language — Yorùbá (a dropdown over the 29 shipped bundles)"
    tag: dropdown_language
  - ceg: x_private:ui_currency
    use: display-only
    type: string
    example: "NGN"
    renders: "Currency — NGN"
    tag: dropdown_currency
  - ceg: x_private:ui_theme
    use: display-only
    type: string
    example: "dark"
    renders: "a brightness chip and a colour chip"
    tag: "chip_brightness_dark"
```

The three `attestation:*` families are read as **display-only**. None is
`emit`: they are owned by CIRISVerify (`owning_component: attestation`,
`owning_repo: CIRISVerify`) and the app holds no keys.

```yaml csd:states
populated: {tag: "proposed:settings_loaded"}
empty:     {tag: "proposed:settings_empty", renders: "not reachable — a settings screen with nothing on it is a bug, not a state; the nearest real thing is the brain-unconfigured case, which shows btn_set_up_agent INSTEAD of the LLM rows"}
loading:   {tag: "proposed:settings_loading", renders: "the section frames with a progress affordance; `isLoading` exists on the ViewModel and drives no tagged element"}
error:     {tag: "proposed:settings_error", renders: "mobile.settings_verify_status_unknown + the diagnostic, when verify-status did not load (SettingsScreen.kt:859-877) — TODAY THIS IS A SNACKBAR for everything else, which is an error state that disappears"}
```

**The error state is a snackbar.** `errorMessage` is collected and shown through
`snackbarHostState.showSnackbar(it)` (`SettingsScreen.kt:135-140`) and then
cleared. A transient toast is not distinguishable from `empty` a second later —
it is not distinguishable from anything, because it is gone.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| attestation / hardware | `GET /v1/system/verify-status` | CIRISServer | live — `src/health.rs:683` |
| sign out | `apiClient.logout()` | CIRISServer | live |
| LLM config (agent builds only) | `getLlmConfig` / `updateLlmConfig` / `listModels` | CIRISAgent | live |
| location | `getCurrentLocation` / `updateUserLocation` / `searchLocations` | CIRISAgent | live |
| re-run setup | `onResetSetup()` → the setup wizard | CIRISClient | local |
| **who is signed in** | — | CIRISServer | **missing on this card** — `GET /v1/auth/me` (`src/auth/session.rs:1147`) exists and this screen does not call it |

## 4. Flow (how)

Sign in on a **node-only** build (no brain); open My things › Devices & keys ›
Account. This is the case CIRISClient#51 was about, so it is the first step.

```yaml
expect:
  state: populated
  visible: [btn_logout, dropdown_language, "proposed:settings_row_attestation"]
  absent: [btn_llm_settings]
```

Change the language to one of the 29 bundles: click `dropdown_language`, choose
an entry.

```yaml
expect:
  visible: [dropdown_language]
```

On a device whose verify-status does not load:

```yaml
expect:
  state: error
  visible: ["proposed:settings_error"]
```

## 5. QA plan

**Platforms.** All five, and the **node build on each** — the whole point of the
card is that it exists without a brain, and the first cut of the CIRISClient#51
fix added it on the node build alone, which `narrowingIsPurelySubtractive`
caught.

**Not tested here.** The attestation levels themselves (no CI device produces a
hardware root); the currency and location pickers, which hit third-party data;
`btn_rerun_setup`, which destroys the session under test.

## 6. Delta — card vs API vs CC

* **The card is not addressable by a CSD.** Two surfaces share one Screen and
  `nav_map` is keyed by Screen. **Ask (CIRISClient), smallest fix:** give Account
  its own `Screen.Account` that composes the same `SettingsScreen` with
  `showAgentSections = false`; the reverse map stops colliding, `nav_map` gains a
  50th entry, and §2 of this file becomes a normal block. The alternative — key
  `nav_map.build()` by surface id and let the runner resolve Screen — changes a
  contract several flows already depend on, so it is the larger change, not the
  smaller one.
* **A sign-out button with no subject.** CC 3.4.5(iv) is about a subject being
  able to verify what is filed about it; the prior question is *which subject*.
  **Ask (CIRISClient):** render the signed-in fed-ID and the active node above
  `btn_logout`, from `GET /v1/auth/me`. On a multi-node client this is not a
  nicety — "sign out" of *which* node is currently unanswerable from the screen.
* **Errors that vanish.** **Ask (CIRISClient):** replace the snackbar with a
  tagged, persistent error block (`StateBlock` in the `danger` tone), per CSD/3
  §2.2 — an error a person can scroll back to is the minimum for a screen whose
  actions include sign-out and re-run-setup.
* **Placement.** Correct, and better than the alternative: Account under
  *Devices & keys* says the account is a property of this device and its keys,
  which is what the attestation block on it actually reports. It should **not**
  move under This node — This node is the substrate, and CC 3.4.7.3 keeps the
  actor and the substrate apart.
