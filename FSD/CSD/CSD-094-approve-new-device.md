# CSD-094 — Approve a new device (the device you already have)

**CSD**: CSD-094 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer 0.5.218 client brief (the second-device flow, CIRISServer#678)
**Pairs with**: CSD-093 (Show approval code, on the new device) · CSD-037 (My Identity, the page this card sits on)
**Flow**: unwritten. The tags below are the contract the card agents build to

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**From a device they already use, a person can make a new device theirs: scan
the code it shows, see which device they are about to approve, approve it, and
then see it in the list of their devices. The card says plainly what does not
follow the device yet.**

Serves **Integrity** through CC 3.4.5: `ownership:*` is owner-only, and the
owner's signature *is* the claim. The person's signing key stays on this device
(the brief: "the person's signing key stays on the first device"), so this is
the only device that can make the claim, and it makes it with no crypto in the
app: `POST /v1/setup/claim-remote` asks this device's own node to sign the
owner-binding and deliver it to the new one (`src/claim_remote.rs:1-21`). And
it serves CC 3.3.6. The point of the flow is that the new device becomes
another occurrence of the same self, not a second person.

It lives in **My things › Devices & keys › My Identity** (decided), next to the
device roster, because approving a device adds to the person's own identity.
It is not in any circle.

## 2. Surface (what)

```yaml csd:surface
surface: identity-management
screen: IdentityManagement
```

A card, `proposed:card_approve_device`, below the roster (CSD-037) on the page
`nav_map` derives as `btn_my_things -> nav_instrument_devices_keys ->
nav_epistemic_identity_management`.

**Two ways in, always both.** `proposed:btn_scan_approval_code` opens the
`QrScanAction` primitive. `proposed:input_approval_code` takes a pasted
approval code or a bare node code, and `proposed:input_approval_pin` appears
when what was pasted carries no PIN, for a PIN read off the other screen. A
scan fills the fields and does not approve: the next screen names the device,
and approving is a separate tap.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:approval_payload
    use: read
    type: unconfirmed
    example: "unconfirmed"
    renders: "nothing by itself: the scanned or pasted approval code, decoded into the node code, PIN and address that CSD-093 put in it. The format is shared with CSD-093 and unconfirmed until one commonTest pins both sides"
    tag: "proposed:input_approval_code"
  - ceg: x_private:claim_pin
    use: emit
    type: string
    example: "7F3K-Q9MZ"
    renders: "a PIN field shown only when the pasted code carried none, masked after entry. Held in memory for the one request and then dropped: not saved, not logged"
    tag: "proposed:input_approval_pin"
  - ceg: x_private:node_code
    use: display-only
    type: string
    example: "CIRIS-V1-7QK2-…"
    renders: "before approving: 'Approve {alias or key id} as your device?' The name comes from decoding the node code on this device (NodeCodeCodec: key id + alias hint); nothing is fetched to say it"
    tag: "proposed:text_approve_device_target"
  - ceg: x_private:cohort_scope
    use: emit
    type: "enum[self]"
    example: "self"
    renders: "not a choice. 'Who can see it: just you' as a fact on the confirm step. A device of yours is `self` (CC 3.3.6); making it reachable is a separate act (CSD-037)"
    tag: "proposed:text_approve_device_scope"
    assert:
      one_of: {"proposed:text_approve_device_scope": [self]}
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "eric-moore-v1"
    renders: "'Approved. {device} is now yours.' Shown only when the response's `identity_key_id` is this person's own; any other value is shown as a failure, since a claim made for someone else is the one thing this card must never report as success"
    tag: "proposed:text_approve_device_done"
  - ceg: x_private:refusal_reason_id
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "the reason in words: 'That code is damaged' (400, bad node code); 'That PIN doesn't match. Check it on the other device' (the target's refusal); 'Couldn't reach that device. Are both on the same network?' (502, or 400 with no address). claim-remote returns prose, not ids, today, so the client maps these by status"
    tag: "proposed:approve_device_refusal"
```

**What the confirmation must say, and must not.** Under "Approved": "New files
and chats will reach both devices. Things from before today won't open there
yet. It stays private until you make it reachable." Each clause is a stated
limit from the brief (§6). None of it is written as a promise with a date.

```yaml csd:states
populated: {tag: "proposed:card_approve_device", renders: "the heading 'Approve a new device', one line ('Open CIRIS on the new device and choose Use an existing identity'), Scan and the paste field"}
empty:     {tag: "proposed:card_approve_device", renders: "the card has no empty state of its own: before a code is scanned or pasted it IS the populated card. The roster's empty state is CSD-037's"}
loading:   {tag: "proposed:approve_device_busy", renders: "'Approving {device}…' with a progress affordance and no other control: the claim is one request and it either lands or refuses"}
error:     {tag: "proposed:approve_device_refusal", renders: "the refusal inline on the card, in the error tone, with the fields kept so the person can fix the PIN rather than rescan"}
```

## 3. Contracts (who)

Verified against CIRISServer `main` @ `046e1b39` (2026-09-25) through `gh api`.
No 0.5.218 code is pushed to any branch or PR yet, so anything "0.5.218" below
is taken from the maintainer's brief and is not readable source.

| value | endpoint | owner | state |
|---|---|---|---|
| approve | `POST /v1/setup/claim-remote {node_code, claim_pin, cohort_scope, target_url?}` on this device's own node | CIRISServer | **live**: `src/claim_remote.rs:1241-1244`, handler `:386-519`. Owner-gated once owned (`SYSTEM_ADMIN`, `require_verb(ClaimRemote)`), loopback-only via the setup-route guard. The client already has `client.claimRemote(...)` (`SetupViewModel.kt:1203`) |
| the response | `{wa_id, identity_key_id, cohort_scope, role, owner_binding_attestation_id, …, local_directory_updated}`: the target's `SetupRootResponse` (`src/auth/bootstrap.rs:708-733`) plus a local flag (`claim_remote.rs:819-834`) | CIRISServer | live. **It also carries the new node's freshly minted owner session** (CIRISServer#393, `bootstrap.rs:721-733`). This client must not keep, store or log it: it belongs to the other device |
| refusals | `400` bad node code / cohort / no address, `502` unreachable, the target's status for a bad PIN, all as `{"error": "claim-remote failed: …"}` prose (`claim_remote.rs:504-517`) | CIRISServer | live, **without reason ids**. **Ask (CIRISServer):** named ids for these, like every other route in 0.5.216+ |
| reaching the new device | the node code's `transport_hint`, else `target_url`; with neither, the approving node falls back to its OWN loopback (`claim_remote.rs:237-248`) | CIRISServer | **unconfirmed** for desktop/phone targets, which publish no hint (CSD-093 §6). The card passes `target_url` from the approval code |
| the new device in the roster | the roster is `GET /v1/self/occurrences` (CSD-037). On `main`, claim-remote writes an owner-binding and a bootstrap peer, **not** an `identity_occurrence` (`claim_remote.rs:704-720`); the new node shows up in `GET /v1/setup/owned-nodes` | CIRISServer | **unconfirmed**: which list shows an approved device after 0.5.218. #678 is "still building" per the brief's update. Until it is answered, the flow asserts the confirmation, not a roster row |
| link now, not at restart | claim-remote only adds the target to `net.bootstrap_peers` (next boot) | CIRISServer | **not built**: #678, still building |
| old private files open on the new device | re-wrap of the self key for the new node at claim time | CIRISServer #678 · CIRISPersist#916 | **not built**; a stated limit |
| make the new device reachable, from here | a per-device announce on the approving side | CIRISServer #678 | **not built**. The new device cannot announce itself: the person's key is here |
| scanning | `QrScanAction` | CIRISClient | **in build**: not written yet. Branch `wip/qr-encode-scan` @ `353a2ec` has only the encoder (`QrEncoder.kt`, unreviewed WIP) |

## 4. Flow (how)

Two nodes, one person. On device B, run CSD-093 up to the approval code. On
device A (signed in), open My things › Devices & keys › My Identity.

```yaml
expect:
  visible: ["proposed:card_approve_device", "proposed:btn_scan_approval_code", "proposed:input_approval_code"]
```

Paste B's approval code into `proposed:input_approval_code`.

```yaml
expect:
  visible: ["proposed:text_approve_device_target", "proposed:text_approve_device_scope", "proposed:btn_approve_device"]
  absent: ["proposed:input_approval_pin"]
```

Click `proposed:btn_approve_device`.

```yaml
expect:
  visible: ["proposed:text_approve_device_done"]
  text: {"proposed:text_approve_device_done": "is now yours"}
  absent: ["proposed:approve_device_refusal"]
```

B leaves its approval screen for Login by itself (CSD-093 §4).

Paste a bare node code, type a wrong PIN into `proposed:input_approval_pin`,
approve:

```yaml
expect:
  visible: ["proposed:approve_device_refusal"]
  text: {"proposed:approve_device_refusal": "doesn't match"}
```

## 5. QA plan

**Platforms.** The card on all five; the pair on desktop + desktop (two homes,
never sharing `~/ciris`), because the target must be reachable over HTTP from
A's node.

**The PIN.** A `commonTest` against a recording API client asserts that the PIN
appears in exactly one request, the loopback `claim-remote` to this device's own
node, and in no log line. The owner session in the response is asserted to be
dropped. Both are shown red on a planted defect first.

**Not tested here.** The camera (QrScanAction's double stands in). History on
the new device, linking without a restart, and announcing it from here: none is
built, so there is nothing to test yet, and the confirmation text asserts the
limits instead. Re-admission after a restart (CIRISEdge#676 / CIRISPersist#911).

## 6. Delta — card vs API vs CC

* **Stated limits, not promises (brief, CIRISServer#678).**
  * Things written before the approval do not open on the new device yet: old
    private files wait on the approving side re-wrapping their keys at claim
    time (#678), and old chats and community messages wait on CIRISPersist#916.
  * The two devices sync only once linked, and today that happens at the next
    restart. #678 makes the approval link them at once.
  * The new device cannot announce itself, because the person's key is on this
    one. A per-device "make this device reachable" on the approving side is
    planned (#678). Until then the new device stays private.
  * A device that restarts loses its place in the self room and is not
    re-admitted by itself (CIRISEdge#676 / CIRISPersist#911).
  * Revoking a lost device reaches the person's other devices only once #646
    lands.
* **Two ways to add a device on one page.** CSD-037's "Add a device"
  (`input_identity_device_code` → `POST /v1/self/occurrence`, a fedcode the new
  device minted for itself) and this card do different things under similar
  words. The occurrence path is the one that runs persist's
  `rekey_self_occurrence_add` (#678, point 1), which is why its devices can read
  old self files. Approval is the claim path. **Recommendation (CIRISClient):**
  name them by what the person holds: "Approve a new device" (it shows a code)
  and "Add a key you already made" for the occurrence path. Do not put a guard
  behind two ambiguous buttons.
* **API vs CC.** CC 3.3.6 says a second device of one self is an
  `identity_occurrence`. On `main` the claim path makes it an owned *node* and
  no occurrence, so "the device appears in the roster" is exactly the open
  question in §3. It is not a client bug to paper over.
* **The session in the response.** The target mints its owner session and hands
  it back through this device (CIRISServer#393). It is harmless if dropped, but
  it is a credential for the other device passing through this one. **Ask
  (CIRISServer):** omit it on a remote (non-loopback) claim.
