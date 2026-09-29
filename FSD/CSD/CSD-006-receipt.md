# CSD-006 — The receipt (the template every CEG item asserts)

**CSD**: CSD-006 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec §2
**Flow**: `testing/flows/drafts/csd-006-receipt.yaml` (floor `>=0.5.225`) — driven on the first surface that binds the template (Contacts, CSD-005)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**Every item that came off the wire as a signed claim carries a hamburger, and
the hamburger opens the same five facts — a file, a chat, a person, a group, a
note, a vote, a device, a root.** If a row has no hamburger it is not a CEG
item; it is app chrome, and a person can tell by looking which things on screen
are claims and which are furniture. Serves **Transparency**: the five facts are
the CC 2.1 envelope in plain words, and a card's field and its receipt entry
read one table (`ceg/Dimensions.kt`) so they cannot drift apart.

## 2. Surface (what)

```yaml csd:surface
surface: contacts
screen: Contacts
```

The receipt is a sheet, not a surface: it opens over whichever surface holds
the item. It is bound here to the first surface that carries it (CSD-005) so
the checker has a hop to derive; a card CSD binding the receipt names its own
surface and reuses these fields verbatim.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:subject_key_ids
    use: display-only
    type: "list[string]"
    example: ["wa-peer-4a19c2"]
    renders: "Who it is about — the people this record names; each of them can take it back"
    tag: receipt_subject
  - ceg: x_private:attesting_key_id
    use: display-only
    type: string
    example: "wa-self-88b1"
    renders: "Who sent it — the key that signed this record; nobody else could have"
    tag: receipt_attester
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self,family,community,affiliations,species,planet,federation]"
    example: "family"
    renders: "Who can see it — a ScopePill: Just me / Family / Neighbours / Communities and Businesses / Everyone (the 7→5 fold on CohortScope)"
    tag: receipt_scope
  - ceg: x_private:dimension
    use: display-only
    type: string
    example: "consent:replication:v1"
    renders: "What it is — the bound wire dimension in mono, under the family's plain label"
    tag: receipt_dimension
  - ceg: "consent:{kind}"
    bind: {kind: scope}
    use: display-only
    type: string
    example: "consent:scope:share"
    renders: "The rule it follows — what may happen next: kept, shared, or analysed"
    tag: receipt_rule
```

**A fact is one of three things, and the sheet says which.** The node SENT it
(`Fact.Wire`); the constitution FIXES it for this kind of record so it is true
without being sent (`Fact.ByRule`, with the section named under the value); or
the node did not send it (`Fact.NotSent`: "This node did not send this." in the
error tone). Never a guess, never blank, never reduced: all five rows render on
a phone as on a desktop.

The five envelope members are `x_private:` because they are CC 2.1 envelope
members, not registry families — the same convention CSD-004 established for
`attesting_key_id`. `consent:scope` is the one that IS a family leaf
(`consent:{kind}` bound to `scope`, CC 3.3.1).

```yaml csd:states
populated: {tag: sheet_receipt}
empty:     {renders: "there is no empty receipt — a row without a claim has no hamburger, so the sheet never opens on nothing"}
loading:   {renders: "the sheet does not load; it renders the facts the row already holds"}
error:     {tag: receipt_rule, renders: "a NotSent fact renders 'This node did not send this.' in the error tone on its own row; the sheet itself does not fail"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the envelope on every list route | per surface | CIRISServer | **live since 0.5.217** — CIRISServer#616 CLOSED 2026-09-25 |

**The substrate has answered, which is what moves this to `building`.** The
envelope is built once as `grant_receipt` (`CIRISServer src/peer.rs:1524-1560` —
`subject_key_ids`, `attesting_key_id`, `cohort_scope`, `dimension`,
`consent_prefixes`, plus `valid_until` / `row_expires_at`) and attached per row
on five reads: `GET /v1/contacts` (`src/contacts_chat.rs:1704-1712`),
`GET /v1/drive` and `GET /v1/files/{id}/meta` (`src/drive.rs:1521` `envelope_of`,
called `:1844`, `:1965`), `GET /v1/families/{id}` (`src/family_api.rs:561-568`),
`GET /v1/chat/{cid}/messages` (`src/contacts_chat.rs:2588-2596`) and the
`/v1/memory/query` CEG projection (`src/memory_api.rs:663-671`).

**`GET /v1/notes` is the one read that does NOT carry it** — `read_notes` in
`src/drive.rs` never calls `envelope_of`. A note is a CEG item by the rule in §1,
so a Notes card binding this template renders `Fact.NotSent` on every row until
that read joins the others. That is a row to ask for, not a reason to hide the
hamburger.

**The first binding reads all five off the wire.** `models/federation/Contact.kt`
decodes the row's `grant` (`ContactGrant`: every member of `peer.rs::grant_receipt`)
and `ui/screens/PeopleSupport.kt::contactReceipt` renders each fact from it —
`wireFacts == 5` (`PeopleSupportTest`). Re-checked 2026-09-28 on
`integ/0.5.218`: `grant_receipt` is built per row at `src/contacts_chat.rs:1754`
and attached under the key `grant` (`:1823`), `null` when the node holds no
readable receipt.

**Which facts a binding may fix `ByRule`, decided on the first one.** Only a
fact the constitution or the route fixes for EVERY row of that kind. On a
contact that is the dimension alone: a contact IS a `consent:replication:v1`
grant (CC 3.3.7) and `GET /v1/contacts` serves nothing else. The scope is NOT
one — a grant's `cohort_scope` is the consent audience the person chose
(`peer.rs::add_contact`, default federation) — and the attester stopped being
one at 0.5.211, when consent moved from the node to the person. So a grant-less
row (a node older than 0.5.217, or `grant: null`) renders the attester, the
scope and the rule as `NotSent`. The scope used to render `ByRule("federation")`
there; that was a guess and this review removed it, red first. A binding that
wants a `ByRule` fact names the section that fixes it, as CSD-005 does for the
dimension.

**Where this template does NOT apply, and says so.** The Moderation card
(CSD-065) files a `ModerationEvent` and gets back `{attestation_id, duty}`
(`src/safety/moderation.rs:413`); the enforcement ladder's results are
per-target outcomes with the node's own sentences, not signed rows. Neither
carries the envelope, so neither has a hamburger: by §1's rule they are
furniture until the node sends the five facts, and CSD-065 §3 names the
envelope on the moderation response as the row to ask for rather than drawing
a receipt of `ByRule` guesses.

## 4. Flow (how)

Bound per surface; CSD-005 §4 is the first instance.

## 5. QA plan

Spec complete and flow written (`testing/flows/drafts/csd-006-receipt.yaml`, floor `>=0.5.225`); promotes to `testable` when the floor is released and the flow runs on the matrix (#97).

A card CSD that binds this template asserts `visible:` on all five `receipt_*`
tags after clicking its `btn_receipt_<id>`; a card whose rows are furniture
asserts `absent: [btn_receipt_*]` instead.
