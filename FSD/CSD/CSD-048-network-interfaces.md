# CSD-048 — The transports this node carries traffic on (the Interfaces tile)

**CSD**: CSD-048 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route map (PR #111): a screen with routes and no CSD
**Covers**: `Screen.NetworkInterfaces` (`ui/screens/federation/NetworkInterfacesScreen.kt` + `viewmodels/federation/NetworkInterfacesViewModel.kt`)
**Reads with**: **CSD-049** (the Queue tile: the same `GET /v1/federation/metrics` snapshot, projected per plane instead of per medium — two doors, see §6), CSD-051 (the hub)
**Flow**: `testing/flows/drafts/csd-048-network-interfaces.yaml` (floor `>=0.5.225`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see each medium this node moves bytes over — tcp, bluetooth,
lora, whatever the node reports — with bytes in, bytes out and how reachable
its peers are on it; and a medium the node reports nothing on is absent, not a
row of zeros.**

Serves **Transparency** (CC 3.1.9): the transports are the node's own report,
polled every 10 s, projected and never invented.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: NetworkInterfaces
flow_only: true
entry: Everyone › Rules › Global Commons (the hub, CSD-051) → the Interfaces tile (`NetworkTile.INTERFACES`)
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:transport_id
    use: display-only
    type: string
    example: "tcp"
    renders: "one card per medium, from the union of transport_bytes_in_total, transport_bytes_out_total and the reachability keys"
    tag: "card_transport_{id}"
  - ceg: x_private:transport_bytes
    use: display-only
    type: int
    example: 48213
    renders: "bytes in / bytes out on the card"
    tag: "card_transport_{id}"
  - ceg: x_private:peer_reachability_ratio
    use: display-only
    type: float
    example: 0.75
    renders: "the average of the per-peer ratios on that medium, with the sample count; absent when the node reported none"
    tag: "card_transport_{id}"
```

```yaml csd:states
populated: {tag: card_transport_tcp}
empty:     {tag: empty_interfaces, renders: "'No transports reported' — only after a read that returned a snapshot with no transport keys"}
loading:   {renders: "CircularProgressIndicator while the first snapshot is in flight"}
error:     {tag: federation_interfaces_error, renders: "the read-failure block; federation_interfaces_not_on_this_node when the route is absent. Never 'No transports' with a red line under it"}
```

## 3. Contracts (who)

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217).

| value | endpoint | owner | state |
|---|---|---|---|
| the snapshot | `GET /v1/federation/metrics` → `{data: {transport_bytes_in_total, transport_bytes_out_total, peer_reachability_ratio, …}}` (`src/federation_surface.rs:697`, handler `:170-340`) | CIRISServer | live — `getFederationMetrics` (`CIRISApiClient.kt:1645`, node URL), polled every 10 s |
| the reachability key | `peer_reachability_ratio` is keyed `"{peer_key_id}:{medium}"` (`src/federation_surface.rs:222`) | CIRISServer | live — the client split on `|` (the retired agent route's separator), so every peer:medium pair became its own "transport"; `collateTransports` now resolves the medium |

## 4. Flow (how)

Open the hub, tap the Interfaces tile.

```yaml
expect:
  visible: [screen_federation_interfaces, btn_federation_interfaces_refresh]
```

With a node carrying tcp traffic:

```yaml
expect:
  state: populated
  count: {of: "card_transport_*", min: 1}
```

With the node down:

```yaml
expect:
  state: error
  visible: [federation_interfaces_error]
  absent: [empty_interfaces]
```

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-048-network-interfaces.yaml`, floor `>=0.5.225`); promotes to `testable` when it runs on the matrix. **Not moved to `testing/flows/` on the 0.5.225 run (2026-09-29):** `Screen.NetworkInterfaces` is flow-only — `nav_map` derives no hop to it, so the runner only waits for it after sign-in lands on Contacts, and the flow would be `cannot-start` (red) on every leg. To move it: start on LayerGlobalCommons and tap its interfaces tile, as csd-047 does.

**Platforms.** All five. A LoRa or Bluetooth row needs hardware; the matrix
asserts tcp.

**Not tested here.** RSSI / SNR rows (no node reports them yet).

## 6. Delta — card vs API vs CC

* **One snapshot, two doors.** Interfaces and Queue (CSD-049) both read
  `GET /v1/federation/metrics` and nothing else. They are kept as two cards
  because they answer different questions of one read: this card is *per medium*
  (which radio carries what), the Queue is *per plane* (application vs
  replication, queue depth, failures). Neither mutates. Folding them would make
  one long card of two unrelated tables; the rule that fires on a shared
  *mutating* route does not fire here, and the same read behind two views is
  named in both CSDs.
* **A wrong separator, fixed.** Rows multiplied per peer; now one row per medium.
* **Error looked like empty, fixed.**
