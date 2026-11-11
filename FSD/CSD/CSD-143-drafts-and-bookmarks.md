# CSD-143 — Notes, drafts and bookmarks (private until the owner shares one wider)

**CSD**: CSD-143 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-008 (Notes to self: the notes that exist today, a different object, §1), CSD-135 (Feeds: the Local feed lists these), CSD-136 / CSD-137 (what a draft may become), CSD-142 (Observations)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0` (`src/ingest.rs:520-610`, `:678-735`, `:2780-2862`); CIRISServer `origin/main` @ `7af5d4eb` (`src/drive.rs`, `/v1/notes`)
**Flow**: unwritten. No route serves local items or promotion, and no screen exists

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person keeps drafts, bookmarks and private observations that only they can
see, and can share one with a wider circle as a deliberate act, seeing exactly
where it goes and what it becomes before it leaves.**

Falsifiable:

* **Private by construction.** NodeCore writes every local item at
  `cohort_scope: self` regardless of input (`build_local_payload`,
  `src/ingest.rs:716`: `"cohort_scope": "self", // local_data is always
  owner-private`). The card offers no scope picker when saving.
* **Sharing is one act, confirmed.** Promotion is "an explicit user action via
  `promote_payload`" (`ingest.rs:554-557`) that writes a new Contribution at the
  wider scope citing the same `content_sha256`, without re-uploading the body
  (`ingest.rs:2780-2800`). The card sends it only from a ConfirmSheet that names
  the circle and what the item becomes (law L6). The shared item `supersedes`
  the private one (`ingest.rs:2791-2793`), so the card shows the private row as
  "Shared with Neighbours" afterwards and does not call it a separate private
  copy. A `promote_hint` saved on the item only shows a "Ready to share" mark;
  it shares nothing (`ingest.rs:554-559`).
* **Sharing never narrows or stays put.** `promote_payload` accepts any of the
  seven scopes, `self` included, and does not check that the target is wider
  than the source (`ingest.rs:2809-2813`, `is_promotable_scope` only). The card
  offers only scopes wider than the item's own, and says why the others are
  absent.

**Notes are CSD-008's, and they are a different object.** The notes a person
writes today are CIRISServer drive rows: `POST /v1/notes` writes a `text/plain`
row in the self room and `GET /v1/notes` reads them (CIRISServer `src/drive.rs`,
CSD-008 §1). NodeCore's `local_data` with `local_kind: notes` is a separate
Contribution kind that nothing writes yet. Two objects called "note" is a
finding (§6). Until it is settled this card leaves notes to CSD-008 and covers
the three kinds CSD-008 does not: `draft`, `bookmark` and `observation`
(`LocalKind`, `ingest.rs:572-582`).

## 2. Surface (what)

No `csd:surface` block yet (CSD-108's precedent). Proposed under Just me › Files,
beside CSD-007, as one list filtered by kind.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: drafts
screen: Drafts
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:local_kind
    use: display-only
    type: "enum[notes,draft,bookmark,observation]"
    example: "draft"
    renders: "a kind chip on each row: Draft · Bookmark · Observation. A `notes` item, if one arrives, is listed with 'Note (not the Notes in Chats)' until §6 is settled"
    tag: "proposed:chip_drafts_kind.<contribution_id>"
  - ceg: x_private:local_title
    use: display-only
    type: string
    example: "Einstein research notes"
    renders: "the row's title (`title`, optional); none: the first line of the body, marked as such"
    tag: "proposed:row_drafts_item.<contribution_id>"
  - ceg: x_private:local_tags
    use: display-only
    type: "list[string]"
    example: ["physics", "to-share"]
    renders: "the owner's tags as chips. NodeCore says they are 'not surfaced in any federation directory until promoted' (ingest.rs:550-551); the card says so on the share sheet when tags would go with the item"
    tag: "proposed:chip_drafts_tag.<tag>"
  - ceg: x_private:local_created_at
    use: display-only
    type: timestamp
    example: "2026-11-02T20:14:00Z"
    renders: "'Written 2 Nov'"
    tag: "proposed:txt_drafts_item__gloss.<contribution_id>"
  - ceg: x_private:promote_hint
    use: display-only
    type: string
    example: "community · encyclopedia_article"
    renders: "'Ready to share with Neighbours as an article', from `promote_hint{target_scope, target_sub_kind}`. A mark only; nothing has been shared"
    tag: "proposed:chip_drafts_ready.<contribution_id>"
  - ceg: x_private:promote_target_scope
    use: emit
    type: "enum[family,community,affiliations,species,planet,federation]"
    example: "community"
    renders: "the share sheet's circle picker, offering only circles wider than `self`. `planet` is NodeCore's spelling; CC 2.5 says `biosphere` (CSD-135 §6)"
    tag: "proposed:pick_drafts_share__group"
  - ceg: x_private:promote_target_sub_kind
    use: emit
    type: "enum[local_data,encyclopedia_article,news_article,chat_message,blog_post]"
    example: "encyclopedia_article"
    renders: "'Share as': Keep as is · Article · News · Post. Offered only for kinds whose required fields the person has filled (below)"
    tag: "proposed:opt_drafts_share_as.<sub_kind>"
  - ceg: x_private:promotion
    use: emit
    type: unconfirmed
    example: "promote 01HX… to community as encyclopedia_article"
    renders: "the ConfirmSheet: 'Share \"Einstein research notes\" with Neighbours as an article. Everyone in Neighbours can read it. It replaces the private version in your list.' and Share / Cancel"
    tag: "proposed:sheet_drafts_share__confirm"
```

**Morphing does not yet produce the target kind.** `promote_payload` copies the
prior payload, sets `sub_kind`, `cohort_scope` and `supersedes_payload`, and
strips `promote_hint` (`ingest.rs:2807-2836`). The `source` object stays the
local item's. So a draft "promoted" to `news_article` has no `headline`,
`publisher` or `published_at`, and one promoted to `encyclopedia_article` has no
`project` or `revision_id`, all of which those kinds require. The card therefore
does not offer Article or News until NodeCore rebuilds the source for the target
kind, or the card collects the missing fields and NodeCore accepts them. That is
the first ask below. Every field here is `x_private:` (CSD-135 §2's finding).

```yaml csd:states
populated: {tag: "proposed:state_drafts_items", renders: "the owner's local items, newest first, with kind and Ready marks"}
empty:     {tag: "proposed:state_drafts_items__empty", renders: "'No drafts, bookmarks or observations yet. They stay on your own devices until you share one.' Only on an answered empty list"}
loading:   {tag: "proposed:state_drafts_items__loading", renders: "the frame with a progress affordance and no sentence"}
error:     {tag: "proposed:state_drafts_items__error", renders: "'Could not read your drafts', with the node's words. A refused share is drawn on the share sheet by its reason (for example `UnknownPromotionScope`, ingest.rs:2809-2813), and the item stays private"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the owner's local items | no route; NodeCore library function `compose_local_feed` (`src/compose.rs:1144`, Python `local_feed`, `python.rs:340`) lists them among the owner's `self` items; `build_local_payload` (`src/ingest.rs:678`) and `ingest_local_data` (`:3353`) write them | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing.** `build_local_payload` is not exported to Python |
| share wider | no route; `promote_payload` (`src/ingest.rs:2801`) builds the payload; the caller wraps and signs the envelope with `supersedes` set (`ingest.rs:2785-2797`) | CIRISNodeCore | **missing.** Not exported to Python |
| notes to self | `GET /v1/notes`, `POST /v1/notes` | CIRISServer (`src/drive.rs`), CSD-008's | **live**, and a different object (§1) |

## 4. Flow (how)

Every tag is `proposed:`. Open Drafts and share a draft with Neighbours as is:

```yaml
expect:
  state: populated
  visible: ["proposed:pick_drafts_share__group", "proposed:sheet_drafts_share__confirm"]
  absent:  ["proposed:opt_drafts_share_as.news_article"]
```

## 5. QA plan

Not drivable. When routes exist: assert a saved item is `self` whatever the
client sends; assert the share sheet never offers `self`; assert a shared item
appears in the target circle's feed (CSD-135) and the private row reads
"Shared". `compose_local_feed` does not drop superseded rows today
(`compose.rs:1144-1171`), so without a supersedes-aware read the private row
would still list as a live draft; that read is part of the CIRISNodeCore ask.

## 6. Status and asks

Sketched. Open question for the owner: is a note a CIRISServer drive row
(CSD-008, live) or a NodeCore `local_data` item? If both survive the fold, the
person has two places called notes.

Asks, one repo each:

* **CIRISNodeCore**: make `promote_payload` produce a valid payload for the
  target `sub_kind` (or refuse a morph whose required fields are missing); refuse
  a target scope that is not wider than the source; fix its doc, which says "The
  payload itself does not carry `cohort_scope`" (`ingest.rs:2787-2788`) while the
  code sets it (`:2824`); mark or drop superseded items in `compose_local_feed`;
  accept `biosphere`; say whether `local_data` `notes`
  replaces or sits beside the server's notes.
* **CIRISServer**: at Server 1.0, routes to list local items, save one, and
  promote one, the promote returning a typed refusal; and the answer to the notes
  question above, since the live notes are the server's.
