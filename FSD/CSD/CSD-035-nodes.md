# CSD-035 — Nodes (My things › This node › Nodes)

**CSD**: CSD-035 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 1
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see every node they participate in, tell which one they own and
which one they are standing on, add another by its code, and switch between
them — and the screen says plainly when it cannot tell who owns a node.**

Serves **CC 3.2**: the `self` cohort *is* the set of nodes sharing one owner
("a person's own devices, distinct keys unified by the owner-binding graph"), so
this list is not a bookmarks folder — it is the visible form of the boundary that
decides who may read this person's self-scope content. CC 3.2 also fixes the
failure mode the screen must not hide: "a key with two or more distinct owners
has an undefined `self` boundary and MUST NOT be treated as owned by any of
them", and a consumer resolving `self` "MUST treat cardinality ≠ 1 as a refusal".
A row that renders an ambiguous owner as a blank is a refusal drawn as a success.

## 2. Surface (what)

```yaml csd:surface
surface: nodes
screen: ManageNodes
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_nodes`. The screen opens on the **graph** view and carries a
graph ⇄ list toggle (`ManageNodesScreen.kt:145`); everything asserted below is
the list view, because the graph has no per-vertex tags.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Owned by you — derived from `owner` on GET /v1/setup/owned-nodes, NOT from an ownership:* row. The binding's attester, timestamp and the CC 3.2 cardinality are not carried, so the row cannot say WHEN or on WHOSE signature."
    tag: "proposed:row_node_owner"
  - ceg: "health:liveness:{version}"
    bind: {version: v1}
    use: display-only
    type: bool
    example: true
    renders: "a reachability dot on the row — the node answered GET /v1/system/health"
    tag: "proposed:row_node_liveness"
  - ceg: x_private:node_profile_name
    use: display-only
    type: string
    example: "Home"
    renders: "Home  (active)"
    tag: "row_node_{profileId}"
  - ceg: x_private:node_base_url
    use: display-only
    type: string
    example: "http://192.168.1.20:8080"
    renders: "http://192.168.1.20:8080"
    tag: "row_node_{profileId}"
  - ceg: x_private:pinned_key_id
    use: display-only
    type: string
    example: "ciris-node-4a19c2"
    renders: "Pinned / Not pinned — the identity pin taken when the node was added by NodeCode"
    tag: "row_node_{profileId}"
  - ceg: x_private:has_session
    use: display-only
    type: bool
    example: true
    renders: "Signed in / No session"
    tag: "row_node_{profileId}"
```

**The owner row is `unconfirmed` on purpose.** `GET /v1/setup/owned-nodes`
returns `{owner: Option<String>, nodes: [{key_id, is_self}]}` and nothing else
(`CIRISServer/src/auth/bootstrap.rs:1365-1376`). That answers "does this node
think I own it", which is one bit; it does not carry the owner-binding's
attester, its `asserted_at`, or whether the substrate saw more than one binding.
CC 3.2 makes the last of those a *refusal condition*, so the field cannot become
`display-only`-with-a-type until the route carries it.

```yaml csd:states
populated: {tag: "proposed:nodes_list"}
empty:     {tag: "proposed:nodes_empty", renders: "mobile.manage_nodes_empty — the add-by-code and add-by-URL cards stay visible beneath it, because an empty node list has exactly two useful next moves"}
loading:   {tag: "proposed:nodes_loading", renders: "the list frame with a progress affordance and NO empty sentence"}
error:     {tag: "proposed:nodes_error", renders: "Could not read the owned-nodes projection from this node. — distinct from empty: an unreachable local node and a node that owns nothing must not draw the same screen"}
```

Every one of those four is `proposed:`. The screen today renders the empty case
through `localizedString("mobile.manage_nodes_empty")` (`ManageNodesScreen.kt:302`)
with **no tag**, and has no list-level error banner at all — a failed
`getOwnedNodes()` is logged and swallowed (`CIRISApp.kt:992-996`), so the person
sees the empty sentence. That is the exact substitution CSD/3 §2.2 forbids, and
it is recorded here rather than described as working.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the node list + `owner` | `GET /v1/setup/owned-nodes` | CIRISServer | live — `src/auth/bootstrap.rs:1489`, **loopback-only** (`:1501-1503`) |
| reachability | `GET /v1/system/health` | CIRISServer | live — `src/health.rs:589` |
| add by code | `getNodeCode` → local pin, then `claimRemote` | CIRISServer | live |
| claim this node | `POST /v1/setup/claim-remote` | CIRISServer | live |
| mint the owner's fed-ID | `POST /v1/self/identity` | CIRISServer | live — `src/identity.rs:1945`, loopback-only |
| **release a node** | `POST /v1/self/nodes/{node_key_id}/release` | CIRISServer | **live and UNCALLED** — `src/self_devices.rs:469`. No Kotlin call site exists. |
| the owner-binding's attester / timestamp / cardinality | — | CIRISServer | **missing** — blocks `building` for `row_node_owner` |

## 4. Flow (how)

Sign in on a node; open My things › This node › Nodes; switch to the list view.

```yaml
expect:
  state: populated
  count: {of: "row_node_*", min: 1}
  visible: ["proposed:nodes_list", btn_add_node_by_code, btn_add_node_by_url]
```

Add a node by URL: click `btn_add_node_by_url`, fill `field_add_node_name` and
`field_add_node_url`, click `btn_add_node_url_save`.

```yaml
expect:
  count: {of: "row_node_*", min: 2}
```

On a client whose local node is down:

```yaml
expect:
  state: error
  visible: ["proposed:nodes_error"]
  absent: ["proposed:nodes_empty"]
```

## 5. QA plan

**Platforms.** All five. The list view is plain Compose; the graph view is the
one that needs a desktop shot.

**Not tested here.** The graph view (no per-vertex tags — `NodeGraphView` draws
on a canvas); USB save/restore (`btn_nodes_save_usb` / `btn_nodes_restore_usb`
need a removable volume); the claim ceremony itself, which leaves for
`ClaimNodeScreen` and is that screen's CSD; and **releasing a node**, which has
no UI at all (§3).

**Two buttons on this screen leave it, and their destinations belong to other
CSDs.** `btn_manage_nodes_verify` is the *only* entry to `Screen.VerifyAgent`
(`CIRISApp.kt:4043`, back target `:4922`) — **CSD-087** owns that screen.
`btn_add_federation_id` is one of two entries to `Screen.AddFederationId`, the
other being the post-login catch-up (`CIRISApp.kt:987-999`) — **CSD-086** owns
it. This CSD asserts that the buttons are present and drivable, and stops at the
boundary; a flow that walked into either would be asserting another document's
contract.

**One thing about those two destinations IS this card's, because it is this
card's row that goes dark.** `screenToSurface` maps `Screen.VerifyAgent -> null`
(`CIRISApp.kt:5964`), so the Nodes row is unlit for the whole of a verify even
though `btn_manage_nodes_verify` is its only entry and `Screen.ManageNodes` its
only exit; by the parent-surface convention this codebase states at `:5935-5936`
("a leaf of that surface, not a sidebar destination of its own") and applies on
the next line at `:5937`, it should be `NavSurface.Nodes`. `Screen.ClaimNode` has
the mirror-image bug — it falls through to `NavSurface.Help` and should also be
`NavSurface.Nodes`. `AddFederationId` correctly stays `null`: it has no single
parent. Fix and full branch disposition in **CSD-042 §6**; the screens themselves
are **CSD-085** and **CSD-087**.

## 6. Delta — card vs API vs CC

* **CC → card.** CC 3.2's single-owner invariant is the reason this list exists
  and the card does not state it. A node the person does **not** own is
  indistinguishable on the row from one they do, except through `is_self`; and
  CC 3.2's "cardinality ≠ 1 is a refusal" has no rendering at all.
* **API → card.** `POST /v1/self/nodes/{id}/release` shipped on the node
  (0.5.216/0.5.217) and the client has no call site: a person can acquire nodes
  from this screen and cannot let one go. **Ask (CIRISClient):** add
  `releaseNode(nodeKeyId, forceSelf)` to `CIRISApiClient` and a per-row
  affordance here, with the `force_self` confirmation the route requires when
  the node being released is the one serving the request.
* **Card → API.** **Ask (CIRISServer):** widen `OwnedNodesResponse.nodes[]` to
  carry the owner-binding envelope — `owner_key_id`, `asserted_at`, and a count
  of live bindings — so `ownership:responsible_party:node:v1` can leave
  `unconfirmed` and the CC 3.2 refusal can be drawn.
* **Placement.** Correct. Nodes is infrastructure the person operates, not a
  circle's business; This node is where CC 3.4.7.3's substrate/actor split puts
  it.
