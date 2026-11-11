# CSD-136 — Library: an encyclopedia article (body, revisions, corrections, citations, quality)

**CSD**: CSD-136 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-135 (Feeds, where an article row opens this card), CSD-137 (News, the same item shape with a publisher), CSD-138 (Dictionary, which has no kind of its own), CSD-047 (fetch content by digest, the one live route that reaches an article's bytes)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0`; CIRISServer `origin/main` @ `7af5d4eb` (2026-11-09)
**Flow**: unwritten. No route serves article metadata or quality, and no screen exists

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person reading an encyclopedia article can see its text, which revision it
is, what has been corrected and why, what it cites, and how the people this node
trusts rated it, with each rating's count beside it.**

Falsifiable:

* A recanted claim is struck through with its reason beside it, never removed
  (the Document primitive's rule, `packaging/csd5/ui_primitives.json`). In
  NodeCore a claim is a `scores` attestation about the article, not a sentence in
  the body: "structural `recants` on the specific scores attestation that carried
  the false claim (NOT the whole article — recants is per-claim)" (`SCHEMA.md:1256`).
  So the card strikes a rating or a stated claim row, and it never marks up the
  body text, because nothing in the payload points into it.
* A quality number is never shown without its attester count, and an axis with
  no attesters shows a dash, not 0 (law L4).
* The quality number is described as what it is: an **unweighted** mean
  (`compose.rs:1314`, "Unweighted mean of scores across all active
  attestations"), although the field is named `weighted_mean`. The card labels it
  "average of N ratings".
* Until NodeCore filters them, the card says that the average may include
  ratings that were later recanted or withdrawn: `compose_article_quality`'s own
  doc says "Withdrawn / recanted attestations are not filtered here"
  (`compose.rs:1355-1356`), and "active" there means only "not expired"
  (`compose.rs:43-44`). Showing a struck claim beside an average that still
  counts it, without saying so, would be the dishonest number L4 forbids.

## 2. Surface (what)

No `csd:surface` block yet: the screen does not exist (CSD-108's precedent). It is
a detail screen opened from a feed row (CSD-135) or a link from another article,
not a sidebar row.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: null
flow_only: true
screen: LibraryArticle
entry: "an article row in CSD-135's feeds, or a references / see_also link in another article"
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:article_entity_key_id
    use: display-only
    type: string
    example: "wikipedia:article:einstein"
    renders: "the heading. `EncyclopediaArticleSource` has no title field (ingest.rs:78-116), so the heading is the slug after `:article:` with underscores and hyphens shown as spaces, and the full key id under it in monospace"
    tag: "proposed:txt_library_title"
  - ceg: x_private:article_project
    use: display-only
    type: string
    example: "wikipedia"
    renders: "'From Wikipedia, revision 1189912345, edited 3 Nov 2026', from `project`, `revision_id`, `edited_at` (ingest.rs:98-102)"
    tag: "proposed:txt_library_provenance"
  - ceg: x_private:article_body
    use: display-only
    type: string
    example: "Albert Einstein was a theoretical physicist…"
    renders: "the body, fetched by `content_sha256` and hashed before it is shown. text/markdown and text/plain render as text; text/html renders as text with tags removed until the client has a sanitiser it trusts (MEDIA_EDGE.md); application/json says 'Structured data, N bytes' and offers Save"
    tag: "proposed:doc_library_body"
  - ceg: x_private:article_revision
    use: display-only
    type: unconfirmed
    example: "revision 1189912345 · supersedes 1189800001"
    renders: "one row per revision in the `supersedes` chain, newest first, the current one marked. Opening an older one shows it with a banner 'An older revision'"
    tag: "proposed:doc_library_body__revision.<contribution_id>"
  - ceg: "encyclopedia:*"
    use: display-only
    type: unconfirmed
    example: "encyclopedia:accuracy:physics 0.2 by key:fc01…"
    renders: "a stated claim about the article: who said it, on which axis, with what score. A claim that has been recanted is drawn struck through, with the recanting row's reason beside it and its date"
    tag: "proposed:doc_library_body__claim.<attestation_id>"
  - ceg: x_private:recants
    use: display-only
    type: unconfirmed
    example: "Recanted 4 Nov 2026 by key:fc01…: 'misread the source'"
    renders: "the reason beside a struck claim. `recants` is a structural row type (registry `_meta.row_types.structural`), not a dimension family, which is why this is x_private"
    tag: "proposed:doc_library_body__recant.<attestation_id>"
  - ceg: x_private:cites_source
    use: display-only
    type: "enum[primary_source,external_url,doi,isbn,arxiv,caselaw]"
    example: "doi"
    renders: "one row per citation: its kind and its reference string, 'DOI 10.1002/andp.19053221004'. Six kinds, not five: `caselaw` is the sixth (ingest.rs:247-260). An unknown kind is shown verbatim. A URL is shown, never fetched by this card"
    tag: "proposed:doc_library_body__cite.<n>"
  - ceg: "topical_relation:{kind}"
    bind: {kind: see_also}
    use: display-only
    type: string
    example: "see_also → wikipedia:article:special_relativity"
    renders: "'See also' and 'References' lists of linked articles; a target this node does not hold is shown greyed with 'not on this node' rather than hidden"
    tag: "proposed:row_library_link.<target_key_id>"
  - ceg: "encyclopedia:*"
    use: display-only
    type: unconfirmed
    example: "accuracy 0.82 (14 ratings) · physics 0.91, history 0.70"
    renders: "one Metric per axis (accuracy, completeness, NPOV_compliance, citation_quality), as 'average of N ratings', with bucket rows (topic) under it. An axis no one rated shows a dash. A footnote, until NodeCore filters them: 'May include ratings later recanted or withdrawn.'"
    tag: "proposed:stat_library_quality.<axis>"
  - ceg: "encyclopedia:*"
    use: emit
    type: unconfirmed
    example: 0.8
    renders: "Rate this article: an axis, a score from 0 to 1, and an optional topic. Sent only from a ConfirmSheet that names the axis and score (L6). The score range is not stated in NodeCore and is assumed 0 to 1 until it is"
    tag: "proposed:btn_library_rate"
```

**Registry findings.** `encyclopedia:*` and `topical_relation:{kind}` are
registered (rc6, owned by CIRISNodeCore). Three things are not:

* `cites_source:{kind}`, the dimension NodeCore names for citations
  (`ingest.rs:113`). **CIRISConstitution** should register it (NodeCore owns
  it), or NodeCore should drop the name.
* The relation's shape. NodeCore documents
  `topical_relation:{relation}:{target_key_id}` (`ingest.rs:107-108`); rc6
  declares `topical_relation:{kind}` plus the required trailing version segment.
  A third segment holding a key id is not in the family. In this code the
  relations live only in the payload's `topical_relations[]`
  (`ingest.rs:318`); the separate `scores` row per relation is described as
  "Phase 2B" and is not emitted. **CIRISNodeCore** and **CIRISConstitution**
  must agree the shape before Phase 2B emits it.
* The NodeCore quality dimensions (`encyclopedia:accuracy:{topic}` and the rest)
  carry no version segment, which rc6 requires of every scored dimension.

```yaml csd:states
populated: {tag: "proposed:state_library_article", renders: "the heading, provenance, body, then claims, citations, links and quality, each its own block"}
empty:     {tag: "proposed:state_library_quality__empty", renders: "'No one you trust has rated this article yet.' This is the quality read answering with no axes; the article itself is never 'empty'"}
loading:   {tag: "proposed:state_library_article__loading", renders: "the heading from the feed row while the body and quality reads are in flight; no quality numbers and no empty sentence"}
error:     {tag: "proposed:state_library_article__error", renders: "three distinct blocks, each with the node's words: the article record could not be read; the body could not be fetched (`state_library_body__error`, with 'No node you can reach holds these bytes' when the fetch answered not-found); the body's bytes do not match its digest (`state_library_body__unreadable`, nothing shown, CSD-007's rule). None is drawn as empty"}
```

## 3. Contracts (who)

**There is no route for the article record or its quality.** CIRISServer does
not link NodeCore (`Cargo.toml:128`: "folds in at Server 1.0").

| value | endpoint | owner | state |
|---|---|---|---|
| the article record (source fields, relations, citations) | no route; NodeCore library function `build_encyclopedia_payload` (`src/ingest.rs:284`) writes it and `ingest_encyclopedia_article` (`:3276`) stores it; Python exports `contribution` (`python.rs:249`) as a generic read by id | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing.** The encyclopedia builder is not exported to Python |
| the body bytes | `POST /v1/federation/content/{content_id}` | CIRISServer (`src/federation_surface.rs:545` on `origin/main`), the route CSD-047 calls | **live**, but only for someone who already holds the digest; nothing on the client lists articles to give it one |
| revisions | no route; structural `supersedes` (`SCHEMA.md:1254`) | CIRISNodeCore | **missing**: no compose walks the chain |
| claims and recants | no route; structural `recants` (`SCHEMA.md:1256`) | CIRISNodeCore | **missing**: no compose pairs a claim with its recant |
| quality | no route; `compose_article_quality` (`src/compose.rs:1358`), Python `article_quality` (`python.rs:323`) | CIRISNodeCore | **missing.** Output `{article_key_id, sub_kind, quality{axis: {weighted_mean, attester_count, buckets}}, computed_at}` (`compose.rs:1313-1335`) |
| rate an article | no route; a `scores` row on `encyclopedia:*` | CIRISNodeCore / CIRISServer | **missing** |
| dispute a quality reading | spec only: `dispute_quality` (`FSD/MEDIA_SHARING.md:1275`) | CIRISNodeCore | **missing** |

## 4. Flow (how)

Every tag is `proposed:`. Open an article from the Global feed:

```yaml
expect:
  state: populated
  visible: ["proposed:txt_library_title", "proposed:txt_library_provenance", "proposed:doc_library_body"]
  each: {of: "proposed:stat_library_quality.*", matches: "^(—|[01](\\.[0-9]+)? · average of [0-9]+ ratings?)$"}
```

## 5. QA plan

Not drivable. When a route exists: one article with two ratings on `accuracy`,
one of them recanted. Assert the claim row is struck with the reason beside it,
and assert what the average shows: if NodeCore still counts the recanted rating,
the footnote must be present.

## 6. Status and asks

Sketched. Open question: whether the card should hide quality entirely until
NodeCore filters recanted rows, rather than footnote it. The card as written
footnotes it, because hiding a number the node sends is its own kind of claim.

Asks, one repo each:

* **CIRISNodeCore**: filter withdrawn and recanted `scores` rows in
  `compose_article_quality`; rename `weighted_mean` or weight it; state the score
  range; add a title to `EncyclopediaArticleSource` or say the slug is the title;
  a compose that returns an article with its revision chain and its claims paired
  with their recants; export `build_encyclopedia_payload` to Python if the agent
  is meant to write articles.
* **CIRISServer**: routes for the article record, its quality and its revisions
  once NodeCore folds in, each with a typed not-found distinct from a failed read.
* **CIRISConstitution**: register `cites_source:{kind}` or refuse it; settle the
  shape of `topical_relation` (two segments plus version, or three).
