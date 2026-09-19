# CSD-006 — The receipt (the template every CEG item asserts)

**CSD**: CSD-006 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec §2
**Flow**: none — this is the template other CSDs bind

```yaml csd:stage
stage: envisioned
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
| the envelope on every list route | per surface | CIRISServer | **unconfirmed** — CIRISServer#616 is the first ask (contacts) |

## 4. Flow (how)

Bound per surface; CSD-005 §4 is the first instance.

## 5. QA plan

A card CSD that binds this template asserts `visible:` on all five `receipt_*`
tags after clicking its `btn_receipt_<id>`; a card whose rows are furniture
asserts `absent: [btn_receipt_*]` instead.
