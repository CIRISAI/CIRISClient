# CSD-092 — Share my contact code (a card on People)

**CSD**: CSD-092 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: CIRISServer 0.5.218 client brief (CIRISServer#673; the route is readable on `origin/integ/0.5.218`, `src/self_devices.rs:562-847`)
**Pairs with**: CSD-005 (People: the other half, where a code is pasted or scanned in)
**Flow**: `testing/flows/csd-092-share-contact-code.yaml` (floor `>=0.5.225`; the route ships with ciris-server 0.5.218)
**Card**: built, PR #113 — `ContactsScreen.kt` (the card), `ContactCodeState.kt` + `ContactsViewModel` (the states), `ContactCodeResponse` (the wire), `ContactCodeViewModelTest` / `ContactCodeWireTest`

```yaml csd:stage
stage: building
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
    type: string
    example: "CIRIS-V3-AB12-CD34-…"
    renders: "the person's v3 code in mono, grouped in fours (the CC 2.6.8 display form), with Copy beside it. The route returns it as `code` (grouped, shown as text) and `qr_payload` (ungrouped, the same code; `src/self_devices.rs:816-833`), with `format: fedcode-v3`"
    tag: text_contact_code
  - ceg: x_private:contact_code_qr
    use: display-only
    type: string
    example: "CIRIS-V3-AB12CD34…"
    renders: "a real, scannable QR of the UNGROUPED form (`qr_payload`; CC 2.6.8: 'the QR form is ungrouped'), drawn by the QrCode primitive (`ui/primitives/QrCode.kt`, PR #99). contentDescription reads 'Your contact code as a QR'"
    tag: qr_contact_code
  - ceg: x_private:contact_code_nodes_param
    use: emit
    type: "enum[all,list,none]"
    example: "all"
    renders: "three choices: 'All my reachable devices' (default: `nodes` absent = every announced node), 'Choose devices' (`nodes` = the ticked comma list), 'No devices' (`nodes=none`) — `src/self_devices.rs:695-731`. Tagged `opt_contact_code_nodes_all`, `opt_contact_code_nodes_list` and `opt_contact_code_nodes_none`"
    tag: opt_contact_code_nodes_all
    assert:
      one_of: {opt_contact_code_nodes_all: [all, list, none]}
  - ceg: x_private:available_nodes
    use: display-only
    type: "list[string]"
    example: ["node-phone"]
    renders: "one row per device in `available_nodes` (`{node_key_id, label?, announced, has_transport?, this_node}`): its label if any, else its node key id middle-truncated, and a tick box. `available_nodes` holds ONLY announced devices (`announced_nodes_of`, `:691`), so every row here is reachable"
    tag: "row_contact_code_node_*"
  - ceg: "ownership:{relation}:{target_kind}:{version}"
    bind: {relation: responsible_party, target_kind: node, version: v1}
    use: display-only
    type: string
    example: "federation"
    renders: "under the picker, one line: 'Only reachable devices can go in a code. Your other devices are private.' Private devices are not listed here at all; 'reachable' means that device's owner-binding was widened to federation by POST /v1/federation/announce (per device, #655), which is what CC 2.6.8 constraint 2 requires"
    tag: text_contact_code_private_note
  - ceg: x_private:included_nodes
    use: display-only
    type: "list[string]"
    example: ["node-phone", "node-laptop"]
    renders: "under the QR: 'This code includes 2 devices: Phone, Laptop.' Read back from the node's `included_nodes` (`{key_id, transport_pubkey_ed25519_base64}`), not echoed from the picker, so the sentence says what the code actually carries; `nodes_without_transport` names a chosen, announced device whose transport key is not bound here yet (after its next boot), and `reachable_without_directory` is whether a stranger can reach you from this code alone"
    tag: text_contact_code_included
  - ceg: x_private:refusal_reason_id
    use: display-only
    type: "enum[self.node_not_announced,self.contact_code_owner_key_absent,self.contact_code_not_a_person,self.contact_code_key_not_derived,self.contact_code_no_pqc_half,self.contact_code_unencodable]"
    example: "self.node_not_announced"
    renders: "by id, from the bundle (`en.json` `self.*`, PR #113). `node_not_announced` (400): 'That device isn't reachable, so it can't go in a code.' — the picker never offers a private device, so this arrives only when the list went stale, and the card reloads `available_nodes`. The other five (`:629-678`, `:821`) are facts about THIS identity — no owner key, not a person, key not derived, no ML-DSA-65 half, unencodable (409) — and render in `contact_code_error`"
    tag: contact_code_refusal
```

**What the card says about sharing.** One line under the QR: "Anyone with this
code can ask to add you, and can see which of your devices it names." The
second half is CC 2.1's point about rosters ("never globally enumerable") made
concrete. The person is making a disclosure, and it is theirs to make.

```yaml csd:states
populated: {tag: card_contact_code, renders: "the QR, the code, Copy, the device picker and the included sentence; `btn_contact_code_close` shuts it"}
empty:     {tag: contact_code_unreachable, renders: "No one can find you with this yet: none of your devices is reachable. Make this device reachable, or share a code that includes it. With `btn_contact_code_make_reachable`, and `text_contact_code_reachable_status` reporting the announce ('takes effect at the next boot'). NO QR is drawn in this state: a code that resolves to nobody is not shown as if it worked"}
loading:   {tag: contact_code_loading, renders: "the card frame with a progress affordance and no sentence, and no QR placeholder"}
error:     {tag: contact_code_error, renders: "'Could not get your contact code.' with the node's reason by id; and for a node older than 0.5.218 (404, no id): 'This node can't make a contact code yet. It needs ciris-server 0.5.218 or newer.'"}
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
| the contact code | `GET {nodeUrl}/v1/self/contact-code?nodes=` (owner session, at the NODE URL — `contactsNodeUrl`, which works on every agent version; reach through the agent works from agent 2.12.1, CIRISAgent#1213 closed by #1215): `nodes` absent = every announced device, a comma list = exactly those, `none` = the fed-ID only (resolved through the public directory) | CIRISServer | **built, unreleased** (0.5.218; CIRISServer#673 still open). Readable on `origin/integ/0.5.218`: `src/self_devices.rs:611` `contact_code`, routed at `:869`. Ships with 0.5.218 |
| `nodes` encoding | comma list, trimmed, sorted, deduped; an empty list after trimming is a 400 (`:697-712`) | CIRISServer | readable |
| the response | `key_id`, `code`, `qr_payload`, `format` (`fedcode-v3`), `ml_dsa_65_pubkey_sha256`, `available_nodes[{node_key_id,label?,announced,has_transport?,this_node}]`, `included_nodes[{key_id,transport_pubkey_ed25519_base64}]`, `nodes_without_transport[]`, `reachable_without_directory` (`:829-846`) | CIRISServer | readable; decoded 1:1 by `ContactCodeResponse` (`ContactCodeWireTest`) |
| refusal for a private or foreign device | `self.node_not_announced` (400, detail names the refused ids) | CIRISServer | readable (`:716-728`); bundle key present (PR #113) |
| refusals about this identity | `self.contact_code_owner_key_absent`, `self.contact_code_not_a_person`, `self.contact_code_key_not_derived`, `self.contact_code_no_pqc_half` (400s), `self.contact_code_unencodable` (409) | CIRISServer | readable (`:629-678`, `:821`); bundle keys present |
| make this device reachable | `POST /v1/federation/announce` | CIRISServer | **live**: `src/claim_remote.rs:1250`, owner-gated, loopback-only, idempotent. It promotes this node's owner-binding `self → federation` at once; the Reticulum identity announce follows on the **next boot** (`announce_self_handler` doc, `:1054-1076`). The card says both halves (`text_contact_code_reachable_status`) |
| 16-device cap | CC 2.6.8 constraint 3: `node_count ≤ 16`, payload ≤ 1024 bytes | CIRISServer (encoder) | the route neither truncates nor refuses a count: it hands the chosen set to `fedcode::encode`, which fails past the payload bound and surfaces as `self.contact_code_unencodable` (409). The picker caps selection at 16 on this side, so the 409 is unreachable from the card |
| the QR | `QrCode(value, contentDescription, tag)` | CIRISClient | **live** (PR #99): `ui/primitives/QrCode.kt`, drawn from `qr_payload` |
| copy to clipboard | platform clipboard | CIRISClient | live |

## 4. Flow (how)

Sign in on a node with one announced device (this one); open People, then
`btn_contact_code_open`.

```yaml
expect:
  state: populated
  visible: [card_contact_code, qr_contact_code, text_contact_code, btn_contact_code_copy, text_contact_code_included]
  matches: {text_contact_code: "^CIRIS-V3-[A-Z2-7-]+$"}
  count: {of: "row_contact_code_node_*", min: 1}
```

Click `btn_contact_code_copy`, paste it into `input_contacts_add_key` on a
second person's node, submit (CSD-005). The second node lists the first person
as a contact.

Choose `opt_contact_code_nodes_none`, then back to `opt_contact_code_nodes_all`:
the code changes and changes back, and `text_contact_code_included` follows it.

On a node where no device is announced:

```yaml
expect:
  state: empty
  visible: [contact_code_unreachable, btn_contact_code_make_reachable]
  absent: [qr_contact_code]
  text: {contact_code_unreachable: "none of your devices is reachable"}
```

Make a listed device private from another session, then tick it here: the
picker's list is stale, and the node refuses (in CI, a stub forces this, since
the picker never lists a private device):

```yaml
expect:
  visible: [contact_code_refusal]
  text: {contact_code_refusal: "isn't reachable"}
```

On a node older than 0.5.218 (the released line, until 0.5.218 ships):

```yaml
expect:
  state: error
  visible: [contact_code_error]
  absent: [qr_contact_code]
  text: {contact_code_error: "0.5.218 or newer"}
```

## 5. QA plan

Spec complete and flow written (`testing/flows/csd-092-share-contact-code.yaml`, floor `>=0.5.225`); promotes to `testable` when it runs on the matrix. The tags are the client's at 0.5.225; the route is ciris-server 0.5.218's, so on an older node the flow drives the version fact and skips the populated, empty and refusal states. Linux desktop leg run locally the way `five-platform-live-qa.yml` runs it (2026-09-29, candidate 0.5.225, node v0.5.217, `--flows testing/flows`, the two-node fixture): **3/7 passed, 4 skipped** — the card opens, names the version it needs ("0.5.218 or newer") and closes; the four 0.5.218 states skipped as designed. On the matrix run of the same day (36588619656) the version step failed on every desktop leg: the client drew the sentence as the error's body and `StateBlock` registered its tag with the title alone, so the tree could not show it — fixed in the client (the tag now carries body and detail). The flow also closes its card whatever its verdict (`cleanup:`), because left open it replaced People's body for the three flows after it.

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

* **Card vs API.** The route is readable on `origin/integ/0.5.218`
  (`src/self_devices.rs:562-847`) and every response key is decoded 1:1, which
  is what moves this to `building`. It is not `testable` until 0.5.218 is
  released: the flow's floor is `unreleased` because the matrix runs the
  released line, where this route is a bare 404 and the card shows
  `contact_code_error` — asserted above as the one state that CAN run today.
* **API vs CC.** CC 2.6.8 constraint 2 is the ground for
  `self.node_not_announced`, and the default (all announced devices) keeps
  within it. Constraint 3 (≤ 16 nodes, ≤ 1024 bytes) is enforced by the encoder,
  not the route: past the bound the node answers `self.contact_code_unencodable`
  (409), and the client's 16-device cap keeps the card from ever reaching it.
* **CC → card.** The disclosure line under the QR is not decoration. A code that
  names devices is a partial roster in someone else's hands, and CC 2.1 makes
  roster visibility "a one-way disclosure the member chooses".
* **Replaces a wrong answer.** The Network Identity card showed the owner's bare
  fed-ID as the thing to share (CIRISServer#673), and a stranger could not
  resolve it. Once this card exists, that card should point here rather than
  offer the fed-ID as a contact handle.
