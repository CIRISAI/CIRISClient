# CSD-047 — Fetch content from a peer by its digest (the Content tile)

**CSD**: CSD-047 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route map (PR #111): a screen with routes and no CSD
**Covers**: `Screen.NetworkContent` (`ui/screens/federation/NetworkContentScreen.kt` + `viewmodels/federation/NetworkContentViewModel.kt`)
**Reads with**: CSD-051 (the hub), CSD-033 (the peer list it picks from), `PENDING-CSD-007` (Files, where a directory of what can be fetched would live; CIRISServer#651)
**Flow**: `testing/flows/drafts/csd-047-network-content.yaml` (floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**The owner can ask one named peer for one piece of content by its SHA-256, and
gets back exactly those bytes or a named miss. The card never guesses which
peer, and never shows bytes the digest did not name.**

Serves **Integrity**: the node enforces `sha256(payload) == content_id` before
the bytes reach the card (Edge's dispatch-side gate), so what the card shows is
what was asked for or nothing.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: NetworkContent
flow_only: true
entry: Everyone › Rules › Global Commons (the hub, CSD-051) → the Content tile (`NetworkTile.CONTENT`)
```

Two steps: pick a peer (a searchable list of this node's peers), then a digest
and a timeout.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:peer_key_id
    use: emit
    type: string
    example: "ciris-node-9f21ab"
    renders: "one pick row per peer, filtered by input_peer_search (drivable)"
    tag: "peer_pick_row_{keyId}"
  - ceg: "holds_bytes:sha256:{prefix}"
    bind: {prefix: "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"}
    use: read
    type: string
    example: "0f1e2d3c…"
    renders: "the digest field (drivable), validated as 64 hex before the fetch is enabled"
    tag: input_content_id
  - ceg: x_private:content_payload
    use: display-only
    type: string
    example: "hello"
    renders: "the result card: content id, size, fetched-at, and a text preview when the bytes decode"
    tag: card_content_result
```

`holds_bytes:sha256:*` is bound as `read` because the fetch is a read of a peer's
holding by digest; CC 5.2 suppresses that family from the holder directory, which
is why there is no "browse" step: the person must already know the digest.

```yaml csd:states
populated: {tag: card_content_result}
empty:     {tag: empty_content_peers, renders: "'No peers' on the pick step — only after a read that returned zero peers"}
loading:   {renders: "CircularProgressIndicator on the pick step; the fetch button shows its own spinner while a fetch is in flight"}
error:     {tag: federation_content_peers_error, renders: "the read-failure block in place of the peer list. A fetch miss or refusal is text_content_fetch_error with the node's reason, under the form"}
```

## 3. Contracts (who)

Verified against CIRISServer `origin/main` 046e1b39 (0.5.217).

| value | endpoint | owner | state |
|---|---|---|---|
| the peers to pick from | `GET /v1/federation/peers` (`src/federation_peers.rs:1435`) | CIRISServer | live — `listFederationPeers` (node URL) |
| the fetch | `POST /v1/federation/content/{content_id} {peer_key_id, timeout_ms}` → 200 `{data: {content_id, content_type, payload_base64, size_bytes, fetched_at}}`; 400 `INVALID_CONTENT_ID` / `peer_key_id must not be empty` / `timeout_ms must be in 1..=300000`; 404 `CONTENT_MISS {content_id, peer_key_id, reason}`; **owner session required** (`src/federation_surface.rs:699`, handler `:350-420`, `deny_unknown_fields`) | CIRISServer | live — `fetchFederationContent` (`CIRISApiClient.kt:1714`, node URL) into `FederationContentResponse` |
| a directory of what a peer holds | — | CIRISServer | **missing**: CIRISServer#651 (the "directory half"); CC 5.2 makes it structurally invisible, so this is a product question, not a route |

## 4. Flow (how)

Open the hub, tap the Content tile.

```yaml
expect:
  visible: [screen_federation_content, input_peer_search]
```

With at least one peer, pick the first row, then:

```yaml
do:
  - input: {input_content_id: "not-a-digest"}
expect:
  visible: [input_content_id, btn_content_fetch]
```

A digest that is not 64 hex keeps `btn_content_fetch` disabled.

With the node down:

```yaml
expect:
  state: error
  visible: [federation_content_peers_error]
  absent: [empty_content_peers]
```

## 5. QA plan

**Flow not complete.** `testing/flows/drafts/csd-047-network-content.yaml` (floor `unreleased`) never reaches the digest step: that needs a peer picked by `peer_pick_row_<keyId>`, a `click:` takes one literal tag, and no fixture seeds a peer whose key id the flow could name. The digest steps are gated on `input_content_id` and always skip, so the flow is green without driving the half this card is about. It is complete when a seeded peer lets it pick one. Until then this card is not ready to promote.

**Platforms.** All five, as the node's owner. A real fetch needs a second node
holding a known digest; the matrix stands one up.

**Not tested here.** The timeout slider; the payload preview's decoding.

## 6. Delta — card vs API vs CC

* **Two untagged, undrivable fields, fixed.** The digest field and the fetch
  button had no tag and no input sink, so no flow could fetch anything; the
  search field had a tag and no sink.
* **The fetch flag cleared before the fetch ran, fixed.** `launchApi` returned
  immediately, so `fetching` was false throughout and the button never showed a
  fetch in flight.
* **Error looked like empty on the pick step, fixed.**
* **Who signs.** The route requires the owner session; the card is reached under
  it. The act is a read, not an outward act, so no ConfirmSheet.
