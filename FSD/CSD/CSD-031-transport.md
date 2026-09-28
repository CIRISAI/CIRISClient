# CSD-031 — Transport (how this node is reachable, and over what radio)

**CSD**: CSD-031 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, "This node"
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can see how their node is reachable — its Reticulum address, who it
can reach, what it can carry — and, on a desktop, set up the LoRa radio that
makes it reachable when nothing else is.**

Transport is the card that matters when the rest of the app cannot load. CC
3.1.4 files `transport:{kind}`, `peer_reachability:{network}`,
`key_boundary:{scope}` and `delivery:{class}` to CIRISEdge as
substrate-self-reports (CC 3.4.3): "emittable only by the running Edge
instance, which is what makes them honest." This screen is the owner's read of
those, plus the one write CIRISEdge cannot make for itself — the radio
parameters, which are physical.

## 2. Surface (what)

```yaml csd:surface
surface: transport
screen: Transport
```

`nav_map` derives `btn_my_things -> nav_instrument_this_node ->
nav_epistemic_transport`. Not `agentOnly`, correctly: `GET
/v1/federation/identity` and `PUT /v1/config/{key}` are both the node's. This
is the only card in this area carrying a real screen-level tag
(`screen_transport`, `TransportScreen.kt:55`).

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "transport:{kind}"
    bind: {kind: reticulum}
    use: display-only
    type: string
    example: "a1b2c3d4e5f60718"
    renders: "Reticulum address — a1b2c3d4…0718, monospaced. CC 3.1.4 / CC 3.4.3: Edge's own self-report; nobody else may emit it."
    tag: row_transport_address
  - ceg: "peer_reachability:{network}"
    bind: {network: reticulum}
    use: display-only
    type: int
    example: 14
    renders: "Peers — 14 (3 canonical). The parenthetical is the load-bearing half: canonical peers are the ones that anchor."
    tag: row_transport_peers
  - ceg: "delivery:{class}"
    bind: {class: advertised}
    use: display-only
    type: "list[string]"
    example: ["blob", "stream", "announce"]
    renders: "Capabilities — blob, stream, announce. What this node advertises it can carry."
    tag: row_transport_capabilities
  - ceg: "config:{scope}"
    bind: {scope: transport}
    use: read
    type: bool
    example: false
    renders: "Radio — off. Writes `net.radio.enabled`. CC 3.4.5.1 note 1 names `transport` as a leaf that 'MUST be emitted at self' because it carries bootstrap peers and peer sideband; this form is editing exactly that leaf."
    tag: toggle_radio_enabled
  - ceg: x_private:radio_serial_port
    use: read
    type: string
    example: "/dev/ttyUSB0"
    renders: "Serial port — /dev/ttyUSB0. Writes `net.radio.serial_port`."
    tag: input_radio_serial_port
  - ceg: x_private:radio_frequency_hz
    use: read
    type: int
    example: 915000000
    renders: "Frequency — 915000000 Hz. Writes `net.radio.frequency_hz`. NOTE: the screen offers no band guidance, and the legal band differs by country."
    tag: input_radio_frequency
  - ceg: x_private:radio_bandwidth_hz
    use: read
    type: int
    example: 125000
    renders: "Bandwidth — 125000 Hz"
    tag: input_radio_bandwidth
  - ceg: x_private:radio_spreading_factor
    use: read
    type: int
    example: 8
    renders: "Spreading factor — 8"
    tag: input_radio_spreading_factor
  - ceg: x_private:radio_tx_power_dbm
    use: read
    type: int
    example: 17
    renders: "Transmit power — 17 dBm. Same note as frequency: the legal ceiling is jurisdictional and the field is a free integer."
    tag: input_radio_tx_power
  - ceg: "key_boundary:{scope}"
    bind: {scope: transport}
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "The transport identity is a SEPARATE keypair the federation key authorizes by signing the binding — CC 4.4.3.4.3 AV-17: 'the federation signing seed MUST NOT enter the transport layer.' NOT SENT: `GET /v1/federation/identity` returns the signer key id and the destination, not the boundary attestation."
    tag: "proposed:transport_key_boundary"
    blocked_by: CIRISServer#670
```

**`key_boundary:{scope}` is `unconfirmed` and it is the security-relevant
one.** CC 4.4.3.4.3's AV-17 paragraph is explicit that the Reticulum
destination is a separate dual-key transport identity which the user's signing
key *roots*, not shares. A person looking at `row_transport_address` beside a
signer key id has no way to see that those are two keys and that one did not
leak into the other. The family exists precisely to make that visible.

```yaml csd:states
populated: {tag: card_transport_current}
empty:     {tag: "proposed:transport_no_peers", renders: "Nobody reachable yet — zero peers on a fresh node. Distinct from a failed read: a node that just started has an address and no peers, which is normal."}
loading:   {tag: "proposed:transport_loading", renders: "TransportScreen.kt:88 — a spinner and 'Loading…' while `isLoading && identity == null`"}
error:     {tag: "proposed:transport_error", renders: "TransportScreen.kt:95 renders 'Transport facts unavailable (node degraded?).' for `identity == null`. `TransportScreenState.error` carries the real message and is never shown."}
```

**The error sentence is both collapsed and speculative.** One branch covers a
404, a 500, a timeout and a node that genuinely has no identity, and it
guesses at the cause with a question mark. CSD/3 §2.2 requires `error` to be
distinguishable from `empty`; the CC 3.4.3 reading is sharper still — a
substrate-self-report that could not be read is not a degraded substrate, it is
an unread one, and the difference is the whole value of a self-report.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| address, peers, capabilities | `GET /v1/federation/identity` | CIRISServer | live (`/v1/federation/identity`); read from `LOCAL_NODE_URL`, not `baseUrl` (`CIRISApiClient.kt:1230`) |
| radio config write | `PUT /v1/config/{key}` ×7 | **both** | live; owner-gated |
| radio config read-back | `GET /v1/config` | **both** | live |
| the agent's own federation address (Edge `signer_key_id`, `available=false` when Edge is off) | `GET /v1/system/federation` | CIRISAgent (`routes/system/health.py:912`, OBSERVER) | live, **not called** — the brain's answer to the question the first row asks the node; on an agent build the two can differ and the card shows only the node's |
| the edge event stream | `GET /v1/federation/events/{channel}` (SSE) | CIRISServer `src/federation_surface.rs:703` | live — called, **but from the network hub's tiles, not this card** (`FederationEventStream.kt:91`; cited in CSD-051). The route-coverage report placed it here |
| the key-boundary attestation | `GET /v1/federation/identity` — **unconfirmed** | CIRISServer | blocks `building` for `transport_key_boundary` |

Agent tree last commit 2026-08-15; this card's contract is the node's.

## 4. Flow (how)

Open My things → This node → Transport.

```yaml
expect:
  state: populated
  visible: [screen_transport, card_transport_current, row_transport_address, row_transport_peers]
```

Enable the radio and fill the form.

```yaml
expect:
  visible: [card_transport_radio, toggle_radio_enabled, input_radio_serial_port, input_radio_frequency, btn_radio_apply]
```

Apply.

```yaml
expect:
  state: populated
  number: {input_radio_frequency: {min: 137000000, max: 1020000000}}
```

Refresh against a node that is down.

```yaml
expect:
  state: error
  visible: [transport_error]
  absent: [transport_no_peers]
```

The last block fails today.

## 5. QA plan

**Platforms.** All five for the read half. The radio form persists on every
platform and *activates* only on desktop (a sandboxed mobile node cannot open
a serial port), which the screen already explains with a hint; the flow drives
the form on all five and asserts the hint on the four where it applies.

**Not tested here.** That the radio comes up. No RNode hardware in CI, and the
gate must not pretend a persisted config is a working radio — the screen's own
hint makes the same distinction and is the right model.

## 6. Card vs API vs CC — the delta

1. **Placement and build flag are right.** Node-owned data, node-owned write,
   shown on both builds. No change.
2. **The best tag coverage in this area** — fifteen real tags including the
   only `screen_*` tag on any of the twelve cards, and one per radio field.
   What is missing is the states: no tag on loading, empty or error.
3. **The seven-write apply is not atomic, and this one has a physical
   consequence.** `applyRadioConfig` (`TransportViewModel.kt:110`–`126`) issues
   seven independent `PUT /v1/config/{key}` calls in sequence. A failure on
   the third leaves `net.radio.enabled = true` persisted with a stale
   frequency and power. On a desktop node that activates the radio, that is a
   transmitter running on parameters the owner did not choose. **Ask
   (CIRISServer): a batch config write, or a `net.radio.*` document write, so
   the radio's parameters land together or not at all.** Until then the client
   should write `enabled` LAST.
4. **No band or power guidance.** `input_radio_frequency` and
   `input_radio_tx_power` are free integers filtered to digits
   (`TransportViewModel.kt:91`, `:97`). The lawful band and ceiling are
   jurisdictional. The app already knows the owner's ground (CSD-022's
   location section), so this is joinable.
5. **CC gap — ask (CIRISServer/CIRISEdge).** Return the `key_boundary:transport`
   self-report on `GET /v1/federation/identity`, so this screen can show that
   the transport identity is a distinct key the federation key authorized
   rather than the federation key itself (CC 4.4.3.4.3 AV-17). It is one row,
   and it is the difference between "trust us" and a visible boundary.
6. **Two hardcoded English strings.** `"Radio configuration saved"` and
   `"Failed to save radio configuration: …"` (`TransportViewModel.kt:121`,
   `:126`) — every other string on this screen goes through
   `localizedString(...)` with an English `.ifEmpty { }` fallback.
