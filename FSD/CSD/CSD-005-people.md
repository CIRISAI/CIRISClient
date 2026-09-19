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

## 2. Surface (what)

```yaml csd:surface
surface: contacts
screen: Contacts
```

`nav_map` derives `nav_group_manage -> nav_epistemic_contacts`. The screen
class, the nav id and every `contacts_*` tag are unchanged from the surface this
replaces; the visible title is "People". Wave 1 re-homes it as the People tab of
each circle.

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
```

**The receipt renders all five facts every time.** `The rule it follows`
(`consent:scope`) and the holder count are `NotSent`: `GET /v1/contacts` omits
the grant's `attestation_prefixes` and carries no holder information. The row
says "This node did not send this." in the error tone rather than leaving a
blank — an absent fact is a fact about the node. CIRISServer is asked to return
the grant's envelope on the list route so those rows fill in without a client
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
| the grant's envelope on the list route | `GET /v1/contacts` — **unconfirmed** | CIRISServer | blocks `building` for `receipt_attester`, `receipt_rule`, `receipt_holders` |

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
