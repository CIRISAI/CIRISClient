# CSD-092 — Share my contact code (a card on People)

**CSD**: CSD-092 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer 0.5.218 client brief (CIRISServer#673, built on the server's contact-flow branch; not yet pushed)
**Pairs with**: CSD-005 (People: the other half, where a code is pasted or scanned in)
**Flow**: unwritten. The tags below are the contract the card agents build to

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can hand someone the one thing that makes them addable: their own
contact code, as text and as a QR, naming the devices they choose. If no one
could reach them through it, the card says so instead of handing out a code
that resolves to nobody.**

Before 0.5.218 there was nothing correct to hand over. A node code is refused as
a contact, because a node cannot consent (`contacts.unresolvable`,
`NotContactable { identity_type: "node" }`). A bare fed-ID only resolves when
the adder's directory already holds that person. The one route that returned a
person's `fedcode` was `POST /v1/self/identity`, at mint, before any node
existed to name. CIRISServer#673 is that finding, from driving the real desktop
UI through a two-person contact.

Serves **Contextual Integrity** through CC 2.6.8 (FedCode). A `user` code "is the
owner's identity; the owner's nodes and transport are optional. A code that
carries them resolves with no directory … A code that carries none resolves
through the federation directory" via `nodes_owned_by`. Three consequences
shape this card:

* **Which devices go in is the person's choice.** A code names devices, and a
  code can be passed on, so it discloses which devices are yours to whoever ends
  up holding it. That is why there is a picker and not a fixed list. (The
  default is decided: all announced devices.)
* **Only an announced device can go in.** CC 2.6.8 constraint 2: a code "carries
  **lightnet** facts only — federation-scope identity that already announces".
  The node's refusal for a private device, `self.node_not_announced`, is that
  constraint enforced, and the card shows it as a fact about the device, not as
  an error.
* **A code with no devices only works for people you can already be found
  through.** The directory only knows people with at least one announced device,
  so when nothing is announced a no-device code reaches no one. The card says
  so and steers toward fixing it.

A FedCode is unsigned (CC 2.6.8(e)). The card must not present it as a
credential or as proof of anything. It is an address.

## 2. Surface (what)

```yaml csd:surface
surface: contacts
screen: Contacts
```

A card on People (the Contacts surface, CSD-005), opened from the People header
by `proposed:btn_contact_code_open` and shown as `proposed:card_contact_code`.
It lives in People because that is where the person you are adding stands next
to you: one phone shows this card and the other scans it into Add contact
(CSD-005). The code is the same in every circle, since it names the person and
not an audience. It is one card reached from every circle's People tab, not
five cards.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:contact_code
    use: display-only
    type: unconfirmed
    example: "CIRIS-V3-AB12-CD34-…"
    renders: "the person's v3 code in mono, grouped in fours (the CC 2.6.8 display form), with Copy beside it. The route returns it as a string and as a QR payload (the same code); the two JSON keys are not named in anything readable yet (CIRISClient#78)"
    tag: "proposed:text_contact_code"
  - ceg: x_private:contact_code_qr
    use: display-only
    type: string
    example: "CIRIS-V3-AB12CD34…"
    renders: "a real, scannable QR of the UNGROUPED form (CC 2.6.8: 'the QR form is ungrouped'), drawn by the QrCode primitive: QrCode(value, contentDescription, tag). contentDescription reads 'Your contact code as a QR'"
    tag: "proposed:qr_contact_code"
  - ceg: x_private:contact_code_nodes_param
    use: emit
    type: "enum[all,list,none]"
    example: "all"
    renders: "three choices: 'All my reachable devices' (default: `nodes` absent), 'Choose devices' (`nodes` = the ticked list), 'No devices' (`nodes=none`). Tagged `proposed:opt_contact_code_nodes_all`, `proposed:opt_contact_code_nodes_list` and `proposed:opt_contact_code_nodes_none`"
    tag: "proposed:opt_contact_code_nodes_all"
    assert:
      one_of: {"proposed:opt_contact_code_nodes_all": [all, list, none]}
  - ceg: x_private:available_nodes
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "one row per device in `available_nodes`: its label if any, else its node key id middle-truncated, and a tick box. `available_nodes` holds ONLY announced devices (maintainer, CIRISServer#673, 2026-09-25), so every row here is reachable. Items are `{node_key_id, label?}` per the brief; type stays unconfirmed until the shape is readable"
    tag: "proposed:row_contact_code_node_{nodeKeyId}"
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "federation"
    renders: "under the picker, one line: 'Only reachable devices can go in a code. Your other devices are private.' Private devices are not listed here at all; 'reachable' means that device's owner-binding was widened to federation by POST /v1/federation/announce (per device, #655), which is what CC 2.6.8 constraint 2 requires"
    tag: "proposed:text_contact_code_private_note"
  - ceg: x_private:included_nodes
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "under the QR: 'This code includes 2 devices: Phone, Laptop.' It is read back from the node, not echoed from the picker, so the sentence says what the code actually carries"
    tag: "proposed:text_contact_code_included"
  - ceg: x_private:refusal_reason_id
    use: display-only
    type: "enum[self.node_not_announced]"
    example: "self.node_not_announced"
    renders: "'That device isn't reachable, so it can't go in a code.' The picker never offers a private device, so this arrives only when the list went stale (a device was made private after the card loaded): the card reloads `available_nodes` and says so"
    tag: "proposed:contact_code_refusal"
```

**What the card says about sharing.** One line under the QR: "Anyone with this
code can ask to add you, and can see which of your devices it names." The
second half is CC 2.1's point about rosters ("never globally enumerable") made
concrete. The person is making a disclosure, and it is theirs to make.

```yaml csd:states
populated: {tag: "proposed:card_contact_code", renders: "the QR, the code, Copy, the device picker and the included sentence"}
empty:     {tag: "proposed:contact_code_unreachable", renders: "No one can find you with this yet: none of your devices is reachable. Make this device reachable, or share a code that includes it. With `proposed:btn_contact_code_make_reachable`. NO QR is drawn in this state: a code that resolves to nobody is not shown as if it worked"}
loading:   {tag: "proposed:contact_code_loading", renders: "the card frame with a progress affordance and no sentence, and no QR placeholder"}
error:     {tag: "proposed:contact_code_error", renders: "'Could not get your contact code.' with the node's reason by id; and for a node older than 0.5.218 (404, no id): 'This node can't make a contact code yet. It needs ciris-server 0.5.218 or newer.'"}
```

**`empty` is a decision, not a missing case.** When `available_nodes` is empty
(nothing announced), the default (`nodes` absent = all announced) and `nodes=none`
produce the same thing: a code with no devices, which CC 2.6.8 resolves through
a directory that does not list this person. The node would mint it. The card
declines to present it. "Share a code that includes it" in the copy only has a
way out once a device is announced, since `self.node_not_announced` refuses a
private one, so the button here is the announce act and not the picker.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the contact code | `GET /v1/self/contact-code?nodes=` (owner session): `nodes` absent = every announced device, a comma list = exactly those, `none` = the fed-ID only (resolved through the public directory) | CIRISServer | **built, unmerged.** Route, query and semantics are stated by the maintainer on CIRISServer#673 (comment 2026-09-25 22:48) and the brief's "built, tested" update ("a code from one node, pasted on a separate node, resolves to its owner directly"). **Not readable:** no branch or PR carrying it is pushed, `main` @ `046e1b39` has no `contact-code` literal, and #673 is still open. Released only with 0.5.218, which waits on the persist/edge/verify triple |
| `nodes` encoding | comma list (brief, #673) | CIRISServer | stated; not readable in source |
| `available_nodes`, `included_nodes` | same route; `available_nodes` = the announced devices the person may include, `{node_key_id, label?}` | CIRISServer | **names stated** by the maintainer (#673); item shape from the brief, not readable. The code string's and QR payload's own keys are **unconfirmed** |
| refusal for a private or foreign device | `self.node_not_announced` (400) | CIRISServer | "planned id" in the brief; one of the 15 new refusal ids queued on CIRISClient#78. Needs an `en.json` key when the list lands |
| make this device reachable | `POST /v1/federation/announce` | CIRISServer | **live**: `src/claim_remote.rs:1250`, owner-gated, loopback-only, idempotent. It promotes this node's owner-binding `self → federation` at once; the Reticulum identity announce follows on the **next boot** (`announce_self_handler` doc, `:1054-1076`). The card must say both halves |
| 16-device cap | CC 2.6.8 constraint 3: `node_count ≤ 16`, payload ≤ 1024 bytes | CIRISServer (encoder) | **unconfirmed** whether the route refuses or truncates past 16. The picker caps selection at 16 either way |
| the QR | `QrCode(value, contentDescription, tag)` | CIRISClient | **in build**: only the encoder exists, as an unreviewed WIP (`platform/util/QrEncoder.kt`, branch `wip/qr-encode-scan` @ `353a2ec`); the `QrCode` composable is not written yet, and the signature is the one its owner published. Replaces `FedcodeQr` for this use |
| copy to clipboard | platform clipboard | CIRISClient | live pattern (Copy exists elsewhere in the client) |

## 4. Flow (how)

Sign in on a node with one announced device (this one); open People, then
`proposed:btn_contact_code_open`.

```yaml
expect:
  state: populated
  visible: ["proposed:card_contact_code", "proposed:qr_contact_code", "proposed:text_contact_code", "proposed:btn_contact_code_copy", "proposed:text_contact_code_included"]
  matches: {"proposed:text_contact_code": "^CIRIS-V3-[A-Z2-7-]+$"}
  count: {of: "proposed:row_contact_code_node_*", min: 1}
```

Click `proposed:btn_contact_code_copy`, paste it into `input_contacts_add_key`
on a second person's node, submit (CSD-005). The second node lists the first
person as a contact.

Choose `proposed:opt_contact_code_nodes_none`, then back to
`proposed:opt_contact_code_nodes_all`: the code changes and changes back, and
`proposed:text_contact_code_included` follows it.

On a node where no device is announced:

```yaml
expect:
  state: empty
  visible: ["proposed:contact_code_unreachable", "proposed:btn_contact_code_make_reachable"]
  absent: ["proposed:qr_contact_code"]
  text: {"proposed:contact_code_unreachable": "none of your devices is reachable"}
```

Make a listed device private from another session, then tick it here: the
picker's list is stale, and the node refuses (in CI, a stub forces this, since
the picker never lists a private device):

```yaml
expect:
  visible: ["proposed:contact_code_refusal"]
  text: {"proposed:contact_code_refusal": "isn't reachable"}
```

## 5. QA plan

**Platforms.** All five for the card. The copy → paste → contact round trip
needs two nodes and runs on desktop.

**The scan round trip.** Show this card on one client and scan it with
`proposed:btn_scan_contact_code` (CSD-005) on another. That needs a camera or
the QrScanAction primitive's test double. Until that double exists, the text
path (Copy → paste) is the tested path, and paste stays present on every
platform so that it always is one.

**Not tested here.** Whether a code handed on to a third person still works (it
does by construction; a FedCode is unsigned and anyone holding it can use it).
Whether the directory-only resolution works across a real federation (a
CIRISServer ladder concern). The next-boot half of the announce.

## 6. Delta — card vs API vs CC

* **Card vs API.** The route is built and not yet readable: no 0.5.218 code is
  pushed to CIRISServer, so the response keys that the maintainer has not
  named stay `unconfirmed` and the stage is `sketched`. `building` waits on the
  merge and the key list on CIRISClient#78.
* **API vs CC.** CC 2.6.8 constraint 2 is the ground for
  `self.node_not_announced`, and the brief's default (all announced devices)
  keeps within it. Constraint 3 (≤ 16 nodes, ≤ 1024 bytes) is not yet stated by
  the route; the client caps selection at 16 regardless.
* **CC → card.** The disclosure line under the QR is not decoration. A code that
  names devices is a partial roster in someone else's hands, and CC 2.1 makes
  roster visibility "a one-way disclosure the member chooses".
* **Replaces a wrong answer.** The Network Identity card showed the owner's bare
  fed-ID as the thing to share (CIRISServer#673), and a stranger could not
  resolve it. Once this card exists, that card should point here rather than
  offer the fed-ID as a contact handle.
