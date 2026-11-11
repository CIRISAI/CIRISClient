# CSD-138 — Dictionary (no content kind exists; what must be decided first)

**CSD**: CSD-138 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10), "inclusive of dictionary, articles, news, etc"
**Reads with**: CSD-136 (Library, the nearest thing that exists), CSD-135 (Feeds)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0`; CIRISConstitution `rc19` working tree; CIRISServer `origin/main` @ `7af5d4eb`
**Flow**: none. Nothing to drive

```yaml csd:stage
stage: envisioned
owner: CIRISClient
```

## 1. Mission (why)

**A person can look a word up and see what it means, in which language, with
examples and sources, and how the people this node trusts rate the entry.** That
is the aim. This card is `envisioned` because nothing beneath it exists, and it
says so rather than sketching fields no repo has defined.

Falsifiable, for the day it is sketched: a definition is never shown without its
language and its source, and a word this node holds no entry for says "No entry
for this word on this node", distinct from "Could not search".

**What exists today.** No content kind for a dictionary entry exists in any repo
read. `external_content`'s twelve `sub_kind`s (`SCHEMA.md:1131`) have none. The
nearest thing is an encyclopedia article whose `project` is `wiktionary`
(`src/ingest.rs:96-98`, which lists `wikipedia`, `wikidata`, `simple_wikipedia`,
`wiktionary` as examples). Imported that way, a Wiktionary page is a whole page
body with a slug, a revision and a language (CSD-136). It has no headword, no
part of speech, no sense list, no pronunciation and no per-sense source, so a
client cannot look a word up; it can only open a page whose slug happens to be
the word. The registry has `encyclopedia:*` and no lexical family.

## 2. Surface (what)

None, and no tags. A sketch needs fields to bind and a read to call, and neither
exists. The only honest surface today would be CSD-136 opened on a `wiktionary`
article, which needs no card of its own.

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: "encyclopedia:*"
    use: display-only
    type: unconfirmed
    example: "a wiktionary article, read as CSD-136 reads any article"
    renders: "the nearest thing: CSD-136 on an article whose `project` is `wiktionary`"
    tag: "proposed:doc_library_body"
  - ceg: x_private:dictionary_entry
    use: display-only
    type: unconfirmed
    example: "unconfirmed: no shape exists"
    renders: "nothing yet. A headword, its language, senses with examples, and sources, once a repo defines them. No registry family exists, and which repo should register one is the first decision below"
    tag: "proposed:row_dictionary_sense.<n>"
```

```yaml csd:states
populated: {renders: "not designed: no entry shape exists"}
empty:     {renders: "'No entry for this word on this node.' Only when a search answered with nothing"}
loading:   {renders: "the search field with a progress affordance and no sentence"}
error:     {renders: "'Could not search this node's dictionary', with the node's words; never the empty sentence"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| a dictionary entry | none: no kind, no builder, no compose, no route in CIRISNodeCore, CIRISServer (`origin/main`) or the Constitution's registry | undecided (§6) | **does not exist** |
| look a word up | none | undecided | **does not exist** |
| the nearest thing | an `encyclopedia_article` with `project: wiktionary`; no route (CSD-136 §3) | CIRISNodeCore | **missing** |

## 4. Flow (how)

None.

## 5. QA plan

None until §6 is decided.

## 6. Status and asks

Envisioned. Before this card can be sketched, three decisions, in this order:

1. **Is a dictionary entry its own `sub_kind`, or a structured body inside
   `encyclopedia_article`?** A new `sub_kind` is a SCHEMA §4.9.2 amendment
   ("open; future kinds via §4.9.2 amendment", `SCHEMA.md:1131`). A structured
   body needs a media type and a schema for it. Either way it is
   **CIRISNodeCore**'s to propose.
2. **What is an entry's identity?** Headword plus language plus sense is the
   usual key; NodeCore's `entity_key_id` patterns
   (`{project}:article:{slug}`) do not carry a sense. **CIRISNodeCore**.
3. **Is there a lexical quality family** (for example accuracy of a sense, or
   attestation of usage), or does `encyclopedia:*` serve? Registering one is
   **CIRISConstitution**'s, proposed by NodeCore, which owns the content
   families.

Then **CIRISServer** serves a lookup at Server 1.0, and this card is sketched.
