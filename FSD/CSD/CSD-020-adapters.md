# CSD-020 — Adapters (the channels the agent acts through)

**CSD**: CSD-020 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see every channel their agent can act through, what each one
lets the agent do, and take one away — and the screen never shows "no
adapters" when it means "this build has no agent to adapt."**

An adapter is not a plugin. It is the concrete form of a delegation: the owner
gave the brain `agency:message_io` (CC 4.4.3.4.3, the `agency:*` / `infra:*`
split), and an adapter is where that scope touches a real platform. Serves
**Contextual Integrity**: the flow a Discord adapter opens is a transmission
principle the owner agreed to, and the screen is the only place they can see it
or close it.

The screen also carries the OAuth wizard — the one place in this app where a
person hands a third party's credential to their agent. CC 2.4.1.2.1 names
that act precisely: it is a **grant**, not a delegation and not a licence, and
"a grant is non-transferable by default." The screen says none of this today.

## 2. Surface (what)

```yaml csd:surface
surface: adapters
screen: Adapters
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_adapters`. The surface is listed in the `this-node` instrument
(`CirclesNav.kt:145`) and is **not** in that instrument's `agentOnly` set
(`CirclesNav.kt:153`) — see §3 and §6, because that is a defect, not a choice.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "agent_files:{kind}:{platform_or_target}"
    bind: {kind: adapter, platform_or_target: discord}
    use: display-only
    type: string
    example: "agent_files:adapter:discord"
    renders: "Discord — the adapter this row is. The family is the adapter's BYTES (CC 3.1.1 / CC 3.1.9.1), which is what a person is trusting when they load one."
    tag: "proposed:adapters_row_discord"
  - ceg: x_private:adapter_is_running
    use: display-only
    type: "enum[running,stopped,needs_reauth]"
    example: "running"
    renders: "a chip on the row: Running / Stopped / Sign in again"
    tag: "proposed:adapters_row_state_discord"
  - ceg: x_private:services_registered
    use: display-only
    type: "list[string]"
    example: ["communication", "tool", "wise_authority"]
    renders: "What this gives the agent — Messaging · Tools · Wise Authority. Today this is `GET /v1/system/adapters/{id}`'s `services_registered`, rendered as a bare list with no statement that these ARE the agent's new abilities."
    tag: "proposed:adapters_services_discord"
  - ceg: x_private:adapter_tools
    use: display-only
    type: "list[string]"
    example: ["send_message", "list_channels"]
    renders: "What it can do there — send_message, list_channels (`tools` on the details route)"
    tag: "proposed:adapters_tools_discord"
  - ceg: x_private:agency_scope
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Under which grant — `agency:message_io` (CC 4.4.3.4.3). NOT SENT: no adapter route carries the `delegates_to` row this adapter runs under, so the sheet says 'This node did not send this.' rather than leaving a blank."
    tag: "proposed:adapters_receipt_scope"
  - ceg: x_private:oauth_grant_scope
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "What you are about to hand over — the external OAuth scopes (e.g. Home Assistant `read`+`control`). NOT SENT: `startAdapterConfiguration` returns a step list, never the scope string in the authorize URL."
    tag: "proposed:wizard_oauth_scope"
  - ceg: x_private:adapter_metrics
    use: display-only
    type: int
    example: 1284
    renders: "1,284 messages · 0 errors · up 6d — `metrics` on the details route"
    tag: "proposed:adapters_metrics_discord"
```

**Two fields are `unconfirmed` on purpose and they are the constitutionally
load-bearing ones.** A person looking at this screen cannot learn (a) which
delegation scope the adapter runs under, or (b) what the OAuth grant they are
about to sign actually permits. Both are facts the substrate holds and no route
returns. CC 2.4.1.2.1's whole point is that a licence, a grant and a delegation
are three different things with three revocation paths; a UI that renders all
three as a green "Connected!" has collapsed them.

```yaml csd:states
populated: {tag: "proposed:adapters_list"}
empty:     {tag: "proposed:adapters_empty", renders: "mobile.adapter_no_adapters — 'No adapters yet' + 'Tap + to add one'. AdaptersScreen.kt:148 renders this whenever `adapters.isEmpty() && !isLoading`."}
loading:   {tag: "proposed:adapters_loading", renders: "AdaptersScreen.kt:195 — a bare CircularProgressIndicator, no sentence. Correct shape, no tag."}
error:     {tag: "proposed:adapters_error", renders: "DOES NOT EXIST. `AdaptersViewModel` has no `_error` flow at all (only `_wizardError`), so a failed `GET /v1/system/adapters` falls through to `adapters.isEmpty()` and the person is told they have no adapters."}
```

**`error` and `empty` are the same pixels today, and this is the exact failure
CSD/3 §2.2 names.** On a node build it is worse than a collapse: it is a
confident false statement. `CIRISApiClient.listAdapters` calls `nodeSkip`
(`CIRISApiClient.kt:7745`) and **returns an empty list rather than an error**,
so "No adapters yet · Tap + to add one" is rendered on a build where + cannot
work. The "+" then calls `GET /v1/system/adapters/loadable`, which is not
node-skipped, 404s, and surfaces as `wizardError`.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the adapter list | `GET /v1/system/adapters` | CIRISAgent | live (`routes/system/adapters.py`) — **node-skipped client-side, returns `[]`** |
| one adapter's detail | `GET /v1/system/adapters/{adapter_id}` | CIRISAgent | live |
| what may be added | `GET /v1/system/adapters/loadable` | CIRISAgent | live (`adapters.py:595`) — **not node-skipped; 404s on a bare node** |
| load one | `POST /v1/system/adapters/{adapter_type}` | CIRISAgent | live |
| reload one | `PUT /v1/system/adapters/{adapter_id}/reload` | CIRISAgent | live |
| remove one | `DELETE /v1/system/adapters/{adapter_id}` | CIRISAgent | live |
| start the wizard | `POST /v1/system/adapters/{adapter_type}/configure/start` | CIRISAgent | live (`adapter_config.py:258`) |
| one wizard step | `POST /v1/system/adapters/configure/{session_id}/step` | CIRISAgent | live (`adapter_config.py:397`) |
| session status | `GET /v1/system/adapters/configure/{session_id}` | CIRISAgent | live (`adapter_config.py:326`) |
| finish | `POST /v1/system/adapters/configure/{session_id}/complete` | CIRISAgent | live (`adapter_config.py:660`) |
| OAuth return (browser) | `GET /v1/system/adapters/configure/{session_id}/oauth/callback` | CIRISAgent | live, **unauthenticated** — `state` must equal `session_id` (`adapter_config.py:490`) |
| OAuth return (deep link) | `GET /v1/system/adapters/oauth/callback` | CIRISAgent | live; generic across providers, `state` carries `provider:session_id` (`adapter_config.py:569`) |
| the scope the adapter runs under | — **unconfirmed** | CIRISAgent | blocks `building` for `adapters_receipt_scope` |
| the OAuth scopes being granted | — **unconfirmed** | CIRISAgent | blocks `building` for `wizard_oauth_scope` |

Agent routes read from the `~/CIRISAgent` working tree, last commit
**2026-08-15** — six weeks stale relative to this branch, so "live" here means
"live as of that tree."

**Nothing under `/v1/system/adapters` exists on the node.** `CIRISServer`'s
route literals (`git show origin/main -- 'src/*.rs'`) carry `/v1/system/data`,
`/v1/system/health` and `/v1/system/verify-status` and nothing else under
`/v1/system`. Every adapter call on a node build is a 404.

## 4. Flow (how)

Sign in on an agent build; open My things → This node → Adapters.

```yaml
expect:
  state: populated
  count: {of: "adapters_row_*", min: 1}
  visible: [btn_adapters_refresh, btn_add_menu]
```

Expand a row.

```yaml
expect:
  visible: [adapters_services_discord, adapters_tools_discord, adapters_metrics_discord]
```

Add one: `btn_add_menu` → pick a type → the wizard.

```yaml
expect:
  visible: [btn_wizard_next, btn_wizard_close]
```

On an OAuth adapter (Home Assistant), the OAuth step.

```yaml
expect:
  visible: [btn_oauth_sign_in, wizard_oauth_scope]
```

On a node build, open the same surface.

```yaml
expect:
  state: error
  visible: [adapters_error]
  absent: [adapters_empty, btn_add_menu]
```

The last block is the one that fails today, and it is the point of this CSD.

## 5. QA plan

**Platforms.** All five. The OAuth leg differs per platform (Android deep
link `ciris://oauth/callback` → the generic callback route; desktop and iOS use
the system browser and the per-session HTML callback), so the OAuth step is
driven on Android and desktop at minimum.

**Not tested here.** A real third-party authorization (no Home Assistant in
CI); the wizard is driven to the OAuth step and stopped. `needs_reauth` is not
reproducible without expiring a real token.

## 6. Card vs API vs CC — the delta

1. **Wrong build.** `Adapters` is offered on a no-agent build and must not be
   (`CirclesNav.kt:153`). CC 4.4.3.4.3 is explicit: a key whose `identity_type`
   contains `node` "MUST carry **only** `infra:*` scopes; a verifier MUST
   reject any such key presenting any `agency:*` scope." An adapter IS the
   `agency:message_io` surface. A node has nothing to adapt, by constitution
   and not by accident. **Ask: add `NavSurface.Adapters` to the `this-node`
   instrument's `agentOnly` set.**
2. **A false empty.** `listAdapters`'s `nodeSkip` returns `[]` where the honest
   answer is "this build cannot have adapters". Even once (1) lands, the skip
   should return a refusal the screen can render, not a success.
3. **No error state at all.** `AdaptersViewModel` never models a list-level
   error. Every failed read reads as "you have none."
4. **The screen is undrivable.** Seven real tags, all of them chrome
   (`btn_adapters_back`, `btn_adapters_refresh`, `btn_add_menu`,
   `btn_add_menu_cancel`, `btn_add_adapter`, `btn_import_skill`,
   `btn_skill_studio`). The list, the rows, the empty state and every field in
   §2 are untagged. The wizard is better (`btn_wizard_*`, `btn_oauth_sign_in`,
   `input_manual_url`).
5. **The CC gap, and it is the important one.** Nothing on this screen names
   the delegation an adapter runs under or the grant an OAuth step confers.
   **Ask (CIRISAgent):** return the adapter's `delegated_scope[]` on
   `GET /v1/system/adapters/{id}`, and the external scope list on
   `POST .../configure/start`, so the wizard can say what is being handed over
   *before* the browser opens rather than "Connected!" after.
