# CSD-036 — Network (My things › This node › Network)

**CSD**: CSD-036 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: `testing/flows/drafts/csd-036-network-ops.yaml`
**Reads with**: CSD-045 (This node › Own standing — tier S, which used to be a section at the bottom of this card), CSD-051 (the hub this card opens), CSD-032 (the Identity tile, which shows the same signer key to the Everyone circle)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can read this node's own edge facts — the key it signs with, the mode
it is running in, and whether it has the disk to be a server — and can tell a
reading from a default. And the owner can say which other parties' judgements
this node's reader honours.**

Serves **CC 3.1.9 `config:{scope}`**: a node's operating configuration is "published
as an auditable record rather than **inferred from behaviour**". This card is the
place that rule is either honoured or quietly broken. It was broken (§6): the
mode row printed a ViewModel default when nothing answered. It now prints the
mode only when a read answered, and the reason when one did not.

## 2. Surface (what)

```yaml csd:surface
surface: network-ops
screen: NetworkOps
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_network_ops`. This is the operator-infra slice; the social
federation view is Everyone › Rules (`layer-global-commons`), reached from here
by `btn_netops_open_hub`.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:signer_key_id
    use: display-only
    type: string
    example: "ciris-node-4a19c2"
    renders: "Signer key — ciris-node-4a19c2 (mono), or an em dash with the read-failure block beneath it when the node did not answer"
    tag: row_netops_signer_key
  - ceg: "config:{scope}"
    bind: {scope: agent_mode}
    use: display-only
    type: "enum[client,proxy,server]"
    example: "PROXY"
    renders: "Current — PROXY. The row is ABSENT until a read answers; a node without an agent shows 'This node runs without an agent, and the mode is the agent's to report' instead"
    tag: row_netops_mode
  - ceg: x_private:server_eligible
    use: display-only
    type: bool
    example: true
    renders: "SERVER-eligible — yes (only beside a read mode)"
    tag: row_netops_server_eligible
  - ceg: x_private:available_disk_bytes
    use: display-only
    type: int
    example: 214748364800
    renders: "Available — 200.0 GB"
    tag: row_netops_disk_available
  - ceg: x_private:server_minimum_disk_bytes
    use: display-only
    type: int
    example: 107374182400
    renders: "SERVER minimum — 100.0 GB"
    tag: row_netops_disk_minimum
  - ceg: x_private:data_dir
    use: display-only
    type: string
    example: "/home/emoore/ciris"
    renders: "Data directory / /home/emoore/ciris (mono, on its own line)"
    tag: row_netops_data_dir
  - ceg: x_private:reader_standing
    use: display-only
    type: "enum[decided,no_judgements_held,unreadable]"
    example: "decided"
    renders: "a chip: this node's own reader policy over another party's judgements (tier R), for a subject key the owner types"
    tag: chip_reader_standing
  - ceg: x_private:owner_delegation_id
    use: display-only
    type: string
    example: "att-owner-serve-1"
    renders: "Owner delegation id — Named by this node (mono); the id a reader decision is taken under, read from GET /v1/admin/self on 0.5.218, typed on an older node (input_reader_delegation_id + text_reader_delegation_fallback)"
    tag: row_reader_owner_delegation
```

`config:agent_mode` is bound rather than left private because the value **is** a
constitutional object: CC 3.1.9 reserves `config:{scope}` to the node
(`owning_component: node`, reserved under CC 3.4.5) precisely so a node's mode is
a record the node signed, not a guess a reader made. The row's `use` is
`display-only`; the client never emits it. **It is still the brain's report,
not the node's** (§3, §6).

**Tier S is no longer on this card.** This node's own standings (shed load,
stop accepting, legal compulsion) have their own surface, This node › Own
standing (CSD-045). What stays here is tier R (`ReaderPolicySection`,
`section_reader_ops`, `card_reader_policy`): `input_reader_subject` (drivable),
`btn_reader_fold`, the fold's chip and its judgement rows
(`row_reader_judgement_{id16}`, the first 16 characters of the judgement id),
and the honour / decline acts (`btn_reader_honour_{id16}`,
`btn_reader_decline_{id16}`) whose dialog carries the delegation picker above
and `input_reader_reason`.

```yaml csd:states
populated: {tag: screen_network_ops}
empty:     {tag: chip_reader_standing, renders: "not a whole-card state: the identity card always draws. The reader card's 'No judgements held here' chip is a read that returned nothing, and is drawn as such"}
loading:   {tag: progress_netops_mode, renders: "the mode card carries a progress affordance and NO mode row until the read answers"}
error:     {tag: netops_error, renders: "'Could not read' in the mode card when the agent-mode read failed; netops_not_on_this_node when there is no agent to ask; netops_identity_error / netops_identity_not_on_this_node under the signer key row. Distinct from the loading affordance and from a read mode"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| signer key | `GET /v1/federation/identity` | CIRISServer | live — `src/federation_surface.rs:696`; `getFederationIdentity` (node URL) from `NetworkViewModel.loadFederationIdentity`, which now records `identityFailure` |
| agent mode, SERVER-eligibility, disk budget, data dir | `GET /v1/system/agent-mode` | **CIRISAgent**, not the node | **wrong-host** — no such route in CIRISServer `src/*.rs` on `origin/main` (0.5.217); live on CIRISAgent `routes/system/agent_mode.py:78`. `getAgentMode` (`$baseUrl`) from `NetworkViewModel.loadAgentMode`, which now records `modeRead` / `modeFailure`; on a bare node the card says the mode is the agent's to report |
| the persist identity aggregate (the Federation ID card) | `GET /v1/system/peers/federation-identity` | CIRISAgent `routes/system/peers.py:194` | live on the agent only — `getFederationIdentityAggregate` raises `RouteNotOnThisHost` in `ClientMode.NODE`; the card stays absent rather than "initializing" |
| this node's reader fold (tier R) | `POST /v1/admin/reader/fold` | CIRISServer `src/admin_ops.rs:4312` | live — `readerFold` from `SelfReaderOpsSection.kt` `reloadFold()` |
| honour / decline another party's judgement | `POST /v1/admin/reader/honour` · `POST /v1/admin/reader/decline` | `src/admin_ops.rs:4316` · `:4320` | live — `readerHonour` / `readerDecline`. Body `ReaderCommit {delegation_id, reason}`, resolved by `resolve_owner_authority`: the owner's own `infra:serve` delegation |
| the delegation a reader act is taken under | `GET /v1/admin/self` → `owner_delegations` (CIRISServer#676, `integ/0.5.218` `src/admin_ops.rs:3348-3360`) | CIRISServer | live on 0.5.218 (unreleased) — read once by `ReaderPolicyCard`; typed fallback on an older node (CSD-045 §2 has the four-way rule) |
| this node's own standings (tier S) | `GET /v1/admin/self` and the six `/v1/admin/self/*` acts | CIRISServer | live — **CSD-045's card** (`Screen.NodeSelfStanding`), not this one |
| a `config:agent_mode` record signed by the node | — | CIRISServer | **missing** — CIRISServer#668, see §6 |

Admin lines are CIRISServer `origin/main` 046e1b39 (0.5.217); on `integ/0.5.218`
(97900cf5) the same routes sit +96 lines lower (`:4384-4416`), unchanged in set.

## 4. Flow (how)

Sign in on a node with a brain; open My things › This node › Network.

```yaml
expect:
  state: populated
  visible: [screen_network_ops, row_netops_signer_key, row_netops_mode, section_reader_ops]
  one_of: {row_netops_mode: [CLIENT, PROXY, SERVER]}
```

On a node-only build (no brain answering 8080):

```yaml
expect:
  state: error
  visible: [netops_not_on_this_node]
  absent: [row_netops_mode]
```

That second block was written before the fix and failed; it now passes.

## 5. QA plan

**Platforms.** All five. The node-only variant needs the run-without-AI build,
which is exactly where the defect showed.

**Not tested here.** The reader acts' outcomes (they need a judgement another
party issued about a subject this node holds); `btn_netops_open_hub`'s
destination (`layer-global-commons`, CSD-051).

## 6. Delta — card vs API vs CC

* **Wrong host, and it failed open — the failing-open half is fixed.** The card
  is labelled "CIRISEdge · this node" and three of its four cards come from the
  **brain**. When the brain was absent the mode row printed `PROXY`, a fact
  about the client's Kotlin default presented as a fact about the node.
  `NetworkViewModel` now keeps the selector's `mode` (the hub and Settings need a
  value to highlight) apart from `modeRead` (null until read, null again when a
  read fails) and records `modeFailure`; the card renders `modeRead` or the
  reason. `NetworkOpsModeReadTest` was run red against the old view model.
* **CC → card.** CC 3.1.9 wants the mode to be an auditable `config:{scope}`
  record. The node has no route that serves one. **Ask (CIRISServer):** serve
  the node's own `config:agent_mode` record (attester + `asserted_at`) so this
  row stops depending on the brain at all — the mode of the *node* is not the
  brain's to report. **CIRISServer#668** (open) is that ask.
* **Tier S moved out.** A warrant-canary declaration is not a row under the
  disk budget (CSD-045).
* **Placement.** Correct. These are the node's own facts; This node is where the
  Locked Spec puts the substrate.
