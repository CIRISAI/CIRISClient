# CSD-091 — User chat (one room, two people, every message an attestation)

**CSD**: CSD-091 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, leftovers
**Flow**: `testing/flows/drafts/csd-091-user-chat.yaml` (floor `>=0.5.225`)
**Reads with**: CSD-005 (People, where a pair room starts), CSD-103 (the Chats tab that lists rooms), CSD-006 (the receipt every row carries)

```yaml csd:stage
stage: testable
owner: CIRISClient
```

## 1. Mission (why)

**Two people talk, and the transcript says what each message IS — who signed it,
who wrote it, who can see it, and whether it still stands.** Serves
**Contextual Integrity**: the room is a two-member community, its messages are
`cohort_scope: community`, and the same `consent:replication:v1` grant that makes
someone a contact (CSD-005) is the edge the message travels on. Take the grant
away and the room still renders and nothing can leave it — which is why
`POST /v1/contacts` and `POST /v1/chat` are one story and the node refuses a chat
with a non-contact by name (`chat.not_a_contact`).

**A chat row is an ordinary CEG object and is drawn as one.** Every message goes
through the same `AttestationCard` + hamburger as every other attestation on this
client, with `AttKind.Message` (`ChatScreen.kt:422`). That is not a style
decision: a message has an `attestation_id`, an attester, an author, a cohort
scope and a folded status, and a bespoke bubble that hid those would be a second,
weaker object model for rows that are already attestations.

**Two axes, both stated, because collapsing them is the lie this surface is
most likely to tell.** The node signed the row; the human wrote it. Reading the
sender off `attesting_key_id` would label every message — yours and theirs —
with the box that carried it (`ChatScreen.kt:400-409`). And an agent is not its
owner: an own-agent row shares an owner with the reader and can carry
`mine = true` for that reason, so labelling it "You" would attribute a machine's
words to a person (`ChatScreen.kt:440-457`, CIRISClient#37).

## 2. Surface (what)

```yaml csd:surface
surface: null                     # NOT a NavSurface
flow_only: true                   # no sidebar row reaches it
screen: UserChat                  # `data class UserChat(...) : Screen()` — CIRISApp.kt:5773
scopes: [agent, family, local_community, global_communities, global_commons]
entry: "two doors. (1) Contacts' `onOpenChat` — People is on every circle, so a PAIR room is started from whichever circle the contact was read in. (2) A circle's Chats tab — `CommunityChatsScreen` (Neighbours) and its affiliations sibling (Communities and Businesses), CSD-103 — lists every room the node says you are in, pair rooms included; a pair row opens through its contact, a room of more than two opens BY ID"
exit: "back to WHERE THE ROOM WAS OPENED FROM — `Screen.UserChat.from`, Contacts or the Chats tab (CIRISApp.kt — the back map, the arm's `onBack`, and the shell's legacy map all read it); `screenToSurface` keeps THAT surface lit while the room is open — a chat is a leaf of the surface it was opened from, not a destination beside it. Until this review all three sites hard-coded `Screen.Contacts`, so a room opened from Chats went back to People"
```

**Placement, resolved.** The Locked Spec gives every circle a **Chats** tab,
and since PR #121 (CSD-103) Neighbours and Communities and Businesses have one
that lists rooms: `NavSurface.CommunityChats` / `AffiliationsChats` on
`Tab.CHATS` (`CirclesNav.kt`). A pair room is `tier: community` on the wire, so
it folds to Neighbours with the substrate's backing and not by a client guess;
Contacts keeps the "start one" move, because starting a pair room is
`POST /v1/chat` with a contact's key and that key lives on the People row. Just
me's Chats holds the agent conversation (`Interact`, `agentOnly`) and, on the
files branch, Notes; Family and Everyone have no room listing yet, which is a
fact about the wire (no family-tier room read; a pair room is never
`federation`), not a gap in this card.

**A room of more than two opens by its id, and never through `POST /v1/chat`.**
`start_chat` is "the two-member community for (owner, contact)" and refuses
anything else; `GET /v1/chat/{id}/messages` and `POST /v1/chat/{id}/messages`
serve an N-member room by id (CIRISServer#594: `list_room_messages`, and
`send_message`'s roster branch — on `main` 0.5.217 at `src/contacts_chat.rs:3592`
and `:2910`, on `integ/0.5.218` at `:4051` and `:3363`). Until this review the
client could not use that: `UserChatViewModel.enter` always opened through
`POST /v1/chat` with a contact key, so the Chats tab listed rooms of more than
two as "can't open here". `enterRoom(communityId, name, memberCount)` now enters
by id, and the Chats row opens it (`UserChatViewModelTest.aRoomOfMoreThanTwoIsEnteredByItsIdAndNeverThroughPostChat`).
The header's member count comes from the room row (`GET /v1/communities`),
because the transcript carries no roster and the only read that returns one is
the pair-only open. Creating a room, adding to it and leaving it are CSD-102's
routes, not this card's.

```yaml csd:shows
registry_sha256: 95665a2c49627257be3ff84d10287aa49ef5b3cd8b7c6ec048ba6e6224dea839
fields:
  - ceg: x_private:chat_message_body
    use: display-only
    type: string
    example: "did the node come back up?"
    renders: "one message row, oldest first, inside an AttestationCard badged MESSAGE. The tag carries the TEXT as well as the id (ChatScreen.kt:418), so /tree answers 'did the reply arrive, and is it the right one' rather than only 'a row exists' (CIRISClient#27)"
    tag: "chat_msg_*"
  - ceg: x_private:chat_draft
    use: emit
    type: string
    example: "on its way"
    renders: "the composer, disabled while a send is in flight. Its ceiling is measured in UTF-8 BYTES, not UTF-16 code units, because that is what the server counts — the two disagree on every non-ASCII character, so `length` would let ~6,000 emoji past the button and block a 16,000-character ASCII message the server would have taken"
    tag: input_chat_body
  - ceg: "consent:{kind}"
    bind: {kind: replication}
    use: display-only
    type: string
    example: "consent:replication:v1"
    renders: "not drawn on this screen — it is the PRECONDITION. Its absence is what the node says when a room cannot open: 'That person is not a contact yet… a chat whose messages cannot replicate to the other member is not a chat.' The grant itself is rendered in People's receipt (CSD-005); here it is visible only as its own refusal"
    tag: chat_refusal
  - ceg: x_private:cohort_scope
    use: display-only
    type: string
    example: "community"
    renders: "'{count} members · community scope' in the header, and 'type scores · scope community · status live' in the per-row details. Community is persist's tier for exactly this: an audience that is neither self (invisible) nor federation (public)"
    tag: "chat_msg_*"
  - ceg: x_private:attesting_key_id
    use: display-only
    type: string
    example: "node-4a19c2…"
    renders: "'Who sent it' on the card — the NODE that signed the row. Distinct from the author line above the body, and deliberately so"
    tag: "chat_msg_*"
  - ceg: x_private:author_key_id
    use: display-only
    type: string
    example: "eric-moore-v2…"
    renders: "'You' / 'Your agent' / 'An agent' / 'Identifying sender' / the truncated key — five presentations from one branch (`presentationOf`), enumerable and unit-testable without composing anything. 'Identifying sender' is neutral and is NOT a fallback to a person's name"
    tag: "chat_msg_*"
  - ceg: "trust:{job}:{version}"
    bind: {job: confers, version: v1}
    use: display-only
    type: "list[string]"
    example: ["moderate"]
    renders: "the '(moderator)' badge beside the author, its own tagged element. RESERVED — the client reads the duties the node resolved from the live `delegates_to` chain (`author_duties`), which is the same edge CSD-090 confers. Moderation is a duty, not a role: a plain member holding a scoped delegation moderates, and a roster founder with no live chain does not"
    tag: "chat_msg_duty_badge_*"
  - ceg: x_private:message_status
    use: display-only
    type: "enum[live,superseded,withdrawn,recanted]"
    example: "live"
    renders: "the card's standing. The vocabulary is persist's own, so this is a rename and not a reinterpretation; an unrecognised token maps to Active rather than being dropped, because a message whose status this client does not know is still a message that was said"
    tag: "chat_msg_*"
  - ceg: x_private:room_handshake_state
    use: display-only
    type: "enum[ready,awaiting_peer,join_requested,no_author_signer]"
    example: "awaiting_peer"
    renders: "'Waiting for them to join this chat. They will see your invitation when their device next syncs…' — a SYSTEM ENTRY placed in the transcript, in order, never a toast: it is part of the conversation's story and a toast would lose when it happened. The room is end-to-end encrypted and cannot carry a message until both halves of the MLS handshake have replicated"
    tag: "chat_note_*"
  - ceg: x_private:op_disabled_reason
    use: display-only
    type: string
    example: "mobile.chat_op_withdraw_unavailable"
    renders: "Withdraw / Recant / Supersede are SHOWN AND DISABLED with their reason, not hidden and not fake-enabled: each needs the author's own hybrid signature over an envelope naming the target, an owner bearer session is not that signature, and the node exposes no route that would produce one (Attestation.kt:224-236). The object really does have the verb"
    tag: "chat_msg_*"
  - ceg: x_private:draft_bytes
    use: display-only
    type: int
    example: 9012
    renders: "'9012 / 16384 bytes', appearing only past half the ceiling and turning error-coloured past it. Say the ceiling BEFORE the node refuses at it — the refusal is correct but arrives after the writing"
    tag: chat_length_counter
  - ceg: x_private:unopened_reason
    use: display-only
    type: "enum[not_fetched,not_granted,evicted,seal_mismatch,malformed_row,not_text,substrate]"
    example: "not_fetched"
    renders: "a row whose body did not open is NOT a row that says nothing. The token — `contacts_chat.rs::UNOPENED_REASONS`, edge's `UnopenedReason::kind()` verbatim (0.5.218, CIRISServer#602) — picks a sentence from the bundle (`mobile.chat_unopened_<token>`: 'This message hasn't reached this device yet.', 'This message was sealed before this device could read here…', …); a token this client does not know lands on `mobile.chat_unopened_other` WITH its detail. On 0.5.217 the field was edge's Display text ('not_fetched: <sentence>'), and the client reads that shape too (the token before the colon, the sentence after). The client branches on the token and never shows it"
    tag: "chat_msg_unopened_*"
  - ceg: x_private:unopened_detail
    use: display-only
    type: string
    example: "content is in the room's blob store and was not fetched by this read"
    renders: "the substrate's own sentence, under the token's sentence, for a person or a log — never for branching"
    tag: "chat_msg_unopened_*"
```

**The object this whole surface moves has no family in the pinned registry.** A
chat message is an `attestation_type: scores` row whose dimension is
`chat:message:v1` (CIRISServer `src/contacts_chat.rs:12`). `chat:*` is not one of
the registry's 116 prefixes, so every row above that describes the message itself
is `x_private:`. Under CC 4.5.1.3 an open-vocabulary `{kind}` is a legitimate
registration; the registry this CSD pins simply does not carry it, so a rendered
message cannot name its constitutional family. That is an ask (§3), not a
licence to invent one.

```yaml csd:states
populated: {tag: chat_transcript, renders: "the LazyColumn, oldest first, scrolled to the newest row — that is what a reader entering a conversation wants under their thumb"}
empty:     {tag: chat_empty, renders: "for a pair: 'No messages yet. What you send here is a signed attestation in this two-person community…'; for a room of more than two: '…to everyone on this room's roster — its members can read it, and nobody else.' Rendered ONLY when no refusal is up, so an unstarted conversation and a refused read are two observations"}
loading:   {tag: chat_loading, renders: "a bare progress affordance; `transcriptLoaded` tells 'empty' from 'not asked yet'"}
error:     {tag: chat_refusal, renders: "the node's typed `reason_id`, localized, in an error container above the transcript, with the node's English detail beneath it, and NO `chat_empty` under it. `chat.not_a_contact`, `chat.not_a_member`, `chat.message_too_large` and `chat.empty_message` each name a different next move and would otherwise all be red text"}
```

**`error` swallows a state that is not an error.** Sending into a room whose
handshake has not completed returns `503` with `reason_id = chat.state.awaiting_peer`
(CIRISServer `src/contacts_chat.rs:2986-2996`) — and the server's own
`converges_on_its_own()` says that state resolves with nobody doing anything.
The client paints it in the same errorContainer as `chat.not_a_contact`, which
needs the person to act. Worse, the same sentence is *also* sitting in the
transcript as a neutral system note, because `RoomHandshake::note` has three
consumers by design. A person who tries to send early sees one fact twice: once
as a failure, once as a note.

## 3. Contracts (who)

| value | endpoint | owner | state |
|---|---|---|---|
| open / resolve a PAIR room | `POST /v1/chat` at the node URL | CIRISServer (`src/contacts_chat.rs:3787`) | live — idempotent; returns BEFORE any write when the community exists, so for an open room this is a read, and it is the only read that answers with the authoritative roster the header needs. **Pair-only**: `start_chat` builds "the two-member community for (owner, contact)"; a room of more than two is never asked of it |
| enter a room of more than two | `GET /v1/chat/{community_id}/messages` BY ID, no open call | CIRISServer | live — `list_room_messages` (CIRISServer#594; `main` 0.5.217 `:3592`, `integ/0.5.218` `:4051`): persist's admission is the membership gate, and a non-member is refused `chat.not_a_member`. The client's `enterRoom` (this review). What CIRISServer#594 still lists as open — create/invite/revoke by chat routes, per-member reporting — is CSD-102's surface (`/v1/communities`), not a blocker here |
| the transcript | `GET /v1/chat/{community_id}/messages` at the node URL | CIRISServer (`src/contacts_chat.rs:3789`) | live — oldest first, structural composers already folded into each row's `status`; for a room, `handshake` beside it, never as a refusal |
| a row whose body did not open | `unopened_reason` (token) + `unopened_detail` on the transcript row | CIRISServer | **0.5.218** (CIRISServer#602): `UNOPENED_REASONS` at `src/contacts_chat.rs:3004-3012`, a test pins every edge arm lands in it. On 0.5.217 the same field carried edge's `Display` text; the client reads both (§2) |
| send | `POST /v1/chat/{community_id}/messages` at the node URL | CIRISServer (`src/contacts_chat.rs:3789`) | live — a `chat:message:v1` `scores` attestation, `attestation_upsert_local` + `attestation_promote(community)`; for a room, sealed to the fold under the room's DEK with no pair handshake gating it (`:3363` on `integ/0.5.218`) |
| the 16 KB ceiling | `MAX_MESSAGE_BYTES` (`src/contacts_chat.rs:146`) | CIRISServer | live — mirrored in the client at `ChatScreen.kt:72`, and it is a MIRROR: two constants, one number |
| the contact grant | `GET` / `POST /v1/contacts` | CIRISServer (`src/contacts_chat.rs:3784`) | live — CSD-005 |
| the room's handshake state | carried as a system entry with `message_id: chat.state.*` (`src/contacts_chat.rs:694-751`, `803-818`) | CIRISServer | live — and the client renders it correctly through `SystemNoteRow` + `chatEntryText`, whose fallback rules are id, then the server's English, never a blank line and never the raw key (CIRISClient#34) |
| **does this wait resolve by itself** | `converges_on_its_own` — present on `GET .../messages` (`:3742`) and on the contacts refusal (`:2063`), **absent from the send refusal** (`:2986-2996`) | CIRISServer | **missing on the one route that needs it** |
| edit / withdraw / recant a message | no route — it needs the author's own signature and the app holds no keys | CIRISServer | **missing by design**, and the UI says so per-op rather than hiding the verb |
| a CEG family for `chat:message:v1` | not in the pinned registry | CIRISConstitution / CIRISRegistry | **missing** — every message field above is `x_private:` for want of it |

**Wrong-host risk: found and closed.** Every route here is the NODE's, on
`:4243` — and until this review every one of them was called at `$baseUrl`
(`startChat`, `listChatMessages`, `sendChatMessage`), which the route gate
charged to the agent front door and which, on a with-AI install, asked an
agent that before 2.12.1 did not forward them (CIRISAgent#1213, closed by
#1215; reach through the agent works from agent 2.12.1). The sentence above used to read "none", written from
the server side alone. The three calls now take the node URL from the same
active-node provider People uses (`UserChatViewModel.nodeUrl`, `ChatApi`), and
`UserChatViewModelTest` pins where each goes. This surface is not served by
the agent's brain and the client does not ask the agent for it: the direct node
URL works on every agent version.

**What CC fixes for free, and the client obeys.** persist refuses a
community-scoped promotion unless `attested_key_id == attesting_key_id` and every
`subject_key_ids` entry is the producer (`CohortStandingRefusal::NamedSubject`),
so "both members hold revocation rights" is not expressible on a chat row — the
author alone withdraws their own message. And the read gate is persist's §4.3
predicate, resolved from the directory rather than caller-asserted: a non-member
is refused `GET .../messages` **even when they are the node's own owner**.
Authority over a node is not membership in a cohort, which is the whole point of
the tier and is why `chat.not_a_member` is a sentence the client renders rather
than a case it handles.

## 4. Flow (how)

Sign in; open People, pick a contact, open the chat.

```yaml
expect:
  state: populated
  visible: [chat_transcript, input_chat_body, btn_chat_send, btn_chat_refresh, btn_chat_back]
```

On a room whose peer has never opened it, the transcript carries one system note
and no messages:

```yaml
expect:
  count: {of: "chat_note_*", min: 1}
  count: {of: "chat_msg_*", eq: 0}
  state: empty
```

Type and send; the row comes back as an attestation card:

```yaml
expect:
  count: {of: "chat_msg_*", min: 1}
  text: {input_chat_body: ""}
```

Open a row's details (`ViewDetails` on the hamburger):

```yaml
expect:
  matches: {"chat_msg_*": "scope community"}
```

Try to send into a room you are not a member of:

```yaml
expect:
  state: error
  visible: [chat_refusal]
  absent: [chat_empty]
```

Open a room of more than two from Neighbours › Chats (CSD-103's
`community_chat_row_*`): the transcript composes with no `POST /v1/chat`, the
header counts the room's members, and back returns to Chats, not to People:

```yaml
expect:
  screen: UserChat
  visible: [chat_transcript, input_chat_body, btn_chat_send]
```

```yaml
do:
  - click: btn_chat_back
expect:
  screen: CommunityChats
```

A row whose body did not open says so in words (`chat_msg_unopened_<id>`
carries the sentence), with the substrate's detail beneath, and the token never
reaches the screen. The flow cannot make a node produce one on demand, so this
is pinned at unit level instead
(`ChatEntryPresentationTest.every_documented_unopened_token_names_its_own_sentence`,
`…a_system_entry_that_did_not_open_falls_back_to_the_detail_never_the_token`),
and §5 disclaims it for the matrix.

## 5. QA plan

**Platforms.** All five for the transcript and the refusals; **two nodes** for
anything that involves the other side, which `testing/gate/node_fixture.py` does
not produce. A single-node suite can open a room, watch it sit in
`awaiting_peer`, and assert that the note renders and the send is refused — which
is most of what this card claims and none of what a conversation is.

**`input_chat_body` is drivable.** The composer registers
`rememberTextInputDriver("input_chat_body", …)` beside the `OutlinedTextField`
(`ChatScreen.kt`), so `/input` types into the draft the ViewModel owns. An
earlier revision of this section said the opposite; it was true before PR #95
and is not now.

**Not driven on the matrix: a locked row.** A row with `unopened_reason` needs
the far side's content to be absent or sealed to another reader, which the
single-node fixture cannot stage; its rendering is pinned at unit level (§4).

**The two buttons, by contrast, get it exactly right.** `btn_chat_send` and
`btn_chat_refresh` use `testableWithHandler` — which registers for automation
*without* adding a clickable — and the handler re-checks the same predicate the
Material `enabled` gates (`ChatScreen.kt:181`, `:313`). The reason is written at
both sites: `testableClickable` appends an UNCONDITIONAL clickable, so a
`/click` fires whatever `enabled` says, and a message is an attestation — the
duplicate is irreversible. This is the pattern CSD-082/083's `btn_next` is
missing, on a screen where the consequence is worse.

**`UserChat` is missing from the gate's own unreachable list, and that list is
dead code.** `FLOW_ONLY` (`testing/gate/screen_atlas.py:41`) names eleven
screens; `UserChat` is not one of them, and `"Manage"` is, though no
`Screen.Manage` exists in `CIRISApp.kt`. Neither omission has ever been noticed
because the branch that reads the set (`screen_atlas.py:261`) sits inside a loop
over `nav_map.build()` — and **not one of the eleven is reachable by nav_map**,
so `if screen in FLOW_ONLY` has never matched and the `[FLOW]` line it prints has
never been printed. The set's only live effect is the `flow_only` array written
into `docs/atlas.json`, which is therefore a hand-maintained claim about coverage
rather than a derived fact — which is exactly how it came to carry a screen that
does not exist and miss one that does. **Fix:** derive it as
`_screen_classes() - set(hops)`, the same subtraction `check_csd_v3.py:66-84`
already performs to validate a `flow_only:` surface. Then `Manage` disappears
and `UserChat` appears, both without an edit.

**Not tested here.** Delivery to the other member (two nodes); the MLS handshake
completing (`chat.state.ready` arrives when the peer's row replicates, on the
mesh's cadence); the entry-epoch guard, which the ViewModel documents at length
(`UserChatViewModel.kt:62-91`) and states plainly is **not pinned by a test** —
it takes a concrete `CIRISApiClient` rather than an interface, so the race cannot
be driven without a refactor; the long-press gesture (no `/long-press`); the
16 KB refusal at the wire, since the button gates it first.

**Stated limit.** This screen shows that a message was signed, by whom and for
whom — it does not show that it was *delivered*. `POST` returning is a local
commit plus a promotion; reaching the other member is anti-entropy's job and
nothing on this surface reports it. Until a delivery receipt exists
(`delivery_receipt:{stream_id}` is a registry family and no route carries it
here), the honest sentence is that one, and this CSD says it rather than letting
a sent bubble imply arrival.
