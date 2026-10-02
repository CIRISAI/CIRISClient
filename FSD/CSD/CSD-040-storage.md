# CSD-040 — Storage (My things › Everything I shared › Storage)

**CSD**: CSD-040 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: `testing/flows/drafts/csd-040-storage.yaml` (floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see how much this node is actually holding — how many graph rows,
of what kinds, in which scopes, and where on disk — without opening a database.**

Serves **CC 3.1.3 `corpus_health:*`** and the substrate-self-report class at
**CC 3.4.3**: persist reports on its own corpus, and no one else may report on it
for it. That is the constitutional reason the numbers on this card are read-only
and the card has no write of any kind.

The card is **in the wrong instrument** and §6 says so with the CC reason.

## 2. Surface (what)

```yaml csd:surface
surface: storage
screen: Storage
```

`nav_map` derives `btn_my_things -> nav_instrument_everything_i_shared ->
nav_epistemic_storage`.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "system:*"
    use: display-only
    type: int
    example: 18422
    renders: "Total nodes — 18422"
    tag: row_storage_total_nodes
  - ceg: "system:*"
    use: display-only
    type: int
    example: 96
    renders: "New (24h) — 96"
    tag: row_storage_recent_nodes
  - ceg: "system:*"
    use: display-only
    type: timestamp
    example: "2025-11-04T08:12:00Z"
    renders: "Oldest (approx.) — 2025-11-04T08:12:00Z. HEURISTIC, and the card says so: the server computes it from the first 1000-row page per scope, not a full scan (memory_api.rs). The counts are exact; these two dates are not — row_storage_dates_heuristic says it under them."
    tag: row_storage_oldest
  - ceg: "system:*"
    use: display-only
    type: timestamp
    example: "2026-09-25T06:40:11Z"
    renders: "Newest (approx.) — 2026-09-25T06:40:11Z (same heuristic caveat, same line)"
    tag: row_storage_newest
  - ceg: x_private:disk_read_failure
    use: display-only
    type: string
    example: "The disk facts come from the agent, and this node runs without one."
    renders: "in the On disk card's PLACE when /v1/system/agent-mode did not answer — storage_disk_not_on_this_node for a host without the route, storage_disk_error otherwise. A missing card and a card that was never asked for used to look identical."
    tag: storage_disk_not_on_this_node
  - ceg: "corpus_health:n_eff_measurable"
    use: display-only
    type: int
    example: 4102
    renders: "one row per node type, e.g. `concept — 4102`; the card draws the histogram and never names it as corpus health"
    tag: "row_storage_type_{type}"
  - ceg: x_private:nodes_by_graph_scope
    use: display-only
    type: int
    example: 912
    renders: "one row per GRAPH scope, e.g. `local — 912`. NOT `cohort_scope`: these are the memory graph's own scopes, and the two vocabularies do not overlap."
    tag: "row_storage_scope_{scope}"
  - ceg: x_private:data_dir
    use: display-only
    type: string
    example: "/home/emoore/ciris"
    renders: "Data directory — /home/emoore/ciris (mono)"
    tag: row_storage_data_dir
  - ceg: x_private:available_disk_bytes
    use: display-only
    type: int
    example: 214748364800
    renders: "Available — 200.0 GB"
    tag: row_storage_available
```

**`system:*` is the wildcard family and it is being used as one.** The registry
gives persist a single `system:*` prefix (CC 3.1.3, reserved,
`substrate-self-report` under CC 3.4.3) and the graph-store counters are exactly
that: the substrate saying how much of itself there is. `use` is `display-only`
throughout — CC 3.4.3 reserves emission to the substrate, and an `emit` here
would be a load error in the checker, correctly.

The `row_storage_scope_*` histogram is deliberately **not** bound to a CC family.
CC 3.1.9 rules that the seven-token ladders "MUST NOT be reconciled into a single
vocabulary"; the memory graph's scopes are a third vocabulary again, and binding
them to `cohort_scope` would assert a mapping CC forbids.

```yaml csd:states
populated: {tag: card_storage_graph, renders: "the graph-store card with its counters, the histograms, and the On disk card (or its failure line)"}
empty:     {tag: storage_empty, renders: "This node is not holding anything yet. — an all-zero graph (storageGraphState, StorageEmptyTest). Until 2026-09-28 a fresh node rendered `Total nodes — 0`, a number where there is nothing"}
loading:   {tag: storage_loading, renders: "a CircularProgressIndicator with no cards and no sentence"}
error:     {tag: storage_error, renders: "the errorContainer card, the read's message as the tag's text — visible, distinct from empty"}
```

All four are real. Loading and error always behaved correctly and were merely
untagged; empty did not exist and now does (a pure classifier,
`storageGraphState`, pinned by `StorageEmptyTest`); populated is the graph card
rather than the screen frame, so a flow can tell a card from a page.

**Correction (2026-09-27): the eight `row_storage_*` tags, `card_storage_by_type`
and `card_storage_by_scope` DO exist.** They are passed as the `tag` parameter
of `StatRow` (`StorageScreen.kt:90-116`, `:139`), which is why a grep for
`testable("…")` literals missed them. The earlier paragraph here said
`screen_storage` was the only literal and that no flow could be written; both
were wrong, and the flow is now written against those tags
(`testing/flows/drafts/csd-040-storage.yaml`). **Closed 2026-09-28:** the error
card is tagged (`storage_error`); an all-zero graph renders `storage_empty`; a
failed `getAgentMode` is classified (`ReadFailure`) and said in the disk card's
place (`storage_disk_not_on_this_node` / `storage_disk_error`) instead of
swallowed; the two dates are labelled *(approx.)* with the reason under them.
Still true: the agent-mode parse defaults `mode` to PROXY and `data_dir` to ""
(`CIRISApiClient.kt`), so a disk card that reads an empty directory is a parse
default, not a reading — outside this card's reach.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| graph-store counters | `GET /v1/memory/stats` | CIRISServer AND CIRISAgent | live on both — node `src/memory_api.rs:1265`, **unauthenticated by design** (`compose.rs:1700-1702`), a 503 stub on a Postgres-only node (`:1278`); agent `routes/memory.py:585` (observer, wrapped in `{"data":…}`), whose oldest/newest are a different approximation (`limit=1` timeline queries over the last year, `:602-615`) — the heuristic caveat below describes only the node's 1000-row version |
| data dir + free disk | `GET /v1/system/agent-mode` | **CIRISAgent** (`routes/system/agent_mode.py:78`) | **wrong-host** — no such route in CIRISServer 0.5.217 |
| the heuristic flag on the two dates | — | CIRISServer | **missing** — the route does not mark `oldest_node_date` / `newest_node_date` as approximate. The client labels them *(approx.)* itself and says why under them (`row_storage_dates_heuristic`), from the server source rather than the wire; the ask for a wire flag stands, because a label the client writes can drift from what the server does |

## 4. Flow (how)

Sign in on a node; open My things › Everything I shared › Storage.

```yaml
expect:
  state: populated
  visible: [screen_storage, card_storage_graph, row_storage_total_nodes]
  number: {row_storage_total_nodes: {min: 1}}
  count: {of: "row_storage_type_*", min: 1}
```

On a Postgres-only node, where `/v1/memory/stats` is a 503 stub:

```yaml
expect:
  state: error
  visible: [storage_error]
  absent: [card_storage_graph]
```

On a fresh node, where the graph holds nothing:

```yaml
expect:
  state: empty
  visible: [storage_empty]
  absent: [card_storage_graph, row_storage_total_nodes]
```

On a node-only build, the "On disk" card has no host, and its place says so:

```yaml
expect:
  visible: [storage_disk_not_on_this_node]
  absent: [card_storage_disk]
```

That third block used to assert the card's silent absence as a warning to
itself; the fix landed (2026-09-28) and the block now asserts the sentence.

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-040-storage.yaml`, floor `unreleased`); promotes to `testable` when the floor is no longer `unreleased` and the flow runs on the matrix (#97). **Floor left `unreleased` on the 0.5.225 run (2026-09-29):** the flow names `storage_disk_not_on_this_node` (ReadFailureBlock builds `${tagPrefix}_not_on_this_node`). Real in 0.5.225, but built by interpolation, which the flow tag check (`testing/test_flows.py::_client_tag_strings`: whole literals and `$`-headed prefixes only) cannot see; a promoted flow naming them goes red at the keyboard. The fix is in that check, not the flow.

**Platforms.** All five. The Postgres-only variant is a server-side fixture, not
a client platform, and belongs in CIRISServer's matrix; this flow only needs the
503 to be reachable.

**Not tested here.** Whether the counters are *right* — this client cannot audit
persist; the heuristic dates, for the same reason.

## 6. Delta — card vs API vs CC

* **Wrong instrument, and CC says which one.** "Everything I shared" is the
  subject-side consent instrument (CC 3.3.1): what *I* put out, and my authority
  over it. This card reports the substrate's own corpus — `system:*` and
  `corpus_health:*`, both **persist-owned substrate-self-report** families under
  CC 3.4.3, which is the opposite relation: the substrate speaking about itself,
  not me speaking about mine. It also shares two of its four cards with CSD-036
  (`data_dir`, `availableDiskBytes`, from the same call). **Recommended
  placement: My things › This node**, beside Network and Graph. That leaves
  Everything I shared holding exactly one card, Data — which is correct, because
  Data is the only card in the app that is actually about what this person
  shared.
* **Same wrong-host as CSD-036 — closed here (2026-09-28).** `getAgentMode()`
  used to be caught and logged with no error state, so on a node-only build the
  "On disk" card was simply not there, and a missing card and a card that was
  never asked for looked identical. The failure is now classified and rendered
  in the card's place (`storage_disk_not_on_this_node` / `storage_disk_error`).
  CSD-036's copy of the defect is its own.
* **A heuristic presented as a fact — labelled (2026-09-28).** `oldest_node_date`
  and `newest_node_date` are computed from a 1000-row page per scope. The client
  labels them *(approx.)* and says why (`row_storage_dates_heuristic`), from the
  server source. **Ask (CIRISServer) stands:** mark them `approximate: true` on
  the response, so the label follows the wire and not a reading of the code.
  AGENTS.md's own rule — heuristic surfaces say they are heuristic — applies to
  a screen as much as to a gate.
* **Tags.** All four state tags are real, and the flow is staged
  (`testing/flows/drafts/csd-040-storage.yaml`, `client: unreleased`). The stage
  stays `building` until a release carries the tags and the floor can flip;
  nothing else stands between this card and `testable`.
* **Copy.** The card's labels ("Graph store", "Total nodes", "On disk"…) are
  English literals in `StorageScreen.kt`, not bundle keys; the new lines are
  keyed. Not a gate failure (the colour and layout checks do not cover strings),
  but the next change here should key the rest.
