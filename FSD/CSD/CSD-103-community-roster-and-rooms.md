# CSD-103 — Communities and affiliations: who is in it (People) and the rooms (Chats)

**CSD**: CSD-103 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, B6 Neighbours / B7 Communities and Businesses
**Companion**: CSD-102 (the community itself, on the circle's Rules hub) — the same `CommunitiesViewModel` per tier.
**Flow**: `testing/flows/drafts/csd-103-community-roster.yaml` (floor `unreleased`)

```yaml csd:stage
stage: building
owner: CIRISClient
```

## 1. Mission (why)

**In Neighbours and in Communities and Businesses, a person can see who is in
each group they belong to — by the node's fold, as it is now — add a contact to
one, remove someone, and see which members joined late and cannot read yet; and
the Chats tab lists the rooms they talk in.** Serves **Contextual Integrity**:
CC 4.4.3.2.4 resolves a community's CURRENT member set, and CC 2.1 says a roster
is "producer- + self-queryable, NEVER globally enumerable" — so this card shows
only rooms the caller is active in, which is all `GET /v1/communities` serves.

This is the card CSD-043 §1 says the Locked Spec placed in Communities ›
People — *"the people in the affiliations I belong to"* — and says Users is not.
It is not a second Contacts either: a contact is a person you consented to
exchange rows with (CSD-005); a roster is who is in a group, and most of a
group's members need not be your contacts.

**What it is not: the group book.** Who admitted each member, since when, what
changed and what is pending is CIRISServer#650; the node serves the current
roster `{key_id, role, joined_at}` and two plane counts. The card says so
(`txt_community_group_book_unavailable`) rather than dressing a roster as a
history.

## 2. Surface (what)

```yaml csd:surface
surface: community-roster
screen: CommunityRoster
scopes: [community-roster, affiliations-roster]
```

`nav_map` derives `circle_local_community -> tab_people ->
nav_epistemic_community_roster` and `circle_global_communities -> tab_people ->
nav_epistemic_affiliations_roster`: People holds Contacts first in both circles,
so the chain ends on the row. Both render `CommunityRosterScreen`
(`ui/screens/CommunitiesScreens.kt`) over that tier's view model.

### 2.0 The rooms, in Chats — and why pair rooms are listed there

`community-chats` (Neighbours › Chats) and `affiliations-chats` (Communities and
Businesses › Chats) render `CommunityChatsScreen`. Their chains end on the tab,
`circle_local_community -> tab_chats`, because each is its tab's only card.

**Pair rooms are chats, and they are listed in Neighbours › Chats.** A pair room
comes back from `GET /v1/communities` with `kind: pair, tier: community`
(`room_json`, `communities.rs:1362`; `Tier::of` reads an absent policy blob as
`community`, :150). CSD-091 §2 recommended a Chats card listing the person's
rooms and warned that which circle a pair room appears in would be "a
client-side audience judgment with no substrate backing". It has backing now:
the wire says `community`, and `community` folds to Neighbours. A pair room is
never an affiliations chat. Tapping one opens the chat CSD-091 already draws
(`Screen.UserChat`, keyed by the contact whose `chat_community_id` IS the room's
id). A pair room whose contact is gone is listed with "Not a contact any more"
and does not open, because the chat screen enters a room only through a contact.

**A room of more than two opens by its id** (since the people review,
CSD-091). `GET/POST /v1/chat/{id}/messages` serve N-member rooms
(CIRISServer#594), so the chat screen enters such a room by the room's
community id and never asks `POST /v1/chat` (pair-only) for it. The row used to
carry "cannot be opened here yet" (`flag_community_chat_room_unopenable_*`);
that limit is gone. What remains is the pair room whose contact is gone: it
cannot be entered through a contact and says so.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:community_member_key_id
    use: display-only
    type: string
    example: "wa-ann-7c31…"
    renders: "one row per member of each room, titled by the contact's alias when this node has one and the short key otherwise, the key under it"
    tag: "row_community_member_*"
  - ceg: x_private:community_member_role
    use: display-only
    type: string
    example: "founder"
    renders: "a chip with the role word; founder in the brand tone"
    tag: "row_community_member_*"
  - ceg: x_private:community_member_joined_at
    use: display-only
    type: timestamp
    example: "2026-09-02T00:00:00Z"
    renders: "not printed. Read against the room's founded_at: a member who joined after founding carries 'Added after founding: cannot read yet' (CIRISPersist#907). An instant that does not parse is not 'late'"
    tag: "flag_community_member_late_*"
  - ceg: x_private:community_widened_member_reads
    use: display-only
    type: unconfirmed
    blocked_by: CIRISPersist#907
    example: false
    renders: "'Someone added after a room was founded is listed and sealed to, and cannot read its messages yet (CIRISPersist#907).' — always on the card, because the node lists and seals to a widened member that persist's read gate still refuses. #907 is fixed in persist v49; the server markers (the ignored `a_widened_member_reads_the_rooms_messages_cirispersist_907`, the module doc 'What a widened member cannot do yet') are still on `integ/0.5.218` and clear with CIRISServer#700, and this sentence goes when a release carries that"
    tag: txt_community_limit_widened_reads
  - ceg: x_private:community_group_book
    use: display-only
    type: unconfirmed
    blocked_by: CIRISServer#650
    example: "admitted by wa-me on 2026-09-02"
    renders: "'Who admitted each member, and what changed when, is not served yet (CIRISServer#650). This is who is in it now.'"
    tag: txt_community_group_book_unavailable
  - ceg: x_private:community_add_member
    use: emit
    type: string
    example: "wa-cy-11d0…"
    renders: "'Add someone' opens a key field and a chip per contact not already in the room; 'They must already be a contact. Under the room's rule this may need more signatures before it takes effect.'"
    tag: input_community_add_member
  - ceg: x_private:community_remove_member
    use: emit
    type: string
    example: "wa-bo-e2a4…"
    renders: "'Remove' on each row, behind a ConfirmSheet: which room · what changes (removed, key rotated, nothing new reaches them, what they had stays) · who signs (you, under the room's rule)"
    tag: "btn_community_member_remove_*"
  - ceg: x_private:membership_invitation
    use: emit
    type: string
    example: "wa-cy-19c2"
    renders: "on a node that carries invitations (≥ 0.5.218, detected by GET /v1/communities/{id}/invites answering) each room's 'Add someone' is 'Invite someone': a key or a contact chip, then the three-fact confirm (which room · they join only if they accept, within 14 days · you, under the room's rule) and POST /v1/communities/{id}/invites. The room's section then shows them under its members as 'Invited: waiting for them to accept · expires {date}' until they accept or decline (CSD-106). On an older node (a bare 404 on that route) the direct add stands"
    tag: "btn_community_add_member_open_*"
  - ceg: x_private:community_room_kind
    use: display-only
    type: "enum[pair,room]"
    example: "pair"
    renders: "Chats: 'Two people' for a pair room (opens the chat through its contact), '{n} people' for a room (opens the chat by the room's id, CSD-091)"
    tag: "community_chat_row_*"
```

```yaml csd:states
populated: {tag: community_roster_list, renders: "the column of per-room sections (`community_roster_section_*`, one card per room at this tier), each with its members as rows and 'Add someone'"}
empty:     {tag: community_roster_empty, renders: "You are not in any room at this tier yet. Found one from this circle's Rules."}
loading:   {tag: community_roster_loading, renders: "the loading block, never the empty sentence"}
error:     {tag: community_roster_error, renders: "Could not read your communities. … This is not a report that you have none. — the node's reason id, localized"}
```

`community_roster_not_on_this_node` is the fifth: an id-less 404, "This node
doesn't serve communities yet". Chats has the same five under
`community_chats_*` (`_list`, `_empty`, `_loading`, `_error`,
`_not_on_this_node`).

**A change the rule holds.** An add or a remove that one signature does not
meet comes back `community.quorum_pending`; the view model holds it and the
roster says "A roster change is waiting for signatures. Finish it from this
circle's Rules." — where CSD-102's pending card collects and assembles it. One
view model per tier, app-scoped, is what lets a change started here be finished
there.

## 3. Contracts (who)

CIRISServer `origin/main` 046e1b39 (0.5.217), `src/communities.rs`; node URL only
(CSD-102 §3 has the full table and the refusal shape).

| value | route | handler | notes |
|---|---|---|---|
| rosters | `GET /v1/communities` | `list_communities` :1418 | every room's `members` is the fold (record ∪ widenings − revocations) — "the roster is the FOLD", module doc; a removed member is already gone |
| one room, re-read after a change | `GET /v1/communities/{id}` | `read_community` :1497 | the shared view model re-reads the selected room after an add or remove applies (`loadDetail`); `community.not_found` for a room you are no longer in |
| names, and who can be added | `GET /v1/contacts` | `list_contacts`, `src/contacts_chat.rs` (CSD-005) | the add control offers a chip per contact not already in the room, and each member row is titled by the contact's alias when this node has one. A member need not be a contact; the roster is the node's, the names are a courtesy |
| add | `POST /v1/communities/{id}/members` `{key_id, role?}` | `add_member` :1524 → `direct_change` :1133 | target must be a contact (`community.not_a_contact`), not already in (`.already_member`); `founder_only` admits a founder OR an appointed `moderate` holder for a plain member (`tally` :752). **At 0.5.218 an alias for the invitation** (202 `{state: "invited"}`, `communities.rs::add_member` :1695), read as `CommunityChangeOutcome.Invited`, never an applied add. Called only on a node without the invites route (§3.1) |
| invite | `POST /v1/communities/{community_id}/invites` `{key_id, role?}` → 202 `{state: "invited", proposal_id, …, expires_at}` | `communities.rs::invite` :1725 (0.5.218) | one inviter: a founder under `founder_only`, any member otherwise; a contact grant is NOT required. Sent by `CommunitiesViewModel.invite` behind the `community_invite` confirm (CSD-106) |
| the invitations | `GET /v1/communities/{community_id}/invites` → `{community_id, invites: [{proposal_id, invitee_key_id, role, proposer_key_id, proposed_at, expires_at, state, reply_id}], seated_now}` | `list_invites` :1808 (0.5.218) | read per room section (`loadInvites`). A bare 404 is an older node: the room keeps the direct add. A founder's read seats an accepted invitee of a `founder_only` room |
| withdraw | `DELETE /v1/communities/{community_id}/invites/{proposal_id}` | `withdraw_invite` :1888 (0.5.218) | the proposer only (`membership.not_the_proposer`). Offered only where the row's `proposer_key_id` is the viewer's own key (below); with no key readable, on every pending row, and the node answers by id |
| who "you" are, for Withdraw | `viewer_key_id` beside `invites` (**0.5.219 addition**), else `GET /v1/setup/owned-nodes` → `owner` | CIRISServer | the list's own `viewer_key_id` is preferred; on 0.5.218, which does not send it, the view model reads `owned-nodes` once (`CommunitiesViewModel.myKeyId`), the same source the household roster uses (CSD-101) |
| remove | `DELETE /v1/communities/{id}/members/{key_id}` | `remove_member` :1556 | naming yourself is leaving (:1569); `community.last_founder` guards an orphaned room |
| a pair room | `kind: pair` on a list row | `kind_of` :202 | `community.pair_room_fixed` (409) for any roster change on it, :291 |
| widened reads | — | module doc, "What a widened member cannot do yet" | listed, shown and sealed to; refused by the message read gate (`chat.not_a_member`) until CIRISPersist#907. #907 is **fixed in persist v49**; the server markers (the module doc and the ignored `…_cirispersist_907` test, both still on `integ/0.5.218`) clear with CIRISServer#700 |

### 3.1 Nobody joins without their own consent (ruling of 2026-09-30)

The maintainer ruled on 2026-09-30 that **adding someone to a family or a
community requires their consent**, founding members included
(CIRISConstitution#133; persist's enforcement CIRISPersist#955, v52: one
inviter proposes, the invitee accepts or declines, the quorum stays on the
admitting record, expiry 30 days or less). **CIRISServer 0.5.218** ships it
(merged at `53d1ffb5`). On this card (CSD-106):

* **each room's add is "Invite someone"** on a node that answers
  `GET /v1/communities/{id}/invites`: the key field and contact chips as
  before (anyone already invited and unanswered is left out), then a
  three-fact confirm, then `POST …/invites`. The notice says "Invitation sent.
  They join only if they accept.", never "Done: add".
* **pending rows** under the room's members, never counted as members:
  pending (with "expires {date}", and Withdraw on your own), accepted,
  declined, expired.
* **an accepted invitee of a quorum room** is seated by the members through
  `…/changes/envelope {op: add}` → cosign → assemble (a co-signed widening,
  refused `membership.awaiting_acceptance` without the acceptance). This
  client does not call the community envelope route, so the row says "this app
  can't start that yet" instead of offering a control. Under `founder_only`
  the founder's node seats them on its own (the bridge, or the founder's read
  of the invitations).
* **an older node** (a bare 404 on the invites route) keeps "Add someone" and
  the direct add as written above. A confirmed invitation that meets a bare
  404 sends nothing in its place; a direct add answered 409
  `membership.consent_required` (the unreleased interim branch,
  CIRISServer#700) re-reads the invites route and offers Invite if served.

Unaffected: remove, role changes, re-adding someone already active (still
`community.already_member`), and pair rooms, whose invitation is the chat's
own (`POST /v1/chat`, CSD-091).

## 4. Flow (how)

The draft is `testing/flows/drafts/csd-103-community-roster.yaml`. Land on
`CommunityRoster`.

```yaml
expect:
  visible: [screen_community_roster, txt_community_limit_widened_reads, txt_community_group_book_unavailable]
```

With a room founded (CSD-102's flow founds one), its section shows its one
member and the add control:

```yaml
expect:
  state: populated
  count: {of: "row_community_member_*", min: 1}
```

*Cannot assert on one node:* an add (the target must be a contact), a remove, a
late member's flag, or a pair room in Chats — each needs a second person.

## 5. QA plan

**Platforms.** All five.

**Acceptance — functional**
1. The roster is the node's fold; nothing is inferred from Contacts except a
   display name.
2. A late member is flagged from `joined_at` > `founded_at`, and an unparseable
   instant is not flagged (`CommunitiesLogicTest.aMemberAddedAfterFoundingIsLate_andUnknownIsNotLate`).
3. A pair room is a Neighbours chat and never an affiliations one
   (`CommunitiesLogicTest.chatsShowPairRoomsAtTheCommunityTierOnly`).
4. Each tier is placed only in its own circle, and no placement is agent-only
   (`CommunitiesLogicTest.eachTierLivesInItsOwnCircle`).

**Not tested here.**
* Whether a removed member's future reads fail — the DEK rotation is the
  node's (CC 4.4.3.2.2), and a client fixture cannot observe it.
* Peer-authored roster rows: persist admits them on signature alone until
  CIRISPersist#908 (fixed in persist v49; CIRISServer#700 records it in the
  server FSD), so a row another node wrote can appear here without the
  room's rule having been checked by this node. Not visible on the card.
* The transcript of a room of more than two — CSD-091's (it opens by id since
  the people review; this card only routes the tap).
