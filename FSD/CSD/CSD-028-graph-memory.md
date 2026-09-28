# CSD-028 — Graph (the same memory, drawn)

**CSD**: CSD-028 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: none — this surface carries no test tag at all, which is why the stage is `envisioned`

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see the shape of what their node remembers — which memories are
connected to which, and where the dense parts are — and the picture never shows
"nothing here" for a read that failed.**

This is CSD-027's data in the other form. The list answers "what is in here";
the graph answers "what is it like in here", and the second question is the one
a person asks when they are trying to decide whether their agent knows too
much about them. Serves **Contextual Integrity**: a cluster is a flow, and
seeing a cluster is how a person notices a flow they did not intend.

## 2. Surface (what)

```yaml csd:surface
surface: graph-memory
screen: GraphMemory
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_graph_memory`. **Not** in `agentOnly` — correct, since
`/v1/memory/timeline` is the node's. This is inconsistent with `Memory`, which
is `agentOnly` over the same data (CSD-027 §6.1); one of the two is wrong and
it is `Memory`.

This is also the default landing for the old nav's "Memory" entry
(`CIRISApp.kt:2873`, `onMemoryClick = { currentScreen = Screen.GraphMemory }`).

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:graph_total_nodes
    use: display-only
    type: int
    example: 8412
    renders: "8,412 memories (graph_nodes)"
    tag: "proposed:graph_stat_nodes"
  - ceg: x_private:graph_total_edges
    use: display-only
    type: int
    example: 19308
    renders: "19,308 links between them (graph_edges)"
    tag: "proposed:graph_stat_edges"
  - ceg: x_private:graph_nodes_by_scope
    use: display-only
    type: "list[string]"
    example: ["local=6102", "identity=88", "environment=1904", "community=318"]
    renders: "the per-scope breakdown, which is the sentence a person actually needs: 318 of these are shared with a community"
    tag: "proposed:graph_stat_by_scope"
  - ceg: x_private:graph_layout
    use: read
    type: "enum[force,timeline,hierarchy,circular,cylinder]"
    example: "cylinder"
    renders: "3D Cylinder — the layout picker (graph_select_layout); the default"
    tag: "proposed:graph_layout_picker"
  - ceg: x_private:graph_time_range_hours
    use: read
    type: int
    example: 24
    renders: "Last 24 hours (graph_time_range) — the `hours` parameter on the timeline read"
    tag: "proposed:graph_filter_hours"
  - ceg: x_private:graph_include_metrics
    use: read
    type: bool
    example: false
    renders: "Include telemetry nodes (graph_include_metrics / graph_show_telemetry) — off by default, because TSDB summaries swamp the picture"
    tag: "proposed:graph_filter_metrics"
  - ceg: x_private:graph_selected_node_id
    use: display-only
    type: string
    example: "obs_4a19c2ef01"
    renders: "the selected node's id and its details panel (graph_node_id)"
    tag: "proposed:graph_selected_node"
  - ceg: x_private:graph_simulating
    use: display-only
    type: bool
    example: true
    renders: "Settling… (graph_simulating) — the force simulation is still moving; the picture is not final"
    tag: "proposed:graph_simulating"
  - ceg: x_private:graph_updated_at
    use: display-only
    type: timestamp
    example: "2026-09-25T09:31:29.000Z"
    renders: "Read 4 minutes ago (graph_updated)"
    tag: "proposed:graph_updated"
```

```yaml csd:states
populated: {tag: "proposed:graph_canvas"}
empty:     {tag: "proposed:graph_empty", renders: "graph_no_data + graph_no_data_desc + graph_try_filter — 'No graph data' and a nudge to widen the filter"}
loading:   {tag: "proposed:graph_loading", renders: "GraphMemoryScreen.kt:261 — a centred CircularProgressIndicator with no sentence. Correct shape."}
error:     {tag: "proposed:graph_error", renders: "GraphMemoryScreen.kt:305 — an errorContainer banner at the top. It EXISTS, and it is rendered ALONGSIDE the empty state, not instead of it."}
```

**The failure here is the inverse of every other card in this area and it is
worth stating precisely.** `GraphMemoryScreen.kt:269` renders the empty block
on `!state.isLoading && state.nodes.isEmpty()`, with no reference to
`state.error`. When a read fails, `nodes` is empty AND `error` is set, so the
screen shows a red banner saying the read failed *and*, underneath it, a card
saying "No graph data — try widening your filter." One of those two sentences
is false, and the advice is actively misleading: widening the filter will not
fix a 500.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| nodes + edges | `GET /v1/memory/timeline?hours={h}&include_edges=true&include_metrics={b}` | **both** | live on CIRISServer (`/v1/memory/timeline`) and CIRISAgent (`memory.py`) |
| a node's edges | `GET /v1/memory/{node_id}/edges` | **both** | live; **not called by this screen** — edges come from the timeline read |
| a purpose-built graph read | `GET /v1/memory/visualize/graph` | CIRISAgent | live (`memory.py`); **not called**, and agent-only, so not calling it is right |
| per-scope counts | derived client-side | — | `GraphStats.nodesByScope`, computed from the node list |

Agent tree last commit 2026-08-15.

## 4. Flow (how)

Open My things → This node → Graph.

```yaml
expect:
  state: populated
  visible: [graph_canvas, graph_stat_nodes, graph_stat_edges]
```

Open the filters (`graph_filters`), narrow the window to an hour with nothing
in it.

```yaml
expect:
  state: empty
  visible: [graph_empty]
  absent: [graph_error]
```

Point the app at a node that 500s on the timeline read.

```yaml
expect:
  state: error
  visible: [graph_error]
  absent: [graph_empty]
```

The last `absent:` is the assertion this CSD exists to add.

Select a node.

```yaml
expect:
  visible: [graph_selected_node]
```

## 5. QA plan

**Platforms.** All five. The canvas is a `Canvas` with gesture handling and a
force simulation; the five-platform value here is the gestures, not the
picture.

**Not tested here.** The rendering itself — node positions, edge routing,
the cylinder projection. A flow can assert that the canvas composed and that
the stats are right; it cannot assert that the graph is legible. Also not
tested: the force simulation's settling, which is time-dependent and would
make the gate flaky for no assertion gained.

## 6. Card vs API vs CC — the delta

1. **Zero test tags. Not one.** `GraphMemoryScreen.kt` (559 lines) contains no
   `testable`, `testableClickable` or `testTag` call. Every field and state in
   §2 is `proposed:`, which is why this CSD is `envisioned` and not `sketched`.
   A surface with no tags cannot be driven, cannot be screenshotted by step,
   and cannot fail a gate — it can only fail a person.
2. **Empty and error render together.** See §2. **Ask (this repo): make the
   empty branch `state.error == null && state.nodes.isEmpty()`.** One
   condition, and it is the single highest-value line change across all twelve
   cards in this area.
3. **Build flag is right, and disagrees with its sibling.** Graph is shown on
   a node build; Memory is not; both read `/v1/memory/*` from the same host.
4. **CC: the same envelope gap as CSD-027.** The graph colours nodes by
   `GraphScope` (`ColorTheme.kt:183–186`) and knows nothing of `cohort_scope`.
   The per-scope breakdown (`graph_stat_by_scope`) is the one place in the app
   where a person could learn "318 of your memories are visible to a
   community", and it is computed from the graph axis, not the visibility axis.
   Same ask as CSD-027 §6.4 — the CC 2.1 envelope on the memory reads — and
   this screen is where it would pay off most, because a count is easier to
   act on than a list.
5. **Recommended placement: unchanged.** Graph and Memory should sit next to
   each other under This node, and they do.
