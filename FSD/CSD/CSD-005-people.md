# CSD-005 — People (the Contacts surface, rebuilt on the primitives)

**CSD**: CSD-005 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, wave 0
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: building
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
    type: string
    example: "wa-self-88b1"
    renders: "Who sent it — the granting key, now on the wire (GET /v1/contacts carries grant_receipt since 0.5.217). The sheet still says 'This node' by rule because the client discards the field; see §3."
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
```

**The receipt renders all five facts every time.** It renders three of them
`ByRule` and `The rule it follows` as `NotSent` — and that is now WRONG, because
the node sends all five. The row says "This node did not send this." in the error
tone rather than leaving a blank, which was the honest answer while the route was
silent; against 0.5.217 it is the client contradicting the wire.

**CIRISServer#616 CLOSED 2026-09-25 and shipped in 0.5.217.** `GET /v1/contacts`
attaches `grant_receipt` per row (`CIRISServer src/contacts_chat.rs:1704-1712`,
built at `src/peer.rs:1524-1560`) carrying `subject_key_ids`,
`attesting_key_id`, `cohort_scope`, `dimension` and `consent_prefixes`. So
`x_private:attesting_key_id` is `type: string` here, not `unconfirmed`: the
substrate has answered.

**What is left is this repo's.** `models/federation/Contact.kt:27-66` declares
four contact-only members and no `grant`, so the wire field is decoded into
nothing and `ui/screens/PeopleSupport.kt:66-83` still composes the sheet from
rules. `wireFacts == 1` of 5 against a node that sends 5. Adding the field and
reading it in `contactReceipt` is the whole change, and it is what takes this
card to `testable`.

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
| the grant's envelope on the list route | `GET /v1/contacts` → `grant_receipt` | CIRISServer | **live since 0.5.217** (#616 closed); `src/contacts_chat.rs:1704-1712` |
| reading that envelope into the sheet | — | **CIRISClient** | **missing** — `Contact.kt:27-66` has no `grant` member, so `PeopleSupport.kt:66-83` renders 4 of 5 facts by rule; blocks `testable`, not `building` |

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

Search for a string no contact matches → `contacts_empty`; clear it → the list
again. On a fresh node with no contacts → `card_contacts_add` and no
`contacts_empty`.

## 5. QA plan

**Platforms.** All five. The Contacts entry screen is what CIRISAgent's
five-platform gate leans on; no tag it drives has changed.

**Not tested here.** The long-press gesture (no `/long-press` endpoint); the
hamburger is the drivable equivalent.
