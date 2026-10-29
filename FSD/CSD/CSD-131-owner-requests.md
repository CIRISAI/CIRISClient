# CSD-131 — Requests from your nodes (sign or decide)

**CSD**: CSD-131 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer#837 (owner requests); CC 3.2 (the announced owner-binding)
**Extends**: CSD-035 (Nodes — the requests sit above the node list). **No new screen** (§2).
**Reads with**: CSD-085 (a remotely claimed node, whose owner never held the pen on it), CSD-037 (the owner's devices, which sign)
**Flow**: none yet — the routes are unreleased (§3)

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**When one of your nodes needs you to sign something, you see it, you see
exactly what it is, and you sign it or decline it from one of your own
devices. Nothing a node needs from its owner is skipped.**

A node claimed remotely, or set up with a staged owner, never had its owner's
pen on it. Three acts need that pen: accepting the roots the node accepted,
announcing the owner-binding at `federation` scope (a second binding beside the
`self` one, CC 3.2), and the `consent:community_trust` grant that lets the
node's traces be captured. Each used to be skipped with only a log line, so a
managed agent was never rooted, never resolvable to its owner, and never
shipped a trace. The node now lists what it needs; the owner's device signs it;
the node applies it. Serves **Integrity** (only the owner, or a live key of
the owner's roster, can sign) and **Transparency** (every request is shown with
what it does before anything is signed).

## 2. Surface (what)

```yaml csd:surface
surface: nodes
screen: ManageNodes
```

**No new screen: a "Requests from your nodes" section on Nodes (CSD-035),**
above the node list, drawn only when at least one owned node lists a request.
One row per request: the node's name and key id, what it asks in plain words,
and Sign and Decline.

| kind | what the row says | what signing does |
|---|---|---|
| `root_acceptance` | "{node} accepts the trust root {root}. Accept it as yours too?" | the owner accepts the same roots, so the canonical treats the node as rooted |
| `announce` | "Make it known that you own {node}." | a federation-scope owner-binding beside the `self` one (CC 3.2) |
| `community_trust` | "Let {node}'s traces be captured for the commons." | the `consent:community_trust` capture grant |

**Sign is behind a ConfirmSheet with three facts:** which node, what is
signed, and that it is signed with this device's key as the node's owner. A
declined request stays listed, marked "Declined on {date}", because the node
recomputes its requests from its own rows and will ask again; declining is
saying no for now, never a hidden skip.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:owner_request
    use: display-only
    type: unconfirmed
    example: "echo-speculative · Make it known that you own echo-speculative."
    renders: "one row per request the node lists: the node's name and short key id, the kind in plain words (root acceptance / announce / capture grant), and what signing does"
    tag: "proposed:row_owner_request_*"
  - ceg: x_private:owner_request_sign
    use: emit
    type: unconfirmed
    example: "announce"
    renders: "Sign, behind the three-fact confirm (sheet_owner_request_sign): this device signs, the node applies. The notice says what landed, by kind"
    tag: "proposed:btn_owner_request_sign_*"
  - ceg: x_private:owner_request_decline
    use: emit
    type: unconfirmed
    example: "community_trust"
    renders: "Decline: nothing is signed; the row stays, marked declined, because the node will list it again"
    tag: "proposed:btn_owner_request_decline_*"
```

```yaml csd:states
populated: {tag: "proposed:owner_requests_list", renders: "the 'Requests from your nodes' heading and one row per request, above the node list"}
empty:     {tag: "proposed:owner_requests_empty", renders: "nothing visible: no requests is the normal case; a zero-height marker carries the tag"}
loading:   {tag: "proposed:owner_requests_loading", renders: "nothing visible while the nodes are asked; the node list's own loading speaks"}
error:     {tag: "proposed:owner_requests_error", renders: "per node: 'Couldn't ask {node} what it needs.' with the refusal by id; a node without the route draws nothing"}
```

## 3. Contracts (who)

Three hops: the node lists, the owner's device signs, the node applies.
Today the CIRIS manager carries the bundle between them; delivery over the mesh
to the owner's devices comes with CIRISPersist#1140 (persist v61).

| value | endpoint | owner | state |
|---|---|---|---|
| what a node needs | `GET {node}/v1/self/owner-requests` | CIRISServer (`src/owner_requests.rs`, CIRISServer#837) | **unconfirmed, unreleased.** Kinds `announce`, `root_acceptance`, `community_trust`; computed from the node's own rows on every read, so there is no stored queue |
| sign them | `POST {this device}/v1/self/owner-requests/sign` | CIRISServer#837 | **unconfirmed, unreleased.** Signs only for the node named, only for that node's owner, only these kinds; refuses per item |
| apply them | `POST {node}/v1/self/owner-requests/apply` | CIRISServer#837 | **unconfirmed, unreleased.** The signer must be `owner_of(node)` or a live roster key of it; each row is checked by kind, type, dimension and subject and admitted through persist's own doors |
| refusals | the eight ids #837 adds | CIRISServer#837 | **unconfirmed** — listed as server localization debt; rendered by id |
| reaching a remote node | the owned node's address | CIRISServer · CIRISPersist | **blocked_by: CIRISPersist#1140** for mesh delivery; until then a node the owner's device cannot reach directly is signed for through the manager |

## 4. Flow (how)

Two nodes: the owner's device D and a remotely claimed node N.

1. N lists `announce`, `root_acceptance` and `community_trust` (`GET N/v1/self/owner-requests`).
2. On D, Nodes shows three rows under "Requests from your nodes".
3. Sign `announce` → `sheet_owner_request_sign` → confirm. D signs; N applies; the row leaves on the re-read.
4. Decline `community_trust`. The row stays, marked declined; N still lists it.
5. After all are signed, N lists nothing and the section is not drawn.

## 5. QA plan

`commonTest`: one row per listed request, labelled by kind; Sign sends the
node's own requests to this device's sign and the result to the node's apply,
never anything built by the client; a decline signs nothing and keeps the row;
a bare 404 draws nothing; a refusal renders by id. `desktopTest` wire test over
two sockets (node, device).

## 6. Delta — card vs API vs CC

* **Request and bundle bodies** are in CIRISServer#837 and not yet transcribed
  here; every field is `unconfirmed` until the routes ship.
* **Mesh delivery** (CIRISPersist#1140): without it the owner's device reaches
  the node directly or through the manager.
* **A fourth kind**, re-signing the node's own provisional replication grant,
  is next on the server's list.
