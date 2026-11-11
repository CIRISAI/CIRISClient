# CSD-135 — Feeds (what this node holds, by circle: mine, my communities', everyone's)

**CSD**: CSD-135 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-136 (Library), CSD-137 (News), CSD-139 (Posts), CSD-140 (Media gallery), CSD-141 (Events), CSD-143 (Drafts and sharing wider), CSD-007 (Files, the drive plane), CSD-065 (Moderation, where a report goes)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0`; CIRISServer `origin/main` @ `7af5d4eb` (2026-11-09)
**Flow**: unwritten. No route serves a feed and no screen exists, so every tag is `proposed:`

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can see the content items this node holds, sorted into three feeds by
how widely each was shared, and can tell an empty feed from a feed the node could
not read.** The three feeds are NodeCore's: Local (`cohort_scope: self`, authored
by the owner), Community (`family`, `community`, `affiliations`) and Global
(`species`, `planet`, `federation`) (`src/compose.rs:1144`, `:1181`, `:1195`).

Falsifiable:

* An item appears in exactly one feed, the one its `cohort_scope` names. The
  Local feed holds only the owner's own items (`compose.rs:1144`, the
  `author_id != owner_key_id` filter).
* A feed the node could not compose is drawn as an error with the node's words,
  never as "Nothing here yet".
* The feed claims no ranking. NodeCore composes in the order the rows arrive and
  scores nothing (`compose.rs:1206-1250`); the card sorts by nothing it was not
  sent and says "in the order the node holds them".

**What this card does not do: trust depth.** The brief asked for a reader-set
depth of 0 to 3. NodeCore's depth is an **admission** setting, not a feed filter:
`src/trust_depth.rs:8-14` calls it "operator-side local config", applied "when
admitting inbound content", with defaults client 0, proxy 0, server 1. The feed
functions take no depth argument. On a node a person owns, the operator and the
reader are the same person, so the setting can be shown, but it belongs with the
node's settings and it changes what the node takes in, not what this card hides.
This card shows the current depth read-only, labelled as the node's admission
depth, and links to where it is set. Nothing serves it today.

## 2. Surface (what)

No `csd:surface` block yet: the screen does not exist, and the checker refuses a
screen `sealed class Screen` does not declare (CSD-108's precedent). The design is
one screen with three tabs. The proposed placement is the Files tab of each
circle, because every item is a content-addressed body (`content_sha256`) and
"Files holds files" (the Locked Spec, B3): Local under Just me › Files, Community
under Family and Neighbours › Files, Global under Everyone › Files. Placement is
an open question (§6).

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: feed
screen: Feed
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:feed_tier
    use: read
    type: "enum[local,community,global]"
    example: "community"
    renders: "three chips: Mine · My communities · Everyone. The chip names the feed the node composed (`tier` in the output, compose.rs:1131), never one the client inferred"
    tag: "proposed:chip_feed_tier.<tier>"
  - ceg: x_private:external_content_entry
    use: display-only
    type: string
    example: "Climate summit opens in Nairobi"
    renders: "one row per item: its label, its kind, its circle and its author. `display_label` is the source's headline, else its title, else its version tag (compose.rs:1269-1280). Blog posts (`post_title`), encyclopedia articles (no title field) and media carry none of those, so they arrive with no label and the row shows the item's `entity_key_id` in monospace, never a blank line"
    tag: "proposed:row_feed_entry.<contribution_id>"
  - ceg: x_private:external_content_sub_kind
    use: display-only
    type: "enum[encyclopedia_article,news_article,accord_data,local_data,chat_message,blog_post,image,audio,video,film,model_3d,event_listing]"
    example: "news_article"
    renders: "a kind chip on the row (Article · News · Accord · Draft · Message · Post · Image · Audio · Video · Film · 3D · Event). A kind this app does not know is shown verbatim. Tapping the row opens that kind's card (CSD-136, 137, 139, 140, 141)"
    tag: "proposed:chip_feed_entry_kind.<contribution_id>"
  - ceg: x_private:cohort_scope
    use: display-only
    type: "enum[self,family,community,affiliations,species,planet,biosphere,federation]"
    example: "community"
    renders: "the scope pill on the row, in the circle's words. `planet` is NodeCore's spelling; CC 2.5's closed set says `biosphere` (§6)"
    tag: "proposed:scope_feed_entry.<contribution_id>"
  - ceg: x_private:author_id
    use: display-only
    type: string
    example: "key:7f21c0…"
    renders: "the author's contact name where this node has one, else the short key id. Never a name the client made up"
    tag: "proposed:txt_feed_entry__gloss.<contribution_id>"
  - ceg: x_private:trust_depth
    use: display-only
    type: unconfirmed
    example: 1
    renders: "'This node admits content from people you trust and the people they trust (depth 1).' Read-only here, with a link to the node's settings. Depth is the node's admission setting (trust_depth.rs:8-14), not a feed filter"
    tag: "proposed:lens_feed_trust__depth"
  - ceg: x_private:feed_computed_at
    use: display-only
    type: timestamp
    example: "2026-11-10T09:12:00Z"
    renders: "'As of 09:12', from the output's `computed_at` (compose.rs:1133)"
    tag: "proposed:state_feed_entries__freshness"
```

**Every field here is `x_private:`, and that is a finding, not a choice.** They
are payload fields of the `external_content` Contribution (`SCHEMA.md` §4.29,
`:1050`) and of NodeCore's composed output, not dimension families, and the
registry has no way to name a payload field. Which repo should answer: the
**CIRISConstitution** registry, by deciding whether Contribution payload fields
get registered names or the card standard gets a second binding form.

```yaml csd:states
populated: {tag: "proposed:state_feed_entries", renders: "the rows of the chosen feed, with 'As of' above them"}
empty:     {tag: "proposed:state_feed_entries__empty", renders: "'Nothing shared at this circle yet.' Drawn only when the node answered with an empty `items` list"}
loading:   {tag: "proposed:state_feed_entries__loading", renders: "the StateBlock spinner and no sentence"}
error:     {tag: "proposed:state_feed_entries__error", renders: "'This node could not read its feed', with the node's detail. A node that does not serve feeds at all is `state_feed_entries__not_on_this_node`: 'This node does not serve shared content yet.' Neither is ever drawn as the empty sentence"}
```

## 3. Contracts (who)

**There is no route.** CIRISServer does not link NodeCore (`Cargo.toml:128`:
"ciris-node-core — folds in at Server 1.0"). No `/v1/` route on `origin/main`
serves a feed.

| value | endpoint | owner | state |
|---|---|---|---|
| Local feed | no route; NodeCore library function `compose_local_feed` (`src/compose.rs:1144`), also exported to Python as `local_feed` (`src/python.rs:340`) | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing** |
| Community feed | no route; `compose_community_feed` (`compose.rs:1181`), Python `community_feed` (`python.rs:359`) | same | **missing.** Its doc names a `cohort` filter (`compose.rs:1176-1178`), but `compose_feed_for_tier` reads only `sub_kind` (`compose.rs:1206-1250`), so the feed mixes every family, community and affiliation the node holds |
| Global feed | no route; `compose_global_feed` (`compose.rs:1195`), Python `global_feed` (`python.rs:370`) | same | **missing** |
| admission depth | no route; `effective_trust_set` / `admits_at_depth` (`src/trust_depth.rs`), Python `python.rs:558`, `:611`. These answer "would this source be admitted at depth N", not "what is this node's depth" | CIRISNodeCore (oracle); the setting itself is node config | **missing**: nothing reads the configured depth back |
| report, block, mute from a row | spec only: `FSD/MEDIA_SHARING.md` §11.3 (`:1265-1280`) lists `report`, `block_actor`, `mute_actor` per feed, `report_to_cohort_moderators` on Community, `submit_for_wa_review` on Global. No such function exists in `src/` | CIRISNodeCore spec; CSD-065 holds the report flow that exists | **missing** |

## 4. Flow (how)

Every tag is `proposed:`, so this is the shape a flow will take, not one that runs.
Open the feed on a node that serves it, choose My communities:

```yaml
expect:
  state: populated
  visible: ["proposed:chip_feed_tier.community", "proposed:state_feed_entries__freshness"]
  each: {of: "proposed:scope_feed_entry.*", one_of: [family, community, affiliations]}
```

On a node without the route:

```yaml
expect:
  visible: ["proposed:state_feed_entries__not_on_this_node"]
  absent:  ["proposed:state_feed_entries__empty"]
```

## 5. QA plan

Not drivable until a route exists. When it does: one node seeded with one item at
each of `self`, `community` and `federation`; assert each lands in exactly one
feed; assert a 500 from the feed route draws the error block and not the empty
sentence; assert a blog post row shows its `entity_key_id` and not a blank label.

## 6. Status and asks

Sketched. Open questions: where the feeds live in the circles (Files is proposed);
whether the reader should be offered a view-only depth filter on top of the
node's admission depth, which would need a new function.

Asks, one repo each:

* **CIRISServer**: serve the three feeds once NodeCore folds in (Server 1.0), with
  a typed refusal for "this node does not serve feeds" distinct from a failed read.
* **CIRISNodeCore**: implement the `cohort` filter `compose_community_feed`
  documents, or remove it from the doc; give `display_label` a value for blog
  posts (`post_title`), encyclopedia articles and media, or return the
  `entity_key_id` as the documented fallback the comment at `compose.rs:1273-1274`
  promises; accept `biosphere`, the CC 2.5 spelling, in `is_promotable_scope`
  (`src/ingest.rs:2843`) and the Global feed's scope set, since an item at
  `biosphere` is refused today and would fall out of every feed.
* **CIRISNodeCore**: a read of the node's configured admission depth, so a card
  can show it.
* **CIRISConstitution**: say how a card binds a Contribution payload field (every
  field on this card is `x_private:` for that reason).
