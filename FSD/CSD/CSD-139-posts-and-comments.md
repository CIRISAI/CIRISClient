# CSD-139 — Posts and comments (a blog post, the comments on it, and the replies to those)

**CSD**: CSD-139 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the owner's request (2026-11-10) to fill the card set with the NodeCore and Registry screens
**Reads with**: CSD-135 (Feeds), CSD-136 (Library: body, quality and recants are drawn the same way), CSD-091 and CSD-110 (chats: a different object, §1), CSD-065 (Moderation)
**Sources read**: CIRISNodeCore `feat-scale-model-v0.7-fountain` @ `93b94e0`; CIRISServer `origin/main` @ `7af5d4eb` (2026-11-09)
**Flow**: unwritten. No route and no screen

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

## 1. Mission (why)

**A person can read a post, see who wrote it and where it was first published,
read the comments on it as a thread, and tell a comment from a reply.**

Falsifiable:

* A comment is drawn under the post it `comments_on`, and a reply under the
  comment it `replies_to` (`src/ingest.rs:814-816`, `:227-228`). A comment
  whose post this node does not hold is still drawn, under "On a post not on this
  node", never dropped.
* A post shows where it came from. NodeCore's posts and comments are
  **imported**: a blog post is "imported from Medium / Substack / personal blogs /
  Tumblr / LiveJournal" (`ingest.rs:809-810`) with a required `platform`,
  `blog_id`, `author_handle` and `post_title` (`ingest.rs:1768-1778`), and a
  comment is a `chat_message` "imported from Discord / Slack / Twitter /
  iMessage / SMS / XMPP / IRC" (`ingest.rs:737-739`). The card says "First
  published on substack by @handle", and it does not present an imported post
  as written here.
* An author handle is the platform's string. Only `author_key_id` (optional,
  `BlogPostSource`) ties it to a CIRIS key, and the card marks a post as from a
  known key only when that field is present.

**These are not the client's chats.** CSD-091 and CSD-110 are CIRISServer room
messages, drawn as `chat:*` rows (CSD-091 §2). NodeCore's
`chat_message` is a record of a message from another platform. They share a
family prefix (`chat:*`, registry rc6) and a word, and nothing else. A person
cannot write a native comment here today: no kind is defined for a comment
authored on CIRIS, and whether that is a `chat_message` with a new platform tag
or a room message is undecided (§6).

## 2. Surface (what)

No `csd:surface` block yet (CSD-108's precedent). A detail screen opened from a
feed row.

```yaml
# csd:surface once the screen is declared in CIRISApp.kt
surface: null
flow_only: true
screen: Post
entry: "a post row in CSD-135's feeds"
```

```yaml csd:shows
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
fields:
  - ceg: x_private:post_title
    use: display-only
    type: string
    example: "On coherence"
    renders: "the heading (`post_title`, required)"
    tag: "proposed:txt_posts_title"
  - ceg: x_private:post_origin
    use: display-only
    type: string
    example: "substack · @ericmoore · https://ericmoore.substack.com/p/on-coherence"
    renders: "'First published on substack by @ericmoore, 15 May 2026', from `platform`, `author_handle`, `published_at`, with the canonical `post_url` shown as text and opened only by an explicit tap that says it leaves the app"
    tag: "proposed:txt_posts_origin"
  - ceg: x_private:post_body
    use: display-only
    type: string
    example: "Coherence is not consensus…"
    renders: "the body, fetched by `content_sha256` and hashed before it is shown, as CSD-136 draws a body"
    tag: "proposed:doc_posts_body"
  - ceg: x_private:post_tags
    use: display-only
    type: "list[string]"
    example: ["philosophy", "ciris"]
    renders: "the source platform's tags as chips, labelled as the platform's"
    tag: "proposed:chip_posts_tag.<tag>"
  - ceg: "chat:*"
    use: display-only
    type: unconfirmed
    example: "@reader1 on substack-comments, 16 May: 'Agreed, though…'"
    renders: "one Transcript row per comment, oldest first: handle, platform, time (`sender_handle`, `platform`, `sent_at`) and the body. A reply is indented under its parent"
    tag: "proposed:msg_posts_comment.<contribution_id>"
  - ceg: "topical_relation:{kind}"
    bind: {kind: comments_on}
    use: display-only
    type: unconfirmed
    example: "comments_on → blog:substack:@ericmoore:on-coherence"
    renders: "places a comment under its post. Not drawn as text"
    tag: "proposed:msg_posts_comment.<contribution_id>"
  - ceg: "topical_relation:{kind}"
    bind: {kind: replies_to}
    use: display-only
    type: unconfirmed
    example: "replies_to → chat:substack-comments:on-coherence:c-118"
    renders: "places a reply under its comment, with 'Replying to @handle'. A parent this node does not hold: 'Replying to a comment not on this node'"
    tag: "proposed:txt_posts_reply__gloss.<contribution_id>"
  - ceg: "blog:*"
    use: display-only
    type: unconfirmed
    example: "accuracy 0.7 (4 ratings)"
    renders: "per-axis quality as CSD-136 draws it. `compose_article_quality` walks `blog:*` for `blog_post` (compose.rs:1384), but no `blog:*` axis is named anywhere in NodeCore, so the card names none in advance and shows whatever axes arrive"
    tag: "proposed:stat_posts_quality.<axis>"
```

`blog:*`, `chat:*` and `topical_relation:{kind}` are registered (rc6, owned by
CIRISNodeCore); the `topical_relation` shape finding is CSD-136's. Payload fields
are `x_private:` for the reason CSD-135 §2 states.

```yaml csd:states
populated: {tag: "proposed:state_posts_post", renders: "title, origin, body, tags, then the comment thread"}
empty:     {tag: "proposed:state_posts_comments__empty", renders: "'No comments on this node.' Only when the comments read answered with none; the post itself is never 'empty'"}
loading:   {tag: "proposed:state_posts_post__loading", renders: "the title from the feed row while the post and its comments load"}
error:     {tag: "proposed:state_posts_post__error", renders: "'Could not read this post', with the node's words; a failed comments read is `state_posts_comments__error` ('Could not read the comments') under a drawn post"}
```

## 3. Contracts (who)

**There is no route.** CIRISServer does not link NodeCore (`Cargo.toml:128`).

| value | endpoint | owner | state |
|---|---|---|---|
| the post record | no route; NodeCore library function `build_blog_payload` (`src/ingest.rs:1756`), `ingest_blog_post` (`:3401`) | CIRISNodeCore; CIRISServer folds NodeCore at Server 1.0 | **missing.** Not exported to Python |
| a comment or reply | no route; `build_chat_payload` (`ingest.rs:1684`), `ingest_chat_message` (`:3377`) | same | **missing** |
| the thread (comments on a post, replies to a comment) | no route and **no function**: nothing composes the Contributions whose relations point at a given key | CIRISNodeCore | **missing** |
| the body | `POST /v1/federation/content/{content_id}` | CIRISServer (`src/federation_surface.rs:545` on `origin/main`) | **live** for a caller holding the digest |
| quality | no route; `compose_article_quality` with `sub_kind: blog_post` (`compose.rs:1358`) | CIRISNodeCore | **missing** |

## 4. Flow (how)

Every tag is `proposed:`. Open a post with two comments and one reply:

```yaml
expect:
  state: populated
  visible: ["proposed:txt_posts_title", "proposed:txt_posts_origin"]
  count: {of: "proposed:msg_posts_comment.*", eq: 3}
```

## 5. QA plan

Not drivable. When a route exists, also: a comment whose post is absent is drawn
under "On a post not on this node"; a post with no `author_key_id` shows no
known-key mark.

## 6. Status and asks

Sketched. Open question: whether a person writes posts and comments here at all,
or only reads imported ones. If they write, the client needs a kind for a native
comment, and a room message (CSD-110) may already be it.

Asks, one repo each:

* **CIRISNodeCore**: a compose that returns a post with its comment thread
  (Contributions whose `topical_relations` name it, and replies to those); name
  the `blog:*` quality axes; decide whether a comment written on CIRIS is a
  `chat_message` with its own `platform` value or something else.
* **CIRISServer**: the post and its thread as routes at Server 1.0.
