# CSD-022 — Settings (every build's, and the agent's, on one screen)

**CSD**: CSD-022 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**On any build, a person can change their language, say where they are, and
sign out. On an agent build the same screen also carries the brain's
configuration — and the two must not be confused, because one of them works
without an agent and the other does not.**

`Screen.Settings` is the only surface in the app that both builds reach and
that a person must be able to reach (CIRISClient#51: before the fix, a
run-without-AI owner could not sign out on any platform). That makes it the
app's floor, and a floor that silently offers agent-only doors is not a floor.

## 2. Surface (what)

```yaml csd:surface
surface: agent-settings
screen: Settings
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_agent_settings`. Present in BOTH modes (`CirclesNav.kt:151` —
"Settings is every build's — language, ground, sign out"). `NavSurface.Account`
routes to the same `Screen.Settings` from the `devices-keys` instrument under
a different label, which `EpistemicNav.kt:139` explains.

### 2.0.1 The three planes on this screen, which the code does not separate

| plane | sections | works without an agent? |
|---|---|---|
| **the PERSON's, on this device** | language, currency, theme, brightness, live background, classic-viz, the Visualization entry | yes — all local (`SecureStorage`) |
| **the PERSON's, on the node** | ground (location), sign out, re-run setup, data management, consent | sign out yes; **ground no** |
| **the AGENT's** | AI configuration → LLM, agent mode, attestation | no |

The screen renders all three unconditionally (`SettingsScreen.kt:287`–`387`).

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "provenance:build_manifest:{target}:locale:{lang_code}"
    bind: {target: ciris_client, lang_code: yo}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Yorùbá — and, once the route carries it, whether THIS build's Yorùbá bundle is the signed one (CC 3.1.2, per-locale signed sub-manifest). NOT SENT today: the picker lists the 29 bundles the app shipped with and attests nothing about them."
    tag: dropdown_language
    blocked_by: CIRISClient#102
  - ceg: x_private:display_currency
    use: read
    type: string
    example: "USD"
    renders: "US Dollar — with the disclaimer (mobile.settings_currency_disclaimer) that conversion is indicative"
    tag: dropdown_currency
  - ceg: x_private:user_location
    use: read
    type: string
    example: "Lagos, Nigeria"
    renders: "Where you are — searched (`GET /v1/setup/location-search`) and set (`POST /v1/setup/location`). AGENT-ONLY ROUTE (§3)."
    tag: "proposed:input_location_search"
  - ceg: x_private:brightness_preference
    use: read
    type: "enum[system,light,dark]"
    example: "system"
    renders: "Follow the system / Light / Dark"
    tag: "chip_brightness_${pref.name.lowercase()}"
  - ceg: x_private:live_background_enabled
    use: read
    type: bool
    example: true
    renders: "Show the memory behind the conversation — a device preference; the thing it enables (LiveGraphBackground) renders only on Interact, which is agent-only"
    tag: switch_live_background
  - ceg: "attestation:self_verify"
    use: display-only
    type: float
    example: 1.0
    renders: "This app verified itself — the attestation card's level ladder (mobile.settings_attestation_*). CC 3.4.5 files this as a pro-self statement about the emitter, so it is the one integrity claim this screen may make about itself."
    tag: "proposed:settings_attestation_level"
  - ceg: "hardware_custody:{platform}"
    bind: {platform: android}
    use: display-only
    type: float
    example: 1.0
    renders: "Held in this phone's secure hardware — the Play Integrity / App Attest rung"
    tag: "proposed:settings_platform_attestation"
  - ceg: x_private:session_end
    use: read
    type: bool
    example: true
    renders: "Sign out — `POST /v1/auth/logout`. The one control that must work on every build."
    tag: btn_logout
```

```yaml csd:states
populated: {tag: "proposed:settings_loaded"}
empty:     {tag: "proposed:settings_empty", renders: "not reachable — the device-preference plane always has values. Declared so the four-state rule is answered honestly rather than omitted: this surface has no empty."}
loading:   {tag: "proposed:settings_loading", renders: "mobile.settings_loading_config while the LLM config read is in flight; the local preferences are already rendered"}
error:     {tag: "proposed:settings_error", renders: "`SettingsViewModel._errorMessage` → a snackbar that disappears. A section whose read failed (ground, attestation) needs its own persistent row saying which one."}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| sign out | `POST /v1/auth/logout` | **both** | live on CIRISServer (`/v1/auth/logout`) and CIRISAgent (`auth.py`) |
| search a place | `GET /v1/setup/location-search` | CIRISAgent | live (`setup/location.py:216`) — `CIRISApiClient.searchLocations` (`CIRISApiClient.kt:13285`) from `SettingsViewModel.kt:1086` — **wrong-host on a node build** |
| set your ground | `POST /v1/setup/location` | CIRISAgent | live (`setup/location.py:407`) — `updateUserLocation` (`CIRISApiClient.kt:13407`) from `SettingsViewModel.kt:1111` — **wrong-host on a node build** |
| read your ground | `GET /v1/setup/location` | CIRISAgent | live (`setup/location.py:444`) — `getCurrentLocation` (`CIRISApiClient.kt:13463`) from `SettingsViewModel.kt:1143` — **wrong-host on a node build** |
| the country list | `GET /v1/setup/countries` | CIRISAgent (`setup/location.py:237`) | live, **wired and unreached** — `getCountries` (`CIRISApiClient.kt:13341`) implements `CIRISApiClientProtocol.getCountries` (`CIRISApiClientProtocol.kt:171`) and nothing calls it. The route-coverage report marked it CALLED; the place picker is search-only |
| the LLM config read-back | `GET /v1/setup/config` | CIRISAgent | live (`setup/config.py`) — client node-skips it (`CIRISApiClient.kt:7486`) |
| language / currency / theme / viz | — | **device** | `SecureStorage`; no route, correctly |
| self-attestation | `GET /v1/setup/verify-status` · `/attestation-status` | CIRISAgent | live (`setup/attestation.py`); the node's `/v1/system/verify-status` is a different route |
| this build's locale manifest | — **nothing emits it** | **CIRISClient** | **missing, and ours.** CIRISVerify SHIPPED the verifier: `provenance:build_manifest:{target}:locale:{lang_code}` is live from v3.8.0 (CIRISVerify#37, CLOSED) — `ciris-verify-core/src/federation_provenance.rs:56`, constructor `:160`, longest-prefix dispatch `:432`; CIRISRegistry#28/#29 both CLOSED. What is absent is the PRODUCER: `grep -rl build_manifest` over this repo, excluding `FSD/CSD/`, returns zero files — no workflow, no packaging script, no pinned `ciris-verify`. We ship the 29 bundles (AGENTS.md: "`localization/` — the OTHER thing this repo owns"), so only we can sign them. **CIRISClient#102** |

`CIRISServer` serves `/v1/setup/status`, `/root`, `/connect-node`,
`/owned-nodes`, `/claim-remote`, `/consent-disclosure`, `/reset-device-auth` —
**and no `/v1/setup/location*`**. Agent tree last commit 2026-08-15.

## 4. Flow (how)

Open My things → This node → Settings, on a node build.

```yaml
expect:
  state: populated
  visible: [dropdown_language, btn_logout]
  absent: [btn_llm_settings, btn_viz_settings]
```

The `absent:` line fails today — see §6.

Change the language.

```yaml
expect:
  visible: ["language_yo"]
```

Sign out.

```yaml
expect:
  screen: Login
```

On an agent build, the agent plane is present.

```yaml
expect:
  visible: [btn_llm_settings, btn_viz_settings, btn_data_management, btn_consent]
```

## 5. QA plan

**Platforms.** All five, and this is the surface where that matters most: the
#51 regression was platform-shaped (desktops appeared to escape it only because
a stale `clientMode=AGENT` landed them on Interact by accident, #48).

**Not tested here.** Play Integrity / App Attest cannot be satisfied in CI, so
the attestation card is driven to its unverified rung and the rung is asserted;
the verified rung is a device test.

## 6. Card vs API vs CC — the delta

1. **A test asserts the thing this screen defeats.**
   `CirclesNavTest.settingsLivesUnderThisDeviceOnEveryBuild` (line 158) ends
   with `assertFalse(NavSurface.LLMSettings in inst.surfaces(hasAgent = false),
   "a node build has no model to configure")`, and
   `theNodeBuildIsASubsetOfTheAgentBuild` (line 71) pins the same property for
   the whole tree. Both pass. But `SettingsScreen` renders `btn_llm_settings`
   (line 302) and `btn_viz_settings` (line 387) with no `hasAgent` condition,
   so both surfaces are reachable on a node build through a door the rail
   closed — the tests assert the *nav tree*, not *reachability*. `hasAgent` is
   threaded into the shell only (`CIRISApp.kt:4823`); `SettingsScreen` never
   receives it. **Ask (this repo): thread `hasAgent` into `SettingsScreen`,
   gate the agent plane on it, and extend the test to assert reachability
   rather than membership.**
   (Note in passing: `EpistemicNav.kt:135` cites this test as
   `narrowingIsPurelySubtractive`, a name that no longer exists — the comment
   outlived the rename.)
2. **Ground is agent-only and is not marked.** `LocationSection` is rendered
   unconditionally and calls three `/v1/setup/location*` routes the node does
   not serve. On a node build the section loads nothing and offers a search
   that cannot succeed. This is the card the Locked Spec calls "ground" — it
   belongs to the PERSON and should work on every build. **Ask (CIRISServer):
   serve `/v1/setup/location`, or move the person's ground off the agent.**
3. **The three planes are one scroll.** A person cannot tell that language is
   theirs, ground is the node's, and AI configuration is the brain's. The
   `## 2.0.1` table is the split the screen should render as three cards with
   three headings.
4. **CC:** `attestation:self_verify` and `hardware_custody:{platform}` are the
   two families this screen genuinely emits about itself, and CC 3.4.5's
   disposition note confirms they are pro-self statements needing no consent
   gate. They are rendered as a level ladder with no CEG binding; binding them
   would let the receipt sheet explain what "Level 4" means in the same
   vocabulary the rest of the app uses.
5. **Localization:** `dropdown_language` picks among 29 bundles and attests
   nothing about them. `provenance:build_manifest:{target}:locale:{lang_code}`
   (CC 3.1.2) is the family that answers "is the Yorùbá you are reading the
   signed one". **Ask (CIRISVerify / this repo): carry the per-locale manifest
   digest into the build so the picker can show it.**
