# CSD-049 — What this node has sent, received and is still carrying (the Queue tile)

**CSD**: CSD-049 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route map (PR #111): a screen with routes and no CSD
**Covers**: `Screen.NetworkQueue` (`ui/screens/federation/NetworkQueueScreen.kt` + `viewmodels/federation/NetworkQueueViewModel.kt`)
**Reads with**: **CSD-048** (the Interfaces tile: the same snapshot per medium — two doors, CSD-048 §6), CSD-051 (the hub)
**Flow**: `testing/flows/drafts/csd-049-network-queue.yaml` (floor `>=0.5.225`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see whether this node is actually moving records: what it has
sent and received on the application plane, what is still queued, what failed,
and — separately, because the first four say nothing about it — whether
replication is carrying records to and from peers. A counter nobody read is
never drawn as 0.**

Serves **Transparency** (CC 3.1.9). The node's own `plane_note` is the mission
in the node's words: the envelope counters "are the APPLICATION plane … anti-entropy
replication increments none of them, so 0 there says nothing about carriage".
A card that showed only the four counters told an operator a silent node was
healthy (CIRISServer#377).

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: NetworkQueue
flow_only: true
entry: Everyone › Rules › Global Commons (the hub, CSD-051) → the Queue tile (`NetworkTile.QUEUE`)
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:durable_queue_depth
    use: display-only
    type: int
    example: 3
    renders: "Queued: 3 — or '—' before the first snapshot is read"
    tag: text_queue_depth
  - ceg: x_private:envelopes_sent_total
    use: display-only
    type: int
    example: 120
    renders: "Sent: 120 (application plane, summed over envelope kinds)"
    tag: text_envelopes_sent
  - ceg: x_private:envelopes_received_total
    use: display-only
    type: int
    example: 98
    renders: "Received: 98"
    tag: text_envelopes_received
  - ceg: x_private:send_failures_total
    use: display-only
    type: int
    example: 0
    renders: "Send failures: 0, warning-toned when > 0; '—' when not read"
    tag: text_send_failures
  - ceg: x_private:carriage_standing
    use: display-only
    type: "enum[unreadable,not_exercised,idle,moving,withholding]"
    example: "moving"
    renders: "Sending records to peers — Moving; 'Not tried yet (untested, not clean)' for not_exercised; the raw token when unknown"
    tag: text_carriage_standing
  - ceg: x_private:receive_standing
    use: display-only
    type: "enum[unreadable,not_exercised,idle,converged,applying,refusing]"
    example: "converged"
    renders: "Receiving records from peers — Up to date"
    tag: text_receive_standing
  - ceg: x_private:replication_served_total
    use: display-only
    type: int
    example: 15
    renders: "Records served: 15 (summed over kinds); '—' on a node that does not report the plane"
    tag: text_replication_served
  - ceg: x_private:replication_applied_total
    use: display-only
    type: int
    example: 4
    renders: "Records applied: 4"
    tag: text_replication_applied
```

```yaml csd:states
populated: {tag: text_queue_depth}
empty:     {tag: text_replication_not_reported, renders: "'This node does not report replication yet … not the same as nothing moving' — the replication card on a node older than the plane fields; the counters above still render"}
loading:   {renders: "CircularProgressIndicator while metrics is null and loading"}
error:     {tag: federation_queue_error, renders: "the read-failure block in place of the whole card when no snapshot was ever read; federation_queue_not_on_this_node when the route is absent. A later poll that fails keeps the last reading under the transient error card"}
```

## 3. Contracts (who)

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217).

| value | endpoint | owner | state |
|---|---|---|---|
| the snapshot | `GET /v1/federation/metrics` → `{data: {envelopes_sent_total, envelopes_received_total, send_failures_total, verify_failures_total, durable_queue_depth, transport_bytes_in_total, transport_bytes_out_total, peer_reachability_ratio, inline_text_subscriber_count, replication_envelopes_served_total, withholds_by_reason, apply_refusals_by_kind, replication_applied_total, replication_duplicate_total, replication_round_outcomes_total, replication_round_routing, bootstrap_door_outcomes, blob_route_refusals, carriage_standing, receive_standing, receive_decided_total, plane_note}}` (`src/federation_surface.rs:697`, handler `:170-340`) | CIRISServer | live — `getFederationMetrics` (node URL), polled every 5 s. **Two dropped fields fixed**: the client read `verified_feed_subscriber_count`, a key no node sends (the node serves it as `inline_text_subscriber_count`, `:317`), and none of the replication-plane fields. `carriage_standing`, `receive_standing`, `replication_*_total`, `receive_decided_total` and `plane_note` are now modelled, nullable, absent-is-not-zero |
| the standing vocabularies | `carriage_standing` ∈ `unreadable|not_exercised|idle|moving|withholding` (`src/operator_surface.rs:391-421`); `receive_standing` ∈ `unreadable|not_exercised|idle|converged|applying|refusing` (`:486-525`) | CIRISServer | live — rendered by token; an unknown token is shown as sent |
| `withholds_by_reason`, `apply_refusals_by_kind`, `replication_round_routing`, `bootstrap_door_outcomes`, `blob_route_refusals` | same route | CIRISServer | live, **not modelled** — the diagnostics tile (`Screen.NetworkDiagnostics`, no CSD in this group) is where they belong; recorded here so they are not mistaken for absent |

## 4. Flow (how)

Open the hub, tap the Queue tile.

```yaml
expect:
  state: populated
  visible: [screen_federation_queue, text_queue_depth, text_envelopes_sent, text_envelopes_received, card_queue_replication]
  number: {text_queue_depth: {min: 0}}
```

On a node that reports the replication plane:

```yaml
expect:
  visible: [text_carriage_standing, text_receive_standing]
  one_of: {text_carriage_standing: [unreadable, not_exercised, idle, moving, withholding]}
```

With the node down:

```yaml
expect:
  state: error
  visible: [federation_queue_error]
  absent: [text_queue_depth]
```

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-049-network-queue.yaml`, floor `>=0.5.225`); promotes to `testable` when it runs on the matrix. **Not moved to `testing/flows/` on the 0.5.225 run (2026-09-29):** `Screen.NetworkQueue` is flow-only — `nav_map` derives no hop to it, so the runner only waits for it after sign-in lands on Contacts, and the flow would be `cannot-start` (red) on every leg. To move it: start on LayerGlobalCommons and tap its queue tile, as csd-047 does.

**Platforms.** All five.

**Not tested here.** The five unmodelled diagnostic maps (§3); the sent/received
bar's proportions.

## 6. Delta — card vs API vs CC

* **Two dropped fields, fixed** (§3). The subscriber count was always 0.
* **Zeros where nothing was read, fixed.** Every counter defaulted to `0L` before
  the first snapshot; they are null until read and drawn as `—`.
* **The plane that carries records was invisible, fixed.** The card now shows
  carriage and receive standings and the replication totals, with the node's own
  caveat that the application counters say nothing about them.
* **Two doors with CSD-048**, stated there and here.
