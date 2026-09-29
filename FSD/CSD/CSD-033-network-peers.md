# CSD-033 — The peers this node holds (the Peers tile)

**CSD**: CSD-033 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route map (PR #111): a screen with routes and no CSD
**Covers**: `Screen.NetworkPeers` (`ui/screens/federation/NetworkPeersScreen.kt` + `viewmodels/NetworkPeersViewModel.kt`)
**Reads with**: **CSD-104** (a peer row opens `Screen.NetworkPeerDetail`: trust, appearance and the short-code ceremony live there, not here), CSD-046 (the same peer set drawn as a graph), CSD-051 (the hub)
**Flow**: `testing/flows/drafts/csd-033-network-peers.yaml` (floor `>=0.5.225`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see every node this node knows — which are canonical, which
they have trusted, blocked or never judged — filter by that, and add a peer
from a node code. A peer list that could not be read never looks like an
empty one.**

Serves **Integrity**: the trust a row shows is *this node's* recorded judgement
(`LocalPeerState.trust`, a `config:*` sideband row at `cohort_scope: self`, CC
3.4.5.1), not a reputation. Changing it is CSD-104's act, behind the row.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: NetworkPeers
flow_only: true
entry: Everyone › Rules › Global Commons (the hub, CSD-051) → the Peers tile (`NetworkTile.PEERS`); also the back target of Screen.NetworkPeerDetail (CSD-104)
```

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:peer_key_id
    use: display-only
    type: string
    example: "ciris-node-9f21ab"
    renders: "one row per peer, alias or key id, canonical badge, trust chip; tapping it opens CSD-104"
    tag: "peer_row_{keyId}"
  - ceg: x_private:peer_trust
    use: display-only
    type: "enum[trusted,untrusted,blocked,unknown]"
    example: "unknown"
    renders: "the trust chip on the row, and the filter row above the list"
    tag: "peer_row_{keyId}"
  - ceg: x_private:show_ciris_infrastructure
    use: display-only
    type: bool
    example: false
    renders: "a switch: show the canonical CIRIS infrastructure peers or hide them"
    tag: switch_show_infra
  - ceg: x_private:node_code
    use: emit
    type: string
    example: "CIRIS-V1-…"
    renders: "the paste field in the add-peer sheet (drivable), the scan button, and the submit"
    tag: input_add_peer_code
```

```yaml csd:states
populated: {tag: list_peers}
empty:     {tag: empty_peers, renders: "'No peers yet' with the add-peer affordance — only after a read that returned zero rows"}
loading:   {renders: "CircularProgressIndicator while loading and the list is empty"}
error:     {tag: federation_peers_error, renders: "the read-failure block in place of the list; federation_peers_not_on_this_node when the route is absent. Never the dismissable banner over 'No peers yet'"}
```

## 3. Contracts (who)

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217) and CIRISAgent `main`.

| value | endpoint | owner | state |
|---|---|---|---|
| the peer list | `GET /v1/federation/peers[?canonical_only&trust]` → `{peers: [LocalPeerState…], total}` (`src/federation_peers.rs:1435`, handler `:596`) | CIRISServer | live — `listFederationPeers` (`CIRISApiClient.kt:1265`, node URL) into `LocalPeerState {key_id, pubkey_ed25519_base64, pubkey_ml_dsa_65_base64?, canonical, trust, first_seen, appearance?, alias_override?, notes?, last_seen?}` |
| add a peer from a node code | `POST /v1/system/peers/add-from-code {code}` → `{data: {peer, was_already_present}}`; 400 `INVALID_NODE_CODE {subtype}`, 409 `PUBKEY_CONFLICT`, 503 `BOOTSTRAP_SEEDER_UNAVAILABLE` (CIRISAgent `routes/system/peers.py:250`, SYSTEM_ADMIN) | CIRISAgent | live on the agent only — `addPeerFromNodeCode` raises `RouteNotOnThisHost` in `ClientMode.NODE`, so the sheet says "this node can't …" instead of a raw 404. **No node-side equivalent exists**: a bare node cannot add a peer by code from this card |
| a row's trust and appearance, the short-code check | `PUT /v1/federation/peers/{key_id}/trust`, `…/appearance`, `GET …/sas`, `PUT …/sas` | CIRISServer | live — **CSD-104's card**, reached by tapping a row |

## 4. Flow (how)

Open the hub, tap the Peers tile.

```yaml
expect:
  visible: [screen_federation_peers, switch_show_infra, btn_add_peer]
```

With at least one peer:

```yaml
expect:
  state: populated
  visible: [list_peers]
  count: {of: "peer_row_*", min: 1}
```

Tap `btn_add_peer`, type into `input_add_peer_code`:

```yaml
do:
  - click: btn_add_peer
  - input: {input_add_peer_code: "not-a-code"}
  - click: btn_add_peer_submit
expect:
  visible: [sheet_add_peer, text_add_peer_error]
```

With the node down:

```yaml
expect:
  state: error
  visible: [federation_peers_error]
  absent: [empty_peers]
```

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-033-network-peers.yaml`, floor `>=0.5.225`); promotes to `testable` when it runs on the matrix. **Not moved to `testing/flows/` on the 0.5.225 run (2026-09-29):** `Screen.NetworkPeers` is flow-only — `nav_map` derives no hop to it, so the runner only waits for it after sign-in lands on Contacts, and the flow would be `cannot-start` (red) on every leg. To move it: start on LayerGlobalCommons (which has a hop) and tap `tile_federation_peers`, as csd-047 does.

**Platforms.** All five. The add-by-code path needs an agent; the bare-node leg
asserts the sheet's "not on this node" copy instead.

**Not tested here.** The QR scanner (`btn_scan_qr`; the scanner primitive is
`feat/qr-encode-scan`'s). Everything behind a row (CSD-104).

## 6. Delta — card vs API vs CC

* **Error looked like empty, fixed.** A failed list read was a dismissable banner
  over "No peers yet"; once dismissed, a dead node read as a node with no peers.
* **The paste field had no input sink, fixed.** `/input` refused it (CIRISClient#30),
  so no flow could add a peer.
* **Add-by-code is agent-only.** The node has `/v1/federation/node-code` to *give*
  a code and no route to *take* one; the seeder lives in the agent. **Ask
  (CIRISServer):** `POST /v1/federation/peers/add-from-code`, so a bare node can
  add a peer from this card. Search found no existing issue; draft in the report.
* **Duplication.** `GET /v1/federation/peers` is also read by Contacts,
  DataManagement, Delegations, ManageConsent, NetworkContent and the trust graph.
  Those are reads of one list for different questions; the only mutation on this
  card (`add-from-code`) is called from nowhere else.
