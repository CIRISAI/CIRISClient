# CSD-036 — Network (My things › This node › Network)

**CSD**: CSD-036 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can read this node's own edge facts — the key it signs with, the mode
it is running in, and whether it has the disk to be a server — and can tell a
reading from a default.**

Serves **CC 3.1.9 `config:{scope}`**: a node's operating configuration is "published
as an auditable record rather than **inferred from behaviour**". This card is the
place that rule is either honoured or quietly broken, and today it is broken
(§6): the mode row prints a ViewModel default when nothing answered.

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
    renders: "Signer key — ciris-node-4a19c2 (mono), or an em dash when the node did not answer"
    tag: row_netops_signer_key
  - ceg: "config:{scope}"
    bind: {scope: agent_mode}
    use: display-only
    type: "enum[client,proxy,server]"
    example: "PROXY"
    renders: "Current — PROXY"
    tag: row_netops_mode
  - ceg: x_private:server_eligible
    use: display-only
    type: bool
    example: true
    renders: "SERVER-eligible — yes"
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
  - ceg: x_private:self_standing_axis
    use: display-only
    type: "enum[load_shed,accepting,compelled]"
    example: "load_shed"
    renders: "one card per axis — this node's own three standings (tier S, admin_ops.rs)"
    tag: "card_self_axis_load_shed"
  - ceg: x_private:reader_standing
    use: display-only
    type: string
    example: "honouring"
    renders: "a chip: this node's own reader policy over other parties' judgements (tier R)"
    tag: chip_reader_standing
```

`config:agent_mode` is bound rather than left private because the value **is** a
constitutional object: CC 3.1.9 reserves `config:{scope}` to the node
(`owning_component: node`, reserved under CC 3.4.5) precisely so a node's mode is
a record the node signed, not a guess a reader made. The row's `use` is
`display-only`; the client never emits it.

```yaml csd:states
populated: {tag: screen_network_ops}
empty:     {tag: "proposed:netops_empty", renders: "unreachable in practice — the identity card always draws; the disk card is simply absent when `status` is null, which is the defect below, not an empty state"}
loading:   {tag: "proposed:netops_loading", renders: "the three card frames with a progress affordance and no values"}
error:     {tag: "proposed:netops_error", renders: "This node did not answer for its mode. — NetworkViewModel already sets `error`; the screen never reads it"}
```

**`error` does not exist on this screen.** `NetworkViewModel.loadAgentMode()`
catches and stores the failure (`NetworkViewModel.kt:78-80`); `NetworkOpsScreen`
never collects `error`, and `row_netops_mode` renders
`mode.wire.uppercase()` from `_mode = MutableStateFlow(AgentMode.PROXY)`
(`NetworkViewModel.kt:37`, `NetworkOpsScreen.kt:94`) whether or not anything was
read. `OpsRow` publishes the value to the automation tree with the comment that
"a mode row rendered is true whether it shows a real reading or a ViewModel
default, and telling those apart is the whole point" — and the default defeats
it one layer up.

**The six `row_netops_*` tags named above are NOT `testable*` literals.**
`NetworkOpsScreen.kt` at v0.5.224 carries exactly two: `screen_network_ops` and
`btn_netops_open_hub`. Everything else this card can assert lives in
`SelfReaderOpsSection.kt` (`section_self_reader_ops`, `card_self_axis_$tag`,
`chip_reader_standing`, `progress_self_standing`, `input_self_delegation_id`,
`btn_self_declare_$tag`, `text_self_partition`, `text_self_distinct_zeroes`).
So the signer key, the mode, the disk budget and the data dir — the four values
this card exists to show — are rendered and UNTAGGED. They must be written
`proposed:` or tagged before any flow can assert them;
`testing/flows/csd-036-network-ops.yaml` drives the self/reader half only.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| signer key | `GET /v1/federation/identity` | CIRISServer | live — `src/federation_surface.rs:696` |
| agent mode, SERVER-eligibility, disk budget, data dir | `GET /v1/system/agent-mode` | **CIRISAgent**, not the node | **wrong-host** — no such route in CIRISServer `src/*.rs` on `origin/main` (0.5.217); live on CIRISAgent `routes/system/agent_mode.py:78` (tree of 2026-08-15, may be stale) |
| this node's own standings (tier S) | `GET /v1/admin/self` | CIRISServer `src/admin_ops.rs:4288` | live — `getSelfStanding` (`CIRISApiClient.kt:5270`) from `SelfReaderOpsSection.kt:88`, mounted on this screen at `NetworkOpsScreen.kt:117`. The six tier-S and three tier-R rows below replace the brace globs this table carried; all nine are called |
| shed load / resume | `POST /v1/admin/self/shed` · `POST /v1/admin/self/resume-load` | `src/admin_ops.rs:4289` · `:4291` | live — `selfShedLoad` / `selfResumeLoad` (`CIRISApiClient.kt:5492, 5509`) from `SelfReaderOpsSection.kt:246` |
| stop / resume accepting | `POST /v1/admin/self/stop-accepting` · `POST /v1/admin/self/resume-accepting` | `src/admin_ops.rs:4295` · `:4299` | live — `CIRISApiClient.kt:5530, 5547` |
| declare / lift compulsion | `POST /v1/admin/self/compelled` · `POST /v1/admin/self/compulsion-lifted` | `src/admin_ops.rs:4303` · `:4307` | live — `selfDeclareCompelled` (`CIRISApiClient.kt:5573`, from `SelfReaderOpsSection.kt:252`) · `CIRISApiClient.kt:5595`. The only rung reachable under partition; CSD-045 owns its semantics |
| this node's reader fold (tier R) | `POST /v1/admin/reader/fold` | `src/admin_ops.rs:4312` | live — `readerFold` (`CIRISApiClient.kt:5627`) from `SelfReaderOpsSection.kt:633` |
| honour / decline another party's judgement | `POST /v1/admin/reader/honour` · `POST /v1/admin/reader/decline` | `src/admin_ops.rs:4316` · `:4320` | live — `readerHonour` / `readerDecline` (`CIRISApiClient.kt:5713, 5741`) from `SelfReaderOpsSection.kt:749, 747` |
| an `config:agent_mode` record signed by the node | — | CIRISServer | **missing** — see §6 |

Admin lines are CIRISServer `origin/main` 046e1b39 (0.5.217); on `integ/0.5.218`
(97900cf5) the same routes sit +96 lines lower (`:4384-4416`), unchanged in set.

## 4. Flow (how)

Sign in on a node with a brain; open My things › This node › Network.

```yaml
expect:
  state: populated
  visible: [screen_network_ops, row_netops_signer_key, row_netops_mode]
  one_of: {row_netops_mode: [CLIENT, PROXY, SERVER]}
```

On a node-only build (no brain answering 8080):

```yaml
expect:
  state: error
  visible: ["proposed:netops_error"]
  absent: [row_netops_mode]
```

That second block **fails today** and is written anyway: it is the assertion the
fix has to satisfy, and a flow that asserted the present behaviour would pin the
defect.

## 5. QA plan

**Platforms.** All five. The node-only variant needs the run-without-AI build,
which is exactly where the defect shows.

**Not tested here.** The tier-S / tier-R admin rungs
(`SelfReaderOpsSection.kt`) — they are a second surface living on this screen
with ~20 tags of their own and deserve their own CSD; `btn_netops_open_hub`,
whose destination is `layer-global-commons`.

## 6. Delta — card vs API vs CC

* **Wrong host, and it fails open.** The card is labelled "CIRISEdge · this
  node" and three of its four cards come from the **brain**. When the brain is
  absent the mode row prints `PROXY` — a fact about the client's Kotlin
  default presented as a fact about the node. **Ask (CIRISClient):** collect
  `NetworkViewModel.error` in `NetworkOpsScreen` and render a tagged error row
  instead of the default; make `_mode` nullable so "not read" has no rendering.
* **CC → card.** CC 3.1.9 wants the mode to be an auditable `config:{scope}`
  record. The node has no route that serves one. **Ask (CIRISServer):** serve
  the node's own `config:agent_mode` record (attester + `asserted_at`) so this
  row stops depending on the brain at all — the mode of the *node* is not the
  brain's to report.
* **Placement.** Correct. These are the node's own facts; This node is where the
  Locked Spec puts the substrate.
