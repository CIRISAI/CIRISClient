# FSD — The CIRIS UI language

**Status:** as built, 2026-09-06 (client 0.5.203, CIRISAgent PR #1154)
**Author:** CIRISClient
**Covers:** the one declarative language the client renders from and the
automation drives through — its two halves, the contract between them, what is
enforced today, and what is not yet.

---

## 1. What it is

One language, two halves, one vocabulary:

| half | owner | declares | consumed by |
|---|---|---|---|
| **rendering** — `interactive_config` | CIRISAgent, per adapter manifest | what the UI shows: a *stack* of *cards*, each with contents, sources, links and outputs | the client's `AdapterWizardDialog`, which is a pure renderer |
| **exposure** — test tags | CIRISClient, per composable | what the UI exposes: every control, addressable by name, with a proof that it can be driven | the test server (`/tree`, `/act`, …) and the five-platform gate |

The HyperCard reading is the intended model and it fits the keys that already
exist: a **card** has a *title*, *contents*, *sources* (where values come from),
*links* (what must be true to reach it, where it goes) and *outputs* (what it
writes). A **stack** is an ordered list of cards. A **flow** (CIRISClient#39,
not built) is a stack across screens, with assertions on recorded state.

What connects the halves is a naming rule, §4.

---

## 2. Half 1 — rendering: `interactive_config`

### 2.1 Shape

```
InteractiveConfiguration
  required: bool
  workflow_type: str
  completion_method: str
  steps: [ConfigurationStep]
```

A `ConfigurationStep` is a card. After CIRISAgent PR #1154 it has **exactly one
spelling per axis**:

| axis | keys | notes |
|---|---|---|
| identity | `step_id`, `step_type` | `step_id` unique within the stack; `step_type ∈ {input, select, confirm, discovery, oauth, device_auth}` |
| contents | `title`, `description`, `fields[]` | a field is `{name, label, type, input_type, required, default, placeholder, description, options, sensitive, readonly, min, max, depends_on}`; **a single field is a list of one** |
| sources | `options_method`, `discovery_method`, `oauth_config`, `device_auth_config`, `default`, `dynamic_fields` | where values come from when not literal |
| links | `depends_on: [step_id]`, `condition: {field, equals \| not_equals \| values}` | ordering, and the one containment predicate |
| outputs | `fields[].name`, `completion_method`, `action` | a select writes its one declared field; `discovery` writes `base_url`; `oauth` writes `oauth_tokens` |
| requiredness | `required: bool` (default `false`) | **the only key on this axis** |
| documentation | `_comment` | permitted anywhere; read by nothing |

Retired, and rejected by lint: `optional`, `field`, `field_name`, `field_id`,
and the `Dict` arm of `depends_on`. Each was a second way to say something the
schema already said, and each let two keys disagree.

Census at this writing: 22 of 64 adapters, 83 steps —
`input` 39 · `confirm` 24 · `select` 14 · `discovery` 3 · `oauth` 2 · `device_auth` 1.

### 2.2 Runtime semantics (what the keys DO)

Enforced in `AdapterConfigurationService` (CIRISAgent) as of #1154:

- **`condition` is evaluated** by one function, `current_step(session)`, on
  start, on every advance, and by all three status routes. A step whose
  condition does not hold is skipped, never shown. Three operators,
  exhaustively; anything else is a `ValueError`, because a predicate with no
  operator must not silently show *or* hide. A condition on a field not yet
  collected does not hold.
- **A select writes the field it declares.** `collected_config[fields[0].name] = selection`
  in addition to `collected_config[step_id]`. Before this, adapters read
  `dialect` / `response_mode` / `auth_method` and the engine only ever wrote
  the step_id — `external_data_sql` could not complete a wizard, and no
  `condition` could ever hold.
- **Step-level `required` blocks an empty submission.** Field-level `required`
  blocks a missing field. A select step may be skipped (`"selection": "skip"`)
  iff it is not required.
- `confirm` advances unconditionally; `discovery` and `oauth` hold the index
  until their answer arrives.

### 2.3 Static invariants (lint)

`tests/ciris_adapters/test_interactive_config_lint.py` — 113 checks over the
tree, every one data-level:

1. validates as `InteractiveConfiguration`
2. every key on the stack, step and field is a declared key (`extra="allow"` is
   not a licence — an undeclared key is how the drifts got in)
3. no retired key
4. `step_id`s unique; every field named
5. `depends_on` names an **earlier** step
6. `condition.field` is **written by an earlier step** and carries exactly one operator
7. a select declares at most one field
8. the migration (`tools/dev/migrate_interactive_config.py --check`) is a fixed point
9. the schema itself has one key per axis (so a revert cannot pass)

### 2.4 The wire

The client never reads the manifest. It reads the session:

```
POST /v1/system/adapters/{type}/configure/start      → session {session_id, current_step_index, current_step, total_steps}
POST /v1/system/adapters/configure/{sid}/step  {step_data}  → StepResult {success, next_step_index, data, error}
GET  /v1/system/adapters/configure/{sid}/status      → session (current_step through the SAME evaluator)
POST /v1/system/adapters/configure/{sid}/complete
```

`step_data` by step type: `input` → `{field.name: value}`; `select` →
`{"selection": "id"}` or `"id1,id2"` for `multiple`, `"skip"` to skip;
`discovery` → `{"selected_url"}` or `{"manual_url"}`; `oauth` → callback
parameters. `ConfigStepInfo` (adapter listing) carries `required`; `optional`
stays on the wire **derived** as `not required`, deprecated.

### 2.5 The renderer

`AdapterWizardDialog` (client) maps `current_step` through `mapConfigStep()` —
null-tolerant on `required` and `fields` — and branches on `step_type`:
`discovery` → discovered list + manual URL; `oauth`/`device_auth` → sign-in
button; `select` → `options_method` results as radio/checkbox rows (it does
**not** render `fields`; the declared field is the output name); `confirm` →
summary; everything else → `InputStepContent` over `fields`. On every advance
it re-fetches status and renders whatever `current_step` says, so a skipped
step simply arrives as the next step. The step counter can jump.

---

## 3. Half 2 — exposure: test tags

### 3.1 Vocabulary

Every control carries `<prefix>_<name>`:

| prefix | count | meaning |
|---|---|---|
| `btn_` | 425 | a button; `btn_wizard_next`, `btn_wizard_complete`, `btn_nav_drawer_open` |
| `input_` | 138 | a text field; the wizard's are `input_config_<field.name>` |
| `card_`, `chip_`, `item_`, `menu_`, `txt_`, `text_`, `screen_` | 20–40 each | containers, choices, list rows, drawer items, labels, screen markers |
| `opt_` | | one of a mutually exclusive set; `opt_run_without_ai` / `opt_run_with_ai` |

Wizard tags: `item_adapter_type_<type>`, `item_discovered_<id>`,
`input_manual_url`, `btn_submit_manual_url`, `btn_oauth_sign_in`,
`input_config_<name>`, `btn_wizard_next`, `btn_wizard_complete`,
`btn_wizard_confirm`, `btn_wizard_dismiss`.

### 3.2 Proofs

A tag proves *visibility*. The modifier proves *drivability*:

| modifier | registers | `/click` does |
|---|---|---|
| `testable(tag)` | position only | Robot fallback at the centre — **not reliable** (CIRISClient#28) |
| `testableClickable(tag) { }` | position + handler + clickable | runs the handler on the UI thread (#30) |
| `testableWithHandler(tag) { }` | position + handler | runs the handler |
| `testableInput(tag)` | position + text sink | `/input` lands text; `inputValue` is read back (#31) |

All three modifiers are one implementation in `platform/TestAutomation.kt`
(#33), registering on composition and **unregistering on dispose**.

### 3.3 Present vs usable

An element is **present** when composed and positioned. It is **usable** when
present, on screen, and drivable. The two differ exactly where it hurts:
`ModalNavigationDrawer` composes its content always and translates it off
screen when closed, so on a phone the whole nav rail is present with live
handlers and cannot be tapped. As of 0.5.204:

- `ElementInfo.visible` = the window-clipped bounds have area (`isOnScreen`)
- `/tree` lists present elements and says which are visible
- `/wait` resolves only on usable (positioned ⇒ must be visible; handler-only popup content keeps its allowance)
- `/click` and `/input` refuse an off-screen element rather than fire it
- **every refusal names what IS usable**: `…; on screen and drivable now: [btn_nav_drawer_open, …]`

### 3.4 Lints

- `client/tools/check_ui_drivable.py` — every interactive control uses a
  drivable modifier; fails on **new** offenders against a baseline (~185)
- `GET /undrivable` — the runtime half: what is tagged-but-not-drivable on the current screen
- `check_localization_sync.py --server-src` — server-owned strings byte-exact

### 3.5 Endpoints

Shared, all platforms: `/health` `/screen` `/tree` `/act` `/click` `/input`
`/wait` `/element/{tag}` `/undrivable` `/state`. Desktop adds `/mouse-click`,
`/mouse-click-xy`, `/screenshot`, `/navigate`. Desktop still runs its own copy
of the server; the remaining consolidation is #33.

---

## 4. The seam

A manifest field named `dialect` is, by construction, drivable as
`input_config_dialect`. The **outputs vocabulary of half 1 is the addressing
vocabulary of half 2.** That is the property everything else is built on:

- a step can be written as YAML, served by a mock agent, rendered by the client, and driven by the gate before any adapter code exists;
- a flow (#39) can be linted against both halves: *"this flow drives `input_config_dialect`; the manifest declares `dialect` on step `select_dialect`; that step is reachable after `…`"*.

---

## 5. Contract summary

**Agent guarantees.** Every manifest passes the lint. `current_step` is the one
evaluator. A select writes its declared field. `required` is enforced. The
session wire above is stable; new keys are additive.

**Client guarantees.** `mapConfigStep` is null-tolerant on every optional key.
The renderer branches on `step_type` and nothing else. Every wizard control is
tagged from the table in §3.1 and drivable. `run_without_ai` and every other
field of `CompleteSetupRequest` reaches `POST /v1/setup/complete`
(`CompleteSetupWireTest` counts them — #41 was the SDK re-typing dropping 15).

**Both.** Absence is never a negative. A step with no `required` is not
required; a select with no `fields` writes only its `step_id`; a session with
no `current_step` is complete.

---

## 5a. The look — two grounds, sixteen tokens, nine primitives (wave 0)

The locked spec (Claude Design, 2026-09) settles how the client looks and what
it is made of. Wave 0 landed the spine; everything after it composes from it.

**Two grounds.** Paper (light) and Instrument (dark), following the brightness
preference. Sixteen tokens resolved per ground in `ui/theme/CirisTokens.kt` —
the ONLY file that may name a colour — and read by name:
`CirisTheme.tokens.mute`, `CirisTheme.tokens.circle(CohortScope.FAMILY)`.
Three surfaces (ground · raised · sunken) and no fourth; raised is lighter than
ground and sunken darker in BOTH grounds; every text token clears 4.5:1 on
every surface (`CirisTokensContrastTest` measures it — two shipped values were
re-resolved because they did not). `CirisTheme` also feeds Material's neutral
slots from the tokens, so the 1,600 existing `MaterialTheme.colorScheme` reads
land on the grounds without being touched; the three accents stay on the
person's `ColorTheme` until wave 2 decides.

**The lint.** `client/tools/check_colour_literals.py` refuses a NEW colour
literal anywhere else (a baseline of 933 in 46 files at landing that can only
fall). Type is a five-step scale (`CirisType`: display · title · body · label ·
signed — mono marks everything signed); shape is 5–6dp radius, hairlines, no
shadows (`CirisShape`).

**Nine primitives** under `ui/primitives/`, and every screen is made of them
and nothing else: `CardShell` (never nests — a nested shell throws in test
mode), `FieldRow` (label above value by default; two-column at ≥560dp, the
one layout that differs), `ItemRow` (icon · title · meta · hamburger),
`ReceiptSheet` (the five facts, generic over any claim), `ScopePill` (the only
place a circle colour is rendered), `Chip`, `StateBlock` (populated · empty ·
loading · error · gone · hidden-by-your-rules · UNREVIEWED — error never looks
like empty, unreviewed is never green), `ConfirmSheet` (exactly three facts),
`CeremonyBlock`. A tenth primitive means a misclassified family or a design
inventing something the wire cannot carry.

**The receipt is the test.** If a row came off the wire as a signed claim its
`ItemRow` carries a `Receipt` and therefore a hamburger (`btn_receipt_<id>`)
that opens the same five facts as a long-press: who it is about ·
who sent it · who can see it · what it is · the rule it follows
(`subject_key_ids` · `attesting_key_id` · `cohort_scope` · `dimension` ·
`consent:scope`). A fact is `Wire` (the node sent it), `ByRule` (the
constitution fixes it, section named) or `NotSent` ("This node did not send
this." in the error tone) — never a guess, never blank, never reduced. A row
with no hamburger is furniture. CSD-006 is the template; CSD-005 (People) is
its first binding.

**The namespace is the design system.** `ceg/Dimensions.kt` is generated from
the pinned CEG registry (`client/ceg/`, rc5@44ae7b2): one `Dim.<name>` per
family with its polarity, renderer class, and label/gloss localization keys.
A screen names `Dim.consentKind`; a family with no registry row cannot be
named, so cannot be rendered (CC 3.1.7 R2). `gen_dimension_table.py --check`
keeps the table current and refuses a gloss or renderer override the registry
does not know. The 71 glyphs are generated the same way from the design's path
data (`client/design/icon-paths.json` → `ui/glyphs/CirisGlyphs.kt`; `Glyph()`
draws them, dashes honoured).

**The proof screen** is People (`ui/screens/ContactsScreen.kt`): a list, an
empty state that lands on "add someone", loading, the node-too-old error, and
the receipt on every contact row. The screen class, nav id and every
`contacts_*` tag are unchanged — they are the contract CIRISAgent's
five-platform gate drives — and the file names no colour.

**The spine (after wave 1).** A live run showed the frame was not yet sound,
and five things were fixed before any more cards were built on it. *A tab is
named for what it holds*: Files holds files, so until the files spine (B3)
exists it holds nothing and says so, and the memory graph, the environment
snapshot, the commons, the cognitive sessions, tickets and the scheduler moved
to where they honestly belong. *The accord is in one place* — Everyone ›
Safety carries the trust root, the holder flow and the `accord:*` attestations,
because in an emergency nobody should have to remember which of three tabs we
filed the kill switch under. *Settings is this device's*, not a rule of a
circle, and every build can reach it. *One top bar and one back*: a screen
inside the shell draws neither — `LocalInsideShell` says the frame is already
there and `ui/shell/ScreenTopBar.kt` keeps the screen's own actions while
dropping its title and its arrow, so the shell's card header is the only
title and `btn_nav_back` the only back. Wave 1 had said "inside the shell" by
forcing `LocalIsCompactWindow` true at every width, which told a 1600dp desktop
it was a phone; the two questions now have two names. *The side can be put
away*: `btn_rail_toggle` on the mark opens and closes the rail, and the circles
fall back to the bottom bar when it is closed, because they are the one piece
of chrome that never moves.

**The shell (wave 1).** `ui/shell/CirclesShell.kt`: five circles in a fixed
bottom bar (a 208dp rail at ≥900dp, subtitles and the five instruments under
it), the seven tabs above the content (scrolling below 700dp, all fitting
above), My things (the avatar, top left — not a sixth circle) and Stop
everything (top right, every circle, every tab, above the scroll). The one
tree is `ui/nav/CirclesNav.kt`: every surface placed once, in a tab for some
circles or under an instrument, from the Card Atlas; `CirclesNavTest` pins
no orphans, no double placements, and the node build a subset of the agent
build. A tab with one card shows it (People *is* Contacts); with several,
rows; with none, the honest sentence for that circle. The SOON badge, the
placeholder component, the substrate gates, the six rail groups and the five
placeholder-only screens are deleted, not restyled.

Tags: `circle_<slug>`, `tab_<id>`, `btn_my_things`, `nav_instrument_<id>`,
`btn_stop_everything`, `btn_rail_toggle`, `shell_card_header` /
`shell_card_title`, `btn_nav_back`, and `nav_epistemic_<surface>` wherever a
surface is listed; `testing/gate/nav_map.py` derives every hop from the tree,
and all 49 of them were driven on a live agent build after the spine pass.

**Next, in order.** Geist as the faces behind the two `CirisType` family
slots. Then wave 2, one agent per circle (Just me and Family first), each
finishing all seven tabs — per-screen top bars go, the literal baseline falls
to zero, the receipt lands on every row. Files (wave 3) lands with the first
circle.

## 6. Not built, in order

1. **`/tree` on timeout prints what is on screen** — done in 0.5.204 for `/wait`, `/click`, `/input`.
2. **A generated screen registry** from `check_ui_drivable.py`: screen → tags → modifier, emitted, not maintained.
3. **Flows**: `flow: <name>`, `client: ">=0.5.204"`, `steps: [manifest steps and screen tags]`, `asserts: [{env: …}]` — version-legible, linted against the registry and the manifests.
4. **A mock agent**, in mockllm's shape (`MOCK: true`, fail-closed in production), serving `/v1/setup/*`, `/v1/system/*`, `/v1/chat/*` from the manifests.
5. **Regenerate the client SDK** from a current OpenAPI; `openapi.json` says `1.0.0`.

Known gaps: `mcp_server`'s manifest names an `options_method` no class
implements; `get_config_schema()` in three adapters duplicates the manifest and
nothing calls it; the desktop test server is not yet the shared one.
