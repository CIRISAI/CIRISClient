# CSD-002 — Environment graph (what this circle has, wants and can lend)

**CSD**: CSD-002 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISClient#45,
**rebound** at the circles redesign
**Flow**: tools/qa_runner/flows/environment_card.yaml — **stale**, see §4

```yaml csd:stage
stage: building
owner: CIRISClient
```

> **Rebound, not rewritten-from-nothing.** This CSD was written against the old
> shell and named `surface: layer-local-community` / `screen: LayerLocalCommunity`
> — the *hub the card sat on*, because in that shell the environment page was
> reached from a hub tile. Wave 1 made the surface a card in a tab, and the
> question a CSD answers ("can you get there and back") is now derived rather
> than asserted. So the document is rebound to the surface it was always about,
> and its mission has had to grow: an entry that can be left is table stakes,
> and it is not what this screen is for.

## 1. Mission (why)

**A person can see what this circle has, wants, needs, and will lend or barter —
add an item, and take one back off the list.** Serves **Beneficence**: the
environment graph is the circle's open-call surface, and an open call nobody can
read is a need nobody meets.

The card is the shopping-list shape: five categories (*Want · Need · Have · Can
borrow · Can barter*), a chip per category carrying its count, a card per item
with quantity and condition, and a context-enrichment section under it for
whatever the node's adapters know about the surroundings.

## 2. Surface (what)

```yaml csd:surface
surface: environment-graph
screen: EnvironmentInfo
```

`nav_map` derives `circle_local_community -> tab_decisions ->
nav_epistemic_environment_graph` — Neighbours › Decisions
(`CirclesNav.kt:125`), sharing that tab with Health & Reputation, which is why
the chain ends on the row rather than the tab.

**Neighbours only**, and that is right: an open call is made to the people near
enough to answer it. It is not in Everyone, where a broadcast need is a
different mechanism with different consent; it is not in Just me, where there is
nobody to answer.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "need:{domain}:{kind}"
    bind: {domain: household, kind: ladder}
    use: emit
    type: string
    example: "need:household:ladder"
    renders: "Need — Ladder ×1. A broadcast claim that this entity has a stated need."
    tag: "item_${item.id}"
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: holds, target_kind: tool, version: v1}
    use: display-only
    type: string
    example: "ownership:holds:tool:v1"
    renders: "Have — Drill ×1, good condition"
    tag: "proposed:chip_env_category_have"
  - ceg: x_private:env_category
    use: emit
    type: "enum[want,need,have,can_borrow,can_barter]"
    example: "can_borrow"
    renders: "the five category chips, each with its count — Can borrow (3)"
    tag: "proposed:chip_env_category_can_borrow"
  - ceg: x_private:env_category_count
    use: display-only
    type: int
    example: 3
    renders: "the number in the chip"
    tag: "proposed:txt_env_category_count_can_borrow"
  - ceg: x_private:env_item_condition
    use: emit
    type: string
    example: "good"
    renders: "good condition"
    tag: "proposed:row_env_item_condition"
  - ceg: x_private:context_enrichment
    use: display-only
    type: "list[string]"
    example: ["weather", "calendar"]
    renders: "what this node's adapters know about the surroundings, one expandable group each"
    tag: "enrichment_header_${key}"
```

**`need:{domain}:{kind}` is non-reserved** (`node`/CIRISNodeCore, CC 3.1.9.3,
`polarity: positive-only`) so `emit` loads — and positive-only is the right
polarity for an open call: you can state a need, and there is no negative need.

**`ownership:{relation}:{target_kind}:{version}` IS reserved** — CC 3.4.5,
`registry`/CIRISRegistry — so `display-only` is enforced. That is a real
constraint on this card and it is one the card currently ignores: the *Have*
category is an ownership claim, the add-item dialog lets a person create one
freely (`EnvironmentInfoScreen.kt:431` defaults `category` to `"have"`), and
what the node actually writes is a graph node in the `environment` scope, not an
`ownership:*` attestation. The card and the family are not the same object, and
the CSD says so rather than binding `emit` to a reserved prefix it does not
touch.

**`mesh_config:{key}` is gone from this document.** The previous cut bound
`mesh_config:{key}` with `{key: environment}` and rendered "Environment — local
community". That was a claim about a node's declared configuration; this screen
is about a community's things. Two different objects that shared a word.

```yaml csd:states
populated: {tag: "proposed:list_env_items", renders: "the chips with their counts, then one card per item"}
empty:     {tag: "proposed:txt_env_no_items", renders: "Nothing in this category yet. (Per category — the chips stay, so a person can see the others are not empty.)"}
loading:   {tag: "proposed:spinner_env", renders: "a progress affordance in place of the list; the chips are not drawn with stale counts"}
error:     {tag: "proposed:txt_env_error", renders: "Could not read this circle's environment."}
```

**All four exist as pixels and none has a tag** — loading at
`EnvironmentInfoScreen.kt:116`, error at `:128` (an `errorContainer` card with a
warning glyph), empty at `:185` (a `surfaceVariant` card with an info glyph).
Error and empty are visually distinct, which is the CSD/3 §2.2 requirement met
in the rendering and unassertable in the harness. The nine tags this screen has
(`btn_environment_back`, `btn_environment_refresh`, `btn_add_item`,
`item_${id}`, `entity_${id}`, `group_$group`, `domain_$domain`,
`enrichment_$key`, `enrichment_header_$key`) are all navigation and content;
none is a state.

## 3. Contracts (who)

Verified against ciris-server `origin/main` at 0.5.217 (2026-09-25) and the
`~/CIRISAgent` working tree, whose last commit is **2026-08-15** — six weeks
stale, so the agent rows are "true of that tree".

| value | endpoint | owner | state |
|---|---|---|---|
| the items | `POST /v1/memory/query` `{scope: "environment", limit: 100}` | CIRISServer `src/memory_api.rs:1267` · CIRISAgent `routes/memory.py` | **live on both.** `GraphScope::Environment` is one of the node's four scopes |
| **add an item** | `POST /v1/memory/store` | CIRISAgent `routes/memory.py:328` | **wrong-host.** The node's memory API is READ-ONLY — `src/memory_api.rs:1265-1272` mounts `stats`, `timeline`, `query`, `{node_id}` and `{node_id}/edges`, and no store |
| **delete an item** | `DELETE /v1/memory/{node_id}?scope=environment` | CIRISAgent `routes/memory.py:432` | **wrong-host.** The node mounts `/v1/memory/{node_id}` as GET only |
| context enrichment | `GET /v1/system/adapters/context-enrichment?refresh=true` | CIRISAgent `routes/system/adapters.py:757` | **agent-only, and unguarded.** There is no `nodeSkip` on `getContextEnrichment` (`CIRISApiClient.kt:11112`), so on a node build the call 404s and throws |
| a Postgres-backed node | all five memory routes | CIRISServer `src/memory_api.rs:1274-1320` | **503 by design** — "memory API requires SQLite backend" |

**This card is placed as though it worked on a node and two-thirds of it does
not.** `Placement(NavSurface.EnvironmentGraph, Tab.DECISIONS,
setOf(LOCAL_COMMUNITY))` carries no `agentOnly` flag (`CirclesNav.kt:125`), so
the node build offers `btn_add_item` and a delete action on every item card
against routes the node does not mount, and opens the screen with an enrichment
read that throws.

**Two asks, and they point at different repos:**
* **CIRISServer** — mount the environment scope's write side
  (`POST /v1/memory/store`, `DELETE /v1/memory/{node_id}`), or say the node's
  graph is read-only on purpose so the client can hide the controls honestly.
* **CIRISClient** — `getContextEnrichment` needs a `nodeSkip` guard *that
  announces itself* rather than one that returns an empty map. CSD-071 §2.1 is
  the cautionary case: a fabricated empty is worse than a raised error, because
  it becomes a sentence blaming the reader.

The Postgres row is a third, quieter state: five routes that answer 503 with a
specific reason, and a client that will render whatever its `catch` writes.

## 4. Flow (how)

`tools/qa_runner/flows/environment_card.yaml` is **stale**: it was two steps —
click the tile on the Local Community hub, then assert back returns to the hub
rather than `homeTarget`, which CIRISClient#48 established the hard way. Neither
step exists now. The hub tile is gone; the shell's `btn_nav_back` is the only
back and the shell owns where it goes, so the bug that flow was written for
cannot recur in the same shape and the flow asserts a chain nobody walks.

What replaces it, using real tags:

Land on `EnvironmentInfo` (Neighbours › Decisions, derived).

```yaml
expect:
  visible: [btn_environment_refresh, btn_add_item]
```

*Cannot yet assert:* the five chips, their counts, or which is selected.

Against a node with environment items:

```yaml
expect:
  count: {of: "item_*", min: 1}
```

Against a node with an adapter reporting surroundings:

```yaml
expect:
  count: {of: "enrichment_header_*", min: 1}
```

**On a node build the second step is the interesting one and it is not written,**
because what should happen is that `btn_add_item` is absent (§3) and today it is
present.

## 5. QA plan

**Platforms.** All five. **Corners** matter more: `LOCAL_NODE` and `REMOTE_NODE`
are where the three broken reads and writes live, and `testing/cases.py` has no
environment case in any corner.

**Acceptance — functional**
1. The five categories and their counts are on screen before any item is.
2. Adding an item is offered only where it can succeed.
3. "Nothing in this category" and "could not read" are different renderings.
4. The enrichment section is absent rather than erroring where no adapter reports.

**Not tested here.**
* **Acceptance 2**, entirely — the controls are offered on a build that cannot
  serve them (§3).
* **All four states** — every tag is `proposed:` (§2).
* **The 503 Postgres path.** A real second backend, no fixture, and the client's
  rendering of it is whatever the generic `catch` produces.
* **That an item is a `need:*` on the wire.** The screen writes a graph node in
  the `environment` scope; whether that projects to the open-call family is the
  substrate's business and is asserted nowhere.
