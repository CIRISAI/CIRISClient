# CSD-020 — Adapters (the channels the agent acts through)

**CSD**: CSD-020 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
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
    blocked_by: CIRISAgent#1211
  - ceg: x_private:oauth_grant_scope
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "What you are about to hand over — the external OAuth scopes (e.g. Home Assistant `read`+`control`). NOT SENT: `startAdapterConfiguration` returns a step list, never the scope string in the authorize URL."
    tag: "proposed:wizard_oauth_scope"
    blocked_by: CIRISAgent#1211
  - ceg: x_private:adapter_metrics
    use: display-only
    type: int
    example: 1284
    renders: "1,284 messages · 0 errors · up 6d — `metrics` on the details route"
    tag: "proposed:adapters_metrics_discord"
  # §7 — connectors: database credentials handed to the SQL adapter via /v1/connectors.
  - ceg: x_private:connector
    use: display-only
    type: string
    example: "Orders · sql · sql_postgres_1a2b3c4d"
    renders: "one card per connector: name, type and id — never the credential, which no route returns"
    tag: adapters_connector_0
  - ceg: x_private:connector_standing
    use: display-only
    type: "enum[registered,healthy,unhealthy,disabled]"
    example: "registered"
    renders: "Handed over, never tested. That is not proof the agent can use it. — never 'Connected'"
    tag: adapters_connector_standing_0
  - ceg: x_private:connector_grant
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "What it lets your agent do — the SQL adapter's seven tools, labelled as what ANY SQL connector grants, because no route says what this one does"
    tag: adapters_connector_grant_0
    blocked_by: CIRISAgent#1211
  - ceg: x_private:connector_delegation
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Delegation record: None. Handing this credential over wrote no delegation or consent record."
    tag: adapters_connector_delegation_0
    blocked_by: CIRISAgent#1211
  - ceg: x_private:connector_test
    use: display-only
    type: "enum[passed,failed,not_run]"
    example: "not_run"
    renders: "Did not run. The agent reported success without touching the database: … (tool bus unavailable, skipped)"
    tag: adapters_connector_test_result_0
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
| every adapter module type | `GET /v1/system/adapters/types` | CIRISAgent (`adapters.py:237`) | live, **wired and unreached** — `getModuleTypes` (`CIRISApiClient.kt:8040`) has no caller |
| the ones with a config wizard | `GET /v1/system/adapters/configurable` | CIRISAgent (`adapters.py:533`) | live, **wired and unreached** — `getConfigurableAdapters` (`CIRISApiClient.kt:8095`) has no caller; `loadable` (above) is what the card reads |
| what survives a restart | `GET /v1/system/adapters/persisted` · `DELETE /v1/system/adapters/{adapter_type}/persisted` | CIRISAgent (`adapters.py:273, 315`) | live, **not called** — the card cannot say which loaded adapters will come back after a restart, or stop one from coming back |
| discovered adapters with eligibility | `GET /v1/system/adapters/available` | CIRISAgent (`adapters.py:368`) | live, **not called** |
| install a missing adapter's dependencies | `POST /v1/system/adapters/{adapter_name}/install` | CIRISAgent (`adapters.py:395`) | live, **not called** |
| re-check eligibility after installing | `POST /v1/system/adapters/{adapter_name}/check-eligibility` | CIRISAgent (`adapters.py:487`) | live, **not called** — so an ineligible adapter has no path to becoming eligible from this card |
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
| connectors — the list (§7) | `GET /v1/connectors` | CIRISAgent (`routes/connectors.py:327`) | live; **admin only** (`:340`) — `listConnectors`, called from `AdapterConnectorsViewModel` |
| connectors — test one (§7) | `POST /v1/connectors/{connector_id}/test` | CIRISAgent (`connectors.py:404`) | live; admin only — a failed test is HTTP 200 with `success: false`; a "skipped"/"(simulated)" success is drawn as **did not run** |
| connectors — remove one (§7) | `DELETE /v1/connectors/{connector_id}` | CIRISAgent (`connectors.py:547`) | live; admin only; irreversible, behind the three-fact ConfirmSheet — removes the route's record only (`:582-584`) |
| connectors — register (§7) | `POST /v1/connectors/sql` | CIRISAgent (`connectors.py:198`) | live, **not called** — the SQL adapter's wizard is the add door; a second form for the same credential is a duplicate |
| connectors — update (§7) | `PATCH /v1/connectors/{connector_id}` | CIRISAgent (`connectors.py:480`) | live, **not called** — `enabled` is a flag nothing that uses the connection reads (`:517-520`) |

Agent routes read from the `~/CIRISAgent` working tree, last commit
**2026-08-15** — six weeks stale relative to this branch, so "live" here means
"live as of that tree." The six catalogue / install rows were added in the
citation pass from CIRISAgent `main` 29371660de (2026-09-26), `routes/system/adapters.py`.

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

## 7. Connectors — the same credential, through a second door

**Added with the connectors work (numbered CSD-108 at assignment, folded in
here under the no-duplicate-cards rule).** `/v1/connectors*` had no caller in
the client except the generated `ConnectorsApi.kt`. It is not a second card,
because **a connector is a configuration of an adapter this card already
manages.** Read from CIRISAgent **main** at `2937166` (2026-09-26):

* `ciris_adapters/external_data_sql/manifest.json` declares a wizard whose
  `connection_server` step asks for `host`, `port`, `database`, `user`,
  `password` (typed `password`) and `connector_id` — this card's "+ → Add
  adapter" already hands exactly that credential to the agent.
* `POST /v1/connectors/sql` (`routes/connectors.py:198`) takes the same fields,
  keeps them in a module-level dict (`_connector_registry`, `:142-143`), and
  hands them to that same adapter by calling its `initialize_sql_connector`
  tool on the tool bus (`:282-291`).

**How a connector differs from an adapter, from the code.** An adapter is a
loaded module that registers services on the agent's buses (`GET
/v1/system/adapters/{id}` → `services_registered`, `tools`); it is persisted
as adapter config and survives a restart. A connector is one credential fed
INTO one adapter's tool service: it registers nothing of its own, lives only
in the route's memory (a restart empties the list while the adapter may still
hold the connection), and does nothing at all unless `external_data_sql` is
loaded — if the tool-bus call fails the route logs it and still answers
`registered` (`:292-297`). An adapter is the channel; a connector is a key
handed to it.

**What the section shows** (`AdapterConnectorsSection.kt`, under the adapter
list; `AdapterConnectorsViewModel`):

* **What it grants.** For `sql`, the seven tools the SQL adapter exposes for
  every connector (`external_data_sql/service.py:190-200`): find, export,
  **delete** and anonymise a person's rows, verify deletion, stats, and
  read-only queries (`_validate_sql_query`, `:1170-1178`). Labelled as what any
  SQL connector grants, because no route says what THIS one does
  (CIRISAgent#1211) — hence `blocked_by` on `adapters_connector_grant_<i>`.
* **Never the secret.** `ConnectorInfo` carries no `config` (`:112-122`) and
  `ConnectorDto` has no field to put one in (`AgentConnectorsTest.
  theSecretHasNowhereToLand`).
* **Never "Connected".** The standing line says what is known:
  `registered` → "Handed over, never tested. That is not proof the agent can
  use it."; `healthy`/`unhealthy` → the last test's stored result;
  `disabled` → a mark the SQL tools never read (`:517-520`).
* **No delegation record — said, not implied.** `adapters_connector_delegation_<i>`
  reads "None. Handing this credential over wrote no delegation or consent
  record (CIRISAgent#1211)." #1211 names exactly this collapse for the OAuth
  wizard; a connector is the same act with a database password.
* **Test results as what ran.** `POST /v1/connectors/{id}/test` answers
  "success" without running anything when the tool bus is absent
  ("(tool bus unavailable, skipped)", `:383-384`) and for every REST connector
  ("(simulated)", `:399-401`), and then stores `healthy` (`:451-453`). The
  section shows those as **"Did not run"**, never as a pass
  (`connectorTestOutcome`; red-tested).

**What the section deliberately does not do.**
* **Add.** The SQL adapter's wizard is the add path; a second form for the
  same credential is the duplication the card rules forbid.
* **Enable/disable.** `PATCH {enabled}` flips a flag nothing that uses the
  connection reads (`:517-520`); a switch with no effect is a false control.
* **Edit config.** It would mean re-typing a password into a field the agent
  merges blind (`:514-515`).

**Remove is irreversible, so it is confirmed.** `DELETE /v1/connectors/{id}`
"cannot be undone" (`:555`) and removes only the route's record — "The
SQLToolService in the adapter handles its own lifecycle" (`:582-584`). The
ConfirmSheet (`sheet_connector_remove`) names three facts: **who** — the
connector's name and id; **what changes** — it leaves the list for good, the
agent's SQL tools may keep the connection until the agent restarts, and
changing the database password is what cuts it now; **who signs** — nobody,
because no delegation was recorded when it was handed over, so there is none
to withdraw.

**Contracts.**

| value | endpoint | owner | state |
|---|---|---|---|
| the list | `GET /v1/connectors` | CIRISAgent (`connectors.py:327`) | live; **admin only** (`:340`). `data`: `connectors[]` of `ConnectorInfo` {`connector_id`, `connector_type`, `connector_name`, `status`, `registered_at`, `last_tested?`, `last_test_result?`, `total_requests`}, `total` |
| test one | `POST /v1/connectors/{id}/test` | CIRISAgent (`:404`) | live; admin only. `data`: `ConnectorTestResult` {`connector_id`, `success`, `message`, `latency_ms`, `tested_at`} — a failed test is HTTP 200 with `success: false` |
| remove one | `DELETE /v1/connectors/{id}` | CIRISAgent (`:547`) | live; admin only |
| register | `POST /v1/connectors/sql` | CIRISAgent (`:198`) | live; **not called** (the wizard is the add path) |
| update | `PATCH /v1/connectors/{id}` | CIRISAgent (`:480`) | live; **not called** (see above) |
| what this connector grants | — | CIRISAgent | **not served**; `blocked_by: CIRISAgent#1211` |
| the delegation it runs under | — | CIRISAgent | **not served**; `blocked_by: CIRISAgent#1211` |

**States.** `adapters_connectors` (the section) with `adapters_connector_<i>`
rows (populated) · `adapters_connectors_empty` "No database has been handed
to your agent this way since it last started." (empty) ·
`adapters_connectors_loading` · `adapters_connectors_error` /
`adapters_connectors_not_on_this_node` (error) · `adapters_connectors_admin_only`
for the 403. Controls: `btn_connector_test_<i>`, `btn_connector_remove_<i>`,
`btn_connector_remove_confirm` / `btn_connector_remove_cancel`.

**Flow.** `testing/flows/drafts/csd-020-adapter-connectors.yaml` (floor
`unreleased`).

**The delta (asks, drafts in the PR).**
1. `ConnectorTestResult` needs a `performed: bool`; the client should not
   have to match "skipped" and "(simulated)" in English to know a test never
   ran, and `status` must not become `healthy` on a test that did not run.
2. `POST /connectors/sql` must fail when the tool-bus registration fails,
   instead of logging and answering `registered` (`:292-297`).
3. `DELETE` must also drop the connector from the SQL tool service, or say
   in its response that it did not (`:582-584`).
4. The connector list must survive a restart, or the route must say it is
   volatile (`:142`); today a restart hides every connector the adapter may
   still be holding.
5. `POST /connectors/sql`'s 400 tells the caller to "Use POST /connectors for
   other types" (`:236`); no such route exists.
