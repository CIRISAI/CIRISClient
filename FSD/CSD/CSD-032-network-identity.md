# CSD-032 — This node's federation identity (the Identity tile)

**CSD**: CSD-032 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route map (PR #111): a screen with routes and no CSD
**Covers**: `Screen.NetworkIdentity` (`ui/screens/federation/NetworkIdentityScreen.kt` + `viewmodels/NetworkIdentityViewModel.kt`)
**Reads with**: CSD-051 (the Everyone hub that opens it), CSD-036 (This node › Network, which shows the same signer key as an operator fact)
**Flow**: `testing/flows/drafts/csd-032-network-identity.yaml` (floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**A person can read the key this node signs the federation with, how many
peers it holds and how many of them are canonical, and get this node's own
code to hand to someone else — and can tell a measured zero from a zero the
node could not measure.**

Serves **Transparency** (CC 3.1.9: a node's operating facts are published as a
record, not inferred) and **CC 5.4.6**: the node code is a *node*'s reachability,
and a node code is not a person's contact code (`contacts.unresolvable`, CSD-104
and the 0.5.218 brief). The card says which it is showing.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: NetworkIdentity
flow_only: true
entry: Everyone › Rules › Global Commons (the Reticulum hub, CSD-051) → the Identity tile (`NetworkTile.IDENTITY`, CIRISApp.kt `Screen.LayerGlobalCommons` arm)
```

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:signer_key_id
    use: display-only
    type: string
    example: "ciris-node-4a19c2"
    renders: "the full signer key in mono, with a copy control (btn_copy_signer_key)"
    tag: text_signer_key_id
  - ceg: x_private:peer_count_total
    use: display-only
    type: int
    example: 7
    renders: "Peers: 7 — or '—' when peer_counts_standing is store_unavailable or self_identity_unresolved: a zero the node says it did not measure is not drawn as 0"
    tag: text_peer_count_total
  - ceg: x_private:peer_count_canonical
    use: display-only
    type: int
    example: 2
    renders: "Canonical: 2 — same rule"
    tag: text_peer_count_canonical
  - ceg: x_private:federation_capability
    use: display-only
    type: "list[string]"
    example: ["envelopes", "content_fetch"]
    renders: "one chip per capability the node declares (FEDERATION_CAPABILITIES)"
    tag: chip_capability_envelopes
  - ceg: x_private:node_code
    use: display-only
    type: string
    example: "CIRIS-V1-…"
    renders: "THIS node's code, from the node itself, with a copy control — the key it signs with above, not the agent's"
    tag: text_my_node_code
```

**Two hosts, one card, said on the card.** The signer key, counts, capabilities
and node code come from the NODE (`LOCAL_NODE_URL`). The Federation ID
aggregate card comes from the AGENT (`/v1/system/peers/federation-identity`); on
a bare node that route does not exist and the card says so
(`federation_id_card_not_on_this_node`) instead of sitting in "initializing"
forever.

```yaml csd:states
populated: {tag: card_identity_header}
empty:     {tag: text_peer_count_total, renders: "not a whole-card state: a node always has a signer key; 'Peers: 0' with peer_counts_standing = measured is a real zero"}
loading:   {renders: "the card frame with a CircularProgressIndicator while identity is null and loading"}
error:     {tag: federation_identity_error, renders: "'Could not read' block in place of the header, stats and capabilities; federation_identity_not_on_this_node when the route is absent. NOT the dismissable banner over a header of dashes"}
```

## 3. Contracts (who)

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217) and CIRISAgent `main`.

| value | endpoint | owner | state |
|---|---|---|---|
| signer key, counts, capabilities | `GET /v1/federation/identity` → `{data: {signer_key_id, crate_version, peer_count_total, peer_count_canonical, peer_counts_standing, capabilities}}` (`src/federation_surface.rs:696`, handler `:120-152`) | CIRISServer | live — `getFederationIdentity` (`CIRISApiClient.kt:1230`, node URL). `peer_counts_standing` (`measured` / `store_unavailable` / `self_identity_unresolved`, CIRISServer#372) was **dropped by the client**; now `FederationIdentity.peerCountsStanding` and `peerCountReading()` |
| this node's code | `GET /v1/federation/node-code` → `{code, qr_payload, key_id, alias_hint}` (`src/federation_nodecode.rs:92`, handler `:50`) | CIRISServer | live — `getNodeCode(LOCAL_NODE_URL)` (`CIRISApiClient.kt:2386`). **Was** the agent's `GET /v1/system/peers/my-node-code` (CIRISAgent `routes/system/peers.py:132`), which carries the AGENT's key on a with-AI install and 404s on a bare node |
| the Federation ID aggregate | `GET /v1/system/peers/federation-identity` → `{data: {aggregate, node_code_key_id, node_code_pubkey_ed25519_base64}}` (CIRISAgent `routes/system/peers.py:194`, OBSERVER) | CIRISAgent | live on the agent only — `getFederationIdentityAggregate` raises `RouteNotOnThisHost` in `ClientMode.NODE` and the card says so |
| the bound owner's fed-ID | `GET /v1/setup/owned-nodes` → `owner` (`src/auth/bootstrap.rs:1489`, loopback-only) | CIRISServer | live — best-effort, null when unclaimed |
| this node's conformance declaration | `GET /v1/federation/conformance` (`src/federation_admin.rs`, `conformance`; `DeclaredConformance {profiles, build_profiles, ceg_wire_version, wire_vocabulary_sha256, …}`, `src/conformance.rs:454`) | CIRISServer | **live and uncalled from any screen** — `getNodeCapabilities` (`CIRISApiClient.kt:9608`) parses it through `CapabilityWire` and is reached only from the capability gate. It belongs on this card as a "what this node claims" row; not built in this pass, see §6 |

## 4. Flow (how)

Open the hub, tap the Identity tile.

```yaml
expect:
  state: populated
  visible: [screen_federation_identity, card_identity_header, text_signer_key_id, row_identity_stats, card_node_code, text_my_node_code]
```

On a bare node (no agent):

```yaml
expect:
  visible: [federation_id_card_not_on_this_node]
  absent: [banner_federation_error]
```

With the node down:

```yaml
expect:
  state: error
  visible: [federation_identity_error]
  absent: [card_identity_header]
```

## 5. QA plan

**Platforms.** All five; the bare-node variant on desktop and Android.

**Not tested here.** The QR placeholders (`img_identity_qr_placeholder`,
`img_node_code_qr_placeholder`): the QR primitive lands with `feat/qr-encode-scan`,
and this card should adopt it then. The conformance row (§6).

## 6. Delta — card vs API vs CC

* **Wrong host, fixed.** The node code was the agent's, so on a with-AI install
  the card handed out the *agent's* key under the heading "my node code", and on
  a bare node it failed. It is now the node's own `/v1/federation/node-code`.
* **A dropped field, fixed.** `peer_counts_standing` was on the wire and not in
  the model, so an unreadable peer store drew "Peers: 0".
* **Error looked like empty, fixed.** A failed identity read drew a header of
  dashes under a dismissable banner; dismissed, it was indistinguishable from a
  node with no peers. It now draws the read-failure block.
* **Open (client):** `getNodeCapabilities` is an orphan. **Ask (CIRISClient):**
  a "Declares" row on this card: the CC 2.2 profiles this node claims, its build
  ceiling and the wire version, from `GET /v1/federation/conformance`. Public,
  unauthenticated, and the one governance fact about this node a peer reads
  before peering.
* **Placement.** This card is reached from Everyone › Rules through the hub;
  CSD-051 records why that hub should move under This node. This card would move
  with it.
