# CSD-005 — People (the Contacts surface, rebuilt on the primitives)

**CSD**: CSD-005 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 0
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see who they have consented to exchange messages with, add
someone by their code, and open the receipt on any of them — and the screen
says honestly when the node cannot answer.** The screen is wave 0's proof that
the nine primitives express a real surface: a list, an empty state, a loading
state, and the one "node fact" error the no-gating rule honours (a node too old
to serve `GET /v1/contacts`). Serves **Contextual Integrity**: a contact is a
`consent:replication:v1` grant, and its receipt says so.

**Adding someone takes their contact code, pasted or scanned (0.5.218).** The
input a person shares is their contact code (CSD-092, CC 2.6.8): a v3 code that
names their devices resolves with no directory, so a stranger across a table is
addable. A fed-ID still works when this node's directory already knows them. A
node code does not, and cannot: a node cannot consent, so the node refuses it as
`contacts.unresolvable`, and the card turns that into "That isn't a person's
code" and points at the contact code. Paste is always present. The QR scan is an
extra way to fill the same field, never the only one.

## 2. Surface (what)

```yaml csd:surface
surface: contacts
screen: Contacts
```

`nav_map` derives `circle_<circle> -> tab_people`: Contacts IS the People tab
in every circle, so the shell shows it directly and the chain ends on the tab
(wave 1). The screen class, the nav id and every `contacts_*` tag are unchanged
from the surface this replaces; the visible title is "People".

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: string
    example: "consent:replication:v1"
    renders: "What it is — consent:replication:v1 (fixed by the rule for this kind of record, CC 3.3.7)"
    tag: receipt_dimension
  - ceg: x_private:subject_key_ids
    use: display-only
    type: "list[string]"
    example: ["wa-peer-4a19c2"]
    renders: "Who it is about — wa-peer-4…19c2"
    tag: receipt_subject
  - ceg: x_private:attesting_key_id
    use: display-only
    type: unconfirmed
    example: "unconfirmed"
    renders: "Who sent it — This node (fixed by the rule, CC 3.3.7); the key itself is not on GET /v1/contacts"
    tag: receipt_attester
  - ceg: x_private:cohort_scope
    use: display-only
    type: string
    example: "federation"
    renders: "Who can see it — Everyone (fixed by the rule, CC 3.3.7). The grant itself is a public record. What you send each other is not."
    tag: receipt_scope
  - ceg: x_private:trust_state
    use: display-only
    type: "enum[trusted,untrusted,blocked,unknown]"
    example: "trusted"
    renders: "a chip on the row: Trusted / Untrusted / Blocked / Unknown"
    tag: "proposed:contacts_row_trust"
  - ceg: x_private:contact_input
    use: emit
    type: string
    example: "CIRIS-V3-AB12-CD34-…"
    renders: "the one Add contact field, labelled for a contact code; a fed-ID is also accepted. Scanning fills this same field and does NOT submit: adding someone writes a consent grant (CC 3.3.7), so the person sees what they scanned before they grant it"
    tag: input_contacts_add_key
  - ceg: x_private:contact_add_outcome
    use: display-only
    type: "enum[added,already,refused]"
    example: "already"
    renders: "'Added {who}.' on a fresh grant; 'Already in your contacts.' when the node returns freshly_emitted: false (the same person twice is a no-op, not an error); the refusal block otherwise"
    tag: "proposed:contacts_add_already"
```

**The receipt renders all five facts every time.** `The rule it follows`
(`consent:scope`) and the holder count are `NotSent`: `GET /v1/contacts` omits
the grant's `attestation_prefixes` and carries no holder information. The row
says "This node did not send this." in the error tone rather than leaving a
blank — an absent fact is a fact about the node. CIRISServer#616 asks for the
grant's envelope on the list route so those rows fill in without a client
change.

`x_private:attesting_key_id` is `type: unconfirmed` deliberately: the identity
is fixed (the granting node is this node) but the key is not carried, so the
sheet says *This node* by rule and cannot show the key until the route does.

```yaml csd:states
populated: {tag: contacts_list}
empty:     {tag: contacts_empty, renders: "No contacts yet (or: No contacts match “q”) — but over an EMPTY, UNSEARCHED list the add card (card_contacts_add) is shown INSTEAD, because that list has exactly one useful next move"}
loading:   {tag: contacts_loading, renders: "the frame with a progress affordance and NO sentence"}
error:     {tag: contacts_error, renders: "the list-level error banner; and contacts_unsupported for the node-too-old state: This node doesn't have contacts yet — This node is running {version}. Contacts and chat need ciris-server 0.5.185 or newer …"}
```

`error` and `empty` cannot look alike: `StateBlock` gives error the `danger`
tone, the `error` glyph, a hairline box and an uppercase label, and empty the
`mute` tone with none of those (`PrimitiveRulesTest.errorNeverLooksLikeEmpty`).

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| contacts | `GET /v1/contacts` | CIRISServer | live since 0.5.185 |
| add a contact | `POST /v1/contacts` | CIRISServer | live; returns `consent_prefixes` |
| add by contact code | `POST /v1/contacts` with the code in `key_id` | CIRISServer | **live**: `AddContactRequest.key_id` takes a fed-ID **or** a fedcode and also accepts the aliases `code` and `contact` (`src/contacts_chat.rs:1732-1742` @ `046e1b39`). The client sends `key_id` (`CIRISApiClient.kt:1490`), so no client change is needed to send a code |
| adding the same person twice | same route; `freshly_emitted: false` | CIRISServer | **live**: the standing grant already covers every prefix, so nothing is written (`contacts_chat.rs:1775-1778`). The card reads it as `proposed:contacts_add_already` |
| "That isn't a person's code" | refusal `contacts.unresolvable` (400) | CIRISServer (id) · CIRISClient (copy) | the id is **live** (`contacts_chat.rs:529`); the bundle's copy is still "That identifier does not resolve to a contact." (`en.json:644`). **Ask (CIRISClient card):** "That isn't a person's code. Ask them for their contact code: People › Share my contact code." |
| a code that does not decode | refusal `contacts.malformed_code` (400) | CIRISServer | live (`contacts_chat.rs:520`); bundle key present |
| a fed-ID this directory does not know | refusal `contacts.unknown_fed_id` (404) | CIRISServer | live; the bundle's copy says "Admit the key first (peering)". With codes available, the node's own detail is the better steer: "If they handed you a CODE, paste that instead" (`contacts_chat.rs:488-491`). **Ask (CIRISClient card):** point at the contact code, as for `unresolvable` |
| a pasted code on a separate node | same route, end to end | CIRISServer | **built, unmerged** (0.5.218, CIRISServer#673): the brief's update reports "a code from one node, pasted on a separate node, resolves to its owner directly" as tested. Not readable: no branch or PR is pushed |
| remove a contact | `DELETE /v1/contacts/{key_id}`, signed by the person, never the node; the response lists `remaining_grants` | CIRISServer | **built, unmerged** (0.5.218, CIRISServer#657, still open; not readable). The receipt sheet gets `proposed:btn_receipt_act_remove_{keyId}` with a ConfirmSheet. **Grants the node wrote before the person re-signed them cannot be withdrawn and stay live.** Each one in `remaining_grants` renders as a row in `proposed:contacts_remove_remaining` reading "Still active: written by this node before you signed your contacts, so it can't be withdrawn here", and the contact is NOT reported as removed while any remain. The success line says "Removed" only when `remaining_grants` is empty |
| scan a code | `QrScanAction` primitive, `proposed:btn_scan_contact_code` | CIRISClient | **in build**: not written yet. Branch `wip/qr-encode-scan` @ `353a2ec` has only the encoder (`QrEncoder.kt`, unreviewed WIP). Paste does not depend on it |
| the grant's envelope on the list route | `GET /v1/contacts` — **unconfirmed**, asked in CIRISServer#616 | CIRISServer | blocks `building` for `receipt_attester`, `receipt_rule`, `receipt_holders` |

## 4. Flow (how)

Sign in on a node; land on `Contacts`.

```yaml
expect:
  state: populated
  count: {of: "contacts_row_*", min: 1}
  visible: [contacts_list, input_contacts_search]
```

Open a receipt without a long-press: click `btn_receipt_<keyId>`.

```yaml
expect:
  visible: [sheet_receipt, receipt_subject, receipt_attester, receipt_scope, receipt_dimension, receipt_rule, btn_receipt_close]
  matches: {receipt_dimension: "consent:replication:v1"}
  text: {receipt_rule: "did not send"}
```

Add by contact code: open `btn_contacts_add_open`, paste a person's contact
code (CSD-092) into `input_contacts_add_key`, click `btn_contacts_add_submit`.

```yaml
expect:
  visible: [btn_contacts_add_open_chat]
  absent: [contacts_add_refusal]
```

Submit the same code again: nothing new is written and nothing is refused.

```yaml
expect:
  visible: ["proposed:contacts_add_already"]
  absent: [contacts_add_refusal]
```

Paste a node code (`CIRIS-V1-…`) instead:

```yaml
expect:
  visible: [contacts_add_refusal]
  text: {contacts_add_refusal: "isn't a person's code"}
```

Scan instead of paste: `proposed:btn_scan_contact_code` fills
`input_contacts_add_key` and does not submit; `btn_contacts_add_submit` is still
the act.

Search for a string no contact matches → `contacts_empty`; clear it → the list
again. On a fresh node with no contacts → `card_contacts_add` and no
`contacts_empty`.

## 5. QA plan

**Platforms.** All five. The Contacts entry screen is what CIRISAgent's
five-platform gate leans on; no tag it drives has changed.

**Not tested here.** The long-press gesture (no `/long-press` endpoint); the
hamburger is the drivable equivalent. The camera half of the scan: it needs the
QrScanAction test double, and until that exists the paste path is the tested
path. The `input_contact_code` tag is deliberately NOT minted: the field already
exists as `input_contacts_add_key`, and renaming it would break the tag the
five-platform gate drives.
