# CSD-027 — Memory (what the node holds, as a list)

**CSD**: CSD-027 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can search everything their node remembers, open one memory, and
see which scope it was filed under — because the scope is who else can see it.**

The Locked Spec moved the memory graph out of the Files tab and into This
node, on the grounds that Files holds files. That is right, and it leaves this
card carrying a question Files does not: a memory node has a `scope`
(`local` / `identity` / `environment` / `community`), and under CC 2.3.3 the
visibility axis — `cohort_scope` + `family_id` / `community_id` — is what
decides who can see the bytes. A list that renders scope as a colour swatch has
rendered the most consequential field as decoration.

## 2. Surface (what)

```yaml csd:surface
surface: memory
screen: Memory
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_memory`. Listed as `agentOnly` (`CirclesNav.kt:154`) — **and
that is wrong**: every route this screen calls is served by the node. See §6.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:memory_node_id
    use: display-only
    type: string
    example: "obs_4a19c2ef01"
    renders: "obs_4a19c2ef — the node id (memory_label_id)"
    tag: "proposed:memory_row_obs_4a19c2ef01"
  - ceg: x_private:memory_node_type
    use: read
    type: "enum[OBSERVATION,CONCEPT,IDENTITY,CONFIG,TSDB_SUMMARY,AUDIT_ENTRY]"
    example: "OBSERVATION"
    renders: "Observation — the node type, and the filter chip vocabulary (memory_node_type)"
    tag: chip_memory_node_type_all
  - ceg: x_private:memory_scope
    use: read
    type: "enum[local,identity,environment,community]"
    example: "community"
    renders: "Shared with a community — memory_scope_community. Today this is a coloured chip (ColorTheme.kt:183–186) and nothing else."
    tag: "proposed:memory_scope_obs_4a19c2ef01"
  - ceg: x_private:memory_cohort_scope
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who can see this — the CC 2.1 envelope's `cohort_scope`, plus `community_id` / `family_id` where set. NOT SENT: `/v1/memory/query` returns the graph node, not the envelope the bytes were filed under, so this screen cannot tell a self-scoped memory from a federated one. CC 2.3.3 makes these different axes and the screen has only the graph one."
    tag: "proposed:memory_receipt_scope"
  - ceg: "holds_bytes:sha256:{prefix}"
    bind: {prefix: 4a19c2ef}
    use: display-only
    type: float
    example: 1.0
    renders: "This node holds the bytes — the CC 3.1.9.1 directory row. Load-bearing by its ABSENCE: CC 5.2 forbids emitting this for `cohort_scope: self | family` unconditionally, so a memory with no holds_bytes row is not missing, it is invisible on purpose."
    tag: "proposed:memory_holds_bytes"
  - ceg: x_private:memory_created_at
    use: display-only
    type: timestamp
    example: "2026-09-24T11:02:19.000Z"
    renders: "Remembered 2 days ago (memory_label_created / memory_label_updated)"
    tag: "proposed:memory_created_obs_4a19c2ef01"
  - ceg: x_private:memory_total_nodes
    use: display-only
    type: int
    example: 8412
    renders: "8,412 memories (memory_total_nodes) — from GET /v1/memory/stats"
    tag: "proposed:memory_total"
  - ceg: x_private:memory_attributes
    use: display-only
    type: "list[string]"
    example: ["channel=discord:general", "confidence=0.82"]
    renders: "the attributes block on the details sheet (memory_attributes)"
    tag: "proposed:memory_attributes_obs_4a19c2ef01"
```

```yaml csd:states
populated: {tag: "proposed:memory_list"}
empty:     {tag: "proposed:memory_empty", renders: "memory_no_memories + memory_no_memories_desc + memory_try_graph — 'Nothing here yet' and a nudge toward the graph view"}
loading:   {tag: "proposed:memory_loading", renders: "the frame with a progress affordance and no sentence (MemoryViewModel sets isLoading before both reads)"}
error:     {tag: "proposed:memory_error", renders: "`MemoryScreenState.error` EXISTS (MemoryScreen.kt:698) and `MemoryScreen` never reads it. A failed query therefore renders memory_no_memories: 'Nothing here yet' over a node that may hold everything."}
```

**This is the plainest instance of the §2.2 failure in this area**, because the
field is right there in the state object and the composable does not consult
it. A person whose node refused the read is told their memory is empty.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| search | `POST /v1/memory/query` | **both** | live on CIRISServer (`/v1/memory/query`) and CIRISAgent (`memory.py`) |
| the recent list | `GET /v1/memory/timeline` | **both** | live |
| totals | `GET /v1/memory/stats` | **both** | live |
| one memory | `GET /v1/memory/{node_id}` | **both** | live |
| its envelope (`cohort_scope`) | — **unconfirmed** | CIRISServer | blocks `building` for `memory_receipt_scope` |
| its `holds_bytes` row | — **unconfirmed** | CIRISServer | blocks `building` for `memory_holds_bytes` |

**All four live routes are served by the node.** Agent tree last commit
2026-08-15.

## 4. Flow (how)

Open My things → This node → Memory.

```yaml
expect:
  state: populated
  count: {of: "memory_row_*", min: 1}
  visible: [input_memory_search, btn_memory_refresh, memory_total]
```

Search for a string nothing matches.

```yaml
expect:
  state: empty
  visible: [memory_empty]
```

Clear it (`btn_memory_clear_search`) → the list again.

Open one memory.

```yaml
expect:
  visible: [memory_attributes_obs_4a19c2ef01, memory_scope_obs_4a19c2ef01, btn_memory_close_details]
```

Switch to the graph (`btn_memory_switch_graph`) → CSD-028.

Point the app at a node that refuses the query.

```yaml
expect:
  state: error
  visible: [memory_error]
  absent: [memory_empty]
```

The last block fails today.

## 5. QA plan

**Platforms.** All five.

**Not tested here.** A memory at `cohort_scope: family`. CC 5.2 makes those
structurally invisible outside the cohort, so the gate would have to stand up a
second member to see one — worth doing once `memory_receipt_scope` lands,
because the assertion that matters is that a family memory shows NO
`holds_bytes` row and the screen does not read that as a defect.

## 6. Card vs API vs CC — the delta

1. **Wrong build flag.** `Memory` is in `agentOnly` and every route it calls
   is served by `CIRISServer`. A node-build owner cannot see what their own
   node remembers. **Ask (this repo): remove `NavSurface.Memory` from the
   `this-node` instrument's `agentOnly` set.** This is the mirror image of the
   Adapters / Runtime / Telemetry defect: three cards shown that should be
   hidden, one hidden that should be shown, all from the same list.
2. **The error state is modelled and unread.** See §2 states.
3. **Eight real tags, all of them controls.** `btn_memory_back`,
   `btn_memory_refresh`, `btn_memory_clear_search`, `btn_memory_toggle_filters`,
   `btn_memory_switch_graph`, `btn_memory_close_details`,
   `chip_memory_node_type_all`, `input_memory_search`. Not one row, value or
   state is tagged.
4. **CC conflict — the substantive one.** The screen's `scope` and the CC
   envelope's `cohort_scope` are different axes (CC 2.3.3 names three:
   visibility, revocability, delivery) and the screen shows only the first,
   as a colour. **Ask (CIRISServer): return the CC 2.1 envelope fields —
   `cohort_scope`, `community_id` / `family_id`, `subject_key_ids` — on
   `/v1/memory/query` and `/v1/memory/{node_id}`,** so a person can see who
   else can read a memory before they decide it is harmless.
5. **Recommended placement: unchanged, and the Locked Spec's reasoning holds.**
   Memory is not a file. But it is the node's, not the agent's, and the
   `agentOnly` flag says otherwise.
