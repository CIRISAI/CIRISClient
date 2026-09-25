# CSD-091 — User chat (one room, two people, every message an attestation)

**CSD**: CSD-091 · **Standard**: CSD/3 (`CSD.md`) · **Origin**: the Locked Spec, leftovers
**Flow**: unwritten — the tags below are the contract the flow will drive

```yaml csd:stage
stage: sketched
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
entry: Contacts' `onOpenChat` (CIRISApp.kt:4107) — People is a tab in every circle, so a room is opened from whichever circle the contact was read in
exit: back to `Screen.Contacts` (CIRISApp.kt:564, 4133); `navSurfaceForScreen` keeps the Contacts card lit while the room is open (CIRISApp.kt:5937) — a chat is a leaf of that surface, not a destination beside it
```

**The placement is wrong, and the spec already says where it belongs.** The
Locked Spec gives every circle a **Chats** tab. The entire nav declares two
placements on it and both are Just me: `Interact` (`CirclesNav.kt:87`,
`agentOnly`) and, on `origin/feat/b3-files`, `Notes` (`CirclesNav.kt:90`,
`setOf(AGENT)`). So Chats is empty in Family, in Neighbours, in Communities and
Businesses and in Everyone — while the conversation those tabs are named for is
a leaf of People. CSD-007 makes the case without needing this card: "a chat is a
group of two; a note to self is the group of one." The group of one is in Chats.
The group of two is not.

**Recommended placement: a Chats card in the four non-self circles listing the
person's open rooms, with Contacts keeping the "start one" move.** The
constitutional caveat is worth stating rather than glossing: the room's
`cohort_scope` is `community` whichever circle it is shown in — the wire carries
no family/neighbour distinction for a pair room — so which circle a given
conversation appears in is a client-side audience judgment with no substrate
backing. Today that judgment is dodged by reaching every room through its
contact, which is defensible and is not what the spec asked for.

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
    renders: "the '(moderator)' badge beside the author. RESERVED — the client reads the duties the node resolved from the live `delegates_to` chain, which is the same edge CSD-090 confers. Moderation is a duty, not a role: a plain member holding a scoped delegation moderates, and a roster founder with no live chain does not"
    tag: "proposed:chat_msg_duty_badge"
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
    renders: "'9012 / 16384 bytes', appearing only past half the ceiling and turning error-coloured past it. Say the ceiling BEFORE the node refuses at it — the refusal is correct but arrives after the writing. The counter carries NO test tag (ChatScreen.kt:325-341)"
    tag: "proposed:chat_length_counter"
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
empty:     {tag: "proposed:chat_empty", renders: "'No messages yet. What you send here is a signed attestation in this two-person community…' — rendered with NO tag (ChatScreen.kt:237-247), so an unstarted conversation and a transcript that failed to compose are the same observation to the gate"}
loading:   {tag: "proposed:chat_loading", renders: "a bare CircularProgressIndicator, also UNTAGGED (ChatScreen.kt:232-235). It is correctly distinguished from empty in the code — `transcriptLoaded` tells 'empty' from 'not asked yet' — and indistinguishable from it to automation"}
error:     {tag: chat_refusal, renders: "the node's typed `reason_id`, localized, in an error container above the transcript, with the node's English detail beneath it. `chat.not_a_contact`, `chat.not_a_member`, `chat.message_too_large` and `chat.empty_message` each name a different next move and would otherwise all be red text"}
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
| open / resolve the room | `POST /v1/chat` | CIRISServer (`src/contacts_chat.rs:3787`) | live — idempotent; returns BEFORE any write when the community exists, so for an open room this is a read, and it is the only read that answers with the authoritative roster the header needs |
| the transcript | `GET /v1/chat/{community_id}/messages` | CIRISServer (`src/contacts_chat.rs:3789`) | live — oldest first, structural composers already folded into each row's `status` |
| send | `POST /v1/chat/{community_id}/messages` | CIRISServer (`src/contacts_chat.rs:3789`) | live — a `chat:message:v1` `scores` attestation, `attestation_upsert_local` + `attestation_promote(community)` |
| the 16 KB ceiling | `MAX_MESSAGE_BYTES` (`src/contacts_chat.rs:146`) | CIRISServer | live — mirrored in the client at `ChatScreen.kt:72`, and it is a MIRROR: two constants, one number |
| the contact grant | `GET` / `POST /v1/contacts` | CIRISServer (`src/contacts_chat.rs:3784`) | live — CSD-005 |
| the room's handshake state | carried as a system entry with `message_id: chat.state.*` (`src/contacts_chat.rs:694-751`, `803-818`) | CIRISServer | live — and the client renders it correctly through `SystemNoteRow` + `chatEntryText`, whose fallback rules are id, then the server's English, never a blank line and never the raw key (CIRISClient#34) |
| **does this wait resolve by itself** | `converges_on_its_own` — present on `GET .../messages` (`:3742`) and on the contacts refusal (`:2063`), **absent from the send refusal** (`:2986-2996`) | CIRISServer | **missing on the one route that needs it** |
| edit / withdraw / recant a message | no route — it needs the author's own signature and the app holds no keys | CIRISServer | **missing by design**, and the UI says so per-op rather than hiding the verb |
| a CEG family for `chat:message:v1` | not in the pinned registry | CIRISConstitution / CIRISRegistry | **missing** — every message field above is `x_private:` for want of it |

**Wrong-host risk: none.** Every route here is the NODE's, on `:4243`. This
surface does not exist on the agent and does not ask it anything — the contrast
with CSD-010, which is entirely the agent's, is the cleanest example in the
client of the two hosts staying apart (`CIRISApp.kt:338-347`).

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
```

## 5. QA plan

**Platforms.** All five for the transcript and the refusals; **two nodes** for
anything that involves the other side, which `testing/gate/node_fixture.py` does
not produce. A single-node suite can open a room, watch it sit in
`awaiting_peer`, and assert that the note renders and the send is refused — which
is most of what this card claims and none of what a conversation is.

**`input_chat_body` cannot be typed into.** `client/tools/check_ui_drivable.py
--list` names it (with `input_duty_subject`, CSD-090) — an `OutlinedTextField`
carrying `Modifier.testable("input_chat_body")` (`ChatScreen.kt:296`) with no
`rememberInputSinks` in the file, so `/input` answers success and types nothing.
The composer of the user-to-user chat is undrivable, which means **the gate
cannot send a message on this screen at all**; the flow above is aspirational
until `CirisTextField` (`ui/primitives/Controls.kt:95`) replaces it. One line.

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
