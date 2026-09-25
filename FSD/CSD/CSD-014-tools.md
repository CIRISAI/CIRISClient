# CSD-014 — Tools (what the agent can reach for, under This node)

**CSD**: CSD-014 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**Before the agent does anything on the person's behalf, the person can read
the whole list of things it is able to do, who supplies each one, and what it
costs.** A capability inventory is the precondition for informed delegation:
CC 4.5.5 rules that the principal an act is taken on behalf of is discovered by
walking the `delegates_to` graph from `attesting_key_id` and is *never* a
payload field, so the person at the root of that chain is answerable for every
tool below it. They cannot be answerable for a list they have not seen.

The falsifiable claim, and it is half-true today: **this screen shows what the
agent can do.** It does. It does not show what the agent must not do. CC
3.1.5.4 makes the apophatic bound a first-class family —
`prohibited:{category}`, polarity −1/−0.5 only, the 22 NEVER_ALLOWED categories
— and an inventory that lists only capabilities is the reassuring half of the
picture shown alone.

## 2. Surface (what)

```yaml csd:surface
surface: tools
screen: Tools
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_tools`. `agentOnly` (`CirclesNav.kt:155`) — correct: a node with
no brain has nothing to hold a tool.

**`EpistemicNav.kt:72` says otherwise and is stale.** Its comment reads *"the
agent-only Tools child is dropped from the surfaced tree (the Tools object
remains defined for route compatibility)"*. `CirclesNav.kt:149` lists it and
`nav_map` reaches it. The comment describes the shell that was deleted.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:tool_name
    use: display-only
    type: string
    example: "read_file"
    renders: "the row title; the row's tag is derived from it (tool_read_file)"
    tag: "tool_${tool.name}"
  - ceg: x_private:tool_provider
    use: display-only
    type: string
    example: "filesystem"
    renders: "WHO SUPPLIES IT — the provider chip, and the second filter"
    tag: "proposed:tools_row_provider"
  - ceg: x_private:tool_when_to_use
    use: display-only
    type: string
    example: "When the user asks about the contents of a file they named."
    renders: "the guidance the agent itself is given, shown to the person unchanged"
    tag: "proposed:tools_row_when_to_use"
  - ceg: x_private:tool_cost
    use: display-only
    type: float
    example: 0.0
    renders: "Cost: 0.0 — and a tool whose envelope omits `cost` renders the SAME 0.0 (CIRISApiClient.kt:9654)"
    tag: "proposed:tools_row_cost"
  - ceg: x_private:tool_parameters
    use: display-only
    type: "list[string]"
    example: ["path", "encoding"]
    renders: "the expanded card's Parameters section"
    tag: "proposed:tools_row_parameters"
  - ceg: x_private:provider_count
    use: display-only
    type: int
    example: 4
    renders: "the Providers summary above the list"
    tag: "proposed:tools_provider_count"
  - ceg: prohibited:{category}
    bind: {category: unconfirmed}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "WHAT IT WILL NEVER DO — not on this screen. CC 3.1.5.4's 22 categories have no route and no card anywhere in the client."
    tag: "proposed:tools_prohibited"
```

**`cost` reads 0.0 for a tool that did not state one.**
`tool["cost"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0`
(`CIRISApiClient.kt:9654`) makes "free" and "did not say" the same rendering,
and `provider` falls back to the literal string `"unknown"` while `category`
falls back to `"general"` (`:9653, :9652`). Of the three, cost is the one that
is money. CSD/3 §3 refuses a number that was not read; the wire mapping supplies
one before the UI ever gets the chance.

```yaml csd:states
populated: {tag: "proposed:tools_list", renders: "the provider summary, the search box and two filters, then one card per tool"}
empty:     {tag: "proposed:tools_empty", renders: "No tools available, or No tools match your filters when a filter is set (ToolsScreen.kt:272 branches on both)"}
loading:   {tag: "proposed:tools_loading", renders: "a progress indicator INSTEAD of the list (ToolsScreen.kt:126)"}
error:     {tag: "proposed:tools_error", renders: "the error card at ToolsScreen.kt:132 — reachable, and the empty branch explicitly excludes it (`state.error == null` at :272)"}
```

`empty` and `error` are correctly exclusive here: the empty branch is guarded on
`state.error == null`, so a failed read cannot present as an empty list. This is
the pattern the other six surfaces in this area should copy.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the tool list | `GET /v1/system/tools` | CIRISAgent (`routes/system/tools.py:119`, prefix `system/__init__.py:39`) | live — direct `client.get` at `CIRISApiClient.kt:9630`, parsed as raw JSON |
| the 22 prohibitions | **no route** — `prohibited:{category}`, CC 3.1.5.4 | CIRISAgent | **missing**; blocks `building` for `proposed:tools_prohibited` |
| tool balance / purchase | `GET /v1/api/tools/balance`, `POST /v1/api/tools/purchase` | — | **wrong-host and dead**: `ToolsApi.kt` declares five `/v1/api/tools/*` routes; `CIRISApiClient` never constructs a `ToolsApi`, and neither `CIRISServer origin/main src/` nor `CIRISAgent routes/` contains that literal |

**Host check.** `/v1/system/tools` is the brain's. `CIRISServer origin/main`
serves `/v1/system/health`, `/v1/system/data*` and `/v1/system/verify-status`
and nothing else under `/v1/system` — so the surface being `agentOnly` is what
keeps a run-without-AI install from 404ing here.

**CIRISAgent working tree is dated 2026-08-15**, five weeks behind today.

## 4. Flow (how)

My things → This node → Tools, on an agent with at least one provider.

```yaml
expect:
  state: populated
  visible: [btn_tools_refresh]
  count: {of: "tool_*", min: 1}
```

Type a string no tool matches into the search box.

```yaml
expect:
  state: empty
  visible: [proposed:tools_empty]
```

Expand a tool: click its `tool_<name>` row.

```yaml
expect:
  visible: [proposed:tools_row_parameters, proposed:tools_row_when_to_use]
```

## 5. QA plan

**Platforms.** All five. Search, two dropdown filters and an expandable card —
no platform branch.

**Not tested here.** Whether a listed tool actually runs; this screen is an
inventory and never invokes one. Whether `cost` is denominated in the same unit
as the credits badge on Interact — nothing on the wire says.

**Upstream asks.**
CIRISAgent — make `cost` required on `GET /v1/system/tools`, or carry it as
`null` so the client can render "not stated" instead of a false zero; and
expose the CC 3.1.5.4 prohibited categories on a read route so the inventory can
show both halves.
CIRISClient — delete `ToolsApi.kt`'s five `/v1/api/tools/*` routes or wire them
to a host that serves them; a generated client for an endpoint nobody serves is
a second source for a question `/v1/system/tools` already answers.
