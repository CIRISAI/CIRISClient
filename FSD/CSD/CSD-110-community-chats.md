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
registry_sha256: f666f334db6b5e82dd7f75e6cbe82c926d27dcd9bd81d208784527cbce651c37
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
  - ceg: x_private:room_open_by_id
    use: display-only
    type: string
    example: "chat:room:v1:allotment"
    renders: "resolved, never printed: a room of more than two is opened BY ITS ID — tapping the row opens CSD-091's chat on `GET/POST /v1/chat/{id}/messages` (CIRISServer#594). `POST /v1/chat` is never asked for it"
    tag: "community_chat_row_*"
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

**A room of more than two opens by its id — the limit this card stated is
gone** (the people review, CSD-091). It used to be listed with "cannot be opened
here yet" (`flag_community_chat_room_unopenable_*`), because
`UserChatViewModel.enter` always called `POST /v1/chat {key_id}`, the pair-room
door. The chat screen now enters a room by community id and reads
`GET /v1/chat/{id}/messages` directly (N-member rooms, CIRISServer#594); the
tap hands the room to it (`onOpenRoom`), and back returns to this tab. A pair
room still opens through its contact, and one whose contact is gone still says
"Not a contact any more" and does not open.

## 3. Contracts (who)

CIRISServer `origin/main` 046e1b39 (0.5.217), `src/communities.rs`; node URL
only, as CSD-102 §3 states and pins.

| value | route | handler | notes |
|---|---|---|---|
| the rooms | `GET /v1/communities` | `list_communities` :1418 | every room the caller is ACTIVE in, pair rooms included (`kind_of` :202); the tier filter is the client's, on the row's `tier` |
| the contact a pair room is with | `GET /v1/contacts` | `list_contacts`, `src/contacts_chat.rs` (CSD-005) | each contact carries the derived `chat_community_id`; the pair room whose id equals it is that contact's. Best effort: a failed contacts read leaves every pair room listed as "not a contact any more", which is the honest reading of "no grant this node can see" |
| opening a pair room | `POST /v1/chat` `{key_id}` | `contacts_chat.rs::start_chat` | not called here — CSD-091's `UserChatViewModel.enter` calls it on the chat screen this row opens |
| opening a room of more than two | `GET /v1/chat/{id}/messages`, `POST /v1/chat/{id}/messages` | `contacts_chat.rs` (CIRISServer#594) | not called here — the row hands the room's id to CSD-091's chat screen, which reads and writes the room by id (the people review) |
| a pair room still joining (0.5.218) | `GET /v1/communities` lists the rooms the caller is ACTIVE in | CIRISServer (`src/communities.rs::list_communities`) | **by the route, not listed here**: since 0.5.218 a pair room opens by invitation (CIRISServer#706), and until both people are seated the invitee is in no room, so the Chats list cannot show an invitation; the opener's one-member room is listed only once its record is held. An invitation to talk is shown on the contact's People row (CSD-005) and the waiting room in the chat itself (CSD-091 `chat_pair_waiting`) |

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
3. A room of more than two opens by its id, never through `POST /v1/chat`
   (the people review's `UserChatViewModelTest` pins the chat side).

**Not tested here.** The transcript — CSD-091's. Whether the fold is right —
the node's.
