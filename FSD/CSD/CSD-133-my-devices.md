# CSD-133 — My devices (My things › Devices & keys)

**CSD**: CSD-133 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the steward's ruling of 2026-11-04: self keys, devices and agents are three rosters, so three cards, because removing an occurrence is destructive and belongs on its own card
**Reads with**: CSD-037 (My keys: the self-key roster, and the release confirm this card shares), CSD-132 (Agents you own), CSD-085 (claim), CSD-094 (approve a new device)
**Flow**: none yet — release is driven under CSD-037's tags
**Built**: `ui/screens/MyDevicesScreen.kt`, `NavSurface.MyDevices`, `ownedDevices()` (`models/federation/NodeCode.kt`); tested by `OwnedAgentsTest`

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see every device bound to them that does not run an agent, and
release one.** Devices (owned nodes) were a section of the self-key screen, so
two kinds of removal sat together: revoking a key and releasing a device. Each
roster now has its own card and its own remove. Serves **Integrity**: release is
the roster's decision, held for its quorum (CSD-037).

## 2. Surface (what)

```yaml csd:surface
surface: my-devices
screen: MyDevices
```

**Its own row under My things › Devices & keys,** between My keys and Agents
you own. Every owned node is on exactly one of My devices and Agents you own, by
`ownedAgents()`. "Your nodes" is gone as a term: a node you own is a device.
**Add a device** goes to My keys, where approving one adds its key. **Claim a
device** is CSD-085.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:owned_device
    use: display-only
    type: string
    example: "Kitchen laptop · 3f9a…c21e · This device"
    renders: "one card per owned device: its label when it has one, the shortened key, and 'This device' on the one serving you"
    tag: "identity_node_row_*"
  - ceg: x_private:owned_device_release
    use: emit
    type: string
    example: "3f9a…c21e"
    renders: "Release, behind CSD-037's three-fact confirm; held for the roster's quorum"
    tag: "btn_identity_release_*"
```

```yaml csd:states
populated: {tag: identity_node_list, renders: "one card per device"}
empty:     {tag: identity_nodes_empty, renders: "'No devices are bound to you yet, apart from any that run an agent.'"}
loading:   {tag: screen_my_devices, renders: "the intro and the two actions; the cards fill in when the owned nodes are read"}
error:     {tag: identity_release_outcome, renders: "a release refusal by id, from the shared outcome block"}
```

**Another node you own, from here (Eric, 2026-11-05).** Each row that is not this
device has View (`btn_identity_view_<key_id>`), shown when this device's node is
0.5.245 or newer. It opens `RemoteNode`, a SNAPSHOT of that node read through this
one (`POST /v1/mesh/relay`): its network metrics and its newest 100 log lines,
with the time it was read (`txt_remote_node_snapshot`) and Refresh
(`btn_remote_node_refresh`). Event streams cannot cross the relay, so there is no
live view. Each half can fail alone. The relay's own failures are said in words:
unreachable, timed out, could not send, no transport, and a target too old to
relay these reads ("needs an update", 403 "not a relayable control op"). A target
that refuses the signer reads as "only its owner's devices". The memory graph is
not relayable and is not offered.

## 3. Contracts (who)

All at the local node, owner session.

| value | endpoint | owner | state |
|---|---|---|---|
| the owned nodes | `GET /v1/setup/owned-nodes` | CIRISServer | live |
| release | `POST /v1/self/nodes/{node_key_id}/release` | CIRISServer | live; held for the roster's quorum (CSD-037) |
| another owned node's metrics and logs | `POST /v1/mesh/relay {target_key_id, method, path}` relaying `GET /v1/federation/metrics` and `GET /v1/telemetry/logs?limit=100` | CIRISServer 0.5.245 `src/mesh_relay.rs` (`RELAYABLE`) | **called** — `meshRelay`; both nodes need 0.5.245 |
| shared reads of the view model it shares with My keys (CSD-037) | `GET /v1/federation/self-key-record`, `GET /v1/self/occurrences`, `GET /v1/system/health` | CIRISServer | live — read on entry by `IdentityManagementViewModel.load()`; this screen draws none of them |

## 4. Flow (how)

1. My things › Devices & keys › My devices: `screen_my_devices`.
2. `btn_identity_release_{key}` opens CSD-037's release confirm.
3. A held release reads as held, never as done.

## 5. QA plan

`OwnedAgentsTest.everyOwnedNodeIsOnExactlyOneCardSoEachReleaseHasOneHome`, and the
nav test that keys, devices and agents are three cards in that order. Release
itself is pinned under CSD-037.

## 6. Delta — card vs API vs CC

* **Rename** is on Agents you own only; offering it here waits on a release that
  names plain devices.
