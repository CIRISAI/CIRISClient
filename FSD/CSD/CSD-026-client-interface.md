# CSD-026 — Interface (how this device draws the agent)

**CSD**: CSD-026 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can tune how the living visualization behaves on THIS device, and
nothing they change here leaves the device or reaches anyone else's.**

Every other card under "This node" is about a node or an agent. This one is
about a phone. Twenty-four sliders over `CellVizConfig` — rotation, membrane
radius, opening lifetimes, mote drift, port geometry — persisted to
`SecureStorage` under `viz_config_*` keys and reloaded at app start. No route,
no attestation, no cohort scope. That is not a gap; it is the correct answer
for a device preference, and this CSD exists partly to say so explicitly so
nobody "fixes" it by syncing it.

## 2. Surface (what)

```yaml csd:surface
surface: client-interface
screen: VizSettings
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_client_interface`. Listed as `agentOnly` (`CirclesNav.kt:155`).
That is defensible but incompletely enforced — see §6.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:viz_rotation_deg_per_sec
    use: read
    type: float
    example: 6.0
    renders: "Rotation — 6.0°/s, a slider whose min/max mirror `CellVizConfig.sanitized`'s `coerceIn`"
    tag: viz_settings_rotationDegPerSec
  - ceg: x_private:viz_membrane_radius_fraction
    use: read
    type: float
    example: 0.42
    renders: "Membrane radius — 0.42 of the canvas"
    tag: viz_settings_membraneRadiusFraction
  - ceg: x_private:viz_min_openings
    use: read
    type: int
    example: 2
    renders: "Fewest openings — 2, a stepper with ${tag}_minus / ${tag}_plus"
    tag: viz_settings_minOpenings
  - ceg: x_private:viz_max_openings
    use: read
    type: int
    example: 6
    renders: "Most openings — 6"
    tag: viz_settings_maxOpenings
  - ceg: x_private:viz_max_memory_motes
    use: read
    type: int
    example: 120
    renders: "Most memories shown at once — 120"
    tag: viz_settings_maxMemoryMotes
  - ceg: x_private:viz_memory_load_window_hours
    use: read
    type: int
    example: 24
    renders: "Look back — 24 hours. This one is NOT purely cosmetic: it is the window of the `/v1/memory/timeline` read the background makes."
    tag: viz_settings_memoryLoadWindowHours
  - ceg: x_private:viz_breathe_period_sec
    use: read
    type: float
    example: 4.0
    renders: "Breath — one every 4.0 s"
    tag: viz_settings_breathePeriodSec
  - ceg: x_private:viz_config_persisted
    use: display-only
    type: bool
    example: true
    renders: "Kept on this device only — the honest sentence this screen does not print. `CellVizConfigStore` writes `viz_config_<field>` to SecureStorage, field by field, so a partial read falls back per field rather than corrupting the set."
    tag: "proposed:viz_settings_locality_note"
```

**Every field is `x_private:` and that is the finding, not an omission.** There
is no CEG family for a device's rendering preferences, and there should not be
— a CEG row is a claim addressed to somebody. The one field worth a second
look is `viz_settings_memoryLoadWindowHours`, which sets the `hours` parameter
on a real memory read; it is a preference that changes what is fetched.

```yaml csd:states
populated: {tag: "proposed:viz_settings_list"}
empty:     {tag: "proposed:viz_settings_empty", renders: "not reachable — `CellVizConfig` always has defaults. Declared rather than omitted: this surface has no empty, because a preference set is never absent."}
loading:   {tag: "proposed:viz_settings_loading", renders: "not reachable — the config is read synchronously from a StateFlow already populated at app start"}
error:     {tag: "proposed:viz_settings_error", renders: "not reachable through a read: `CellVizConfigStore` falls back per field on a parse failure by design. The honest error here is a WRITE failure — SecureStorage refusing — and it is not modelled at all. A slider that silently fails to persist is this screen's only lie."}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| every slider | — | **device** (`SecureStorage`, `viz_config_*`) | live, no route |
| the thing being tuned | `GET /v1/memory/timeline?hours=…` | **both** | live; `LiveGraphBackground` reads it |
| a failed persist | — **unconfirmed** | this repo | blocks `building` for `viz_settings_error` |

No node or agent route backs this screen. That is the whole contract.

## 4. Flow (how)

Open My things → This node → Interface.

```yaml
expect:
  state: populated
  count: {of: "viz_settings_*", min: 20}
```

Move a stepper.

```yaml
expect:
  visible: [viz_settings_minOpenings_plus, viz_settings_minOpenings_minus]
  number: {viz_settings_minOpenings: {min: 0, max: 24}}
```

Restart the app and reopen.

```yaml
expect:
  number: {viz_settings_minOpenings: {eq: 3}}
```

The restart assertion is the only one that tests what this screen is for.

## 5. QA plan

**Platforms.** All five, and the persistence assertion matters per platform:
`SecureStorage` is a different implementation on each (Keychain, EncryptedShared
Preferences, the desktop keyring), and the field-by-field write is exactly the
thing that fails differently on each one.

**Not tested here.** The visual result. A slider changing a number is
drivable; whether the membrane looks right is not, and this CSD does not
pretend otherwise.

## 6. Card vs API vs CC — the delta

1. **Whose setting is it? The PERSON's, on this DEVICE** — the only card in
   this area of which that is true. That makes "This node" a slightly wrong
   home: it is not this node's interface, it is this app's. The Locked Spec
   has no "this device" instrument, so This node is the least-wrong shelf;
   recorded rather than proposed, because inventing a sixth instrument for one
   card is worse.
2. **`agentOnly` is defensible and leaks.** `LiveGraphBackground` is composed
   only from `InteractScreen` (`InteractScreen.kt:601`), which is agent-only,
   so on a node build there is nothing to tune and hiding the card is right.
   But `SettingsScreen` renders `btn_viz_settings` (line 387) and
   `switch_live_background` unconditionally, so a node-build owner is offered
   the entry and the toggle for a surface the rail hid and a renderer that
   never runs. Same defect as CSD-022 §6.1; one fix covers both.
3. **No write-failure state.** A slider that does not persist looks identical
   to one that does until the next launch. **Ask (this repo): have
   `CellVizConfigStore` surface a write failure and render it.**
4. **Title is hardcoded English.** `VizSettingsScreen.kt:87` —
   `Text("Visualization")`, not `localizedString(...)`, in a repo that keeps
   29 bundles at parity by check. Small, and exactly the kind of thing that
   survives because nobody re-reads it.
5. **CC: nothing to bind, deliberately.** No family, no scope, no attester. A
   device preference is not a claim. This is the one card in this area where
   "no CEG binding" is the correct answer rather than a gap, and the
   `viz_settings_locality_note` field exists to make the screen say so.
