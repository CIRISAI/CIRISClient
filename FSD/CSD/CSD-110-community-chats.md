# CSD-110 — The rooms you talk in (Neighbours › Chats, Communities and Businesses › Chats)

**CSD**: CSD-110 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the route-coverage gate (#111): `CommunityChats` / `AffiliationsChats` called routes no CSD on that screen cited
**Covers**: `Screen.CommunityChats` and its sibling `Screen.AffiliationsChats` — one composable, `CommunityChatsScreen` (`ui/screens/CommunitiesScreens.kt`), over the tier's `CommunitiesViewModel`. Split out of CSD-103 §2.0, which described these screens but named only the roster in `csd:surface`; a chats screen is not a roster sibling.
**Reads with**: CSD-103 (who is in each room), CSD-102 (the community itself), CSD-091 (the chat a pair room opens)
**Flow**: none yet — every step it could take on one node is CSD-103's list-state steps under other tags; a pair room needs a second person

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**In Neighbours and in Communities and Businesses, the Chats tab lists the
rooms this person is in at that tier, as the node's fold has them, and a
two-person room opens the chat.** The Locked Spec gives every circle a Chats
tab, and CSD-091 §2 records that until now the non-self circles' Chats tabs
were empty while the conversation they are named for was reachable only from a
contact. Serves **Contextual Integrity**: the rows here are only rooms the
caller is active in, which is all `GET /v1/communities` serves (CC 2.1, a
roster is never globally enumerable).

**Pair rooms are Neighbours chats, on the wire's say-so.** A pair room comes
back with `kind: pair, tier: community` (`src/communities.rs::room_json` :1362;
`Tier::of` :150 reads an absent policy blob as `community`). CSD-091 warned
that which circle a pair room appears in would be "a client-side audience
judgment with no substrate backing"; the wire now says `community`, and
`community` folds to Neighbours (`ui/nav/CohortScope.kt`). A pair room is
never an affiliations chat.

## 2. Surface (what)

```yaml csd:surface
surface: community-chats
screen: CommunityChats
scopes: [community-chats, affiliations-chats]
```

`nav_map` derives `circle_local_community -> tab_chats` and
`circle_global_communities -> tab_chats`: each is its tab's only card, so the
chain ends on the tab.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:community_room_kind
    use: display-only
    type: "enum[pair,room]"
    example: "pair"
    renders: "'Two people' under a pair room, '{n} people' under a room of more than two. Rooms of more than two first, then pairs, each group by name"
    tag: "community_chat_row_*"
  - ceg: x_private:community_name
    use: display-only
    type: string
    example: "Allotment"
    renders: "a room's name; a pair room is titled by the contact it is with (alias, else the short key), because a pair room's record has no name of its own"
    tag: "community_chat_row_*"
  - ceg: x_private:pair_room_contact
    use: display-only
    type: string
    example: "wa-ann-7c31…"
    renders: "resolved, never printed: the contact whose derived `chat_community_id` IS the room's id. Tapping the row opens CSD-091's chat with that contact. A pair room with no such contact carries 'Not a contact any more. The room is still there and nothing can leave it.' and does not open"
    tag: "flag_community_chat_not_a_contact_*"
  - ceg: x_private:room_unopenable
    use: display-only
    type: bool
    example: true
    renders: "on every room of more than two: 'A room of more than two cannot be opened here yet; the chat screen opens a room only through a contact.' The row is listed and does not open"
    tag: "flag_community_chat_room_unopenable_*"
```

```yaml csd:states
populated: {tag: community_chats_list, renders: "one row per room at this tier, pair rooms included"}
empty:     {tag: community_chats_empty, renders: "No rooms here yet. A chat with a contact appears here once it is opened."}
loading:   {tag: community_chats_loading, renders: "the loading block, never the empty sentence"}
error:     {tag: community_chats_error, renders: "Could not read your communities. … This is not a report that you have none. — the node's reason id, localized"}
```

`community_chats_not_on_this_node` is the fifth: an id-less 404 is the route
not mounted ("This node doesn't serve communities yet. It needs CIRISServer
0.5.216 or later."), never "no rooms".

**Why a room of more than two does not open.** `UserChatViewModel.enter`
always calls `POST /v1/chat {key_id}` — the pair-room door (`src/contacts_chat.rs`)
— and there is no way to enter a room by its id. Opening an N-member room
would mean entering a room the client cannot address, so the row says so
instead of opening an empty transcript. The edit it needs is CSD-091's: enter
by community id, read `GET /v1/chat/{id}/messages` directly, and let back
return to Chats rather than to Contacts.

## 3. Contracts (who)

CIRISServer `origin/main` 046e1b39 (0.5.217), `src/communities.rs`; node URL
only, as CSD-102 §3 states and pins.

| value | route | handler | notes |
|---|---|---|---|
| the rooms | `GET /v1/communities` | `list_communities` :1418 | every room the caller is ACTIVE in, pair rooms included (`kind_of` :202); the tier filter is the client's, on the row's `tier` |
| the contact a pair room is with | `GET /v1/contacts` | `list_contacts`, `src/contacts_chat.rs` (CSD-005) | each contact carries the derived `chat_community_id`; the pair room whose id equals it is that contact's. Best effort: a failed contacts read leaves every pair room listed as "not a contact any more", which is the honest reading of "no grant this node can see" |
| opening a pair room | `POST /v1/chat` `{key_id}` | `contacts_chat.rs::start_chat` | not called here — CSD-091's `UserChatViewModel.enter` calls it on the chat screen this row opens |
| opening a room of more than two | — | — | no client door: `POST /v1/chat` is pair-only and nothing enters a room by id (§2). Listed, not opened |

## 4. Flow (how)

Land on `CommunityChats`.

```yaml
expect:
  visible: [screen_community_chats, btn_community_chats_refresh]
  absent:  [community_chats_not_on_this_node]
```

*Cannot assert on one node:* a pair row (`community_chat_row_*` with a
contact) and the chat it opens need a peered contact; a room of more than two
needs CSD-102's founding on a node with contacts.

## 5. QA plan

**Platforms.** All five; the chain ends on the tab.

**Acceptance — functional**
1. A pair room lists under Neighbours and never under Communities and
   Businesses (`CommunitiesLogicTest.chatsShowPairRoomsAtTheCommunityTierOnly`).
2. A pair room resolves to the contact whose `chat_community_id` is its id, and
   to nobody otherwise (`CommunitiesLogicTest.aPairRoomIsWithTheContactWhosePairIdItIs`).
3. A room of more than two is listed with its reason and has no click handler.

**Not tested here.** The transcript — CSD-091's. Whether the fold is right —
the node's.
