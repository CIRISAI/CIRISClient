# CSD-093 — Show approval code (the new device, first run)

**CSD**: CSD-093 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer 0.5.218 client brief (the second-device flow, CIRISServer#678)
**Pairs with**: CSD-094 (Approve a new device, on the device you already have). This card is the half that shows; that one is the half that scans
**Flow**: unwritten. The tags below are the contract the card agents build to

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**Someone who already has CIRIS on one device can set up another as the same
person, without making a second identity: the new device shows a code, the
device they already hold approves it, and the one secret involved never
leaves this device's screen except by being looked at.**

Today a new device's first run has three ways to get an identity: mint a new
one, associate an existing key id, or import a keyset from a folder
(`SetupScreen.kt:2337-2450`). None of them is "I already have this on my
phone". Minting a second identity for the same person is the failure this
card exists to prevent. CC 3.3.6 (`identity_occurrence`) is how one self lives
on several devices; a second fed-ID is a second person as far as the
federation is concerned.

Serves **Autonomy** and **Integrity** through CC 3.4.5: `ownership:*` is
owner-only, and "a third party asserting your responsible party is a seizure by
attestation". The claim that makes this node yours is signed by *you*, on the
device that holds your key (CSD-094). This device only proves it is the device
being claimed, and it proves that with the one-time claim PIN the node printed
for exactly this purpose.

## 2. Surface (what)

```yaml csd:surface
surface: null
screen: Setup
flow_only: true
entry: "Setup's YOU step on a first-run node (reached as in CSD-082: Login `btn_local_login`, Startup under the HA addon, or ServerConnection after a node switch), then `proposed:btn_setup_use_existing_identity` (Use an existing identity) → Show approval code"
exit: "Login, once `GET /v1/setup/status` reports `is_first_run: false` (the device you already have claimed this node). Cancel returns to the YOU step with nothing written"
```

**Where it lives, decided from the code.** A branch of `Screen.Setup`'s YOU
step, next to the three identity choices already there. It is not a new
Screen. It is not `Screen.ClaimNode` either: CSD-085 records that ClaimNode is
only ever reached in-shell, after sign-in, and drives the *claiming* side. A
first-run device has no session and no identity yet, which is exactly the
Setup wizard's situation (CSD-082 §2). Setup is `FLOW_ONLY` (`screen_atlas.py`),
so this is `flow_only: true` with Setup's entry and a different exit.

Choosing this branch **replaces** the wizard's automated self-claim. That claim
(`SetupViewModel.claimLocalNodeOwnership`, `SetupViewModel.kt:1162-1208`) reads
the same PIN and sends it to the local node in `POST /v1/setup/claim-remote` to
make this node its own owner's. On this branch there is no local owner to claim
for, and the PIN is for the other device. So the branch must not mint, must not
self-claim, and must not complete setup on its own.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:approval_payload
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "one QR, drawn by QrCode(value, contentDescription, tag), carrying this node's code, its one-time claim PIN, and an address the other device can reach it at. The payload format is the client's to define and CSD-094's scanner must read the same one; it is unconfirmed until both cards agree it (§3)"
    tag: "proposed:qr_approval_code"
  - ceg: x_private:node_code
    use: display-only
    type: string
    example: "CIRIS-V1-7QK2-…"
    renders: "'This device' and its node code in mono, grouped in fours, for anyone who cannot scan"
    tag: "proposed:text_approval_node_code"
  - ceg: x_private:claim_pin
    use: display-only
    type: string
    example: "7F3K-Q9MZ"
    renders: "'Approval PIN: 7F3K-Q9MZ' in large mono, readable across a table, with 'Type this on your other device if it can't scan.' It is shown on this screen and nowhere else: no Copy button, no share sheet, no log line"
    tag: "proposed:text_approval_pin"
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: bool
    example: false
    renders: "while false: 'Waiting for your other device to approve this one…'. When the node reports it is owned, 'Approved. Sign in to finish.' and the wizard goes to Login. The client shows the claim; it cannot make it (CC 3.4.5)"
    tag: "proposed:text_approval_waiting"
```

```yaml csd:states
populated: {tag: "proposed:card_approval_code", renders: "the QR, the node code, the PIN and the waiting line, with `proposed:btn_approval_code_cancel`"}
empty:     {tag: "proposed:approval_code_no_pin", renders: "'This device has no approval PIN.' Either the node is already claimed (then: 'This device already belongs to someone. Sign in instead.') or the PIN has not been seen yet (`GET /v1/setup/status` has no `claim_pin_file`). No QR is drawn without a PIN: half a code would fail at the other device with a worse message"}
loading:   {tag: "proposed:approval_code_loading", renders: "reading this node's code and PIN: the card frame with a progress affordance and no sentence"}
error:     {tag: "proposed:approval_code_error", renders: "'Could not read this device's code.' with the node's reason, and Try again. Distinct from `empty`: the node did not answer, which is not the same as the node having no PIN"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| this node's code | `GET /v1/federation/node-code` (`client.getNodeCode`, `SetupViewModel.kt:1197`), or the code captured from the boot banner | CIRISServer | live |
| the claim PIN | the node's own `<home>/claim_pin` file (0600), located by `claim_pin_file` in `GET /v1/setup/status` (`src/auth/bootstrap.rs:1418`) and read by `readLocalClaimPin()` (`CIRISApp.kt:2663`); the boot-banner capture is the fallback | CIRISServer (writes) · CIRISClient (reads a local file) | live; the same provider the self-claim uses |
| "you've been approved" | `GET /v1/setup/status` → `is_first_run` flips to `false` once `/v1/setup/root` has accepted the claim (`bootstrap.rs:1413-1436`) | CIRISServer | live; the card polls it |
| an address the other device can reach | the node code's `transport_hint`; `POST /v1/setup/claim-remote` falls back to the approving node's OWN loopback when there is none (`src/claim_remote.rs:237-248`) and accepts `target_url` to override (`:365-369`) | CIRISServer | **unconfirmed, and the flow cannot work without it.** A desktop or phone node's code carries no hint, so the QR must carry `target_url`. Whether 0.5.218 changes this is not stated in anything readable; ask on CIRISClient#78 |
| the QR payload format (node code + PIN + address) | none: a client-side format that CSD-093 writes and CSD-094 reads | CIRISClient | **unconfirmed**; proposed as one URI carrying `node_code`, `claim_pin` and `target_url`. Must be pinned by a shared unit test in `commonTest` before either card is `building` |
| the QR | `QrCode(value, contentDescription, tag)` | CIRISClient | **in build**: only the encoder exists, as an unreviewed WIP (`platform/util/QrEncoder.kt`, branch `wip/qr-encode-scan` @ `353a2ec`); the `QrCode` composable is not written yet |

### The PIN contract (asserted, not just intended)

**No request this client makes carries the claim PIN.** On this branch the
client reads the PIN from a local file and draws it; the only way it leaves the
device is by someone looking at the screen or scanning the QR. The branch makes
no `claim-remote`, no `setup/root`, no `setup/complete`, and logs nothing that
contains it (`DebugBundle.kt:123-136` already redacts it from debug bundles, and
that stays true).

How it is asserted: a `commonTest` drives this branch against a recording API
client and fails if any request's URL, headers or body contains the PIN string.
That is a unit-level assertion because the flow DSL has no predicate over
requests (CSD.md §5); it is still a contract, and it must be shown to fail on a
planted leak before it is believed (AGENTS.md).

**What the contract does not cover, stated so no one reads more into it.** The
PIN does cross the network once, and not from this client: the approving node
sends it to *this* node's `POST /v1/setup/root` (`claim_remote.rs:253-268`),
which is how this node checks it. That hop goes back to the node that issued the
PIN, and it is the server's protocol, not a request this card makes. On the
approving device the PIN also travels over loopback to that device's own node,
in the `claim-remote` body (CSD-094).

## 4. Flow (how)

On a first-run node, reach Setup's YOU step; click
`proposed:btn_setup_use_existing_identity`.

```yaml
expect:
  state: populated
  visible: ["proposed:card_approval_code", "proposed:qr_approval_code", "proposed:text_approval_node_code", "proposed:text_approval_pin", "proposed:text_approval_waiting"]
  matches: {"proposed:text_approval_pin": "^[A-Z0-9]{4}-[A-Z0-9]{4}$"}
  absent: [input_fedid_label]
```

On a second, already-set-up device, approve this one (CSD-094). This device
notices by itself:

```yaml
expect:
  screen: Login
```

Cancel instead: `proposed:btn_approval_code_cancel` returns to the YOU step,
and `input_fedid_label` is visible again. Nothing was minted or claimed.

On a node that is already claimed:

```yaml
expect:
  state: empty
  visible: ["proposed:approval_code_no_pin"]
  absent: ["proposed:qr_approval_code"]
```

## 5. QA plan

**Platforms.** The card on all five. The pair (this card plus CSD-094) needs two
nodes where the approving one can reach this one over HTTP, so desktop + desktop
on one host (two homes, per the one-home rule) is the cheapest pair.

**The PIN.** The recording-client unit test above, red on a planted leak first.
Gallery screenshots of this step show a PIN. That is acceptable only because the
PIN is one-time and dead once the node is claimed; a shot of an *unclaimed*
node's PIN is a live secret, so gallery runs claim the node before the run ends.

**Not tested here.** The camera scan on the other device (QrScanAction's test
double, CSD-094). Reachability across NAT: a phone behind a carrier NAT is not
reachable by the approving node at all, and this card cannot fix that (§6).

## 6. Delta — card vs API vs CC

* **Card vs API: reachability is the open question.** `claim-remote` reaches
  the target through the node code's `transport_hint` or an explicit
  `target_url`. Desktop and phone nodes publish no hint, and a phone may not be
  reachable over HTTP from anywhere. The flow works on one LAN with `target_url`
  in the QR and is unproven elsewhere. **Ask (CIRISServer, on CIRISClient#78):**
  say whether 0.5.218's second-device path is HTTP-to-target like first-run
  claim, or rides the mesh; the QR's contents depend on it.
* **Known limits of the 0.5.218 path, stated as limits (CIRISServer#678).**
  The brief's 2026-09-25 update says three of the four 0.5.218 branches are
  built and #678 (link at claim, re-wrap old private files, announce the new
  device from the first) is **still building**. So these are the limits of
  what will ship, not of a draft.
  Files and chats written before the approval do not open on this device yet
  (no re-wrap of the self key on claim-remote; CIRISPersist#916). The two
  devices do not sync until a restart (the claim only adds a bootstrap peer).
  This device stays private until the other one makes it reachable. The card's
  "Approved" line must not promise that history is here.
* **CC.** The design keeps CC 3.4.5 intact: this device signs nothing and
  claims nothing, and the owner-binding is made by the key holder on the
  approving device. CC 3.3.6 is where this should end: the new device as an
  `identity_occurrence` of the same self. On `main`, claim-remote writes an
  owner-binding and no occurrence, which CSD-094 §6 records.
* **The wizard's own completion.** CSD-082 ends on Login because completion
  restarts the node. This branch never reaches COMPLETE: the AI step and the
  join question are the approving person's to answer later, on a device that is
  signed in. That is a deliberate narrowing, and it means an agent on this node
  is configured after sign-in, not during setup.
