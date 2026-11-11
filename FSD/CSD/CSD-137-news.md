# CSD-137 — News (headline, publisher, byline, how long it holds, and the publisher's standing)

**CSD**: CSD-137 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-136 (Library: body, claims, recants, citations and quality are drawn the same way and are not repeated here), CSD-135 (Feeds)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0`; CIRISServer `origin/main` @ `7af5d4eb` (2026-11-09)
**Flow**: unwritten. No route and no screen

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person reading a news article sees who published it and who wrote it, when,
until when the publisher says it holds, whether it has been corrected, and how the
people this node trusts rate both the article and its publisher.**

Falsifiable:

* An article past its `valid_until` is still shown, under a banner "Past the
  date its publisher gave (12 Nov 2026)". It is never silently hidden; the
  field is a "staleness contract" consumers "downweight or filter"
  (`src/ingest.rs:167-171`), and dropping it unseen would be the client deciding.
* A corrected article links to its correction. NodeCore's correction is two rows:
  `recants` on the false claim and `topical_relation:corrects` on the new article
  pointing at the old (`SCHEMA.md` §4.29, the `news_article` bullet). The card
  draws the old article with "Corrected by …" and the new with "Corrects …".
* The publisher's standing is shown as what it is: a separate rating of the
  publisher (`news:source_quality:{publisher}`, `SCHEMA.md:1263`), never folded
  into the article's own numbers. NodeCore has not built publisher-weighted
  article quality ("not yet done", `FSD/CEWP.md:683`), so the card does not weight
  anything by publisher either.
* A publisher is a key, not an entity this node describes. There is no publisher
  record: only `publisher` (a tag such as `reuters`), `publisher_key_id`, and the
  source-quality ratings (`ingest.rs:134-140`). The card shows the tag and the
  key, and claims nothing about the organisation.

## 2. Surface (what)

No `csd:surface` block yet (CSD-108's precedent). A detail screen opened from a
feed row. The body, claims, recants, citations, links and per-article quality
are CSD-136's blocks with `news:*` in place of `encyclopedia:*`; this card adds
the fields below.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: null
flow_only: true
screen: NewsArticle
entry: "a news row in CSD-135's feeds, or a corrects / references link"
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:news_headline
    use: display-only
    type: string
    example: "Climate summit opens in Nairobi"
    renders: "the heading (`headline`, required, ingest.rs:155)"
    tag: "proposed:txt_news_headline"
  - ceg: x_private:news_publisher
    use: display-only
    type: string
    example: "reuters · publisher:reuters"
    renders: "'Published by reuters' with the publisher key id under it in monospace. The tag is the publisher's own string, shown as sent"
    tag: "proposed:txt_news_publisher"
  - ceg: x_private:news_byline
    use: display-only
    type: string
    example: "Jane Okafor"
    renders: "'By Jane Okafor', and a verified-key mark only when `byline_key_id` is present. No byline: 'No byline' (wire stories are often unbylined, ingest.rs:143-145), never an empty line"
    tag: "proposed:txt_news_byline"
  - ceg: x_private:news_section
    use: display-only
    type: string
    example: "world"
    renders: "a chip with the publisher's section, when sent"
    tag: "proposed:chip_news_section"
  - ceg: x_private:news_published_at
    use: display-only
    type: timestamp
    example: "2026-11-09T06:30:00Z"
    renders: "'9 Nov 2026, 06:30' in the reader's time zone"
    tag: "proposed:txt_news_published"
  - ceg: x_private:news_valid_until
    use: display-only
    type: timestamp
    example: "2026-11-12T00:00:00Z"
    renders: "'Holds until 12 Nov 2026'. Past it, the banner above the headline. Absent: nothing drawn"
    tag: "proposed:txt_news_valid_until"
  - ceg: "topical_relation:{kind}"
    bind: {kind: corrects}
    use: display-only
    type: unconfirmed
    example: "corrects → news:article:reuters:2026-11-08:summit-dates"
    renders: "on a correction: 'Corrects: <headline of the old article>'. On the corrected article: 'Corrected by: <headline>'. The reverse link needs a read that finds who points at an article; nothing serves that"
    tag: "proposed:row_news_correction.<target_key_id>"
  - ceg: "news:*"
    use: display-only
    type: unconfirmed
    example: "source_quality 0.74 (31 ratings)"
    renders: "'Publisher standing: average of 31 ratings', drawn apart from the article's own quality, with a line 'This rates reuters, not this article.' No ratings: a dash"
    tag: "proposed:stat_news_publisher_standing"
  - ceg: "news:*"
    use: display-only
    type: unconfirmed
    example: "accuracy 0.9 (3) · bias political_spectrum 0.1 (5) · freshness 0.6 (2)"
    renders: "one Metric per axis, as CSD-136 draws them: accuracy (with topic buckets), bias (with its axis named, never a single good/bad colour), freshness, eyewitness_distance (compose.rs:1349-1354)"
    tag: "proposed:stat_news_quality.<axis>"
```

`news:*` and `topical_relation:{kind}` are registered (rc6, owned by
CIRISNodeCore). The payload fields are `x_private:` because the registry has no
name for a Contribution payload field (CSD-135 §2 states that finding once). The
version-segment and `topical_relation` shape findings are CSD-136's and apply
here unchanged.

```yaml csd:states
populated: {tag: "proposed:state_news_article", renders: "headline, publisher, byline, dates, then CSD-136's body and quality blocks, then the publisher's standing"}
empty:     {tag: "proposed:state_news_standing__empty", renders: "'No one you trust has rated this publisher yet.' The article itself is never 'empty'"}
loading:   {tag: "proposed:state_news_article__loading", renders: "the headline from the feed row while the record and the two quality reads are in flight"}
error:     {tag: "proposed:state_news_article__error", renders: "'This node could not read this article', with its words; the publisher-standing read failing on its own is `state_news_standing__error` ('Could not read the publisher's standing') and leaves the article drawn"}
```

## 3. Contracts (who)

**There is no route.** CIRISServer does not link NodeCore (`Cargo.toml:128`).

| value | endpoint | owner | state |
|---|---|---|---|
| the article record | no route; NodeCore library function `build_news_payload` (`src/ingest.rs:337`), `ingest_news_article` (`:3300`) | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing.** Not exported to Python |
| the body | `POST /v1/federation/content/{content_id}` | CIRISServer (`src/federation_surface.rs:545` on `origin/main`) | **live** for a caller holding the digest (CSD-047) |
| per-article quality | no route; `compose_article_quality` with `sub_kind: news_article` (`compose.rs:1358`) | CIRISNodeCore | **missing** |
| publisher standing | no route and **no function**: `compose_article_quality` reads rows targeting an article; nothing composes `news:source_quality:{publisher}` rows about a publisher | CIRISNodeCore (`FSD/CEWP.md:683`, "not yet done") | **missing** |
| "corrected by" (reverse link) | no route and no function: no compose finds the articles whose `corrects` points here | CIRISNodeCore | **missing** |

## 4. Flow (how)

Every tag is `proposed:`. Open an unbylined wire story past its validity:

```yaml
expect:
  state: populated
  visible: ["proposed:txt_news_headline", "proposed:txt_news_publisher", "proposed:txt_news_valid_until"]
  matches: {"proposed:txt_news_byline": "^No byline$"}
```

## 5. QA plan

Not drivable. When a route exists: one article with `valid_until` in the past,
one with a correction; assert the banner, assert both directions of the
correction link, assert the publisher standing never appears inside the
article's quality block.

## 6. Status and asks

Sketched.

Asks, one repo each:

* **CIRISNodeCore**: a compose for a publisher's standing (rows on
  `news:source_quality:{publisher}` about `publisher_key_id`), separate from
  article quality; a read of the articles that `corrects` a given article.
* **CIRISServer**: the news record, article quality and publisher standing as
  routes at Server 1.0, each with not-found distinct from a failed read.
