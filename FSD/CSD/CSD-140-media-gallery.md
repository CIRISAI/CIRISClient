# CSD-140 — Media gallery (images, audio, video, film and 3D shared at a circle, each with its description and AI disclosure)

**CSD**: CSD-140 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-007 (Files: the drive plane and the node's render policy, which this card obeys), CSD-047 (fetch by digest), CSD-134 (Calls: live streams, not gallery items), CSD-066 (Child safety), CSD-065 (Moderation), `FSD/MEDIA_EDGE.md` (the renderer stack)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0` (`src/ingest.rs`, `FSD/MEDIA_SHARING.md`); CIRISServer `origin/main` @ `7af5d4eb` (2026-11-09)
**Flow**: unwritten. No route serves media items and no screen exists

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can browse the media shared at a circle and, for every item, read
what it shows and whether its uploader says it was made by AI, before any pixel
or sound is played.**

Falsifiable:

* **Nothing is drawn bare (law L10).** A media tile is drawn only with a
  description and an AI-disclosure value. The description is `alt_text` for an
  image, `transcript` for audio, `captions` for video and film. NodeCore
  requires it only at `community` and wider (`src/ingest.rs:1337-1343`, and the
  same rule at `:1406-1411`, `:1479-1484`; film at any scope, `:1554-1556`), so an
  item shared at `self` or `family` may have none. Such an item is drawn as a
  closed tile, "Not shown: no description", with its kind, size and date, and
  the owner is offered "Add a description". A 3D model has **no description
  field at all** (`Model3dSource`, `ingest.rs:1254-1290`), so no 3D model can be
  drawn until NodeCore adds one.
* **The AI disclosure is the uploader's word, and says so.** Every media source
  carries `is_ai_generated: bool` and, when true, `generated_by`; NodeCore
  refuses an AI item at `community` or wider without it (`ingest.rs:1307-1308`,
  EU AI Act Art. 50 per the field's doc). The card renders `true` as "Made with
  AI (model-name), says the uploader" and `false` as "Not marked as AI-made by
  the uploader", never "Not AI".
* **The node decides what is rendered, not this card.** CSD-007's policy rows
  hold: a Tier A image, audio or video is rendered only from a rendition the
  node produced, and the node says `renditions: false` today (CSD-007's policy table,
  CIRISServer#614). So until that changes every tile is closed, with its
  description shown as text. That is the designed state, not a failure.
* A content warning or rating blurs the tile until the reader opens it, and the
  reason is named (`cw_class`, `content_rating`).

**How this relates to CSD-007 and CSD-134.** CSD-007 lists files in a drive and
owns the render policy, the digest check and Save a copy; a gallery item's bytes
are fetched and checked the same way, so this card reuses those rules and does
not restate them. What CSD-007 does not have is the item's description, rating,
class and AI disclosure, which live in NodeCore's media payload. CSD-134 is a
live call. A saved call is a kept stream (CSD-134 §1) and is not a gallery item
unless a repo decides a kept stream is a `video` Contribution, which none has.

## 2. Surface (what)

No `csd:surface` block yet (CSD-108's precedent). One grid per circle, under that
circle's Files tab beside CSD-007, filtered by kind.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: gallery
screen: Gallery
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:media_sub_kind
    use: display-only
    type: "enum[image,audio,video,film,model_3d]"
    example: "image"
    renders: "the tile's kind glyph and a filter chip per kind"
    tag: "proposed:media_gallery_item.<contribution_id>"
  - ceg: x_private:media_description
    use: display-only
    type: string
    example: "Two children planting a mango seedling in red soil"
    renders: "the description, always drawn as text on the tile. Missing: the closed tile 'Not shown: no description'"
    tag: "proposed:media_gallery_item__alt.<contribution_id>"
  - ceg: x_private:media_ai_generated
    use: display-only
    type: bool
    example: true
    renders: "'Made with AI (sdxl-1.0), says the uploader' when true, from `generated_by`; 'Not marked as AI-made by the uploader' when false. NodeCore names this `authenticity:ai_generated` (ingest.rs:1348); no `authenticity:*` family is in the rc6 registry"
    tag: "proposed:media_gallery_item__ai_disclosure.<contribution_id>"
  - ceg: "content_class:{class}"
    bind: {class: photograph}
    use: display-only
    type: string
    example: "photograph"
    renders: "a small class label (one of 35, ingest.rs:1019-1083). Required at community and wider (ingest.rs:1304-1305); absent below, and then not drawn"
    tag: "proposed:media_gallery_item__class.<contribution_id>"
  - ceg: "content_rating:{scheme}:{rating}"
    bind: {scheme: pegi, rating: "12"}
    use: display-only
    type: string
    example: "PEGI 12"
    renders: "each declared rating, scheme then rating, verbatim"
    tag: "proposed:txt_gallery_rating.<contribution_id>"
  - ceg: "cw_class:{class}"
    bind: {class: medical}
    use: display-only
    type: string
    example: "medical"
    renders: "a blurred tile with 'Content warning: medical. Tap to show.' The reveal is per item and is not remembered across sessions"
    tag: "proposed:media_gallery_item__blur.<contribution_id>"
  - ceg: x_private:media_dimensions
    use: display-only
    type: string
    example: "4032 × 3024 · 2.1 MB · 3 Nov 2026"
    renders: "pixel size (`width_px`, `height_px`), byte size, and `captured_at` when present; duration for audio and video"
    tag: "proposed:txt_gallery_item__gloss.<contribution_id>"
  - ceg: x_private:media_creator
    use: display-only
    type: string
    example: "key:a91e… (not the uploader)"
    renders: "'Made by <contact or short key>' only when `creator_key_id` is present and differs from the uploader"
    tag: "proposed:txt_gallery_creator.<contribution_id>"
  - ceg: "image:*"
    use: display-only
    type: unconfirmed
    example: "no axes named yet"
    renders: "per-medium quality, when any arrives (`image:*`, `audio:*`, `video:*`, `film:*`, `model_3d:*`). No axis is named in NodeCore and `compose_article_quality` returns nothing for media sub_kinds (compose.rs:1380-1388), so nothing is drawn today"
    tag: "proposed:stat_gallery_quality.<axis>"
```

**Registry findings.** `content_class:{class}`, `content_rating:{scheme}:{rating}`,
`cw_class:{class}` and the five medium families are registered (rc6, owned by
CIRISNodeCore). `authenticity:*`, which NodeCore's SCHEMA lists for AI disclosure
(`SCHEMA.md` §4.29, "Multimedia dimension families"), is not;
**CIRISConstitution** should register it, as NodeCore proposes. The payload
fields are `x_private:` for the reason CSD-135 §2 states.

```yaml csd:states
populated: {tag: "proposed:state_gallery_items", renders: "the grid; every tile closed until the node offers renditions, each with its description and disclosure as text"}
empty:     {tag: "proposed:state_gallery_items__empty", renders: "'No media shared at this circle yet.' Only on an answered empty list"}
loading:   {tag: "proposed:state_gallery_items__loading", renders: "tile frames with no description text and no empty sentence"}
error:     {tag: "proposed:state_gallery_items__error", renders: "'Could not read this circle's media', with the node's words. A tile whose bytes fail the digest is CSD-007's `file_unreadable` on that tile alone; the grid stays"}
```

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| the list of media items with their payloads | no route; NodeCore library functions `ingest_image` / `ingest_audio` / `ingest_video` / `ingest_film` / `ingest_model_3d` (`src/ingest.rs:3452`, `:3475`, `:3498`, `:3524`, `:3548`) write them; the builders are exported to Python (`build_image_payload`, `python.rs:684`, and its siblings); no compose lists them except as rows in CSD-135's feeds | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing** |
| the node's render policy | `GET /v1/media/policy` | CIRISServer (`src/drive.rs:4077` on `origin/main`), called by CSD-007 | **live** |
| the bytes, by digest | `POST /v1/federation/content/{content_id}` | CIRISServer (`src/federation_surface.rs:545` on `origin/main`), called by CSD-047 | **live** for a caller holding the digest |
| a rendition the client may draw | none: the node reports `renditions: false` | CIRISServer#614 | **missing**; until it lands no tile opens |
| upload with description and disclosure | `POST /v1/files` uploads bytes (CSD-007) but carries no `alt_text`, rating, class or `is_ai_generated` | CIRISServer | **missing**: no route takes the media payload |
| report, block, mute, dispute, escalate | spec only (`FSD/MEDIA_SHARING.md:1265-1280`) | CIRISNodeCore | **missing**; CSD-065 holds the report flow that exists |

## 4. Flow (how)

Every tag is `proposed:`. Open the gallery at a community with one described
image and one family-scope image with no description:

```yaml
expect:
  state: populated
  each: {of: "proposed:media_gallery_item__ai_disclosure.*", matches: "^(Made with AI.*says the uploader|Not marked as AI-made by the uploader)$"}
  count: {of: "proposed:media_gallery_item__alt.*", min: 1}
```

## 5. QA plan

Not drivable. When a route exists, assert L10 directly: for every
`media_gallery_item.<id>` there is an `__alt.<id>` and an `__ai_disclosure.<id>`,
or the tile is the closed "Not shown" tile. Assert no 3D model is ever drawn
open.

## 6. Status and asks

Sketched.

Asks, one repo each:

* **CIRISNodeCore**: a description field for `model_3d`; require a description at
  every scope, or say which scopes may omit it and why; enforce the "at least one
  rating at federation scope" its image doc states (`ingest.rs:1091-1092`), which
  no builder checks; a compose that lists media items with their payload fields;
  name the per-medium quality axes.
* **CIRISServer**: an upload route that carries the media payload (description,
  rating, class, AI disclosure) and refuses a federation-scope item without
  them, as NodeCore's builders do; renditions (CIRISServer#614).
* **CIRISConstitution**: register `authenticity:*`.
