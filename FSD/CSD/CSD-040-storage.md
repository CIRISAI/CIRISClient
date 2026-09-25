# CSD-040 — Storage (My things › Everything I shared › Storage)

**CSD**: CSD-040 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
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
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
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
    renders: "Oldest — 2025-11-04T08:12:00Z. HEURISTIC: the server computes it from the first 1000-row page per scope, not a full scan (memory_api.rs). The counts are exact; these two dates are not, and the card does not say so."
    tag: row_storage_oldest
  - ceg: "system:*"
    use: display-only
    type: timestamp
    example: "2026-09-25T06:40:11Z"
    renders: "Newest — 2026-09-25T06:40:11Z (same heuristic caveat)"
    tag: row_storage_newest
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
populated: {tag: screen_storage}
empty:     {tag: "proposed:storage_empty", renders: "This node is not holding anything yet. — today a fresh node renders `Total nodes — 0`, which is a number where there is nothing"}
loading:   {tag: "proposed:storage_loading", renders: "a CircularProgressIndicator (StorageScreen.kt:78-80) with no cards and no sentence — the one state of the four that is genuinely right, and it is untagged"}
error:     {tag: "proposed:storage_error", renders: "the errorContainer card at :82-86 — visible, correctly distinct from empty, and untagged"}
```

Three of four are `proposed:`, and unusually the *behaviour* is already correct:
this screen distinguishes loading from error from populated. It is only
undrivable, which is a smaller fix than the other cards in this area.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| graph-store counters | `GET /v1/memory/stats` | CIRISServer | live — `src/memory_api.rs:1265`, **unauthenticated by design** (`compose.rs:1700-1702`); on a Postgres-only node the route is a 503 stub (`:1278`) |
| data dir + free disk | `GET /v1/system/agent-mode` | **CIRISAgent** (`routes/system/agent_mode.py:78`) | **wrong-host** — no such route in CIRISServer 0.5.217 |
| the heuristic flag on the two dates | — | CIRISServer | **missing** — the route does not mark `oldest_node_date` / `newest_node_date` as approximate, so the client cannot either |

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
  visible: ["proposed:storage_error"]
  absent: [card_storage_graph]
```

On a node-only build, the "On disk" card has no host:

```yaml
expect:
  absent: [card_storage_disk]
```

That third block asserts the *present* behaviour and is written as a warning:
the card disappearing silently is what §6 asks to change, so this block is the
one that must be rewritten when the fix lands, not preserved.

## 5. QA plan

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
* **Same wrong-host as CSD-036, failing more quietly.** `getAgentMode()` is
  caught and logged with no error state (`StorageScreen.kt:49-53`), so on a
  node-only build the "On disk" card is simply not there. A missing card and a
  card that was never asked for look identical. **Ask (CIRISClient):** set the
  error and render a tagged row saying the disk facts come from the brain and
  the brain did not answer.
* **A heuristic presented as a fact.** `oldest_node_date` and `newest_node_date`
  are computed from a 1000-row page per scope. **Ask (CIRISServer):** mark them
  `approximate: true` on the response, or drop them; **or (CIRISClient)**, label
  them "approx." until it does. AGENTS.md's own rule — heuristic surfaces say
  they are heuristic — applies to a screen as much as to a gate.
* **Tags.** Every state tag here is `proposed:`. The behaviour is right and
  undrivable; this is the cheapest card in my area to move to `testable`.
