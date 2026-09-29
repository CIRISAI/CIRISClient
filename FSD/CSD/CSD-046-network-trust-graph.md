# CSD-046 — The peer set as a graph (the Trust graph tile; the Map tile folded in)

**CSD**: CSD-046 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route map (PR #111): two screens with routes and no CSD
**Covers**: `Screen.NetworkTrustGraph` (`ui/screens/federation/NetworkTrustGraphScreen.kt` + `viewmodels/federation/NetworkTrustGraphViewModel.kt`). **`Screen.NetworkMap` is retired into it** (below).
**Reads with**: CSD-033 (the same peers as a list), CSD-104 (tapping a node opens the peer detail), CSD-051 (the hub)
**Flow**: `testing/flows/drafts/csd-046-network-trust-graph.yaml` (floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

**Why the Map was folded (the dedup rule, PR #111).** `NetworkMapScreen` and
`NetworkTrustGraphScreen` called the same routes (`/v1/federation/peers`,
`/v1/federation/identity`, `/v1/federation/peers/{}`) and drew the same peer set
in the same three tiers — canonical, trusted, the rest. The Map's own view model
said so: "no transport surfaces geo telemetry … so we deliberately do NOT
fabricate locations. Instead we project the peer list into three concentric
tiers." That is the trust graph with the labels off. The Map screen, its view
model and its tile are removed; CSD-034 was reserved for it and is left unused.
When a geo pipeline exists, a map is a new card with a new route, not this one.

## 1. Mission (why)

**A person can see, at a glance, how this node's peers stand around it: the
canonical ones nearest, the ones this node has trusted next, everyone else
outside; and reach any one of them to check its key.**

Serves **Integrity**, as CSD-033 does: the tiers are this node's own recorded
judgements, not a reputation. The graph is a projection of the list; nothing on
it is computed that the list does not already carry.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: NetworkTrustGraph
flow_only: true
entry: Everyone › Rules › Global Commons (the hub, CSD-051) → the Trust graph tile (`NetworkTile.TRUST_GRAPH`); a node in the canvas calls `onPeerClick(keyId)` → Screen.NetworkPeerDetail (CSD-104)
```

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:peer_trust
    use: display-only
    type: "enum[trusted,untrusted,blocked,unknown]"
    example: "trusted"
    renders: "the ring a peer is drawn on: canonical inner, trusted middle, the rest outer"
    tag: canvas_trust_graph
  - ceg: x_private:signer_key_id
    use: display-only
    type: string
    example: "ciris-node-4a19c2"
    renders: "the centre node's label — this node; drawn as '—' when the identity read failed, without failing the graph"
    tag: canvas_trust_graph
```

```yaml csd:states
populated: {tag: canvas_trust_graph}
empty:     {tag: empty_trust_graph, renders: "'No peers yet' — only after a read that returned zero peers"}
loading:   {renders: "CircularProgressIndicator over the empty canvas"}
error:     {tag: federation_trust_graph_error, renders: "the read-failure block; federation_trust_graph_not_on_this_node when the route is absent. Never 'No peers yet' with a red line under it"}
```

## 3. Contracts (who)

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217).

| value | endpoint | owner | state |
|---|---|---|---|
| the peer set | `GET /v1/federation/peers` (`src/federation_peers.rs:1435`) | CIRISServer | live — `listFederationPeers` (node URL). **The primary read**: the graph is drawn from it alone |
| the centre label | `GET /v1/federation/identity` (`src/federation_surface.rs:696`) | CIRISServer | live — `getFederationIdentity`; **secondary**: its failure no longer fails the graph |
| per-peer reachability (capped) | `GET /v1/federation/peers/{key_id}` (`src/federation_peers.rs`, `get_peer`) | CIRISServer | live — `fetchReachabilityCapped`, best-effort |

## 4. Flow (how)

Open the hub, tap the Trust graph tile.

```yaml
expect:
  visible: [screen_federation_trust_graph, canvas_trust_graph, btn_trust_graph_refresh]
```

With the node down:

```yaml
expect:
  state: error
  visible: [federation_trust_graph_error]
  absent: [empty_trust_graph]
```

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-046-network-trust-graph.yaml`, floor `unreleased`); promotes to `testable` when the floor is no longer `unreleased` and the flow runs on the matrix (#97).

**Platforms.** All five; the canvas has no per-vertex tags, so the populated
assertion is the canvas and the count is not assertable (the list, CSD-033,
carries the count).

**Not tested here.** Tapping a vertex (CSD-104's entry).

## 6. Delta — card vs API vs CC

* **Two cards, one act, folded.** See the head of this file.
* **An identity failure failed the graph, fixed.** `runApi` wrapped both reads,
  so an unreadable identity drew a readable peer list as an error.
* **Error looked like empty, fixed.**
