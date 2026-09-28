# CSD-042 — Help (My things › Help)

**CSD**: CSD-042 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can find out what this thing is, what it keeps, what it cannot do, and
who to ask — from any screen, on any build, whether or not anything is
reachable.**

Serves **CC 1.15.4** (Incompleteness Awareness), whose third bullet is the one a
help surface is accountable to: *"Transparent Signalling: Clearly communicate
uncertainty and reasons for deferral."* CC 7.1.1 repeats it as a duty —
*"communicate limits"* — and because `capacity:incompleteness_awareness` is one
of the five factors and `capacity:composite` is their **minimum** (CC 3.1.8.1), a
surface that hides its limits caps the whole composite. Help is where the limits
are said out loud.

It is also the only card in the app that must work when **nothing** works, which
is why it is its own instrument rather than a section of Account.

## 2. Surface (what)

```yaml csd:surface
surface: help
screen: Help
```

`nav_map` derives `btn_my_things -> nav_instrument_help -> nav_epistemic_help`.
It is the instrument's only card.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:help_section_id
    use: display-only
    type: "enum[getting_started,features,settings,privacy,troubleshooting]"
    example: "privacy"
    renders: "an expandable card per section, titled from mobile.help_section_<id>"
    tag: "item_help_{sectionId}"
  - ceg: x_private:help_item
    use: display-only
    type: "list[string]"
    example: ["mobile.help_q_data_stored", "mobile.help_a_data_stored"]
    renders: "a question and its answer inside the expanded card; every string is a localization key, so all 29 bundles carry it"
    tag: "proposed:help_item_{key}"
  - ceg: x_private:app_version
    use: display-only
    type: string
    example: "0.5.224"
    renders: "Version 0.5.224 (build 1187) — from getAppVersion() / getAppBuildNumber(), the PLATFORM's idea of the version"
    tag: "proposed:help_app_version"
  - ceg: x_private:node_version
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. The card shows the app's version and never the node's, so 'this node is too old for X' — the one refusal the no-gating rule allows — cannot be diagnosed from Help."
    tag: "proposed:help_node_version"
  - ceg: x_private:limits_statement
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "NOT RENDERED. There is no section saying what this build does NOT do — no machine-translation disclosure, no 'no native reviewer', no named CC gaps. See §6."
    tag: "proposed:help_limits"
```

```yaml csd:states
populated: {tag: "proposed:help_sections"}
empty:     {tag: "proposed:help_empty", renders: "not reachable — `getHelpSections()` is a compiled-in list, so the populated state is the only one the data can produce"}
loading:   {tag: "proposed:help_loading", renders: "not reachable — nothing is fetched"}
error:     {tag: "proposed:help_error", renders: "not reachable — nothing can fail"}
```

**Three of the four states are genuinely unreachable, and that is the point of
this card.** Help takes no `apiClient`, makes no call, and reads one compiled-in
list plus two platform functions. CSD/3 §2.2 requires all four to be declared;
declaring them as unreachable with the reason is the honest form, and it is a
property worth pinning in a test, because the first dependency added here is the
one that breaks the promise the instrument makes.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the FAQ | `getHelpSections()`, compiled in (`HelpScreen.kt:303-345`) | CIRISClient | live, offline |
| the app version | `getAppVersion()` / `getAppBuildNumber()` | CIRISClient (per-platform) | live, offline |
| GitHub issues | `btn_help_github` → `https://github.com/CIRISAI/CIRISAgent/issues` | external | live |
| docs | `btn_help_docs` → an external URL | external | live |
| the node's version | `GET /v1/system/health` | CIRISServer | live and **uncalled here** |
| the node's own version, unenriched | `GET /v1/health` | CIRISServer `src/health.rs:585` (handler `server_health`) — node-only by design (`:573-575`: "a caller that wants the node's own answer must keep having somewhere to get it") | live and **uncalled anywhere in the client** — the client reads `/v1/system/health` (`CIRISApiClient.kt:6122`) and `:4243/health` (`BackendEndpoint.kt:72`). For a Help card that says "this is the node you are talking to", this is the route that cannot be answered by a folded brain. The route-coverage report said the client "falls back to" it; it does not — only comments name it (`CIRISApp.kt:1215-1218`, `StartupViewModel.kt:41, 492`) |

## 4. Flow (how)

On a client with **no node configured at all** — the hardest case, and the one
this card exists for — open My things › Help.

```yaml
expect:
  state: populated
  count: {of: "item_help_*", eq: 5}
  visible: ["proposed:help_app_version"]
```

Expand the privacy section: click `item_help_privacy`.

```yaml
expect:
  visible: ["proposed:help_item_mobile.help_q_data_stored"]
```

## 5. QA plan

**Platforms.** All five, **and every one of them in the offline state**. The
version strings are the only per-platform code on the screen, so a shot per
platform is the test.

**Not tested here.** The two external links (they leave the app); the
translations themselves, which `check_localization_sync.py` owns and which no
flow should re-assert.

## 6. Delta — card vs API vs CC

* **The help card does not say what the app cannot do.** This is the substantive
  gap, and CC 1.15.4 is the reason it counts as one. Concretely missing, from
  things this repo already knows: that every non-English string is
  machine-translated and MQM-reviewed by a judge of a different model family than
  the writer, with **no native-reviewer pipeline** behind it; that capacity scores
  are an operator-facing render the agent never reads (CC 3.4.5); that revoking a
  device does **not** re-encrypt what was already shared (CC 4.5.12.1, CC 3.3.6.1);
  that a deletion request has no completion receipt today (CSD-039 §6). **Ask
  (CIRISClient):** add a sixth section, `limits`, holding exactly those, with the
  CC reference on each. Each is a sentence this repo has already written
  somewhere a user will never read.
* **Help cannot diagnose the one refusal the no-gating rule permits.** A node too
  old to serve a route is a legitimate, visible refusal (CSD-005 shows the
  pattern), and the person's next question is "what version am I on?" — which
  Help answers for the app and not for the node. **Ask (CIRISClient):** show the
  node's version beside the app's, from `GET /v1/system/health`, and say plainly
  when the node is unreachable rather than omitting the row.
* **A property worth a test, not a fix.** Help has no `apiClient` parameter and
  must keep not having one. **Ask (CIRISClient):** a unit test asserting
  `HelpScreen`'s signature takes no client — the same shape as
  `narrowingIsPurelySubtractive`, guarding a promise that is currently kept only
  by habit. The node-version row above is the first thing that will tempt someone
  to break it; it should arrive as an optional, already-resolved string passed in
  by the shell, not as a fetch on this screen.
* **`NavSurface.Help` is returned for a screen that is not Help, and that makes
  one obvious flow assertion unsound.** `screenToSurface` has a single `when`
  branch covering six screens with no `-> null`
  (`CIRISApp.kt:5960-5962`): `Screen.Startup, Screen.Login, Screen.Setup,
  Screen.ServerConnection, Screen.ClaimNode, Screen.Help -> NavSurface.Help`.
  `showSidebar` (`:1789-1792`) suppresses the shell for **four** of those, not
  five — `Screen.ClaimNode` is absent, and the comment at `:1785-1786` names the
  same four ("Pre-login screens (Startup/Login/Setup/ServerConnection) have no
  shell") — so the two definitions of "flow-only" agree with each other and both
  disagree with the branch, which is why this has survived. On ClaimNode the
  shell therefore renders with `activeSurface = NavSurface.Help`; because Help is
  an *instrument* surface, `CirclesNav.tabOf(Help)` is null and the tab row runs
  on a null tab. The wrong back target is masked separately, by the legacy map at
  `:4921` taking precedence over `activeSurface`.
  **Consequence for §4:** a step that asserts arrival on Help by reading the
  highlighted nav row would pass while standing on the claim screen, so that
  assertion is unsound until the fix lands and this CSD's flow must assert a
  `item_help_*` element instead.

  **Ask (CIRISClient): `Screen.ClaimNode -> NavSurface.Nodes`, on its own line,
  and leave `showSidebar` alone.** This codebase already has a rule for an
  in-shell detail screen with no nav row of its own — it maps to its **parent**
  surface, so the parent stays lit while you are inside it — and states it in a
  comment at `CIRISApp.kt:5935-5936`, applied on the line beneath it at `:5937`:
  *"A chat keeps the Contacts card lit — it
  is a leaf of that surface, not a sidebar destination of its own."* Four screens
  already follow it: `SkillStudio`/`SkillImport -> Skills` (`:5910`),
  `NetworkPeerDetail -> LayerGlobalCommons` (`:5926`), `UserChat -> Contacts`
  (`:5937`), `DutyConferral -> Accord` (`:5941`). ClaimNode's parent is Nodes:
  its entries are the ManageNodes button (`:4042`) and an InteractScreen
  affordance (`:2969-2971`), **both inside the shell**, and its exit is
  `Screen.ManageNodes`. So `-> null` would make it the only in-shell detail
  screen that highlights nothing, and suppressing the chrome would hide the nav
  on a screen the person reached *by navigating*.

  **An earlier cut of this paragraph asked for `-> null` plus a `showSidebar`
  exclusion. That was wrong on both halves** and is corrected here rather than
  quietly dropped: it would have traded a wrong highlight for no highlight, and
  hidden the shell on an in-shell screen. The flows CSDs caught it.

  **The four pre-login screens must stop mapping to Help in the same commit.**
  `Screen.Startup`, `Screen.Login`, `Screen.Setup` and `Screen.ServerConnection`
  each return `NavSurface.Help` today and are wrong for a different reason from
  ClaimNode: they have no parent at all, because they are outside the shell
  rather than inside it. `-> null` is right for exactly these four. They are
  currently harmless only because `showSidebar` happens to exclude them, which
  means the correctness of `screenToSurface` is being supplied by a *different*
  function — so any caller that reads it outside that guard gets `Help` for the
  login screen. Fixing ClaimNode and leaving these four would remove the one
  symptom anybody has seen while keeping the latent defect that produced it.

  **The same defect, opposite symptom, on the Nodes card: `Screen.VerifyAgent`
  is `-> null` today** (`CIRISApp.kt:5964`) with its only entry
  `btn_manage_nodes_verify` and its exit `Screen.ManageNodes`, so the Nodes row
  goes dark while a person verifies a build. By the convention above it should
  also be `NavSurface.Nodes`. `AddFederationId` correctly stays `null` — it
  returns to `addFederationIdReturnScreen` and can be auto-pushed over the
  landing screen by the catch-up effect (`:987-999`), so it has no single parent.

  **The whole branch, disposed of:** four pre-login screens `-> null`; ClaimNode
  and VerifyAgent `-> NavSurface.Nodes`; AddFederationId stays `null`; Help stays
  `NavSurface.Help`. Three distinct answers across eight screens, grouped four
  ways, which is how they came to be lumped into two arms in the first place, and
  why the fix has to name every screen rather than the one that showed. **CSD-085** owns the claim screen and
  **CSD-087** the verify screen; **CSD-035** owns the Nodes card whose row is the
  one that should stay lit.

* **Placement.** Correct, and structurally so: an instrument of its own, reachable
  from the avatar on every screen, needing nothing. No circle would be right —
  Help is not about an audience.
